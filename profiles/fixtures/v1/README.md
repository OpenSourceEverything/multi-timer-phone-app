# Profile bundle v1 fixtures

These are shared contract inputs for Android and the native companion. Do not
copy separate versions into platform-specific tests. Every valid example must
preserve its schedule and audio semantics on encode/decode; generated local
IDs are intentionally absent from these files.

## Valid

- periodic.json: repeating beep and completion action.
- scripted.json: increasing offsets, speech/beep combinations and Unicode text.
- completion-only.json: no scheduled cues; only finish actions.
- speech-only.json: an elapsed-time announcement with no beeps.

## Rejected

- unsupported-version.json: version 2 is not implemented by v1 readers.
- local-identity.json: local identity fields are not portable fields.
- empty-profiles.json: a bundle needs at least one profile.
- fractional-period.json: time values require integer tokens.
- unsorted-cues.json: offsets must increase strictly; this is a semantic check.
- silent-cue.json: an event must contain speech or a positive beep duration.
- mixed-schedule.json: periodic profiles cannot also carry scripted events.
- duplicate-member.json: repeated object member names must be rejected, even
  when their two values happen to match. Ordinary map decoding can conceal this.

See [the format document](../../../docs/profile-format.md) for the normative
rules. Document-size, nesting-depth, text-byte and large-count cases should be
generated in tests rather than committing huge fixture files. JSON Schema
cannot enforce every runtime rule, particularly member duplication, lexical
integer notation, UTF-8 byte counts and increasing offsets.
