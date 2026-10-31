# Continuous AudioTrack screen-off prototype

## Goal

Determine whether a continuously active PCM AudioTrack with one sample-timed
beep per second continues through a 20-minute unplugged, screen-locked run on
the Samsung Android 12 test phone.

## Scope and safety

- Prototype only in the debug APK; do not change the released timer engine.
- Use a `mediaPlayback` foreground service started from the visible test screen.
- Keep AudioTrack playing through the silent parts of each one-second sample
  period. Do not schedule beeps with Handler callbacks or exact alarms.
- Record audio frame timestamps, monotonic and elapsed-realtime clocks, buffer
  underruns, output route type, and screen-interactive state to a private CSV.
- Do not clear app data, uninstall either app, run connected Gradle tests on the
  daily-use phone, or change power/battery settings.
- AudioTrack timestamps and underrun counts are platform evidence; an external
  recording is required to assess audible output.

## Exact phone procedure

1. Install or update only Multi Timer Debug. Do not update the Play package.
2. Leave saved timers and profiles untouched. Unplug USB power and keep the
   phone on its normal speaker route.
3. Open the separate `Audio Prototype Debug` launcher icon.
4. Confirm the alarm volume is audible, tap **Start 20-minute test**, and
   listen to at least 30 seconds of one-beep-per-second playback while awake.
5. If possible, start an audio recording on a second device near the phone.
6. Press the phone's power button once to lock it. Leave it locked and
   unplugged for the full 20 minutes; do not interact with it.
7. Unlock the phone. The prototype should have stopped automatically. If it
   still shows active, tap **Stop test** and wait for the saved state to change.
8. Tap **Export last run log**. Keep the ZIP and, when available, the external
   audio recording together for review.

## Review criteria

- The PCM generator places cue starts at frame 0 and every 48,000 frames at
  48 kHz; chunk boundaries do not change the generated samples.
- The phone log spans the full run and includes progressing AudioTrack frame
  positions, both Android monotonic and elapsed-realtime observations, and the
  underrun counter. An underrun increase is a failure signal to review.
- Compare the external recording against a one-second cadence. Record missing,
  duplicated, late, or distorted beeps; do not infer acoustic success from the
  AudioTrack frame head alone.
- A clean 20-minute result qualifies this phone and route only. It does not
  prove behavior across manufacturers, Android versions, audio routes, process
  termination, or force-stop.

## Current status

- The debug-only prototype completed a user-reported unplugged, screen-locked
  run on the Samsung Android 12 phone on 2026-10-29. The phone was reconnected
  afterward.
- The run lasted 20 minutes. All 121 periodic samples had AudioTrack timestamps;
  119 samples recorded the screen as non-interactive. Frames advanced
  monotonically, the underrun count stayed at zero, and audio-frame time and
  AudioTimestamp time differed by about 0.55 ms over the timestamped interval.
- This verifies continuous AudioTrack frame progress on this phone and route.
  The user confirms hearing beeps while the phone was locked, but did not
  monitor the full run closely. This confirms sound was audible at least
  intermittently; a recording would be needed to verify the whole cadence.
  The log does not record charging state; unplugged status is user-reported.
- Local unit tests, lint and debug assembly pass. Hosted Android CI passed in
  [run 40972461833](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/40972461833).
- Speech integration is a separate follow-up and is not part of this test.

## References

- [AudioTrack frame timestamps and underruns](https://developer.android.com/reference/android/media/AudioTrack)
- [AudioTimestamp clock and frame position](https://developer.android.com/reference/android/media/AudioTimestamp)
- [Foreground service mediaPlayback type](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android guidance for keeping a device awake during audio playback](https://developer.android.com/develop/background-work/background-tasks/awake)
