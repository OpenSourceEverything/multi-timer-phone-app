import Foundation

/// Portable v1 data boundary. Decoding returns validated snapshots only; it
/// creates no local UUIDs and never reads/writes application persistence.
enum ProfileBundleCodec {
    static let format = "com.ose.multitimer.profiles"
    private static let rootFields: Set<String> = ["format", "version", "profiles"]
    private static let cueFields: Set<String> = ["offsetMillis", "speechText", "beepDurationMillis"]
    private static let profileFields = Set(
        ["name", "mode", "beepDurationMillis", "periodMillis", "events"]
        + ProfileFlags().namedValues.map { $0.0 })

    static func decode(_ data: Data) throws -> [SharedProfile] {
        let root = try object(StrictJSON.parse(data), at: "bundle")
        try exactFields(root, expected: rootFields, at: "bundle")
        try requireProfile(try string(root["format"], at: "format") == format, "format: unsupported profile format")
        try requireProfile(try integer(root["version"], at: "version") == 1, "version: unsupported profile bundle version")
        let values = try array(root["profiles"], at: "profiles")
        try requireProfile((1...ProfileSharingLimits.profileCount).contains(values.count),
                           "profiles: expected 1...32 entries")
        return try values.enumerated().map { try profile($0.element, at: "profiles[\($0.offset)]") }
    }

    static func encode(_ profiles: [SharedProfile]) throws -> Data {
        try requireProfile((1...ProfileSharingLimits.profileCount).contains(profiles.count),
                           "profiles: expected 1...32 entries")
        var writer = Writer()
        try writer.append("{\"format\":")
        try writer.quoted(format)
        try writer.append(",\"version\":1,\"profiles\":[")
        for (index, profile) in profiles.enumerated() {
            if index > 0 { try writer.append(",") }
            try writer.profile(profile)
        }
        try writer.append("]}")
        return Data(writer.bytes)
    }

    private static func profile(_ value: StrictJSONValue, at location: String) throws -> SharedProfile {
        let fields = try object(value, at: location)
        try exactFields(fields, expected: profileFields, at: location)
        let name = try string(fields["name"], at: "\(location).name")
        guard let mode = ProfileMode(rawValue: try string(fields["mode"], at: "\(location).mode")) else {
            throw ProfileFormatError("\(location).mode: unsupported schedule mode")
        }
        let encodedEvents = try array(fields["events"], at: "\(location).events")
        try requireProfile(encodedEvents.count <= ProfileSharingLimits.eventCount,
                           "\(location).events: at most 256 entries")
        var events: [SharedCue] = []
        var previous: Int64 = -1
        for (index, encoded) in encodedEvents.enumerated() {
            let path = "\(location).events[\(index)]"
            let cue = try object(encoded, at: path)
            try exactFields(cue, expected: cueFields, at: path)
            let offset = try integer(cue["offsetMillis"], at: "\(path).offsetMillis")
            try requireProfile(offset > previous, "\(path).offsetMillis: offsets must increase strictly")
            let speech: String?
            if case .null? = cue["speechText"] { speech = nil }
            else { speech = try string(cue["speechText"], at: "\(path).speechText") }
            do {
                events.append(try SharedCue(offsetMillis: offset, speechText: speech,
                                            beepDurationMillis: integer(cue["beepDurationMillis"], at: "\(path).beepDurationMillis")))
            } catch {
                throw ProfileFormatError("\(path): \(error.localizedDescription)")
            }
            previous = offset
        }
        let flags = try ProfileFlags(
            beepOnCue: boolean(fields["beepOnCue"], at: "\(location).beepOnCue"),
            beepOnCompletion: boolean(fields["beepOnCompletion"], at: "\(location).beepOnCompletion"),
            speakOnCompletion: boolean(fields["speakOnCompletion"], at: "\(location).speakOnCompletion"),
            speakCurrentTime: boolean(fields["speakCurrentTime"], at: "\(location).speakCurrentTime"),
            speakElapsedTime: boolean(fields["speakElapsedTime"], at: "\(location).speakElapsedTime"),
            speakRemainingTime: boolean(fields["speakRemainingTime"], at: "\(location).speakRemainingTime"),
            speakOnCue: boolean(fields["speakOnCue"], at: "\(location).speakOnCue"),
            speakClockNameWithSpeech: boolean(fields["speakClockNameWithSpeech"], at: "\(location).speakClockNameWithSpeech"))
        do {
            return try SharedProfile(name: name, mode: mode,
                                     beepDurationMillis: integer(fields["beepDurationMillis"], at: "\(location).beepDurationMillis"),
                                     periodMillis: integer(fields["periodMillis"], at: "\(location).periodMillis"),
                                     events: events, flags: flags)
        } catch {
            throw ProfileFormatError("\(location): \(error.localizedDescription)")
        }
    }

