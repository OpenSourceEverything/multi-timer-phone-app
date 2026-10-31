# MultiTimer work map

This page separates delivered work from the remaining product and release
tracks. Linked task files own detailed requirements and acceptance evidence.
Tasks 006–008 and the task-001 action-policy refactor are committed and pushed.
Android 12 file exchange and Loading/Saving Activity-recreation checks pass on
the phone; the corrected instrumentation tests also pass in hosted Android 15
CI. Process-death recovery remains untested. Completion below distinguishes
local, device and hosted evidence.

## Timer and profile behavior

- Recent user-facing work—overlapping profiles, reusable/private profile
  editing, scripted cues, and spoken time—is in the current source baseline.
- No separate implementation task is currently open for those requests.
- Phone checks still listed under diagnostics are not implied to pass because
  the corresponding code exists.

## Diagnostics — work begun before profile sharing

- Implemented: persistent event ledger, lifecycle/state snapshots, audio
  callbacks, startup reconciliation, bounded reports, and TXT/JSON export.
- Screen-off test reproduced on Samsung Android 12. PowerManagerService logged
  the app's timing partial wake lock as DISABLED during light-idle; the process
  survived, then resumed after USB reconnection. Two spoken cues arrived late
  and repeated the same current elapsed-time value. This confirms the immediate
  mechanism on this phone; Samsung's policy rationale remains unknown.
- Implemented diagnostics preserve scheduler gaps, lock acquire/release
  reasons, tick-boundary power state, process/service IDs and explicit UNKNOWN
  cause classification. App-side `isHeld()` is not proof the device honored
  the wake request.
- Historical prototypes: [003](003-continuous-audiotrack-prototype.md) and
  [004](004-audiotrack-speech-prototype.md) informed the production audio
  design. Their standalone launcher/service has been removed from the current
  source. The separate `com.ose.multitimer.debug` install was removed from the
  tested phone and its local data discarded; new phone tests use the main app.
- In progress: [005 — Production audio timeline](005-production-audio-timeline.md).
  Real timer event batches now use one continuous PCM player while
  TimingService retains schedule decisions and diagnostic attribution. The
  unit/lint/build gate passed. A Samsung Android 12 screen-off run recorded 17
  completed 30-second elapsed-speech events and no AudioTrack underrun/failure
  observations. It used an existing 30-minute timer with four profiles, so it
  is not the isolated four-minute acceptance run; event callbacks alone do not
  prove the user heard each cue. The latest locked-screen spoken-timer test was
  reported as passed, but no test conditions, cue
  count, or diagnostic export. This records reported audibility only; timing
  evidence remains open. The run showed 0.45–4.52 seconds of playback-start delay, with
  longer delays near overlapping minute cues; queueing is a hypothesis to test.
  Revisit exact-alarm scheduling only if this candidate fails or requirements
  change. A later failure report came from an older main-app APK installed
  before `AudioTimelinePlayer` was added: that APK had an approximately
  80-second screen-lock scheduler gap, then completed three cues 26–81 seconds
  late, and contained no AudioTrack implementation. It does not test task 005's
  current implementation. The debug build now shares the main application ID
  and signing certificate, has one Multi Timer launcher, and is installed in
  place. All new phone tests use **Multi Timer**. The separate `.debug` package
  was removed from the tested phone and its local data discarded. The
  single-profile timing-evidence check remains open.
- Still separate: coalesce overdue dynamic-time speech and define catch-up
  behavior for scripted messages and beeps.
- Still open: verify pause/resume and finish-only events, process-exit history
  and audio focus.
- Still open: resolve or measure synchronous event/transition writes on the
  timing-service thread against the goal that diagnostics must not delay cues.
- Optional: ADB bugreport integration.
- Details and evidence: [002 — Postmortem diagnostics](002-postmortem-diagnostics.md).

## Maintainability — older quality roadmap

- Complete: `ClockActionPolicy` provides the same tested Start/Pause decision
  to clock-card rendering and click handling.
- This is a small refactor, not a user-visible feature or a dependency for
  profile sharing.
- Scope: [001 — Quality and profile sharing](001-quality-and-profile-sharing.md).

## Portable profile exchange — active feature track

- Android: [003–009 roadmap](profile-sharing/README.md), then user guide/demo
  checkpoint 015A.
  - Tasks 003–007 are complete and on main in separate feature commits.
  - 007 import coordinator/store boundary passed the full 137-test, lint and
    debug-build gate.
  - 008 Android document-picker UI is implemented and pushed. Physical Android
    12 checks covered export/import/confirmation, cancellation, invalid input,
    a running timer, relaunch, rotation and size-limit rejection.
    Deterministic Loading and Saving Activity-recreation tests pass locally and
    in hosted Android 15 run 40833133919 on commit 25114f6. The preceding run
    exposed an Espresso window-focus race during dialog replacement; the
    correction observes the held state on the Activity thread and still asserts
    the real save result. Process death remains untested.
  - 009 HTTPS catalog/cache is optional; ordinary timer use stays offline.
- iOS companion: tasks 010–013A, followed by local cross-platform verification
  at 014. The actual hosted Xcode build and strict Swift codec gates passed
  (010–011). Editor and file-exchange code is implemented, with actual simulator
  control/picker acceptance still tracked in 012–013A. This is not required for
  ordinary Android profile exchange.
- 015A is complete: ordinary-user Android instructions, three screenshots and
  a 30-second recording are included. 015B covers combined delivery after 014.
- [016 — Verify import recovery after process termination](profile-sharing/016-android-process-recovery.md)
  tracks the remaining process-death check separately from Activity recreation.

## Remaining verification and next steps

Keep diagnostics work separate from profile-exchange acceptance.

1. Finish the simulator editor/system-picker checks in 012–013A, then perform
   the actual local-file cross-platform check at 014. The hosted Mac build
   route and strict codec are already verified.
   Keep Android's timer engine unchanged. Tasks 009 and 013B remain optional
   network/cache work and are deferred.
2. Run task 016 process-termination verification on a disposable emulator.
   This is additional Android coverage; 008/015A are complete within their
   recorded Activity-recreation and file-workflow boundary.
3. Continue task 002 diagnostics verification separately:
   - Verify pause/resume and finish-only cues in the ledger.
   - Measure synchronous ledger-write latency before claiming diagnostics
     cannot delay a cue.
   - Verify process-exit history and heartbeat/drift evidence.
   - Exercise screen-lock/Doze and audio-focus conditions, restoring device
     settings and treating Android restrictions as hypotheses unless proven.

The detailed open checks and device evidence remain in [008](profile-sharing/008-android-file-ui.md)
and [002](002-postmortem-diagnostics.md); this queue states their order, not a
claim that any pending check has passed.

## Release and presentation

- The CI workflow and local build/test baseline are in source. Check hosted CI
  separately; a workflow file or local pass is not a hosted result.
- User-facing Android guide, screenshots and recording belong to 015A; the
  actual two-platform package is 015B after task 014.
- Play account, signing, store listing, closed testing, and rollout are a
  separate release track, not prerequisites for task 006.

## Where status belongs

- This file: short feature buckets and the next task.
- 001 and 002: remaining architecture and diagnostics work respectively.
- [Profile-sharing index](profile-sharing/README.md): detailed 003–015 feature
  dependencies and acceptance checks.
- The source task index owns product-task status. Learning records explain code
  but are not another implementation checklist.

Follow [CONTRIBUTING.md](../../CONTRIBUTING.md) for readable code, comments,
tests, and accurate verification claims.
