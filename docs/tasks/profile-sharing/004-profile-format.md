# 004 — Define the portable profile document

Status: Complete. Dependencies: 003.

The format, JSON Schema and 12 shared fixtures are present. Schema checks
accepted the four valid documents and rejected six structural negatives;
ordered-offset and duplicate-member negatives were checked separately because
they require runtime validation beyond the schema.

## Outcome

Specify a bounded JSON interchange format for reusable cue profiles, independent of local clock storage.

## Read first

docs/profile-format.md; profiles/profile-bundle-v1.schema.json; BeepProfile.java and CueEvent.java under app/src/main/java/com/ose/multitimer/.

## Bounded work

- Define the version envelope, all schedule/audio fields, text and document limits, and semantic validation rules.
- Exclude local profile IDs, cue IDs, clock ownership, running state and diagnostic data.
- Import always creates new local identities; explain duplicate names and explicit confirmation.
- Supply shared valid and invalid fixtures under profiles/fixtures/v1, including periodic, scripted, completion-only, incompatible-version and semantic-error cases.
- Document which checks JSON Schema expresses and which require application validation.

## Verification

Parse every fixture as JSON; validate the schema itself and structural fixtures with a JSON Schema validator when available. Keep intentionally semantic-invalid fixtures separate from structural rejection cases.

## Done when

The public specification, schema and examples agree. Tests can consume these exact fixtures from Android and iOS. No importer is claimed from the format alone.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 005-kotlin-model.md.
