# Production audio timeline

## Goal

Use one continuous PCM AudioTrack for timer-generated beeps and speech, so
scheduled actions share a sample timeline while the screen is locked. Keep
TimingService responsible for clock state and due-event decisions. Keep profile
data and timer persistence compatible.

## Design boundary

- TimingService remains the authoritative timer scheduler. It submits the
  already-due actions and diagnostic event IDs as one audio batch.
- AudioTimelinePlayer owns one long-lived 48 kHz mono signed-16-bit AudioTrack
  while an audio-enabled clock is active. It writes silence between cues and
  places beep and prepared speech samples into that stream.
- TTS synthesis runs off the TimingService thread. Use Android's reported PCM
  format, normalize and resample the clip, then enqueue the batch. A batch with
  both actions starts at one writable frame; the prototype's beep ducking is
  retained as an initial value pending listening review.
- Preserve current semantics: simultaneous beeps merge to the longest active
  beep, speech requests retain dispatch order, and each originating event gets
  its existing AUDIO_STARTED, COMPLETED or FAILED callbacks.
- A TTS clip that is not ready when a batch is submitted must not block the
  timer Handler. Queue it when ready, record actual lateness through the
  existing diagnostic callbacks, and fail explicitly on synthesis timeout or
  playback error. Future work may add look-ahead synthesis if measured lateness
  requires it.
- Declare the service's mediaPlayback foreground-service type only when the
  live audio timeline is active. The current specialUse timer behavior remains
  declared. Do not change battery settings or claim behavior after force-stop,
  process termination or power-off.

## Implementation order

1. Move PCM conversion and mixing out of the debug-only source set into shared
   production code; keep the debug prototype using the same implementation.
2. Add AudioTimelinePlayer with continuous output, asynchronous TTS preparation,
   ordered batch mixing, bounded lifecycle and playback callbacks.
3. Route TimingService batches into that player. Keep the existing event ledger
   registration before dispatch and wait for pending audio before idle shutdown.
4. Test frame placement, PCM formats, overlapping beeps, speech ordering,
   cancellation, failed synthesis and end-of-run cleanup.
5. Install the debug build over the main Multi Timer app
   (`com.ose.multitimer`); do not install a separate debug app. Verify a real
   30-second elapsed-speech timer while the screen is locked, then run a longer
   mixed schedule and inspect the event ledger and underrun observations.

## Reproducible phone check

Run this in the main Multi Timer app, installed from the debug build with the
same application ID and signing key. This updates the app in place; do not
install a second debug package or clear existing app data.

1. Update the existing main app in place, open **Multi Timer**, and verify the
   existing 30-second elapsed-speech timer and its selected profile. Do not
   create another timer or profile for this reproduction.
2. Start it while the screen is awake and confirm one elapsed-time phrase is
   audible. This confirms the test setup before screen-off.
3. Disconnect USB power, lock the screen with the phone's Power button, and
   leave it locked for two minutes. Unlock before reconnecting USB.
4. Record the number of cues heard, late or repeated cues, charging/screen
   state, Android version, and whether another audio app was playing. A
   listener's report is evidence of audibility; the ledger alone cannot prove
   what reached a person.
5. Export diagnostics from **Multi Timer**. Compare expected times with
   `AUDIO_STARTED`/`COMPLETED`, playback-head frames, service and wake-lock
   snapshots, and underrun observations. Redact device identifiers and timer
   names before committing a summary. Keep raw exports out of Git unless
   reviewed for personal data.
6. Pause the existing timer after collecting evidence. Preserve all existing
   timers, profiles, and diagnostic records.

If the test fails, preserve the exported evidence before changing the timer
engine. If the USB connection must remain attached, record that condition and
do not describe the result as an unplugged/Doze test.

## Verification status

- Implemented in the production timer service. Earlier screen-off testing was
  performed on a separate debug package; new phone testing must update and use
  the main `com.ose.multitimer` package in place.
