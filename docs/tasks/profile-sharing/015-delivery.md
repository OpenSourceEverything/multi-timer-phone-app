# 015 — Package an understandable, reviewable release candidate

Status: 015A complete: illustrated Android guide, real screenshots/recording,
build instructions and content review. 015B complete: combined application-file
exchange, illustrated companion guide, six real simulator captures, a reviewed
54-second actual recording and reproducible checks. Optional HTTP/cache remains
deferred; signed distribution is separate.

- 015A Android product documentation: complete the ordinary-user guide, real
  screenshots, short file-exchange recording and reproducible build/test
  instructions. Keep the guide focused on ordinary use and reproducible evidence.
- 015B combined delivery: depends on 014 and covers the actual two-app workflow.
  Do not mark this subchunk complete from Android-only evidence.

## Outcome

Make the completed product work easy for a contributor or user to inspect and reproduce.

## Read first

README.md; CONTRIBUTING.md; shared profile docs; both app build/test reports and demonstrated workflows.

## Bounded work

- Add a compact architecture/data-flow explanation, setup commands and feature documentation for both apps.
- Include real screenshots and a short demonstration captured from identified builds.
- Review comments, tests, fixtures and examples for clarity, consistency and accidental private content.
- Identify the candidate source and artifacts and record passing checks and remaining device limitations.
- Prepare reviewable changes; publication, signed distribution and store rollout require the applicable explicit user request.

The Android capture is a 30-second real-device recording of export -> import
-> preview -> confirmation, plus three screenshots. It was captured from the
installed MultiTimer 1.6.2-debug / code 12 build on Android 12. Published media
is in docs/user-guide/images/. The status bar was cropped and no private
diagnostic export is included.

Final review checklist:

1. Label implemented, tested and still-unverified behavior accurately. Put
   build prerequisites/commands next to the platform that needs them.
2. Explain the data path in one small diagram: reusable profile -> portable
   codec -> local file -> preview/confirmation -> new local copy. Keep optional
   HTTPS/cache work visibly deferred and timer scheduling distinct.
3. Link the shared contract/fixtures and test commands. Use actual screenshots
   and demonstrations from the tested candidate, not generated mock evidence.
4. Review source, documentation and the final diff for secrets, device/account
   data, local paths, unrelated work and comments that contradict the code.
5. Record final test/lint/build results and separate manual platform evidence.
   Stage/commit/publish only when requested; do not sweep other development
   work into a release because it happens to share the working tree.

## Android 015A evidence — 2026-10-29

- [Illustrated user guide](../../user-guide/profile-sharing.md).
- Real screenshots: [export selection](../../user-guide/images/export-selected.png),
  [import preview](../../user-guide/images/import-preview.png), and
  [confirmation](../../user-guide/images/import-confirmed.png).
- [Recording](../../user-guide/images/profile-exchange-demo.mp4) (30 seconds).
- Source: main at 25114f6; installed package com.ose.multitimer.debug,
  version 1.6.2-debug / code 12; pulled installed APK SHA-256
  69a4870a0dd3b16568f6bd0a5f4088387fd6843dbcd5a49e70dc395a24f27e7c.
- Observed: one selected profile exported, opened in the system file picker,
  previewed and confirmed. The temporary export and imported copies were
  removed after capture; no clock was created and the Play package was not
  touched.
- Hosted run 40833133919 passed unit-test/lint/build, Android 15 emulator
  startup and both lifecycle instrumentation tests on source commit 25114f6.
- Reviewed all three screenshots and sampled the existing recording across its
  full duration. The media contains the demonstrated app/file flow; status-bar
  notifications and private account details are absent.
- Activity recreation does not prove process-death recovery; follow-up is
  tracked in [016](016-android-process-recovery.md). No Play release is implied.

## Combined 015B evidence — 2026-10-29

- [Companion guide](../../user-guide/companion.md), shared importable
  [four-profile example](../../../profiles/exchange-examples.json) and
  [data-flow explanation](../../profile-sharing.md#companion-ownership).
- [Actual companion recording](../../user-guide/images/companion-demo.mp4):
  54.333 seconds, H.264, 480×854, 1,037,603 bytes; source 7413a0f9f8f829cff5fe44d318413a47b80b5209.
  Published SHA-256: c8ce4f027688045aec05b0cfd1edaaccb1a53c7cff448a061af4744e93605ea3.
- Capture run [40899895909](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/40899895909)
  passed 24 Mac host tests, 30 simulator unit tests, all three actual UI cases,
  real exported-file validation and bounded capture conversion/transport.
  It includes native-picker cancellation preserving the visible library.
- Recovered original recording: 158.603 seconds, SHA-256
  4d5c2a195bda344c4b8e8180a08de130e55723465c2aa179ef2daaf363b39987.
  Published playback is 3× speed; trailing test teardown is trimmed and the
  final actual confirmation frame is held for two seconds. Sampled the entire
  recording and inspected the tail/final frame; no account/device-private data
  is present. This is real captured interaction, not generated demonstration art.
- Android run [40899887451](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/40899887451)
  passed. Downloaded JUnit XML confirms 142 unit and three emulator tests,
  zero failures/errors/skips. Lint: zero errors/17 existing warnings.
- The full Android → SwiftUI edit/export → Android reimport workflow passed
  [40893550590](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/40893550590)
  on 7a07a05. Application source is unchanged in the recording candidate;
  later edits affect tests, capture and documentation. See task 014 for exact
  files, hashes, preservation checks and provider/test-double boundaries.

## Final content verification

Run required checks for the final candidate; validate documentation links, public-source privacy and the stated reproduction commands.

## Done when

A reviewer can build the apps, understand the feature and see what was actually tested. No private planning links or credential data appear in public content.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Local-file delivery is complete. Optional 009/013B, process-recovery 016 and
diagnostics qualification 002 remain separate follow-ups.
