import Foundation

/// Editable text deliberately remains separate from a validated SharedProfile:
/// typing an incomplete number must not destroy a previously usable profile.
struct CueDraft: Identifiable, Equatable {
    let id: UUID
    var offsetSeconds: String
    var speechText: String
    var beepMillis: String

    init(id: UUID = UUID(), offsetSeconds: String = "0", speechText: String = "", beepMillis: String = "500") {
        self.id = id
        self.offsetSeconds = offsetSeconds
        self.speechText = speechText
        self.beepMillis = beepMillis
    }

    func validated() throws -> SharedCue {
        try SharedCue(offsetMillis: DurationText.milliseconds(offsetSeconds, field: "Cue offset"),
                      speechText: speechText.isEmpty ? nil : speechText,
                      beepDurationMillis: DurationText.integer(beepMillis, field: "Cue beep"))
    }
}

/// A mutable view draft; validate produces a new immutable value at Save or
/// Preview. Switching modes retains inactive draft fields for switching back.
struct ProfileDraft: Equatable {
    var name = "New profile"
    var mode = ProfileMode.periodic
    var periodSeconds = "60"
    var beepMillis = "500"
    var events: [CueDraft] = []
    var flags = ProfileFlags()

    init() {}

    init(_ profile: SharedProfile) {
        name = profile.name
        mode = profile.mode
        periodSeconds = profile.mode == .periodic ? DurationText.seconds(profile.periodMillis) : "60"
        beepMillis = String(profile.beepDurationMillis)
        events = profile.events.map {
            CueDraft(offsetSeconds: DurationText.seconds($0.offsetMillis),
                     speechText: $0.speechText ?? "", beepMillis: String($0.beepDurationMillis))
        }
        flags = profile.flags
    }

    func validated() throws -> SharedProfile {
        try SharedProfile(name: name, mode: mode,
                          beepDurationMillis: DurationText.integer(beepMillis, field: "Default beep"),
                          periodMillis: mode == .periodic
                            ? DurationText.milliseconds(periodSeconds, field: "Repeat interval") : 0,
                          events: mode == .scripted ? events.map { try $0.validated() } : [],
                          flags: flags)
    }
}

/// Decimal seconds use integer arithmetic, preserving milliseconds exactly.
/// Double-based conversion would round portable values near the format limit.
enum DurationText {
    static func milliseconds(_ text: String, field: String) throws -> Int64 {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        try requireProfile((1...2).contains(parts.count) && !parts[0].isEmpty,
                           "\(field): enter seconds with at most three decimal places")
        let whole = try integer(String(parts[0]), field: field)
        let fraction = parts.count == 2 ? String(parts[1]) : ""
        try requireProfile(parts.count == 1 || (!fraction.isEmpty && fraction.count <= 3),
                           "\(field): enter at most three decimal places")
        let fractionValue = fraction.isEmpty ? 0 : try integer(fraction + String(repeating: "0", count: 3 - fraction.count), field: field)
        try requireProfile(whole <= ProfileSharingLimits.maxTime / 1000,
                           "\(field): exceeds portable time range")
        let result = whole * 1000 + fractionValue
        try requireProfile(result <= ProfileSharingLimits.maxTime, "\(field): exceeds portable time range")
        return result
    }

    static func integer(_ text: String, field: String) throws -> Int64 {
        try requireProfile(!text.isEmpty && text.utf8.allSatisfy { (48...57).contains($0) },
                           "\(field): enter a nonnegative whole number")
        guard let value = Int64(text) else { throw ProfileFormatError("\(field): number is too large") }
        return value
    }

    static func seconds(_ millis: Int64) -> String {
        let remainder = millis % 1000
        if remainder == 0 { return String(millis / 1000) }
        var digits = String(format: "%03lld", remainder)
        while digits.last == "0" { digits.removeLast() }
        return "\(millis / 1000).\(digits)"
    }
}
