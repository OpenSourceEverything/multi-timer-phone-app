import XCTest
#if SWIFT_PACKAGE
@testable import MultiTimerProfileCore
#else
@testable import MultiTimerProfiles
#endif

final class ProfileFileIOTests: XCTestCase {
    func testExactLimitAcceptedAndExtraByteRejectedRegardlessOfMetadata() throws {
        let exact = Data(repeating: 32, count: ProfileSharingLimits.documentBytes)
        XCTAssertEqual(try ProfileFileIO.readBounded(InputStream(data: exact)), exact)
        XCTAssertThrowsError(try ProfileFileIO.readBounded(InputStream(data: exact + Data([32])))) {
            XCTAssertTrue($0.localizedDescription.contains("262144"))
        }
    }

    func testEmptyStreamEndsAndReadFailurePropagates() throws {
        XCTAssertTrue(try ProfileFileIO.readBounded(InputStream(data: Data())).isEmpty)
        XCTAssertThrowsError(try ProfileFileIO.readBounded(FailingInput()))
    }

    func testValidFileReadsAndInvalidOrMissingFileDoesNotCreateStorage() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("chosen.json")
        XCTAssertThrowsError(try ProfileFileIO.loadValidated(url))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let profile = try SharedProfile(name: "Chosen", mode: .periodic, periodMillis: 30_000)
        try ProfileBundleCodec.encode([profile]).write(to: url)
        XCTAssertEqual(try ProfileFileIO.loadValidated(url), [profile])
        try Data("malformed".utf8).write(to: url)
        XCTAssertThrowsError(try ProfileFileIO.loadValidated(url))
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "malformed")
    }

    /// A real filesystem denial complements the injected read error below.
    /// Only this temporary document's permissions change; restore them before
    /// checking its bytes and before deleting the test directory.
    func testDeniedDocumentReadPreservesItsOriginalBytes() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("denied.json")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            try? FileManager.default.removeItem(at: directory)
        }
        let bytes = try ProfileBundleCodec.encode([
            SharedProfile(name: "Unchanged", mode: .periodic, periodMillis: 1000)])
        try bytes.write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: url.path)
        XCTAssertFalse(FileManager.default.isReadableFile(atPath: url.path),
                       "Run file-permission tests as an ordinary user, not root")
        XCTAssertThrowsError(try ProfileFileIO.loadValidated(url))
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        XCTAssertEqual(try Data(contentsOf: url), bytes)
    }

    /// Controlled read failure, not a platform permission simulation.
    private final class FailingInput: InputStream {
        init() { super.init(data: Data()) }
        override func open() {}
        override func close() {}
        override func read(_ buffer: UnsafeMutablePointer<UInt8>, maxLength len: Int) -> Int { -1 }
        override var streamError: Error? { ProfileFormatError("Injected stream failure") }
    }
}
