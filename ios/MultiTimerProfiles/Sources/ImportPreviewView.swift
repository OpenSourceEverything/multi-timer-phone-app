import SwiftUI

/// Preview is a selection over immutable decoded rows; equal names remain
/// separate positions. Only the explicit confirmation calls persistence.
struct ImportPreviewView: View {
    @ObservedObject var model: CompanionModel
    let preview: PendingProfileImport
    @State private var selected: Set<Int>
    @State private var errorMessage: String?

    init(model: CompanionModel, preview: PendingProfileImport) {
        self.model = model
        self.preview = preview
        _selected = State(initialValue: Set(preview.profiles.indices))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Nothing is saved until you confirm. Import adds new copies; equal names do not replace existing profiles.")
                }
                Section("Choose profiles") {
                    ForEach(preview.profiles.indices, id: \.self) { index in
                        let profile = preview.profiles[index]
                        Button {
                            if selected.contains(index) { selected.remove(index) } else { selected.insert(index) }
                        } label: {
                            HStack(alignment: .top) {
                                Image(systemName: selected.contains(index) ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(profile.name).font(.headline)
                                    Text(profile.mode == .periodic
                                         ? "Every \(DurationText.seconds(profile.periodMillis)) seconds"
                                         : "\(profile.events.count) scripted cues")
                                    Text(ProfilePreviewPlan.cues(profile).first?.actions
                                         ?? "No scheduled cues in the first minute; check completion settings.")
                                        .font(.footnote).foregroundStyle(.secondary).lineLimit(3)
                                }
                            }
                        }
                        .accessibilityIdentifier("import-choice-\(index)")
                    }
                }
                if let errorMessage { Section { Text(errorMessage).foregroundStyle(.red) } }
            }
            .disabled(model.isBusy)
            .navigationTitle("Preview import")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancelImport() }.disabled(model.isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import \(selected.count)") {
                        Task {
                            do { try await model.confirmImport(request: preview.id, selected: selected) }
                            catch { errorMessage = error.localizedDescription }
                        }
                    }
                    .disabled(selected.isEmpty || model.isBusy)
                    .accessibilityIdentifier("confirm-import")
                }
            }
            .interactiveDismissDisabled(model.isBusy)
        }
    }
}