- Local gate after removing the standalone prototype: `testDebugUnitTest`,
  `lintDebug`, and `assembleDebug` passed. The unit report contains 158 tests
  with zero failures/errors/skips; the four prototype-only PCM tests were
  removed when their unused production-path dependency was replaced by mixer
  coverage. `processReleaseMainManifest`, the debug-APK identity guard, and
  `git diff --check` passed. The Android CI workflow now runs the same package,
  label, and single-launcher guard after assembly.
- Hosted Android checks passed for commit `1bd0fb8`, including unit tests,
  lint, debug assembly, and Android 15 emulator lifecycle/file-preservation
  instrumentation: [run 41065357362](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/41065357362).
- Follow-up failure, 2026-10-30: the Samsung Android 12 main package was
  version `1.6.2`, installed before `AudioTimelinePlayer` was added. APK
  inspection confirmed it contained the legacy `BeepPlayer`/`SpeechPlayer` but
  not `AudioTimelinePlayer`/`TtsPcmSynthesizer`. Its diagnostics show a roughly
  80-second scheduler gap across screen lock; three later cues completed about
  80.7, 53.6, and 26.4 seconds after schedule. The old APK recorded no
  AudioTrack start/underrun/failure observations. This is evidence that the
  tested main app was stale and missed its deadlines; it is not a test of the
  new AudioTrack implementation and does not prove why Android delayed the
  scheduler. Samsung later logged a disabled wake lock, but that log entry was
  about 233 seconds after the recorded gap and is not direct proof of its
  cause.
- The rebuilt debug APK is configured with application ID
  `com.ose.multitimer`, the **Multi Timer** label, one launcher activity, and
  the same signing certificate as the installed main app. It was installed
  with `adb install -r`; the installed APK hash matches the build. The main
  app's existing saved data remained unchanged.
- Observed phone run, 2026-10-30: the debug build ran on a Samsung Android 12
  test phone with the screen off and USB disconnected for about four minutes.
  Snapshots showed the service alive, the app's wake-lock object held, no
  reported device-idle mode, roughly 0.30–0.36 second tick gaps, and no
  estimated deep sleep. The diagnostics database recorded 17 completed
  30-second elapsed-speech events through 510 seconds. Their `AUDIO_STARTED`
  transitions were about 0.45–4.52 seconds after the expected cue times.
  The ledger contains no AudioTrack underrun or playback-failure observations.
- This was not the isolated procedure above: the running clock was an existing
  30-minute timer with four attached profiles, including 30-second elapsed
  speech and other 60-second/scripted cues. Some elapsed-speech starts at
  overlapping minute marks were over four seconds late. That correlation makes
  TTS queueing a follow-up to measure; it does not establish the cause. The
  timer was paused after capture. The listener's count of cues actually heard,
  audio route, and an independent recording are not available. Player callbacks
  cannot prove audibility, so the controlled single-profile acceptance check
  remains open.
- Latest locked-screen spoken-timer test (reported): passed.
  Device/OS, run length, power state, cue count, audio route, and diagnostic
  export were not reported. This records audible success as reported, but does
  not establish timing accuracy or reliability across other conditions.

## Closeout and next work boundary

- The standalone Audio Prototype launcher/service has been removed from the
  current debug build. Debug and release now share the main application ID;
  all new phone tests run in **Multi Timer**.
- The separate debug package was removed from the test phone; its local data
  was discarded. That phone now uses only
  the main `com.ose.multitimer` package. Other devices may have different state.
- The latest locked-screen spoken-timer test is reported as passed. A fully
  reproducible diagnostic result remains open until timing evidence and test
  conditions are captured; do not infer them from the pass report.

## Acceptance and limits

- No Timer, BeepProfile, profile JSON, or user-facing schedule changes.
- Debug and release use one application ID, so phone testing updates the main
  app instead of creating another app identity.
- The app can start one AudioTrack for its active timer audio and reports
  player callbacks against the correct durable event IDs.
- A locked-screen phone check confirms the actual spoken cues are heard and
  compares them with expected/actual ledger times. Zero AudioTrack underruns
  alone is not acoustic proof.
- Record battery impact and audio route. A successful Samsung test applies to
  that device and condition only; Android may still stop or restrict the app.
- Preserve deterministic unit tests, exact reproduction steps, and privacy-safe
  phone evidence in the public project repository.
