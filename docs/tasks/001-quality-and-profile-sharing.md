# 001 — Quality foundation and state coordination

Status: Complete. Regression baseline, CI work, and the small state-coordination
boundary are delivered. Portable profile exchange has its own ordered plan in
[tasks 003–015](profile-sharing/README.md); it is not part of this task.

## Purpose

Keep the native app understandable and testable without rewriting the timer
engine or changing its persisted clock behavior.

## Delivered baseline

- Reproduced the Android build and expanded regression coverage for clocks,
  persistence, recovery, and overlapping profiles.
- Added a GitHub Actions workflow for unit tests, lint, and a debug build. A
  local workflow check does not prove a hosted run passed.
- The historical quality-only run grew from 71 to 79 passing unit tests; lint
  and debug assembly passed locally with 17 lint warnings. Later work expanded
  the suite further; see the current task and run records for newer results.
- Device-only behavior remains separate from local unit tests.

## State-coordination boundary

`ClockActionPolicy` owns the decision for whether a clock card offers Start,
Pause, or no action. Both button rendering and click handling use this same
policy.

- MainActivity reads the policy to render text/enabled state and to route the
  click. `ClockInstance` still owns mutations; MainActivity still persists the
  result and syncs `TimingService`.
- The policy is pure, has no Android dependency, and is covered without
  constructing the Activity.
- ClockInstance, AppStore, TimingService, and persisted clock behavior remain
  unchanged. This is not an MVVM migration.

The extraction is a maintainability improvement, not a user-facing feature or a
prerequisite for profile exchange or ordinary timer use.

## Completion record — 2026-10-28

- Added `ClockActionPolicy` so the clock-card label/enabled state and click
  handler share one decision: Start, Pause, or unavailable. The policy does not
  mutate the clock or perform persistence/service work; those responsibilities
  remain with `ClockInstance` and `MainActivity`.
- Added five unit tests for paused/running finite clocks, completed finite
  clocks, completed stopwatches, precedence for running state, and null input.
  The tests run without constructing an Activity.
- Full local gate passed:
  `ANDROID_HOME=/path/to/Android/Sdk ANDROID_SDK_ROOT=/path/to/Android/Sdk bash gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug`
  - 142 unit tests; zero failures, errors, or skips.
  - `lintDebug`: zero errors and 17 existing warnings; no finding points to the
    new policy or its tests.
  - `assembleDebug` passed. The resulting APK was not installed on the phone.
- No timer model, persistence schema, or service behavior was changed. Device
  timer execution and hosted CI were not tested by this refactor.

## Scope moved elsewhere

- Profile JSON, Android file import/export, HTTPS cache, and the SwiftUI
  companion are specified in [the profile-sharing index](profile-sharing/README.md).
- Physical-device diagnostics are tracked in
  [002 — Postmortem diagnostics](002-postmortem-diagnostics.md).
- Play account, signing, store-listing, and rollout work are release operations,
  not requirements for this code-quality task.

Follow [CONTRIBUTING.md](../../CONTRIBUTING.md) for comments, tests, and
verification evidence. Do not claim a hosted check or device result without
running it.
