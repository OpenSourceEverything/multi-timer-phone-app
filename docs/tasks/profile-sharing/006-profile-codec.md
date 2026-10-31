# 006 — Encode and validate profile JSON

Status: Complete and pushed on main. Dependencies: 005, complete.

## Outcome

Convert between bounded UTF-8 profile documents and detached sharing models, with useful validation errors.

## Read first

docs/profile-format.md; profiles/fixtures/v1/README.md; SharedProfile.kt; JsonSupport in TimeUtils.java; relevant parser tests.

## Bounded work

Expected source: app/src/main/java/com/ose/multitimer/ProfileBundleCodec.kt.
Public API: decode(bytes: ByteArray): List<SharedProfile> and
encode(profiles: List<SharedProfile>): ByteArray. Decode returns detached
models without generating local IDs; toNewProfile remains a confirmation-time
operation owned by task 007.

- Implement a ProfileBundleCodec Kotlin boundary without Android UI or storage dependencies.
- Enforce 256 KiB before parsing, strict UTF-8, nesting depth, exact fields, version, integer tokens, text/count limits and schedule semantics.
- Reject duplicate object members and invalid Unicode; never guess unsupported fields or coerce string/decimal/boolean values into integers.
- Reuse the existing parser only after ensuring its untrusted-input path has a depth limit before recursive descent. Keep existing storage parsing compatible and covered.
- Encode the portable fields only. Preserve supplied profile/event order and use fresh local IDs only when conversion is explicitly requested.
- Consume every shared fixture and test malformed, deep, oversized and boundary documents generated in tests.

Suggested implementation order:

1. Configure the Android test resources to read profiles/fixtures/v1 directly;
   do not copy fixtures into an Android-only tree.
2. Add a bounded parse entry point to JsonSupport while preserving its existing
   storage callers. The existing parser already rejects duplicate keys and
   non-integer tokens; it currently lacks a nesting bound and complete Unicode
   validation. Cover the new parser boundary with tests before reusing it.
3. Decode UTF-8 with malformed/unmappable input set to REPORT. Verify exact
   envelope/profile/event key sets and JSON types, then construct SharedCue
   and SharedProfile values. Include a useful profile/event location in errors.
4. Serialize only portable fields in a stable order. Bound output while building
   it, then enforce the final UTF-8 byte limit as well. Reuse the existing text
   quoting helper after verifying its behavior with valid supplementary text.
5. Exercise the shared corpus and generated limits before the full build.

Minimum behavior coverage:

- All four valid fixtures preserve names, offsets, actions and every flag.
- All eight invalid fixtures are rejected for their intended reason.
- Wrong root/field types, missing/unknown fields and unsupported modes fail.
- UTF-8 byte limit, 32/33 profiles, 256/257 cues, text-byte and safe-integer
  boundaries are covered without silently truncating input.
- Deep objects/arrays fail at the nesting boundary without StackOverflowError.
- Invalid UTF-8 bytes and lone surrogate escapes fail; a valid surrogate pair
  preserves its character through decode/encode.
- Encoder limits are tested, not only decoder limits. Existing stored-model
  serialization tests continue to pass.

Leave Activity controls, manifest permissions, storage migrations and networking
for their owning tasks. This chunk finishes at a tested codec boundary.

## Verification

Run ProfileBundleCodec tests and the full testDebugUnitTest task; lintDebug and assembleDebug must pass.

## Done when

Valid fixture semantics round-trip and every documented rejected input fails before persistence. Existing stored profile JSON still reads.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

## Completion record — 2026-10-28

- Added `ProfileBundleCodec.decode` and `encode`. The boundary checks size
  before UTF-8 decoding, rejects malformed UTF-8/Unicode and duplicate keys,
  limits recursive parsing to depth 16, requires exact v1 fields and integer
  tokens, validates limits/schedule semantics, and returns detached models.
- Extended `JsonSupport` with a strict bounded entry point while retaining the
  existing local-storage parsing path. Shared fixtures now assert each
  documented rejection reason; valid fixtures compare every field across a
  decode/encode/decode round trip.
- Full local check passed:
  `bash gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug`
  with the installed Android SDK selected through `ANDROID_HOME` and
  `ANDROID_SDK_ROOT`.
  - 113 unit tests passed; zero failures, errors or skips. Codec tests: 10.
  - lintDebug: 0 errors, 17 warnings.
  - assembleDebug passed. No phone install, hosted CI or publication occurred.
- The SDK emitted its existing XML-version notice. Repository commit and
  publication status are tracked by Git rather than duplicated in this task.

Next task: 007-import-state.md.
