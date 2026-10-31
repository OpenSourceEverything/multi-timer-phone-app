import XCTest
#if SWIFT_PACKAGE
@testable import MultiTimerProfileCore
#else
@testable import MultiTimerProfiles
#endif

final class ProfileEditorTests: XCTestCase {
    private func profile(period: Int64 = 30_000) throws -> SharedProfile {
        try SharedProfile(name: "Elapsed 🍵", mode: .periodic, periodMillis: period,
                          flags: ProfileFlags(beepOnCue: false, speakCurrentTime: true,
                                              speakElapsedTime: true, speakRemainingTime: true,
                                              speakClockNameWithSpeech: true))
    }

    func testInvalidDraftDoesNotMutateSavedSnapshotAndCancelDiscardsIt() throws {
        let saved = try profile()
        var draft = ProfileDraft(saved)
        draft.name = ""
        draft.periodSeconds = "no"
        XCTAssertThrowsError(try draft.validated())
        XCTAssertEqual(saved.name, "Elapsed 🍵")
        XCTAssertEqual(saved.periodMillis, 30_000)
        XCTAssertEqual(try ProfileDraft(saved).validated(), saved, "Reopening discards an unsaved invalid draft")
    }

    func testFlagsAndInactiveModeFieldsSurviveDraftEdits() throws {
        let original = try profile()
        var draft = ProfileDraft(original)
        draft.mode = .scripted
        draft.events = [CueDraft(offsetSeconds: "0.001", speechText: "Start", beepMillis: "1")]
        let scripted = try draft.validated()
        XCTAssertEqual(scripted.flags, original.flags)
        XCTAssertEqual(scripted.events[0].offsetMillis, 1)
        draft.mode = .periodic
        XCTAssertEqual(try draft.validated(), original)
        XCTAssertEqual(draft.events.count, 1, "Switching back must not erase an inactive cue draft")
    }

    func testSecondsConversionPreservesPortableMillisecondsWithoutDoubleRounding() throws {
        for millis: Int64 in [0, 1, 10, 999, 1000, 60_001, ProfileSharingLimits.maxTime] {
            XCTAssertEqual(try DurationText.milliseconds(DurationText.seconds(millis), field: "test"), millis)
        }
        for text in ["", ".5", "1.", "1.0001", "-1", "1e3", "9007199254741"] {
            XCTAssertThrowsError(try DurationText.milliseconds(text, field: "test"), text)
        }
    }

    func testPreviewCapsDensePeriodsAndCompletionOnlyHasNoScheduledCues() throws {
        XCTAssertEqual(ProfilePreviewPlan.cues(try profile(period: 1)).count, 50)
        XCTAssertEqual(ProfilePreviewPlan.cues(try profile(period: 60_001)).count, 0)
        let completion = try SharedProfile(name: "Finish", mode: .scripted, periodMillis: 0)
        XCTAssertTrue(ProfilePreviewPlan.cues(completion).isEmpty)
    }

    func testStoppingAndReplacingPreviewCancelsPriorDueEvents() throws {
        var timeline = PreviewTimeline()
        timeline.start(try profile(period: 1000), now: 100)
        XCTAssertEqual(timeline.poll(now: 101).count, 1)
        timeline.stop()
        XCTAssertTrue(timeline.poll(now: 160).isEmpty)
        timeline.start(try profile(period: 30_000), now: 200)
        XCTAssertTrue(timeline.poll(now: 201).isEmpty, "Old one-second run must not leak into new preview")
        XCTAssertEqual(timeline.poll(now: 230).map(\.offsetMillis), [30_000])
        XCTAssertEqual(timeline.poll(now: 260).map(\.offsetMillis), [60_000])
        XCTAssertFalse(timeline.isRunning)
    }

    func testLocalLibraryRoundTripPreservesIDsAndRejectedSavePreservesOldFile() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        XCTAssertTrue(try file.load().isEmpty)
        let saved = [LocalProfile(profile: try profile())]
        try file.save(saved)
        XCTAssertEqual(try file.load(), saved)
        let priorBytes = try Data(contentsOf: file.url)
        XCTAssertThrowsError(try file.save([saved[0], saved[0]]))
        XCTAssertEqual(try Data(contentsOf: file.url), priorBytes)
        try file.save([])
        XCTAssertTrue(try file.load().isEmpty)
    }

    func testCorruptLibraryIsReportedInsteadOfSilentlyBecomingEmpty() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = ProfileLibraryFile(url: directory.appendingPathComponent("library.json"))
        try Data("bad existing data".utf8).write(to: file.url)
        XCTAssertThrowsError(try file.load())
        XCTAssertEqual(try String(contentsOf: file.url, encoding: .utf8), "bad existing data")
    }
}
