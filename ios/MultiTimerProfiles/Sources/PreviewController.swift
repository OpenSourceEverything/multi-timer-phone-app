import Foundation
import Combine

/// Drives the visual preview from monotonic time. The pure PreviewTimeline
/// owns due-cue arithmetic; this adapter only schedules UI refresh/cancellation.
@MainActor
final class PreviewController: ObservableObject {
    @Published private(set) var displayed: [PreviewCue] = []
    @Published private(set) var isRunning = false
    private var timeline = PreviewTimeline()
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private let now: () -> TimeInterval

    init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) { self.now = now }

    func start(_ profile: SharedProfile) {
        stop()
        displayed = []
        timeline.start(profile, now: now())
        isRunning = timeline.isRunning
        let request = generation
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard self?.advance(request: request) == true else { return }
                do { try await Task.sleep(nanoseconds: 100_000_000) }
                catch { return }
            }
        }
    }

    /// Stop, navigation away and scene inactivity all use this boundary.
    /// Advancing a cancelled generation cannot add more visible cues.
    func stop() {
        generation = UUID()
        task?.cancel()
        task = nil
        timeline.stop()
        isRunning = false
    }

    private func advance(request: UUID) -> Bool {
        guard generation == request else { return false }
        displayed.append(contentsOf: timeline.poll(now: now()))
        isRunning = timeline.isRunning
        return isRunning
    }
}
