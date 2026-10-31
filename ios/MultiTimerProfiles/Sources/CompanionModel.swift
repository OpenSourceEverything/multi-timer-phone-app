import Foundation
import Combine

/// One validated import request. Request identity prevents a stale sheet or
/// cancelled read from confirming a different, newer document.
struct PendingProfileImport: Identifiable {
    let id: UUID
    let profiles: [SharedProfile]
}

/// Main-thread owner of the saved list. Disk work runs off the UI thread; only
/// a successful atomic replacement publishes the new list to SwiftUI.
@MainActor
final class CompanionModel: ObservableObject {
    @Published private(set) var records: [LocalProfile] = []
    @Published private(set) var isBusy = false
    @Published private(set) var isReady = false
    @Published var errorMessage: String?
    @Published var pendingImport: PendingProfileImport?
    @Published private(set) var isReadingImport = false
    @Published private(set) var transferMessage: String?
    let file: ProfileLibraryFile
    private var didLoad = false
    private var importGeneration = UUID()

    init(file: ProfileLibraryFile? = nil) {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        self.file = file ?? ProfileLibraryFile(url: directory.appendingPathComponent("MultiTimerProfiles/library.json"))
    }

    /// A corrupt library leaves editing disabled; it is not replaced with an
    /// empty file merely because the UI could not reconstruct it.
    func loadOnce() async {
        guard !didLoad else { return }
        didLoad = true
        isBusy = true
        do {
            let source = file
            records = try await Task.detached(priority: .userInitiated) { try source.load() }.value
            isReady = true
        } catch { errorMessage = "Could not open saved profiles: \(error.localizedDescription)" }
        isBusy = false
    }

    /// Invalid drafts fail before persistence. Existing IDs are retained for
    /// edits; a new row gets a fresh UUID even if its name matches another row.
    func save(_ draft: ProfileDraft, editing id: UUID?) async throws {
        let profile = try draft.validated()
        var next = records
        if let id {
            guard let index = next.firstIndex(where: { $0.id == id }) else {
                throw ProfileFormatError("This profile no longer exists")
            }
            next[index] = LocalProfile(id: id, profile: profile)
        } else {
            next.append(LocalProfile(profile: profile))
        }
        try await replace(next)
    }

    func remove(_ id: UUID) async throws {
        try await replace(records.filter { $0.id != id })
    }

    /// Picker selection only loads/validates. It never appends a profile.
    func beginImport(_ url: URL) async {
        guard isReady && !isBusy else { return }
        let request = UUID()
        importGeneration = request
        pendingImport = nil
        isBusy = true
        isReadingImport = true
        do {
            let profiles = try await Task.detached(priority: .userInitiated) {
                try ProfileFileIO.loadValidated(url)
            }.value
            guard importGeneration == request else { return }
            pendingImport = PendingProfileImport(id: request, profiles: profiles)
        } catch {
            guard importGeneration == request else { return }
            errorMessage = "Could not import profile file: \(error.localizedDescription)"
        }
        if importGeneration == request { isBusy = false; isReadingImport = false }
    }

    /// Cancellation invalidates the result. It cannot roll back an append that
    /// has already entered replace; the saving sheet disables dismissal then.
    func cancelImport() {
        guard !isBusy || isReadingImport else { return }
        importGeneration = UUID()
        pendingImport = nil
        if isReadingImport { isBusy = false; isReadingImport = false }
        transferMessage = "Import cancelled; no profiles changed."
    }

    func confirmImport(request: UUID, selected: Set<Int>) async throws {
        guard let preview = pendingImport, preview.id == request, importGeneration == request else {
            throw ProfileFormatError("This import preview is no longer current")
        }
        try requireProfile(!selected.isEmpty && selected.allSatisfy { preview.profiles.indices.contains($0) },
                           "Choose at least one profile")
        let imported = preview.profiles.enumerated().filter { selected.contains($0.offset) }
            .map { LocalProfile(profile: $0.element) }
        try await replace(records + imported)
        pendingImport = nil
        importGeneration = UUID()
        transferMessage = "\(imported.count) profile(s) imported as new copies."
    }

    /// Selection is by local UUID, not name. Export contains portable fields
    /// only and includes no unselected rows or companion-local identities.
    func export(_ ids: Set<UUID>) async throws -> Data {
        try requireProfile(isReady && !isBusy, "Wait for the current operation")
        let profiles = records.filter { ids.contains($0.id) }.map(\.profile)
        try requireProfile(!profiles.isEmpty, "Choose profiles to export")
        isBusy = true
        defer { isBusy = false }
        return try await Task.detached(priority: .userInitiated) {
            try ProfileBundleCodec.encode(profiles)
        }.value
    }

    func setTransferMessage(_ message: String) { transferMessage = message }

    /// Serialize all list mutations: a late disk completion must not publish an
    /// older snapshot over a newer edit or import.
    func replace(_ next: [LocalProfile]) async throws {
        try requireProfile(isReady && !isBusy, "Saved profiles are unavailable or another save is running")
        isBusy = true
        defer { isBusy = false }
        let destination = file
        try await Task.detached(priority: .userInitiated) { try destination.save(next) }.value
        records = next
    }
}
