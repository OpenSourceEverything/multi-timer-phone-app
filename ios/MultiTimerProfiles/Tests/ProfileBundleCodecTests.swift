import XCTest
#if SWIFT_PACKAGE
@testable import MultiTimerProfileCore
#else
@testable import MultiTimerProfiles
#endif

/// The same checked-in corpus is exercised by Android. Generated boundaries
/// stay in tests so large adversarial payloads do not clutter shared examples.
final class ProfileBundleCodecTests: XCTestCase {
    private func fixture(_ name: String, valid: Bool = true) throws -> Data {
        #if SWIFT_PACKAGE
        // SwiftPM cannot bundle resources outside a target directory. Locate
        // the same root corpus from this source file, without copying it.
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("profiles/fixtures/v1")
        #else
        let root = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "v1", withExtension: nil))
        #endif
        return try Data(contentsOf: root.appendingPathComponent("\(valid ? "valid" : "invalid")/\(name).json"))
    }

    private func periodic(name: String = "Every minute", period: Int64 = 60_000) throws -> SharedProfile {
        try SharedProfile(name: name, mode: .periodic, periodMillis: period)
    }

    /// Mutations deliberately use a general JSON writer only to construct bad
    /// structural fixtures; lexical notation/duplicate cases use raw text.
    private func mutated(_ change: (inout [String: Any]) -> Void) throws -> Data {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("periodic")) as? [String: Any])
        change(&root)
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    private func rejects(_ data: Data, containing message: String,
                         file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try ProfileBundleCodec.decode(data), file: file, line: line) { error in
            XCTAssertTrue(error.localizedDescription.localizedCaseInsensitiveContains(message),
                          "Expected \(message), received \(error)", file: file, line: line)
        }
    }

    func testEveryValidSharedFixtureRoundTripsAllPortableFields() throws {
        for name in ["periodic", "scripted", "completion-only", "speech-only"] {
            let original = try ProfileBundleCodec.decode(fixture(name))
            XCTAssertEqual(try ProfileBundleCodec.decode(ProfileBundleCodec.encode(original)), original, name)
        }
        let scripted = try XCTUnwrap(ProfileBundleCodec.decode(fixture("scripted")).first)
        XCTAssertEqual(scripted.name, "Focus – تمرين 🍵")
        XCTAssertEqual(scripted.events.map(\.offsetMillis), [0, 30_000, 60_000])
        XCTAssertEqual(scripted.events.map(\.speechText), ["Start", "Rest", nil])
        XCTAssertEqual(scripted.events.map(\.beepDurationMillis), [0, 500, 750])
    }

    func testEveryInvalidSharedFixtureFailsForItsIntendedReason() throws {
        let cases = [
            ("unsupported-version", "version"), ("local-identity", "unknown fields"),
            ("empty-profiles", "1...32"), ("fractional-period", "integer token"),
            ("unsorted-cues", "increase strictly"), ("silent-cue", "speech or a beep"),
            ("mixed-schedule", "cannot contain events"), ("duplicate-member", "Duplicate member")
        ]
        for (name, reason) in cases { rejects(try fixture(name, valid: false), containing: reason) }
    }

    func testIntegerNotationAndTypesAreNotCoerced() throws {
        let source = String(decoding: try ProfileBundleCodec.encode([periodic()]), as: UTF8.self)
        for token in ["60000.0", "6e4", "\"60000\"", "true", "null", "9223372036854775808"] {
            rejects(Data(source.replacingOccurrences(of: "\"periodMillis\":60000",
                                                    with: "\"periodMillis\":\(token)").utf8),
                    containing: "integer token")
        }
        rejects(Data(source.replacingOccurrences(of: "\"version\":1", with: "\"version\":1.0").utf8),
                containing: "integer token")
        rejects(Data(source.replacingOccurrences(of: "\"beepOnCue\":true", with: "\"beepOnCue\":\"true\"").utf8),
                containing: "boolean")
    }

    func testUnknownAndMissingFieldsAreRejectedAtEachLevel() throws {
        rejects(try mutated { $0["localId"] = "not-portable" }, containing: "unknown fields")
        rejects(try mutated { $0.removeValue(forKey: "version") }, containing: "missing fields")
        rejects(try mutated {
            var profiles = $0["profiles"] as! [[String: Any]]
            profiles[0]["ownerClockId"] = "local"
            $0["profiles"] = profiles
        }, containing: "unknown fields")
        rejects(try mutated {
            var profiles = $0["profiles"] as! [[String: Any]]
            profiles[0].removeValue(forKey: "speakOnCue")
            $0["profiles"] = profiles
        }, containing: "missing fields")
    }

    func testDuplicateEscapedKeysAndMalformedJSONCannotHideBehindMaps() throws {
        let duplicate = #"{"version":1,"\u0076ersion":1}"#
        XCTAssertThrowsError(try StrictJSON.parse(Data(duplicate.utf8))) {
            XCTAssertTrue($0.localizedDescription.contains("Duplicate member"))
        }
        for text in [#"{"a":1,}"#, "[1,]", #"{"a":01}"#, #"{"a":1e}"#, "true false",
                     #"{"a":"\q"}"#, #"{"a":"unfinished}"#] {
            XCTAssertThrowsError(try StrictJSON.parse(Data(text.utf8)), text)
        }
    }

    func testUTF8AndSurrogateEscapesAreStrict() throws {
        rejects(Data([0xC0, 0xAF]), containing: "UTF-8")
        for text in [#"{"name":"\uD800"}"#, #"{"name":"\uDC00"}"#, #"{"name":"\uD800\u0041"}"#] {
            XCTAssertThrowsError(try StrictJSON.parse(Data(text.utf8))) {
                XCTAssertTrue($0.localizedDescription.contains("surrogate"))
            }
        }
        guard case let .string(emoji) = try StrictJSON.parse(Data(#""\uD83C\uDF75""#.utf8)) else {
            return XCTFail("Expected string")
        }
        XCTAssertEqual(emoji, "🍵")
        XCTAssertThrowsError(try StrictJSON.parse(Data([34, 10, 34])))
    }

    func testContainerDepthSixteenAcceptedSeventeenRejectedBeforeDescent() throws {
        let allowed = String(repeating: "[", count: 16) + "0" + String(repeating: "]", count: 16)
        XCTAssertNoThrow(try StrictJSON.parse(Data(allowed.utf8)))
        let rejected = "[" + allowed + "]"
        XCTAssertThrowsError(try StrictJSON.parse(Data(rejected.utf8))) {
            XCTAssertTrue($0.localizedDescription.contains("depth"))
        }
    }

    func testDocumentSizeChecksActualBytesAtExactBoundary() throws {
        let small = try ProfileBundleCodec.encode([periodic()])
        let exact = small + Data(repeating: 32, count: ProfileSharingLimits.documentBytes - small.count)
        XCTAssertEqual(try ProfileBundleCodec.decode(exact).count, 1)
        rejects(exact + Data([32]), containing: "262144")
    }

    func testProfileAndEventCountBoundaries() throws {
        let profile = try periodic()
        XCTAssertEqual(try ProfileBundleCodec.decode(ProfileBundleCodec.encode(Array(repeating: profile, count: 32))).count, 32)
        XCTAssertThrowsError(try ProfileBundleCodec.encode(Array(repeating: profile, count: 33)))
        rejects(try mutated {
            let item = ($0["profiles"] as! [[String: Any]])[0]
            $0["profiles"] = Array(repeating: item, count: 33)
        }, containing: "1...32")
        let cues = try (0..<256).map { try SharedCue(offsetMillis: Int64($0), speechText: nil, beepDurationMillis: 1) }
        let scripted = try SharedProfile(name: "256", mode: .scripted, periodMillis: 0, events: cues)
        XCTAssertEqual(try ProfileBundleCodec.decode(ProfileBundleCodec.encode([scripted])), [scripted])
        XCTAssertThrowsError(try SharedProfile(name: "257", mode: .scripted, periodMillis: 0,
                                             events: cues + [SharedCue(offsetMillis: 256, speechText: nil, beepDurationMillis: 1)]))
        rejects(try mutated {
            var profiles = $0["profiles"] as! [[String: Any]]
            profiles[0]["mode"] = "SCRIPTED"
            profiles[0]["periodMillis"] = 0
            profiles[0]["events"] = (0..<257).map {
                ["offsetMillis": $0, "speechText": NSNull(), "beepDurationMillis": 1] as [String: Any]
            }
            $0["profiles"] = profiles
        }, containing: "256")
    }

    func testTextNormalizationAndByteCountsMatchAndroid() throws {
        XCTAssertEqual(try periodic(name: "\u{0} \tTrim me\r\n").name, "Trim me")
        XCTAssertEqual(try periodic(name: "\u{A0}").name, "\u{A0}", "NBSP must not be stripped")
        XCTAssertThrowsError(try periodic(name: " \t\n"))
        XCTAssertNoThrow(try periodic(name: String(repeating: "🍵", count: 64)))
        XCTAssertThrowsError(try periodic(name: String(repeating: "🍵", count: 65)))
        XCTAssertNoThrow(try SharedCue(offsetMillis: 0, speechText: String(repeating: "é", count: 1000), beepDurationMillis: 0))
        XCTAssertThrowsError(try SharedCue(offsetMillis: 0, speechText: String(repeating: "é", count: 1001), beepDurationMillis: 0))
        XCTAssertThrowsError(try SharedCue(offsetMillis: 0, speechText: " \t", beepDurationMillis: 0))
    }

    func testPortableRangesAndOrdering() throws {
        XCTAssertNoThrow(try periodic(period: ProfileSharingLimits.maxTime))
        for period in [Int64(0), -1, ProfileSharingLimits.maxTime + 1] {
            XCTAssertThrowsError(try periodic(period: period))
        }
        XCTAssertThrowsError(try SharedCue(offsetMillis: -1, speechText: nil, beepDurationMillis: 1))
        XCTAssertThrowsError(try SharedCue(offsetMillis: ProfileSharingLimits.maxTime + 1, speechText: nil, beepDurationMillis: 1))
        XCTAssertThrowsError(try SharedProfile(name: "short beep", mode: .periodic, beepDurationMillis: 49, periodMillis: 1))
        let first = try SharedCue(offsetMillis: 1, speechText: nil, beepDurationMillis: 1)
        XCTAssertThrowsError(try SharedProfile(name: "duplicate", mode: .scripted, periodMillis: 0, events: [first, first]))
        XCTAssertThrowsError(try SharedProfile(name: "mixed", mode: .periodic, periodMillis: 1, events: [first]))
        XCTAssertThrowsError(try SharedProfile(name: "script period", mode: .scripted, periodMillis: 1))
    }

    func testAllFlagCombinationsAndEscapedTextSurviveRoundTrip() throws {
        let cue = try SharedCue(offsetMillis: 0, speechText: "Quote \" slash \\ newline\n🍵", beepDurationMillis: 10_000)
        // Eight independent booleans have 256 combinations; none should be
        // collapsed into one preferred speech mode during a platform exchange.
        for bits in 0..<256 {
            let enabled: (Int) -> Bool = { bits & (1 << $0) != 0 }
            let flags = ProfileFlags(beepOnCue: enabled(0), beepOnCompletion: enabled(1),
                                     speakOnCompletion: enabled(2), speakCurrentTime: enabled(3),
                                     speakElapsedTime: enabled(4), speakRemainingTime: enabled(5),
                                     speakOnCue: enabled(6), speakClockNameWithSpeech: enabled(7))
            let profile = try SharedProfile(name: "Flags", mode: .scripted, periodMillis: 0, events: [cue], flags: flags)
            XCTAssertEqual(try ProfileBundleCodec.decode(ProfileBundleCodec.encode([profile])), [profile])
        }
    }

    func testOversizedOutputStopsAtWriterBoundary() throws {
        let cues = try (0..<256).map {
            try SharedCue(offsetMillis: Int64($0), speechText: String(repeating: "x", count: 2_000), beepDurationMillis: 0)
        }
        let profile = try SharedProfile(name: "too large", mode: .scripted, periodMillis: 0, events: cues)
        XCTAssertThrowsError(try ProfileBundleCodec.encode([profile])) {
            XCTAssertTrue($0.localizedDescription.contains("262144"))
        }
    }
}
