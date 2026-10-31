# 011 — Implement the Swift profile model and codec

Status: Complete. Thirteen portable-core tests pass on Linux Swift 6.1.3 and
hosted macOS; the Xcode/iOS simulator gate passed in run 40853178799 on
5b33161. Dependencies: 010 and 004, complete.

Implemented: immutable SharedProfile/SharedCue values, eight independent
flags, strict token-preserving JSON reader and bounded encoder. Tests exercise
the unchanged shared fixtures plus generated resource/Unicode/type boundaries.
Companion docs/codec-design.md records the isolated parser choice; SwiftPM
compiles the same portable sources for fast Linux/macOS checks. This gate does
not yet establish the editor or actual file exchange.

## Outcome

Decode, validate and encode the same profile document in Swift.

## Read first

docs/profile-format.md; profiles/profile-bundle-v1.schema.json; shared fixtures; Android SharedProfile/ProfileBundleCodec behavior.

## Bounded work

- Use typed Swift values/Codable where appropriate, enforcing portable integer, field, version, count and UTF-8 limits explicitly.
- Match documented duplicate-member, unknown-field and lexical integer behavior; do not rely on permissive defaults silently.
- Enforce increasing scripted offsets and no local identity/ownership fields.
- Run the same shared fixtures used by Android, including semantic-invalid cases.
- Keep the codec independent of SwiftUI and network/file operations.

Suggested implementation order:

1. Add immutable Swift profile/cue values and the same normalization, numeric,
   byte/count and schedule rules as SharedProfile.kt. Keep local Android IDs
   out of Swift values; the companion edits portable configuration only.
2. Implement a ProfileBundleCodec boundary from Data to validated profiles
   and back. Prove duplicate-member, lexical-integer and exact-key behavior
   with focused tests before selecting any parser strategy. Do not silently
   relax the contract because a default decoder accepts a negative fixture.
3. Use the exact root fixtures without copying or "fixing" their contents.
   Assert each negative fails for its intended reason, not an unrelated error.
4. Add generated boundaries: 32/33 profiles, 256/257 cues, depth 16/17, invalid
   UTF-8, unpaired surrogate, valid emoji, trimmed/blank strings, time limits
   and oversized encoded output. Match Android's depth definition in the spec.
5. Add field-by-field semantic round-trips for every mode/flag/action. JSON
   whitespace and object-member order are not semantic differences; times,
   flags, profile order and cue order are.

If meeting a strict parsing rule needs a substantial new dependency or custom
parser, isolate and review that decision before wiring the editor. Document
the chosen mechanism and tests; do not label a permissive workaround complete.

## Verification

Run the Swift unit suite with all shared fixtures and encode/decode round-trip assertions.

## Done when

Both implementations accept/reject the shared fixtures consistently and preserve every portable schedule/audio field.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 012-swiftui-editor.md.
