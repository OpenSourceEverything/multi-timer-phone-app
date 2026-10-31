import XCTest
@testable import MultiTimerProfiles

/// App-adapter tests complement the portable draft/store tests. MainActor
/// ownership matches SwiftUI; persistence still executes on its worker task.
@MainActor
final class CompanionModelTests: XCTestCase {
    func testInvalidEditPreservesPublishedAndPersistedProfile() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        let row = LocalProfile(profile: try SharedProfile(name: "Existing", mode: .periodic, periodMillis: 60_000))
        try file.save([row])
        let model = CompanionModel(file: file)
        await model.loadOnce()
        XCTAssertEqual(model.records, [row])
        var draft = ProfileDraft(row.profile)
        draft.periodSeconds = "invalid"
        do {
            try await model.save(draft, editing: row.id)
            XCTFail("Expected invalid draft rejection")
        } catch {}
        XCTAssertEqual(model.records, [row])
        XCTAssertEqual(try file.load(), [row])
        draft.periodSeconds = "30"
        try await model.save(draft, editing: row.id)
        XCTAssertEqual(model.records[0].id, row.id)
        XCTAssertEqual(try file.load()[0].profile.periodMillis, 30_000)
    }

    func testCorruptLibraryDisablesMutationWithoutReplacingOriginalBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        let original = Data("unreadable existing library".utf8)
        try original.write(to: file.url)
        let model = CompanionModel(file: file)
        await model.loadOnce()
        XCTAssertFalse(model.isReady)
        XCTAssertNotNil(model.errorMessage)
        do {
            try await model.save(ProfileDraft(), editing: nil)
            XCTFail("Expected unavailable-store rejection")
        } catch {}
        XCTAssertEqual(try Data(contentsOf: file.url), original)
    }

    func testPreviewCancelAndConfirmationPreserveExistingIdentity() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        let original = LocalProfile(profile: try SharedProfile(name: "Equal name", mode: .periodic, periodMillis: 1000))
        try file.save([original])
        let incoming = directory.appendingPathComponent("incoming.json")
        try ProfileBundleCodec.encode([original.profile, original.profile]).write(to: incoming)
        let model = CompanionModel(file: file)
        await model.loadOnce()
        await model.beginImport(incoming)
        let cancelled = try XCTUnwrap(model.pendingImport)
        XCTAssertEqual(model.records, [original], "File selection/preview must not save")
        model.cancelImport()
        XCTAssertEqual(try file.load(), [original])
        do {
            try await model.confirmImport(request: cancelled.id, selected: [0])
            XCTFail("A cancelled preview must not save")
        } catch {}
        await model.beginImport(incoming)
        let current = try XCTUnwrap(model.pendingImport)
        try await model.confirmImport(request: current.id, selected: [1])
        XCTAssertEqual(model.records.count, 2)
        XCTAssertEqual(model.records[0], original)
        XCTAssertNotEqual(model.records[1].id, original.id)
        XCTAssertEqual(model.records[1].profile, original.profile)
        XCTAssertEqual(try file.load(), model.records)
        let bytes = try await model.export([model.records[1].id])
        XCTAssertEqual(try ProfileBundleCodec.decode(bytes), [original.profile])
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains(original.id.uuidString))
        do {
            try await model.confirmImport(request: current.id, selected: [1])
            XCTFail("A completed preview must not save twice")
        } catch {}
        XCTAssertEqual(try file.load().count, 2)
    }

    func testMalformedImportPreservesExistingRecordsAndBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        let original = LocalProfile(profile: try SharedProfile(name: "Keep", mode: .periodic, periodMillis: 1000))
        try file.save([original])
        let priorBytes = try Data(contentsOf: file.url)
        let incoming = directory.appendingPathComponent("invalid.json")
        try Data("broken".utf8).write(to: incoming)
        let model = CompanionModel(file: file)
        await model.loadOnce()
        await model.beginImport(incoming)
        XCTAssertNil(model.pendingImport)
        XCTAssertNotNil(model.errorMessage)
        XCTAssertEqual(model.records, [original])
        XCTAssertEqual(try Data(contentsOf: file.url), priorBytes)
    }

    /// Denial occurs at the real filesystem read, before a preview or append.
    /// This does not simulate every third-party provider's revoked grant.
    func testDeniedImportPreservesSavedIdentityAndLibraryBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        let incoming = directory.appendingPathComponent("denied.json")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: incoming.path)
            try? FileManager.default.removeItem(at: directory)
        }
        let original = LocalProfile(profile: try SharedProfile(name: "Keep", mode: .periodic, periodMillis: 1000))
        try file.save([original])
        let priorBytes = try Data(contentsOf: file.url)
        try ProfileBundleCodec.encode([original.profile]).write(to: incoming)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: incoming.path)
        XCTAssertFalse(FileManager.default.isReadableFile(atPath: incoming.path))
        let model = CompanionModel(file: file)
        await model.loadOnce()
        await model.beginImport(incoming)
        XCTAssertNil(model.pendingImport)
        XCTAssertNotNil(model.errorMessage)
        XCTAssertFalse(model.isBusy)
        XCTAssertEqual(model.records, [original])
        XCTAssertEqual(try Data(contentsOf: file.url), priorBytes)
    }
}
