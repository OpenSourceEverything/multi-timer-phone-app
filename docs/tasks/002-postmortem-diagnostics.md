# Postmortem diagnostics

## Goal

Explain why a timer cue was late, silent or failed, with evidence that survives
process death and device restart. State the strongest conclusion the evidence
supports; when Android gives no cause, report the time window and `UNKNOWN`.
Instrument the existing timer engine before considering a scheduler rewrite.

## Roadmap

- **P0:** SQLite event ledger, durable expected-event records, execution
  transitions, startup reconciliation and bounded retention.
- **P1:** lifecycle, foreground-service, wake-lock ownership, Android-state and
  audio snapshots.
- **P2:** heartbeat/drift monitoring, evidence-based diagnosis and TXT/JSON
  export.
- **P3:** optional ADB bugreport integration.

## Constraints

- Keep diagnostics separate from timing decisions.
- Never block cue execution on diagnostic writes.
- Treat Android restrictions as evidence-based hypotheses, not proof.
- Do not require users to change Android or manufacturer settings for ordinary
  timer use. Observe those settings as evidence only.
- Track beep and speech actions independently when one event uses both.
- Use a rolling schedule horizon; do not materialize infinite periodic events.

## Reliability requirement: no manual settings fix

The product target is the most reliable behavior Android permits from its
normal install state. No app can promise cues while the phone is powered off,
the user has force-stopped the app, the operating system has blocked execution,
the speech engine is unavailable, or audio is routed/muted so the user cannot
hear it. The app must state these limits instead of claiming an absolute
guarantee. It must not direct users to battery-optimization screens as the
solution.

Two Samsung Android 12 reproductions show that the current foreground service,
200 ms Handler loop and partial wake lock are not sufficient on this device.
The first run correlated two late cues with screen wake but could not identify
who ended the lock. A later instrumented run captured Samsung's
PowerManagerService marking the app's partial wake lock DISABLED during
light-idle, with no matching app release. The process and service survived.
This confirms the immediate mechanism; why Samsung's policy disabled it remains
unknown. Do not replace the timer engine based only on correlation.

### Android scheduling options reviewed

- `setExactAndAllowWhileIdle()` is not a 30-second repeating solution. Android
  documents delivery throttling of about once per minute in normal operation
  and potentially about once per 15 minutes in low-power idle. It can batch
  pending alarms, so it cannot preserve every 30-second spoken cue.
- `setAlarmClock()` is the Android API intended for a user-visible alarm that
  may wake the device from Doze. Android warns that it can be extremely
  expensive for battery. It requires exact-alarm access on Android 12; the
  `SCHEDULE_EXACT_ALARM` route is special app access granted by the user, which
  conflicts with this product requirement. Android 13 and later offer
  `USE_EXACT_ALARM`, which is automatically granted but restricted to eligible
  core alarm/timer/calendar apps and subject to store policy. This is a
  candidate to evaluate, not an approved implementation.
- Standard alarms, jobs and WorkManager are deferred during Doze and cannot
  serve as a precise 30-second wake-up engine.
- Any wake-alarm experiment must measure battery cost, Android-version
  eligibility, alarm visibility and actual locked-screen cue delivery. Do not
  use an exact alarm every 30 seconds until that evaluation passes.