    private static func exactFields(_ fields: [String: StrictJSONValue], expected: Set<String>, at path: String) throws {
        let actual = Set(fields.keys)
        let missing = expected.subtracting(actual).sorted()
        let unknown = actual.subtracting(expected).sorted()
        try requireProfile(missing.isEmpty && unknown.isEmpty,
                           "\(path): missing fields \(missing); unknown fields \(unknown)")
    }

    private static func object(_ value: StrictJSONValue?, at path: String) throws -> [String: StrictJSONValue] {
        guard let value, case let .object(result) = value else { throw ProfileFormatError("\(path): expected an object") }
        return result
    }

    private static func array(_ value: StrictJSONValue?, at path: String) throws -> [StrictJSONValue] {
        guard let value, case let .array(result) = value else { throw ProfileFormatError("\(path): expected an array") }
        return result
    }

    private static func string(_ value: StrictJSONValue?, at path: String) throws -> String {
        guard let value, case let .string(result) = value else { throw ProfileFormatError("\(path): expected a string") }
        return result
    }

    private static func boolean(_ value: StrictJSONValue?, at path: String) throws -> Bool {
        guard let value, case let .boolean(result) = value else { throw ProfileFormatError("\(path): expected a boolean") }
        return result
    }

    private static func integer(_ value: StrictJSONValue?, at path: String) throws -> Int64 {
        guard let value, case let .number(token) = value,
              !token.contains("."), !token.contains("e"), !token.contains("E"),
              let integer = Int64(token) else {
            throw ProfileFormatError("\(path): expected a 64-bit integer token")
        }
        return integer
    }

    /// Count bytes before each append. Oversized output stops growing at the
    /// document limit rather than allocating the maximum model graph as JSON.
    private struct Writer {
        var bytes: [UInt8] = []

        mutating func append(_ text: String) throws {
            try requireProfile(text.utf8.count <= ProfileSharingLimits.documentBytes - bytes.count,
                               "Encoded bundle exceeds 262144 UTF-8 bytes")
            bytes.append(contentsOf: text.utf8)
        }

        mutating func quoted(_ text: String) throws {
            try append("\"")
            for byte in text.utf8 {
                switch byte {
                case 34: try append("\\\"")
                case 92: try append("\\\\")
                case 0..<32: try append(String(format: "\\u%04x", Int(byte)))
                default:
                    try requireProfile(bytes.count < ProfileSharingLimits.documentBytes,
                                       "Encoded bundle exceeds 262144 UTF-8 bytes")
                    bytes.append(byte)
                }
            }
            try append("\"")
        }

        mutating func profile(_ profile: SharedProfile) throws {
            try append("{\"name\":")
            try quoted(profile.name)
            try append(",\"mode\":")
            try quoted(profile.mode.rawValue)
            try append(",\"beepDurationMillis\":\(profile.beepDurationMillis),\"periodMillis\":\(profile.periodMillis),\"events\":[")
            for (index, cue) in profile.events.enumerated() {
                if index > 0 { try append(",") }
                try append("{\"offsetMillis\":\(cue.offsetMillis),\"speechText\":")
                if let speech = cue.speechText { try quoted(speech) } else { try append("null") }
                try append(",\"beepDurationMillis\":\(cue.beepDurationMillis)}")
            }
            try append("]")
            for (key, enabled) in profile.flags.namedValues {
                try append(",\"\(key)\":\(enabled ? "true" : "false")")
            }
            try append("}")
        }
    }
}
