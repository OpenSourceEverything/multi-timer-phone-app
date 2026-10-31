# 008 — Add Android file sharing

Status: Android file exchange and Loading/Saving Activity-recreation checks
pass on Android 12; the corrected lifecycle tests also pass in hosted Android
15 CI. Process death remains untested.
Dependencies: 007, complete.

## Outcome

Let a person export reusable cue profiles and preview/confirm a chosen profile file.

## Read first

MainActivity.java; AndroidManifest.xml; existing DiagnosticExportProvider/Exporter; import coordinator; Android storage access framework documentation.

## Bounded work

- Add the smallest Kotlin screen or focused UI integration for export and import; retain familiar timer controls.
- Use Android document/share APIs and URI grants rather than broad storage permissions.
- Read bounded bytes and perform work away from the UI thread. A destroyed/recreated screen must not receive a stale callback or import twice.
- Show profile names, schedule summary, count and validation errors before explicit confirmation.
- Export no private timer-owned profiles, clock state, diagnostics or local IDs.
- Document a device checklist for valid exchange, cancelled picker, malformed input, rotation/reopen and a running timer during import.

Suggested implementation order:

1. Add import/export actions to the existing reusable-profile list. Export only
   an explicit reusable selection; never infer it from every stored profile.
2. Use the system document picker for reading/creating files. Restrict URI
   access to the granted operation and close streams. Read at most the agreed
   byte limit plus one byte so oversized input can be rejected without a full
   allocation; send validated bytes through the codec/coordinator.
3. Show Preview and an explicit Import confirmation with the number/names of
   new copies. Disable repeated confirmation while Saving. Cancellation and
   picker failure return without changing persistent profiles.
4. Keep blocking reads/writes off the main thread. The lifecycle adapter must
   detach old observers and invalidate stale callbacks. On recreation either
   retain the current in-memory operation safely or cancel back to Idle with
   a clear message; do not automatically replay confirmation after restart.
5. Exercise the manual checklist against an identified build. Do not install
   on a person's device without the required authorization; an unavailable
   device check stays pending while independent unit-test work continues.

Test URI/open/read/write failures, a lying/absent reported file size, cancelled
picker, excessive bytes, stale callbacks and repeated confirm. Verify export
contains no IDs/ownership/clock data. Record whether rotation/reopen was
actually observed; pure coordinator tests cannot prove Android lifecycle work.

## Verification

Run unit tests/lint/debug assembly. Exercise the identified debug build on a device or emulator, recording actual observations separately.

## Done when

The file workflow is usable and rejected/cancelled imports preserve current
clocks/profiles. Unit/build acceptance and device acceptance are tracked
separately; unperformed device checks remain pending, not silently completed.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Activity recreation during active Loading/Saving is now verified. Main-screen
rotation, picker rotation, preview recreation and oversized picker input were
also observed. Optional task 009 is deferred; actual process termination is
tracked in [016](016-android-process-recovery.md).

## Implementation and verification — 2026-10-28

- Added explicit reusable-profile selection, JSON export through
  `ACTION_CREATE_DOCUMENT`, and import through `ACTION_OPEN_DOCUMENT`. Streams
  are bounded and processed away from the UI thread; no broad storage
  permission was added. Import requires preview selection and confirmation.
- Added `ProfileBundleFileIO` tests for exact-size input, oversized input,
  read/write failures, flush behavior and refusing oversized output before
  touching the destination. Full suite: 137 tests, zero failures/errors/skips;
  lint 0 errors/17 warnings; debug assembly passed. `git diff --check` passed.
- Manual checks on a Samsung Android 12 phone, installed as the separate
  `com.ose.multitimer.debug` package:
  - Exported one selected reusable profile. The resulting v1 JSON contained
    that profile's portable fields only: no profile/event IDs, clock owner or
    clock state.
  - Opened the file for import; preview showed its name and action summary.
    Cancelling preview reported that no profiles changed. Confirming imported
    one profile. Importing again while a 10-minute timer ran left the timer
    running and its profile attachment unchanged.
  - A checked-in duplicate-key JSON fixture was rejected with a specific
    validation error; the running timer continued. Cancelling the document
    picker returned to the app without changes.
  - Backgrounding and reopening the activity preserved the running timer. The
    timer was then paused; after deleting only test-created clock/profile rows,
    the app had no clocks and its six seeded reusable profiles remained.
    Force-stop/relaunch with no active timer also reopened cleanly.
  - On the final APK, Android's window manager forced portrait → landscape →
    portrait. The main screen rendered in both orientations and remained the
    resumed Activity. This checked a display configuration change, not physical
    sensor-driven rotation and not rotation with a picker/preview open.
  - The preview initially described a beep-only template as also speaking.
    `speakOnCue` is a permission gate, not proof that speech content exists.
    `SharedProfileDescription` now checks configured time-speech flags or
    scripted text, plus the master switch; four unit tests cover these cases.
    The corrected summary was confirmed on the rebuilt APK.
