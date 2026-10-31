# 012 — Edit and preview profiles in SwiftUI

Status: Complete for the editor/visual-preview slice. The create/edit/preview/
stop/cancel UI test passed on Xcode 16.4 / iOS 18.5 in run 40856547098, along
with app-adapter and portable tests; actual captures were recovered and
reviewed. That broader run failed only at the separate 013A picker setup.
Dependency: 011, complete and hosted-verified.

The editor keeps invalid draft text separate from saved values, persists
companion-local UUIDs beside portable data using atomic file replacement, and
displays a foreground visual preview capped at 60 seconds/50 cues. Preview
plays no audio. MainActor owns published state; disk work uses a worker.
UI tests drive create/edit/preview/stop/cancel and keep actual XCTest captures.

First hosted gate 40855092264 stopped in the macOS SwiftPM compile because
the package had no minimum macOS version; FileHandle close/read APIs were
unavailable at SwiftPM's older default deployment target. The package now
declares macOS 13 / iOS 17. The subsequent ARM run reached and passed the real
editor UI case; its file-picker gap remains under 013A.

## Outcome

Give the companion one useful workflow: edit a reusable profile and preview its schedule.

## Read first

Swift model/codec, shared schema and fixtures; companion project build/test instructions.

## Bounded work

- Add profile list, periodic/scripted editor and clear validation messages with modest accessible layouts.
- Separate SwiftUI view rendering from editable state/decisions in a small testable model.
- Add a bounded cue preview with start/stop/cancellation; never expand an unbounded periodic schedule.
- Explain state ownership and view lifecycle in code comments.
- Test edits, validation, cancellation and representative screen-state transitions.

Suggested implementation order:

1. Establish one editor-state owner outside View bodies. Keep draft fields
   distinct from the last valid saved model so an invalid edit can show errors
   without destroying a usable profile. Preserve all eight independent flags.
2. Add list -> edit -> validate/save/cancel. Do not add accounts, analytics,
   navigation frameworks or a port of Android's background timing service.
3. Add a foreground preview only. Inject a clock/scheduler for deterministic
   tests; cap the preview horizon/event count, use monotonic elapsed time and
   stop outstanding work on Stop, navigation away or scene inactivity.
4. Document which preview actions are displayed versus actually played/spoken.
   Test schedule generation independently of sound. Simulator rendering is not
   a physical-device audio-timing benchmark.
5. Check long/Unicode text, invalid numbers, periodic/scripted switching and
   readable errors. Capture actual screens only after the simulator runs.

Minimum tests: invalid draft preserves saved profile, cancel discards draft,
all flags survive an edit, a new preview cancels the old one, Stop/exit cancels
scheduled work and periodic previews remain bounded. No sleep-based unit test
should claim real-time accuracy.

## Verification

Run model tests and the simulator build; exercise list/edit/preview on an identified simulator/device with actual screenshots.

## Done when

A person can create/edit/preview a valid profile; leaving or stopping the preview cleans up its work.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 013-ios-sharing-cache.md.
