# 003 — Identify and verify the current baseline

Status: Complete locally. Dependencies: None.

Recorded 2026-10-27: forced unit-test execution passed 92 tests with zero
failures/errors/skips. Lint completed with 0 errors and 17 existing warnings;
debug assembly passed. The source base was 727f372 plus existing local 1.6.2
development work. No device installation or hosted CI was performed here.

## Outcome

Preserve the current development tree and establish the source/toolchain being extended.

## Read first

README.md; CONTRIBUTING.md; docs/tasks/002-postmortem-diagnostics.md; app/build.gradle; .github/workflows/android.yml.

## Bounded work

- Record the current commit and dirty-tree state without discarding, staging or publishing existing work.
- Run the unit tests, lint and debug assembly using the existing SDK. Force the unit test task to run when a prior report is being used as fresh evidence.
- Record test totals, lint warnings, toolchain and debug APK checksum. Keep device observations and hosted CI distinct.
- Identify Kotlin/Gradle compatibility for the next change without upgrading the existing Android plugin or wrapper.

## Verification

ANDROID_HOME=/path/to/sdk bash gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug
ANDROID_HOME=/path/to/sdk bash gradlew --offline --no-daemon testDebugUnitTest --rerun

## Done when

The starting source is identified and the current automated checks have a recorded result. No device, release or hosted-run claim follows from a local build.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 004-profile-format.md.
