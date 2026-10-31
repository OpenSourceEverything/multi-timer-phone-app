import SwiftUI
import UniformTypeIdentifiers

/// One list owner survives sheet presentation. Each editor receives its own
/// copied draft, so dismissing it cannot mutate a saved row.
struct ContentView: View {
    @StateObject private var model = CompanionModel()
    @State private var editor: EditorSelection?
    @State private var selectedExports: Set<UUID> = []
    @State private var isImporting = false
    @State private var isExporting = false
    @State private var exportDocument = ProfileDocument(data: Data())

    private struct EditorSelection: Identifiable {
        let id: UUID
        let record: LocalProfile?
        init(_ record: LocalProfile? = nil) { self.id = UUID(); self.record = record }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("MultiTimer Profiles")
                        .font(.title2)
                        .accessibilityIdentifier("companion-title")
                    Text("Reusable schedules for the Android timer app.")
                        .foregroundStyle(.secondary)
                }
                Section("Saved profiles") {
                    if model.records.isEmpty {
                        Text(model.isBusy ? "Opening profiles…" : "No profiles yet. Tap New profile to begin.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.records) { record in
                        profileRow(record)
                    }
                }
                Section("Local file exchange") {
                    Button("Import file") { isImporting = true }
                        .disabled(!model.isReady || model.isBusy)
                        .accessibilityIdentifier("import-file")
                    Button("Export selected") {
                        Task {
                            do {
                                exportDocument = ProfileDocument(data: try await model.export(selectedExports))
                                isExporting = true
                            } catch { model.errorMessage = error.localizedDescription }
                        }
                    }
                    .disabled(selectedExports.isEmpty || !model.isReady || model.isBusy)
                    .accessibilityIdentifier("export-selected")
                    if model.isReadingImport {
                        ProgressView("Reading and validating…")
                        Button("Cancel import") { model.cancelImport() }
                    }
                    if let message = model.transferMessage {
                        Text(message).font(.footnote).accessibilityIdentifier("transfer-status")
                    }
                }
            }
            .navigationTitle("Profiles")
            .toolbar {
                Button("New profile") { editor = EditorSelection() }
                    .accessibilityIdentifier("new-profile")
                    .disabled(!model.isReady || model.isBusy)
            }
            .sheet(item: $editor) { selection in
                ProfileEditorView(model: model, record: selection.record)
            }
            .sheet(item: $model.pendingImport) { preview in
                ImportPreviewView(model: model, preview: preview)
            }
            .fileImporter(isPresented: $isImporting, allowedContentTypes: [.json]) { result in
                switch result {
                case .success(let url): Task { await model.beginImport(url) }
                case .failure(let error):
                    if (error as NSError).code == NSUserCancelledError {
                        model.setTransferMessage("Import cancelled; no profiles changed.")
                    } else { model.errorMessage = error.localizedDescription }
                }
            }
            .fileExporter(isPresented: $isExporting, document: exportDocument,
                          contentType: .json, defaultFilename: "multitimer-profiles.json") { result in
                switch result {
                case .success: model.setTransferMessage("Selected profiles exported.")
                case .failure(let error):
                    if (error as NSError).code == NSUserCancelledError {
                        model.setTransferMessage("Export cancelled.")
                    } else { model.errorMessage = error.localizedDescription }
                }
            }
            .alert("Profiles unavailable", isPresented: Binding(
                get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                    Button("OK") { model.errorMessage = nil }
                } message: { Text(model.errorMessage ?? "") }
            .task { await model.loadOnce() }
        }
    }

    /// Selection and editing are separate controls, so a row tap cannot both
    /// open an editor and silently change which profiles will be exported.
    private func profileRow(_ record: LocalProfile) -> some View {
        HStack {
            Button {
                if selectedExports.contains(record.id) { selectedExports.remove(record.id) }
                else { selectedExports.insert(record.id) }
            } label: {
                Image(systemName: selectedExports.contains(record.id) ? "checkmark.circle.fill" : "circle")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Select \(record.profile.name) for export")
            .accessibilityIdentifier("export-choice-\(record.profile.name)")
            Button { editor = EditorSelection(record) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(record.profile.name).font(.headline)
                    Text(record.profile.mode == .periodic
                         ? "Every \(DurationText.seconds(record.profile.periodMillis)) seconds"
                         : "\(record.profile.events.count) scripted cues")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier("saved-profile-\(record.profile.name)")
        }
        .swipeActions {
            Button("Delete", role: .destructive) {
                Task {
                    do { try await model.remove(record.id); selectedExports.remove(record.id) }
                    catch { model.errorMessage = error.localizedDescription }
                }
            }
        }
    }
}