- The tested debug build is version 1.6.2-debug / code 12, SHA-256
  `c445fb671da9dfba6ebfa76b839c5654ebbf9afce06b7d93073692d335927a77`.
  It was installed beside the Play app; the Play package and its data were not
  changed. The debug APK's app data and seeded profiles were preserved during
  update. Temporary JSON files created for the device check were removed.
- The entire picker workflow was not repeated on the final APK; the additional
  device-acceptance section below records the checks that were repeated. No
  release or Play-store publication occurred.

## Additional device acceptance — 2026-10-28

The following checks used the already-installed `com.ose.multitimer.debug`
version `1.6.2-debug` / code 12, SHA-256
`c445fb671da9dfba6ebfa76b839c5654ebbf9afce06b7d93073692d335927a77`, on the
A Samsung phone running Android 12. No timer was active.

- Opened the import picker in portrait, changed the display to landscape, and
  confirmed Android DocumentsUI remained open with the Downloads list visible.
  Returned to portrait and cancelled without selecting a file.
- Opened the exported profile in the import preview and changed landscape to
  portrait. MainActivity was recreated, returned to the home screen, and showed
  `Import screen was recreated. Check Reusable profiles before retrying.` No
  profile was imported. This verifies safe interruption, not preservation of
  the preview across recreation.
- Selected a 262,145-byte JSON document through the system picker. The app
  rejected it with `Profile bundle exceeds 262144 bytes`; it did not show an
  import preview or change stored profiles. The timer list was empty and
  TimingService was not running before the check.
- Restored the phone's original rotation lock and system rotation values. All
  test-created Download files and temporary screenshots were removed; the
  prior Play installation and its data were not touched.

A fast rotation attempt 20 ms after selecting a maximum-size whitespace JSON
did not expose a confirmed Loading state or interruption message. It does not
count as Activity-recreation evidence; the active Loading/Saving check remains
open.

Still pending: Activity recreation while the app is actively reading or
validating (`Loading`) and while it is saving (`Saving`). These worker states
may finish before a person can rotate the screen, so there is no reliable
manual click sequence with the current UI. Instrumentation tests now hold the
background worker queue at these points without adding production delays.
For `Saving`, the test releases the held write after recreation and confirms
the profile appears exactly once. This is an Activity-lifecycle check, not
proof of recovery after Android kills the process. This historical checkpoint
predates the local test pass below.

## Latest lifecycle verification — 2026-10-29

- Hosted run 40832225232 on source commit 7bdebc3 passed the app build and
  emulator setup. One of two instrumentation tests failed in the old test
  harness: Espresso could not acquire window focus while the Saving dialog
  replaced the Preview dialog. This was a test synchronization failure, not an
  assertion that the profile append failed.
- The local test correction reads the real coordinator/dialog on the Activity
  main thread and clicks the actual confirmation button without Espresso's
  transient root-focus lookup. The notification-permission grant is also
  limited to Android 13 and newer; Android 12 does not expose it as a runtime
  permission.
- Both tests passed when invoked directly through Android instrumentation on
  the connected Android 12 phone: Loading recreation discarded the delayed
  decode without adding a profile; Saving recreation completed one append
  exactly once. App source behavior was unchanged. Test APK SHA-256:
  a3694f764040957d19dafba0a750c4c57a757c51895b42e74e72e49cd5af447d.
- Hosted run 40833133919 on commit 25114f6 passed the app unit-test/lint/build
  step, emulator startup, and the connected Android lifecycle step. The log
  reports both instrumentation tests completed with zero failures or skips.
- This proves Activity recreation in one process. It does not prove process
  death, OS restart, or durable recovery after the process is killed.
