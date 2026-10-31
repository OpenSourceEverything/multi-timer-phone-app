# Native profile sharing roadmap

This is the execution index for portable profiles and the SwiftUI companion.
Keep task status here and in the owning task; the older task 001 preserves the
original product scope and historical verification. Follow CONTRIBUTING.md.

## Execution

Work one bounded task at a time. Read its dependencies and named source before
editing; preserve the existing working tree. Run its focused checks, then the
required product checks. Record the result and exact next task before handing
off. Do not expand into the next milestone by hiding unfinished acceptance
criteria in a completed checkbox.

### Per-task execution checklist

1. Read this index, the owning task, its completed dependencies and the named
   source/tests. Check the current diff; saved results describe a prior run.
2. State the one observable outcome and the behavior that must survive.
   Limit edits to that task's owning boundary and its tests/docs.
3. Reproduce the relevant existing check before editing. If it fails, diagnose
   that failure before relying on the baseline; preserve unrelated changes.
4. Implement the smallest coherent change. Comment state ownership, validation,
   units, ordering and failure behavior so the next contributor can follow it.
5. Test the successful workflow and its meaningful failure/boundary cases.
   For rejected/cancelled operations, check preservation of prior valid data.
6. Run the focused tests and required product checks. A failed compile/test or
   unchecked acceptance condition keeps the task open; do not weaken the test
   or broaden a suppression to obtain a green report.
7. Review the final diff for scope and comments. Record the actual result,
   remaining limitations and exact next task in the completion record. Hand
   off at that boundary unless the current request authorizes another chunk.

Keep the timer engine, persisted clock schema and diagnostics outside a sharing
task unless that task explicitly owns a change there. Resolve routine choices
within the task; if progress requires an unrelated product change or missing
external access, record the concrete dependency and continue independent work.

Use the [task template](TASK_TEMPLATE.md) for newly discovered work rather than
burying extra requirements in a code comment or expanding an unrelated task.
If a task is too large for one review, split its ordered steps into named
subtasks with their own test gates; do not implement several unverified layers
at once. A missing device, simulator or service check leaves its acceptance
criterion pending. It never becomes a passing result through documentation.

The standard Android check is bash gradlew --no-daemon testDebugUnitTest
lintDebug assembleDebug, with the existing JDK/Android SDK configured. Add
--offline only after dependencies are cached. Use --tests with testDebugUnitTest
for focused iteration, then run the full gate. Do not change release signing,
install on a person's device or publish Git/store artifacts as a routine test.

Android core: 003 → 004 → 005 → 006 → 007 → 008 → 015A user guide/demo.
After the Android checkpoint, continue with iOS core: 004 → 010 → 011 → 012
→ 013A local file sharing. Both local-file workflows join at 014 → 015B
combined delivery. Optional network/cache work 009 and 013B is deferred.

## Current delivery order

1. Android tasks 003–008 and documentation/demo checkpoint 015A are complete.
   File exchange and Activity recreation are verified; process termination has
   separate follow-up coverage in 016.
2. Hosted Xcode access, strict Swift codec, editor/visual preview and actual
   local-file import/export are verified (010–013A). Keep Android's
   timer engine unchanged and do not purchase hardware/services.
3. Local-file exchange 014 and combined guide/recording delivery 015B are verified.
   Optional HTTP/cache work 009 and 013B remains deferred.

Establish the companion's actual macOS/Xcode build route before adding SwiftUI
screens. If that route is unavailable, record the concrete environment need and
continue independent Android/documentation work. No hardware purchase, hosted
service enrollment or signed distribution is required by this roadmap.

Shared schema/examples live in profiles/; the native companion will live under
ios/MultiTimerProfiles in this repository. The app keeps its existing Java
timing engine while new sharing logic is introduced in Kotlin.

## Tasks

- [003 — Identify and verify the current baseline](003-baseline.md) — complete and included on main; no dependencies.
- [004 — Define the portable profile document](004-profile-format.md) — complete and included on main; depends on 003.
- [005 — Add Kotlin and a detached sharing model](005-kotlin-model.md) — complete and included on main; depends on 004.
- [006 — Encode and validate profile JSON](006-profile-codec.md) — complete and pushed on main; depends on 005.
- [007 — Coordinate import preview and confirmation](007-import-state.md) — complete and pushed on main; 137-test/lint/build gate passed; depends on 006.
- [008 — Add Android file sharing](008-android-file-ui.md) — physical Android 12 file flow and deterministic Activity-recreation instrumentation pass locally; corrected tests pass hosted on Android 15 in run 40833133919. Process death remains untested; depends on 007.
- [009 — Download profiles and retain a valid offline copy](009-https-cache.md) — explicitly deferred optional extension; depends on 008.
- [010 — Establish the SwiftUI companion build](010-ios-build.md) — complete; hosted Xcode 16.4/iOS 18.5 build and both unit/UI smoke tests passed in run 40850974667; depends on 004.
- [011 — Implement the Swift profile model and codec](011-swift-codec.md) — complete; 13 portable tests and actual Xcode/simulator gate passed in run 40853178799; depends on 010 and 004.
- [012 — Edit and preview profiles in SwiftUI](012-swiftui-editor.md) — complete; actual editor/preview UI and app-adapter cases passed in run 40856547098; the separate 013A picker case failed setup and remains open; depends on 011.
- [013 — Exchange profile files on iOS](013-ios-sharing-cache.md) — 013A complete; 24 host tests, 30 simulator units, three UI cases and actual exported-file validation passed in 40893514226; 013B network/cache deferred; depends on 012 and 004.
- [014 — Verify Android and SwiftUI exchange together](014-cross-platform-check.md) — complete; actual Android export → SwiftUI import/edit/export → Android reimport passed all three jobs in 40893550590; depends on 008, 011, 012 and 013A.
- [015 — Package understandable user documentation and combined evidence](015-delivery.md) — complete: Android guide/recording plus companion guide/six captures/reviewed 54-second actual recording; combined exchange passed and capture gate 40899895909 is green.
- [016 — Verify Android import recovery after process termination](016-android-process-recovery.md) — pending isolated-emulator follow-up; Activity recreation alone does not cover it; depends on 008.

## Common completion record

- Concrete behavior delivered and files/symbols that own it.
- Tests/checks actually run, source/artifact identity where relevant and limits.
- New/remaining errors or warnings; no invented device or hosted CI result.
- Next task and any unresolved prerequisite.

Public records explain the product. Keep comments technical and explanatory;
keep account data, local private paths and personal planning out of source,
fixtures, screenshots and public task notes.
