# Portable reusable profiles, version 1

A profile bundle shares audible schedules between MultiTimer installations.
It contains templates only; importing a bundle creates new reusable profiles
without changing running clocks or their attached profiles. Files are UTF-8
JSON and may use the .json extension and application/json media type.

## Envelope and identity

The root has exactly three required fields:

- format: the string com.ose.multitimer.profiles.
- version: integer 1. Other versions require an explicit reader implementation.
- profiles: an ordered array of 1–32 reusable profile objects.

The [JSON Schema](../profiles/profile-bundle-v1.schema.json) describes structural
constraints. [Shared fixtures](../profiles/fixtures/v1/README.md) supply concrete
examples and rejection cases. Runtime rules below are normative as well.

Export only reusable profiles with no clock owner. Omit profile IDs, event IDs,
ownerClockId, reusable, scriptOffsetsMillis, clock progress and diagnostic data.
The event array already determines offsets; exporting a second offsets array
would allow the two representations to disagree.

On confirmed import, generate a new local profile ID and new event IDs every
time. Equal display names are permitted; they do not identify or overwrite an
existing profile. Parsing and preview do not persist anything. Confirming a
batch should make one logical list update after every selected item validates.

## Profile fields

All fields are required; unknown fields are rejected in v1:

- name: nonempty text after trimming surrounding characters at or
  below U+0020; at most 256 UTF-8 bytes after normalization.
- mode: PERIODIC or SCRIPTED.
- beepDurationMillis: default beep duration, integer 50–10,000 milliseconds.
- periodMillis: integer 1–9,007,199,254,740,991 for PERIODIC; exactly 0 for SCRIPTED.
- events: array of at most 256 event objects, empty for PERIODIC. A SCRIPTED
  profile may have zero events for a completion-only profile.
- beepOnCue, beepOnCompletion, speakOnCompletion, speakCurrentTime,
  speakElapsedTime, speakRemainingTime, speakOnCue and
  speakClockNameWithSpeech: separate boolean flags matching their Android
  model meanings. Preserve each flag; several spoken-time flags can coexist.

A global speech/beep flag may suppress a stored event's action. Preserve this
configuration rather than guessing from the profile name. The import preview
should identify a profile with no enabled audio output for the user.

## Event fields

- offsetMillis: integer 0–9,007,199,254,740,991 from the clock's cue-time origin.
  Event offsets must increase strictly, with no duplicates or sorting by the
  importer. Reject an unordered list rather than changing its intended meaning.
- speechText: null for no speech, otherwise nonempty text with surrounding
  U+0000–U+0020 trimmed and at most 2,000 UTF-8 bytes after normalization.
- beepDurationMillis: integer 0–10,000; 0 disables the event beep. The event's
  positive range matches the existing CueEvent model, which permits values
  below the profile default's 50 ms minimum.

Each event needs speech or a positive beep duration. Completion actions belong
to the profile flags, not to fabricated timed events.

## Decoder boundaries

- Reject input above 262,144 bytes before parsing. Reject invalid UTF-8 and
  malformed Unicode, including unpaired surrogate escapes.
- Bound recursive container nesting to 16 before descending. Size limits alone
  do not protect a recursive parser from stack exhaustion.
  Count the root object as depth 1, and each nested object/array as one more;
  scalar values do not increase depth. A normal cue is inside the root object
  (1), profiles array (2), profile object (3), events array (4), event object
  (5). Depth 16 is permitted; reject a container at depth 17 before entering it.
- Reject duplicate member names, missing/unknown fields, wrong JSON types and
  unsupported versions. Require integer tokens for version/time: decimal or
  exponent notation and numeric strings are not accepted.
- Portable time values stop at the largest exactly representable JSON/JavaScript
  integer. This fits both Kotlin Long and Swift Int64 without overflow or loss
  of integer identity; it is not a claim that a device can schedule such a span.
- Count text limits in UTF-8 bytes after normalization, not platform-specific
  string lengths. These byte limits require runtime checks beyond JSON Schema.
- Validate every profile before any persistent list mutation. For invalid
  documents, report a useful field/profile location and preserve existing data.

## Example ownership flow

Android reusable profile → detached sharing model → bundle JSON → validated
preview → explicit confirmation → new reusable local profile.

A timer-owned profile is outside the export scope. The screen can offer a
separate, explicit product action to make a reusable copy; sharing itself must
not silently change ownership.
