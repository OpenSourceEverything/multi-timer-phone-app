# 005 — Add Kotlin and a detached sharing model

Status: Complete locally. Dependencies: 004.

## Outcome

Enable a compatible Kotlin compiler and build an immutable representation that can safely cross the profile-sharing boundary.

## Read first

build.gradle; app/build.gradle; .gitignore; docs/profile-format.md; BeepProfile.java; CueEvent.java; existing model tests.

## Bounded work

- Pin a Kotlin Android plugin compatible with the existing Gradle/Android plugin. Align Kotlin bytecode with the existing Java 17 target.
- Implement SharedProfile and SharedCue in the existing package, including the portable limits and schedule validation.
- Export a detached snapshot without changing source models. Reject timer-owned profiles at this boundary.
- Materialize new reusable BeepProfile/CueEvent objects with new identities on every import conversion.
- Test field preservation, snapshot independence, Java interoperability, ownership and invalid boundary values. Document responsibility and logical steps in code.

## Verification

ANDROID_HOME=/path/to/sdk bash gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

## Done when

The mixed Java/Kotlin app builds, tests cover model behavior, and existing storage/scheduler behavior remains unchanged. The model alone is not a JSON or file-sharing UI.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

## Completion record — 2026-10-27

- Pinned Kotlin Android plugin 2.2.21 alongside the existing AGP 8.10.1 and
  Gradle 8.11.1. Java and Kotlin target JVM 17 bytecode.
- SharedProfile.kt owns portable limits, immutable snapshots, read-only export
  conversion and fresh-identity import conversion. No stored-data schema,
  scheduler, service or Activity behavior changed in this chunk.
- SharedProfileTest adds ten behavior tests; SharedProfileInteropTest adds an
  actual Java caller. Full testDebugUnitTest executed 103 tests with zero
  failures, errors or skips. lintDebug and assembleDebug passed, with the same
  17 existing lint warnings and no lint errors.
- This verifies the model/build boundary only, not JSON parsing, file/network
  sharing, device behavior or a SwiftUI implementation. Changes are local;
  no device installation or publication was performed.

Next task: 006-profile-codec.md.
