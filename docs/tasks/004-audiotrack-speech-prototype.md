# AudioTrack spoken-cue prototype

## Goal

Check whether a short spoken elapsed-time cue can share the sample timeline with
the continuous beep stream while the screen is locked. This is a debug-only
experiment; it does not replace the production timer or TTS path.

## Design

- Synthesize a bounded set of phrases before starting playback. Use Android
  TextToSpeech synthesis callbacks, not `speak()` output, so the prototype can
  place the resulting PCM in its own AudioTrack stream.
- Read the format reported by `onBeginSynthesis`, collect audio chunks delivered
  through `onAudioAvailable`, and wait for `onDone` before using the complete
  utterance. Reject unsupported or incomplete audio explicitly.
- Convert supported PCM to the prototype's 48 kHz mono signed-16-bit format.
  Store the short demo's speech clips in memory and place them at exact frame
  offsets alongside the one-beep-per-second tone.
- Mix both events. Lower the beep during speech so words remain understandable;
  keep the beep event present. Treat this gain rule as a test choice, not a
  final product setting.
- Keep the run short: beep every second, speak elapsed-time phrases at 30-second
  intervals, and stop after the final phrase. Log synthesis results, scheduled
  speech frame positions, audio timestamps, underruns and screen state.

## Verification

- Unit-test format conversion, resampling, clipping/headroom, cue offsets and
  beep-plus-speech mixing with deterministic PCM fixtures.
- Run local unit tests, lint, debug assembly and release-manifest inspection.
- On the Samsung phone, confirm beeps and speech while awake, then check speech
  with the screen locked. Focus on hearing the scheduled phrases; an app log
  cannot tell us whether every cue reached the listener.
- Report TTS synthesis completion separately from AudioTrack frame progress and
  user-heard output. A clean short run is a prototype result, not proof of
  production readiness or arbitrary script latency.

## Current status

- The debug-only implementation pre-renders phrases at 30, 60 and 90 seconds,
  mixes them with one-second beeps on one AudioTrack, and records speech frame
  positions and playback observations.
- Local verification passed on 2026-10-29: 157 unit tests, debug lint, debug
  APK assembly and release-manifest processing. Lint reports 17 pre-existing
  warnings and no issue in the new prototype classes.
- The updated debug APK was installed with an in-place update on the Samsung
  phone. The previous prototype log and saved state remained present.
- The earlier 20-minute beep-only run showed continuous frame progress and no
  logged underruns; the user heard some locked-screen beeps but did not monitor
  the full cadence.
- On 2026-10-30, the user ran the speech test multiple times and reported that
  it sounded successful. Two runs completed in about 95 seconds; a third was
  manually stopped after about 48 seconds. All three runs prepared all phrases.
- In both completed-run logs, the audio frame head passed all three scheduled
  speech starts. Samples around those starts reported the screen as
  non-interactive, and the underrun count stayed at zero. The CSV does not
  record charging state. No external audio recording was made, so audibility
  is based on the user's listening report, not an independent microphone check.

## Reproduction procedure

1. Unlock the Samsung phone and open **Audio Prototype Debug**.
2. Tap **Start 95-second beep + speech test**. Wait through preparation and
   confirm a beep is audible while the screen is awake.
3. Press the phone's power button once to lock the screen. If checking unplugged
   behavior, disconnect USB after the test starts.
4. Leave it locked through the spoken cues at 30, 60 and 90 seconds.
5. After the run ends, unlock the phone, reconnect USB and record which spoken
   cues and beeps were audible. Read the saved CSV and compare the speech-slot,
   screen-interactive and underrun fields with that report.

Do not stop or replace the production timer app, clear app data, or change
battery settings for this check. If a cue is missed, report which one and
whether the phone was locked and unplugged; the CSV can then be compared with
the user observation.

## Scope limits

- Pre-rendering proves that prepared speech can be placed on the shared sample
  timeline. It does not prove a future phrase can be synthesized just-in-time
  for an arbitrary timer event; dynamic synthesis lead time is a later design
  question.
- Do not modify `TimingService`, `SpeechPlayer`, profile behavior, or release
  manifests in this task.
- Do not infer screen-locked audibility solely from TTS callbacks, PCM buffers,
  AudioTimestamp, or a zero underrun count.

## References

- [UtteranceProgressListener PCM callbacks](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
- [TextToSpeech synthesis APIs](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [AudioTrack PCM streaming](https://developer.android.com/reference/android/media/AudioTrack)
