import XCTest
@testable import MultiTimerProfiles

/// A simulator-hosted smoke test also proves shared resources are loaded from
/// the checked-in root corpus rather than a divergent companion-only copy.
final class CompanionBuildTests: XCTestCase {
    func testSharedFixtureIsBundledFromTheRepository() throws {
        let fixtures = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "v1", withExtension: nil))
        let data = try Data(contentsOf: fixtures.appendingPathComponent("valid/periodic.json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["format"] as? String, "com.ose.multitimer.profiles")
        XCTAssertEqual(root["version"] as? Int, 1)
        XCTAssertNotNil(root["profiles"] as? [[String: Any]])
    }

}