References: [Android Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby),
[AlarmManager scheduling](https://developer.android.com/develop/background-work/services/alarms),
and [AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager).

## Diagnostic contract for a missed cue

Each report should show the last proven transition and the first missing one,
with both UTC wall time and monotonic elapsed time:

- **Timer engine:** expected deadline, scheduled callback deadline, callback
  arrival, actual dispatch, lateness, and whether multiple due cues were
  coalesced or replayed.
- **Service/process:** process-instance ID, service create/start/destroy/stop,
  foreground entry/result, last heartbeat and tick gap, boot count, and
  `ApplicationExitInfo` reason when Android supplies one.
- **Wake lock:** acquire/release request, explicit release reason, held state
  before and after each operation, held state at tick entry before any
  reacquisition, and whether this process observed a missing lock without a
  matching app release. Include the responsible code path when known.
- **Power state:** device-idle mode, interactive/screen state, power saver,
  app standby bucket and battery-optimization exemption, sampled at the event
  boundary rather than later on the snapshot worker.
- **Audio:** player queue/start/done/error, audio-focus request/result/loss,
  output route when available, stream volume/mute and speech engine. A player
  completion callback proves the platform callback happened; it cannot prove
  that a person heard the sound.
- **Persistence and scheduling:** alarm API, permission/eligibility check,
  requested trigger, API return or exception, callback delivery, and
  cancellation/replacement reason for each wake alarm if one is introduced.

Classify only what was observed. For example, an app-recorded release identifies
our release path; a missing wake lock with no release record means “lock absent
at this observation,” not “Samsung killed it.” `ApplicationExitInfo` may identify
a reported process exit, but absent or generic exit history leaves a bounded
unknown window. A killed process cannot write a final log entry.

If a delayed tick crosses multiple dynamic elapsed-time readouts, speak the
latest current elapsed time once. Do not repeat the same current-time sentence
for each old interval. Keep the catch-up policy for scripted messages and beeps
separate and explicit.

## Acceptance

A persisted event can be traced from scheduled through dispatch and audio
completion, or reported with a bounded diagnosis such as process termination,
late engine observation, playback failure or unknown. A later app start can
reconcile missing transitions and export a human-readable summary with JSON
evidence.

## Current slice

- Added a separate SQLite ledger with event and transition tables.
- The timing service records the next expected cue within a bounded
  five-minute rolling horizon, then records DISPATCHED and EXECUTED.
- Startup reconciliation marks an overdue SCHEDULED row as FAILED when the
  recovery tick did not account for it.
- Retention removes old ledger rows, including stale unfinished records.
- Beep and TTS players now report AUDIO_STARTED, COMPLETED and FAILED through
  a small callback boundary. Events with both beep and speech remain pending
  until every attributed audio action completes.
- Android state snapshots now persist service lifecycle, scheduling, recovery
  and audio-failure context in a versioned `state_snapshot` table. The payload
  records device, process, foreground-service, power, battery, notification,
  alarm-policy and audio state as one point-in-time JSON object.
- A due cue with no enabled audio action is recorded as `NO_AUDIO_ACTION`
  evidence instead of being left indefinitely at `EXECUTED`.
- `ApplicationExitInfo` history is sampled when the service starts on Android
  11+, and a minute heartbeat records the largest observed service-tick gap.
  Gaps over five seconds also get an immediate state snapshot.
- `Export diagnostics` builds a bounded report on a worker thread. The Android
  share sheet receives one ZIP containing `diagnostics.txt` (summary first,
  transitions and state below) and `diagnostics.json` (structured evidence).
  The report includes up to 100 historical failures, 200 recent events and 500
  recent snapshots, with retained totals to show selection limits.
- The classifier reports observed categories and confidence. Scheduled rows
  left overdue, audio-player errors, missing player callbacks, and late
  dispatches are distinguished. Android restrictions remain hypotheses.
- Scheduler-gap snapshots explicitly report cause `UNKNOWN`, even if
  `PowerManager.WakeLock.isHeld()` says true after recovery. That API reports
  the app object's acquire/release state; it does not prove the device honored
  the CPU wake request throughout the gap. When both clocks are available, the
  snapshot also stores elapsed-minus-uptime as an estimated deep-sleep duration,
  not as a root-cause classification.
- A device export revealed that paused or replaced timer runs could leave old
  SCHEDULED rows that startup misclassified as missed callbacks. Successfully
  loaded clock/profile state now cancels undispatched expectations for inactive
  runs; cancelled rows remain visible evidence but are not report problems.
- Each active clock segment now has a persisted ID that survives a service
  rebase and changes on resume/reset. This keeps the same cue identity across
  process recovery. Overdue cue deadlines are reconstructed and existing
  expected times are not rewritten to the late dispatch time.
- Finish beeps and speech now get their own scheduled completion event within
  the same five-minute horizon, followed by dispatch, execution and separate
  audio callbacks. A finish action at the same instant as a scripted cue keeps
  a distinct ledger identity.
- Android-state collection and snapshot inserts now run on a bounded background
  worker. A full queue drops noncritical state samples, with a drop-count row
  on the next successful sample. Event/transition writes and rare lifecycle or
  wake-lock transition records remain synchronous so those key ledger changes
  survive a process crash; measure their worst-case latency before claiming
  diagnostics cannot delay a cue.

## Verification boundary

- Unit tests, lint and debug assembly pass.
- The debug APK installed and launched on the connected phone.
- An earlier live timer created `multi_timer_diagnostics.db` and produced
  durable SCHEDULED, DISPATCHED, EXECUTED, AUDIO_STARTED and COMPLETED rows for
  a spoken cue.
- The diagnostics schema created or migrated the `state_snapshot` table on the
  connected phone and stored real device observations for service startup,
  startup reconciliation, scheduling and foreground-service startup.
- The export APK was installed and launched on the connected phone without
  erasing saved clocks. Export diagnostics opened Android's share sheet; its
  ZIP passed integrity checks and contained parseable TXT and JSON reports.
  This is an export smoke test, not proof of every cue or power state.
- The first 30-second elapsed-speech run on a Samsung Android 12 phone produced
  four screen-off cues at 4:00, 4:30, 5:00 and 5:30; each reached the TTS
  started and completed callbacks. The 6:00 and 6:30 cues were instead
  dispatched together at 6:42, after the power button woke the screen. Their
  dispatch delays were 42.765 and 12.765 seconds. Android battery history
  recorded light-idle entry, then the app's timing wake lock ending, followed
  by CPU sleep until the screen wake. This linked the late batch to screen-off
  power management but did not identify the lock owner. Audio callbacks
  establish the TTS playback lifecycle, not that a person heard the sound.
- A second instrumented 30-second elapsed-speech run reproduced the failure
  without changing phone settings. At 18:14:43 EDT, Samsung's
  PowerManagerService logged `com.ose.multitimer.debug:timing` as
  `PARTIAL_WAKE_LOCK ... DISABLED`, after entering light-idle. No app release
  was logged, and the same process/service IDs were present at recovery. At
  18:16:05, USB reconnection coincided with the service resuming its Handler
  ticks and Samsung logging the lock as enabled again. The gap was 82.192 s in
  elapsed time and 2.551 s in uptime, a 79.641 s elapsed-minus-uptime estimate
  of device sleep. App-side `isHeld()` was true at recovery; that is not
  evidence the vendor honored the lock during the gap.
- The 13:30 and 14:00 cues were dispatched 57.541 s and 27.541 s late. Both
  TTS requests spoke `14 minutes 27 seconds elapsed`; their player start and
  completion callbacks were recorded. The 14:30 cue then resumed on schedule.
  This confirms a separate catch-up defect: delayed dynamic-time cues must be
  coalesced so the latest value is spoken once. Scripted cues and beeps still
  need their own explicit catch-up rule.
- A later cancellation-only APK also installed successfully. The newest APK,
  including stable-segment IDs, completion events and the asynchronous
  snapshot writer, then passed unit tests/lint/assembly and installed while
  the app service was stopped. The phone screen was not taken over for a
  lifecycle test: a paused/resumed timer and a finish-only profile still need
  physical-device exercise before those paths can be called verified.
- The two screen-off reproductions and the instrumented OS log establish the
  immediate Samsung wake-lock disable mechanism on this phone. The vendor's
  policy rationale remains unknown. No phone settings were changed. Process-
  exit correlation and audio-focus ownership remain unverified.
- The follow-up JSON fields and report wording that make this limitation
  explicit passed local tests/build. They were not installed over the active
  phone run, so that reporting update has not yet had a device export check.

## Implementation notes

- `TimingService` still owns scheduling. `AudioDispatch` only carries the
  event-to-action relationship from one service tick to the players.
- `BeepPlayer` merges overlapping beeps into one AudioTrack and fans its
  callbacks out to every event sharing that stream.
- `SpeechPlayer` maps Android utterance IDs back to their originating events.
- The audio tracker treats a mixed beep-plus-speech event as complete only
  after both actions finish; a failed action is terminal evidence for that
  event.
- `DiagnosticSnapshot` is deliberately read-only. It captures platform facts
  without requesting permissions, changing power policy or taking audio focus.
- `PowerManager.WakeLock.isHeld()` only describes the Java object's acquired
  state until app release. Vendor logs can reveal enforcement changes that the
  app API does not expose; reports must not treat `isHeld() == true` as proof
  that the CPU stayed awake.
- Snapshot writes are throttled per kind to avoid turning a frequent timer
  tick into an unbounded log stream. Unknown platform values are preserved as
  `unknown` rather than guessed.
- Event rows are retained for 30 days; denser snapshots for seven days.
  Pruning runs at service start and once per day during long-running services.
- TXT/JSON export uses a read-only, URI-granted content provider. Exports in
  the dedicated app cache older than seven days are removed on the next export.
- The report is a bounded selection, so a very old failure may be present in
  retained SQLite but outside the displayed snapshot window.
- A finish event created only at dispatch (for example, the very first tick
  after an already-expired short timer) has no earlier durable expectation;
  its report must not imply that it was pre-scheduled.
- Previously classified false alarms are retained as historical records, not
  silently rewritten; exports should be read with that migration boundary.
- Event and transition writes are still synchronous SQLite operations on the
  service thread. The asynchronous worker only removes noncritical snapshot
  queries/inserts from that path. Measure and improve worst-case ledger-write
  latency before claiming the diagnostic system cannot delay any cue.

## Remaining work

- Problem: durable diagnostic writes can occupy the timing-service thread.
  - Event/transition writes and rare lifecycle or wake-lock records are
    synchronous; contextual state snapshots use the bounded asynchronous
    worker.
  - Next: measure worst-case synchronous write latency, then design a
    durability-safe approach that does not delay cue execution.
  - Status: open; no latency benchmark has been run.
- Problem: screen-off cue delivery is unreliable on the tested Samsung phone.
  - Evidence now confirms Samsung PowerManagerService disabled the timing
    partial wake lock during light-idle; the timer process remained alive.
  - Task 003's debug-only continuous AudioTrack experiment completed a
    user-reported 20-minute unplugged screen-lock run. Frame progress remained
    monotonic and the logged underrun count stayed at zero. The user heard
    beeps while locked but did not monitor the complete run; every cue is not
    independently verified. The existing timer engine remains unchanged.
  - This result supports the candidate on one phone; it does not promise that
    Android or Samsung will keep the stream audible under every condition.
  - Next: prototype spoken cues on the same sample timeline in a separate
    debug-only run. Keep full cadence recording as a later acceptance check.
  - Exact alarms are not the current first experiment. Revisit them only if
    the AudioTrack trial fails or product requirements materially change.
  - Coalesce overdue dynamic elapsed/remaining-time readouts into one current
    announcement. The reproduction showed duplicate identical text for two
    missed cue intervals. Define scripted-message and beep catch-up separately.
  - Verify pause/resume and finish-only cues separately.
- Optional extension: ADB bugreport integration is P3 and is not needed to
  finish the current ledger/export work.

The implemented work above is not a guarantee that Android will execute every
background cue. See [the task dashboard](README.md) for how this diagnostics
track relates to profile sharing and the separate state-coordination refactor.
