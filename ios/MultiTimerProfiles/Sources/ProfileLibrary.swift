import Foundation

/// Local row identity is separate from portable configuration and display names.
/// Two same-name imports are distinct rows; exports omit these UUIDs.
struct LocalProfile: Identifiable, Equatable {
    let id: UUID
    let profile: SharedProfile
    init(id: UUID = UUID(), profile: SharedProfile) { self.id = id; self.profile = profile }
}

/// The library persists its own IDs beside a portable bundle, rather than
/// extending the public format with companion-specific fields.
struct ProfileLibraryFile {
    let url: URL
    private let maximumLocalBytes = 524_288

    private struct DiskLibrary: Codable {
        let version: Int
        let ids: [UUID]
        let bundle: Data?
    }

    /// Missing storage is a new empty library. Unreadable/corrupt storage throws
    /// so the caller can refuse an overwrite rather than silently losing data.
    func load() throws -> [LocalProfile] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: maximumLocalBytes + 1) ?? Data()
        try requireProfile(data.count <= maximumLocalBytes, "Saved library is too large")
        let disk = try JSONDecoder().decode(DiskLibrary.self, from: data)
        try requireProfile(disk.version == 1, "Unsupported saved-library version")
        try requireProfile(disk.ids.count <= ProfileSharingLimits.profileCount &&
                           Set(disk.ids).count == disk.ids.count, "Invalid saved-library identities")
        if disk.ids.isEmpty {
            try requireProfile(disk.bundle == nil, "Empty library has unexpected profile data")
            return []
        }
        guard let bundle = disk.bundle else { throw ProfileFormatError("Saved profiles are missing") }
        let profiles = try ProfileBundleCodec.decode(bundle)
        try requireProfile(profiles.count == disk.ids.count, "Saved profile count does not match identities")
        return zip(disk.ids, profiles).map { LocalProfile(id: $0.0, profile: $0.1) }
    }

    /// Validate/encode fully before touching the destination. Foundation's atomic
    /// replacement preserves the previous file if writing the replacement fails.
    /// This is one local-file replacement, not a multi-resource transaction.
    func save(_ records: [LocalProfile]) throws {
        try requireProfile(Set(records.map(\.id)).count == records.count, "Duplicate local profile identity")
        let bundle = records.isEmpty ? nil : try ProfileBundleCodec.encode(records.map(\.profile))
        let disk = DiskLibrary(version: 1, ids: records.map(\.id), bundle: bundle)
        let encoded = try JSONEncoder().encode(disk)
        try requireProfile(encoded.count <= maximumLocalBytes, "Saved library is too large")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoded.write(to: url, options: .atomic)
    }
}
