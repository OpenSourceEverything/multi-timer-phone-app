import Foundation

/// Interchange limits, shared with Android. These bound portable configuration;
/// they are not claims about the maximum span a device can schedule accurately.
enum ProfileSharingLimits {
    static let documentBytes = 262_144
    static let profileCount = 32
    static let eventCount = 256
    static let nestingDepth = 16
    static let nameBytes = 256
    static let speechBytes = 2_000
    static let maxTime: Int64 = 9_007_199_254_740_991
}

/// A readable validation failure suitable for an import preview or editor.
struct ProfileFormatError: LocalizedError, Equatable {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Keep schedule choices distinct; completion-only profiles use SCRIPTED with
/// an empty cue list and their independent completion flags.
enum ProfileMode: String, CaseIterable, Equatable {
    case periodic = "PERIODIC"
    case scripted = "SCRIPTED"
}

/// Value semantics let an editor copy flags without mutating a saved snapshot.
/// All eight flags remain independent: a single spoken-time enum would lose data.
struct ProfileFlags: Equatable {
    var beepOnCue = true
    var beepOnCompletion = true
    var speakOnCompletion = false
    var speakCurrentTime = false
    var speakElapsedTime = false
    var speakRemainingTime = false
    var speakOnCue = true
    var speakClockNameWithSpeech = false

    /// Stable flat JSON field order; no local metadata enters this mapping.
    var namedValues: [(String, Bool)] {
        [
            ("beepOnCue", beepOnCue), ("beepOnCompletion", beepOnCompletion),
            ("speakOnCompletion", speakOnCompletion), ("speakCurrentTime", speakCurrentTime),
            ("speakElapsedTime", speakElapsedTime), ("speakRemainingTime", speakRemainingTime),
            ("speakOnCue", speakOnCue), ("speakClockNameWithSpeech", speakClockNameWithSpeech)
        ]
    }
}

/// A validated detached cue. Constructing one neither plays audio nor saves data.
struct SharedCue: Equatable {
    let offsetMillis: Int64
    let speechText: String?
    let beepDurationMillis: Int64

    init(offsetMillis: Int64, speechText: String?, beepDurationMillis: Int64) throws {
        try requireProfile((0...ProfileSharingLimits.maxTime).contains(offsetMillis),
                           "offsetMillis is outside the portable integer range")
        try requireProfile((0...10_000).contains(beepDurationMillis),
                           "Event beepDurationMillis must be from 0 to 10,000")
        let text = try speechText.map {
            try portableText($0, field: "speechText", maximum: ProfileSharingLimits.speechBytes)
        }
        try requireProfile(text != nil || beepDurationMillis > 0, "An event needs speech or a beep")
        self.offsetMillis = offsetMillis
        self.speechText = text
        self.beepDurationMillis = beepDurationMillis
    }
}

/// Immutable portable configuration. IDs, timer ownership and progress belong
/// to each installation's storage, never to an exchanged template.
struct SharedProfile: Equatable {
    let name: String
    let mode: ProfileMode
    let beepDurationMillis: Int64
    let periodMillis: Int64
    let events: [SharedCue]
    let flags: ProfileFlags

    init(name: String, mode: ProfileMode, beepDurationMillis: Int64 = 500,
         periodMillis: Int64, events: [SharedCue] = [], flags: ProfileFlags = ProfileFlags()) throws {
        let text = try portableText(name, field: "name", maximum: ProfileSharingLimits.nameBytes)
        try requireProfile((50...10_000).contains(beepDurationMillis),
                           "Profile beepDurationMillis must be from 50 to 10,000")
        try requireProfile(events.count <= ProfileSharingLimits.eventCount, "At most 256 events are allowed")
        switch mode {
        case .periodic:
            try requireProfile((1...ProfileSharingLimits.maxTime).contains(periodMillis),
                               "A periodic profile requires a positive portable periodMillis")
            try requireProfile(events.isEmpty, "A periodic profile cannot contain events")
        case .scripted:
            try requireProfile(periodMillis == 0, "A scripted profile must have periodMillis 0")
            var previous: Int64 = -1
            for event in events {
                try requireProfile(event.offsetMillis > previous, "Scripted offsets must increase strictly")
                previous = event.offsetMillis
            }
        }
        self.name = text
        self.mode = mode
        self.beepDurationMillis = beepDurationMillis
        self.periodMillis = periodMillis
        self.events = events
        self.flags = flags
    }
}

/// Trim only U+0000...U+0020, matching Android exactly. Foundation's general
/// whitespace set would also strip characters such as NBSP and change meaning.
private func portableText(_ raw: String, field: String, maximum: Int) throws -> String {
    let bytes = Array(raw.utf8)
    var start = 0
    var end = bytes.count
    while start < end && bytes[start] <= 0x20 { start += 1 }
    while end > start && bytes[end - 1] <= 0x20 { end -= 1 }
    try requireProfile(end > start, "\(field) cannot be blank")
    try requireProfile(end - start <= maximum, "\(field) exceeds \(maximum) UTF-8 bytes")
    return String(decoding: bytes[start..<end], as: UTF8.self)
}

/// Throw, rather than assert, at all untrusted-data and editable-draft boundaries.
func requireProfile(_ condition: Bool, _ message: String) throws {
    if !condition { throw ProfileFormatError(message) }
}
