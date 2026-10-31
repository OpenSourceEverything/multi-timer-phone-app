import SwiftUI

/// Views bind to draft text, never directly to saved profiles. Save and Preview
/// validate the complete draft; a failure leaves both the draft and disk intact.
struct ProfileEditorView: View {
    @ObservedObject var model: CompanionModel
    private let recordID: UUID?
    @State private var draft: ProfileDraft
    @State private var errorMessage: String?
    @State private var preview: PreviewSelection?
    @Environment(\.dismiss) private var dismiss

    private struct PreviewSelection: Identifiable {
        let id = UUID()
        let profile: SharedProfile
    }

    init(model: CompanionModel, record: LocalProfile?) {
        self.model = model
        recordID = record?.id
        _draft = State(initialValue: record.map { ProfileDraft($0.profile) } ?? ProfileDraft())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Profile") {
                    TextField("Name", text: $draft.name).accessibilityIdentifier("profile-name")
                    Picker("Schedule", selection: $draft.mode) {
                        Text("Repeating").tag(ProfileMode.periodic)
                        Text("Scripted").tag(ProfileMode.scripted)
                    }.accessibilityIdentifier("profile-mode")
                    if draft.mode == .periodic {
                        LabeledContent("Repeat every (seconds)") {
                            TextField("60", text: $draft.periodSeconds)
                                .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                                .accessibilityIdentifier("profile-period")
                        }
                    }
                    LabeledContent("Default beep (ms)") {
                        TextField("500", text: $draft.beepMillis)
                            .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                            .accessibilityIdentifier("profile-beep")
                    }
                }
                Section("Scheduled actions") {
                    Toggle("Beep at scheduled cues", isOn: $draft.flags.beepOnCue)
                    Toggle("Speak at scheduled cues", isOn: $draft.flags.speakOnCue)
                    if draft.flags.speakOnCue {
                        Toggle("Say current time", isOn: $draft.flags.speakCurrentTime)
                        Toggle("Say elapsed time", isOn: $draft.flags.speakElapsedTime)
                        Toggle("Say remaining time", isOn: $draft.flags.speakRemainingTime)
                    }
                    Toggle("Include timer name in speech", isOn: $draft.flags.speakClockNameWithSpeech)
                }
                Section("When the Android timer finishes") {
                    Toggle("Beep at completion", isOn: $draft.flags.beepOnCompletion)
                    Toggle("Speak at completion", isOn: $draft.flags.speakOnCompletion)
                }
                if draft.mode == .scripted {
                    Section("Scripted cues · increasing offsets") {
                        ForEach($draft.events) { $cue in
                            VStack(alignment: .leading) {
                                TextField("Offset in seconds", text: $cue.offsetSeconds)
                                    .keyboardType(.decimalPad)
                                    // Identify this cue independently of its
                                    // editable text, which may temporarily be blank.
                                    .accessibilityIdentifier("cue-offset-\(cue.id.uuidString)")
                                TextField("Spoken text (optional)", text: $cue.speechText)
                                TextField("Beep milliseconds (0 disables)", text: $cue.beepMillis).keyboardType(.numberPad)
                                Button("Remove cue", role: .destructive) {
                                    let removedID = cue.id
                                    draft.events.removeAll { $0.id == removedID }
                                }
                            }
                        }
                        Button("Add cue") {
                            draft.events.append(CueDraft())
                        }.disabled(draft.events.count >= ProfileSharingLimits.eventCount)
                    }
                }
                Section {
                    Button("Preview schedule") {
                        do { preview = PreviewSelection(profile: try draft.validated()); errorMessage = nil }
                        catch { errorMessage = error.localizedDescription }
                    }.accessibilityIdentifier("preview-profile")
                    Text("Visual foreground preview: up to 60 seconds and 50 cues. No sound is played.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let errorMessage {
                    Section("Check the profile") {
                        Text(errorMessage).foregroundStyle(.red).accessibilityIdentifier("editor-error")
                    }
                }
            }
            .disabled(model.isBusy)
            .scrollDismissesKeyboard(.immediately)
            .navigationTitle(recordID == nil ? "New profile" : "Edit profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(model.isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            do { try await model.save(draft, editing: recordID); dismiss() }
                            catch { errorMessage = error.localizedDescription }
                        }
                    }.disabled(model.isBusy).accessibilityIdentifier("save-profile")
                }
            }
            .interactiveDismissDisabled(model.isBusy)
            .sheet(item: $preview) { ProfilePreviewView(profile: $0.profile) }
        }
    }
}
