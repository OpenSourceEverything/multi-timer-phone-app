import SwiftUI

/// A separate sheet scopes the preview's scheduled work. Leaving the screen or
/// making the scene inactive stops the controller through the same boundary.
struct ProfilePreviewView: View {
    let profile: SharedProfile
    @StateObject private var controller = PreviewController()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section(profile.name) {
                    Text("Visual preview only · no audio")
                    Text(controller.isRunning ? "Preview running" : "Preview stopped")
                        .accessibilityIdentifier("preview-status")
                    Button("Start preview") { controller.start(profile) }
                        .accessibilityIdentifier("start-preview")
                    Button("Stop preview") { controller.stop() }
                        .accessibilityIdentifier("stop-preview")
                }
                if !controller.displayed.isEmpty {
                    Section("Cues observed in this preview") {
                        ForEach(controller.displayed) { cue in
                            Text("\(DurationText.seconds(cue.offsetMillis)) s · \(cue.actions)")
                        }
                    }
                }
                Section("First 60 seconds · at most 50 cues") {
                    let cues = ProfilePreviewPlan.cues(profile)
                    if cues.isEmpty { Text("No scheduled cues in this window.") }
                    ForEach(cues) { cue in
                        VStack(alignment: .leading) {
                            Text("\(DurationText.seconds(cue.offsetMillis)) seconds").monospacedDigit()
                            Text(cue.actions).foregroundStyle(.secondary)
                        }
                    }
                }
                Section("Completion") {
                    Text("Completion actions apply when an Android timer finishes; this preview does not run a timer.")
                        .font(.footnote)
                }
            }
            .navigationTitle("Schedule preview")
            .toolbar { Button("Done") { controller.stop(); dismiss() } }
            .onDisappear { controller.stop() }
            .onChange(of: scenePhase) { _, phase in if phase != .active { controller.stop() } }
        }
    }
}
