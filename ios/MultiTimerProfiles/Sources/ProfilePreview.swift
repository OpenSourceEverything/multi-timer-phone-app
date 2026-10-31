import Foundation

/// Foreground visual preview only. It displays effective cue actions without
/// claiming that a simulator rendered audible speech or alarm timing.
struct PreviewCue: Identifiable, Equatable {
    let offsetMillis: Int64
    let actions: String
    var id: Int64 { offsetMillis }
}

enum ProfilePreviewPlan {
    static let maximumHorizon: Int64 = 60_000
    static let maximumEvents = 50

    /// Both count and time are bounded, even for a one-millisecond interval.
    static func cues(_ profile: SharedProfile) -> [PreviewCue] {
        switch profile.mode {
        case .periodic:
            let count = min(Int(maximumHorizon / profile.periodMillis), maximumEvents)
            guard count > 0 else { return [] }
            return (1...count).map {
                PreviewCue(offsetMillis: Int64($0) * profile.periodMillis,
                           actions: actions(profile, speech: nil, beep: profile.beepDurationMillis))
            }
        case .scripted:
            return profile.events.filter { $0.offsetMillis <= maximumHorizon }.prefix(maximumEvents).map {
                PreviewCue(offsetMillis: $0.offsetMillis,
                           actions: actions(profile, speech: $0.speechText, beep: $0.beepDurationMillis))
            }
        }
    }

    private static func actions(_ profile: SharedProfile, speech: String?, beep: Int64) -> String {
        var result: [String] = []
        if profile.flags.beepOnCue && beep > 0 { result.append("Beep \(beep) ms") }
        if profile.flags.speakOnCue {
            if let speech { result.append("Say: \(speech)") }
            if profile.flags.speakCurrentTime { result.append("Say current time") }
            if profile.flags.speakElapsedTime { result.append("Say elapsed time") }
            if profile.flags.speakRemainingTime { result.append("Say remaining time") }
        }
        return result.isEmpty ? "No enabled scheduled audio" : result.joined(separator: " · ")
    }
}

/// Explicit monotonic input makes time advancement/cancellation deterministic
/// in tests. Starting a new run replaces all old due-cue state.
struct PreviewTimeline {
    private var cues: [PreviewCue] = []
    private var origin: TimeInterval = 0
    private var next = 0
    private(set) var isRunning = false

    mutating func start(_ profile: SharedProfile, now: TimeInterval) {
        cues = ProfilePreviewPlan.cues(profile)
        origin = now
        next = 0
        isRunning = !cues.isEmpty
    }

    mutating func stop() { isRunning = false }

    mutating func poll(now: TimeInterval) -> [PreviewCue] {
        guard isRunning else { return [] }
        let elapsed = max(0, now - origin) * 1000
        let begin = next
        while next < cues.count && Double(cues[next].offsetMillis) <= elapsed { next += 1 }
        if next == cues.count || elapsed >= Double(ProfilePreviewPlan.maximumHorizon) { isRunning = false }
        return Array(cues[begin..<next])
    }
}
