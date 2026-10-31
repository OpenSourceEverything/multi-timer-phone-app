# 007 — Coordinate import preview and confirmation

Status: Complete and pushed on main. Dependencies: 006, complete.

## Outcome

Keep import decisions and screen state testable independently of Android widgets.

## Read first

AppStore.java; MainActivity profile callbacks; SharedProfile.kt; ProfileBundleCodec; existing AppStoreTest fake preferences.

## Bounded work

- Add a small Kotlin ProfileImportCoordinator for Idle, Loading, Preview,
  Saving, Success and Error states. Keep decisions independent of Android
  widgets; a lifecycle adapter can be added in task 008 without rewriting
  MainActivity or adding a new application-wide architecture.
- Inject file/codec/storage dependencies at the side-effect boundary; retain current timer models and service ownership.
- Validate the entire document before a preview. Confirmation inserts all selected reusable templates in one logical saved-list update with fresh IDs.
- Allow equal display names without treating them as identity. Cancellation and rejected inputs leave existing profiles/clocks untouched.
- Prevent double confirmation and stale asynchronous responses from committing an older preview.
- Test state transitions, duplicate names, cancellation, failed batches and independent running-clock state.

Suggested implementation order:

1. Define immutable state and small injected decode/storage interfaces. Cover
   Idle -> Loading -> Preview -> Saving -> Success and the error/cancel paths
   with deterministic fake dependencies before adding platform callbacks.
2. Give each load a generation token. Starting another load or cancelling
   invalidates earlier callbacks. Confirmation is legal only for the current
   preview, and one preview cannot be committed twice. Cancellation before
   Saving must write nothing. Once a save is issued, disable cancellation or
   report its actual completion; closing a screen cannot promise rollback of
   an already-issued persistent write.
3. Add one focused AppStore batch-insert boundary under its existing LOCK.
   Validate/materialize the whole batch before writing; load the latest stored
   profiles inside the same lock and append without replacing existing IDs.
   Do not use several putProfile calls or an unlocked load/save pair: those
   permit partial batches or lost concurrent edits.
4. Preserve current SharedPreferences behavior. Its apply call is not evidence
   of a crash-durable database transaction; describe the guarantee as one
   validated logical update, not rollback across arbitrary disk failures.
5. Connect the coordinator through the injected storage adapter. Verify the
   stored profiles and clocks before/after each rejected, cancelled, successful
   and repeated confirmation case.

Minimum additional tests: empty/invalid selection, duplicate display names,
fresh profile/event IDs, stale decode result after a newer load, cancellation
before confirmation, double confirmation, validation failure anywhere in the
batch, injected save failure and a concurrently added existing profile. A
damaged existing profile list must not be replaced by an import. A fake save
failure must not produce Success. Keep existing AppStore migration and
timer-owned-profile tests passing; no schema migration is needed here.

## Verification

Run ProfileImportCoordinatorTest and AppStoreTest through testDebugUnitTest
--tests, then the full Android unit suite, lintDebug and assembleDebug. No
Activity, device or process-recreation result is implied by these unit tests.

## Done when

Import can be exercised without constructing MainActivity; tests prove rejection/cancellation preserves old state and confirmation does not overwrite running profiles.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 008-android-file-ui.md.

## Local implementation note — 2026-10-28

- Added `ProfileImportCoordinator.kt` with immutable Idle, Loading, Preview,
  Saving, Success and Error states; injected decode, worker and batch-write
  boundaries; request generations; index-based selection; and explicit
  confirmation. Tests cover stale results, cancellation during load and preview,
  duplicate names, empty selection, fresh IDs, double confirmation, oversized
  input and decode/write failures.
- Added `AppStore.appendReusableProfiles`, which validates and copies every
  candidate before reading the latest profile list, rejects identity conflicts,
  rejects unreadable existing profile data without overwriting it, and issues
  one logical profile-list update under the existing lock. Added tests for
  rejected batches, a profile added after preview, and unchanged running-clock
  state.
- Focused coordinator/store tests passed, followed by the complete Android
  gate: 137 unit tests, zero failures/errors/skips; lint 0 errors/17 warnings;
  debug assembly passed. The full suite includes the task-007 coordinator and
  AppStore batch-write tests. Task 007 has no device/UI acceptance criterion.
- The build emitted the existing Android SDK XML metadata notice and Java
  deprecation notes. No lint warning was added by these changes.
- Task 008 owns the remaining Android picker, lifecycle and device checks.

## Verification — 2026-10-28

From the repository root:

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SDK_ROOT=/path/to/Android/Sdk \
  bash gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The command completed successfully. JUnit XML reports 137 tests with zero
failures, errors or skips; `lintDebug` reports 0 errors and 17 warnings;
`assembleDebug` passed. The task-008 preview-summary regression test is included
in this run. Task-007's state/store behavior was also exercised on the device
through task 008's successful import, rejection and running-clock scenarios,
but this is supplemental evidence rather than a task-007 requirement.
