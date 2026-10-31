# 014 — Verify Android and SwiftUI exchange together

Status: Complete. All three application-workflow jobs passed in hosted run
[40893550590](https://github.com/OpenSourceEverything/multi-timer-phone-app/actions/runs/40893550590)
on source 7a07a055545f4b51c4dbb7820cb31fd2cb0ad95e.
Local-file dependencies: 008, 011, 012 and 013A.
The optional network path additionally depends on 009 and 013B; both are deferred.

## Automated application workflow

The manual-dispatch Cross-platform file exchange workflow joins the existing
platform checks at one source commit:

1. A disposable Android 15 emulator selects/exports four public examples using
   MainActivity. It also checks invalid input, cancelled preview, new identities
   and preservation of a running clock attached to an original profile.
2. The hosted iOS simulator imports those actual bytes through Files, cancels
   one preview, confirms another, edits the scripted name/first cue in SwiftUI,
   then exports the four selected profiles through the native destination picker.
3. A second disposable Android emulator imports the companion's actual output
   and compares every field, including the promised edit and unchanged flags.

Small bounded job outputs carry only these public documents. Each collector
checks the real output against the public examples; it does not synthesize a
replacement encode. No hardware, signing identity or new artifact storage is
needed. The Android picker result is stubbed in instrumentation; the Activity,
streams, coordinator and store are real. Android's physical picker has separate
earlier evidence. Apple's simulator picker is driven directly.

Run after publication with gh workflow run exchange.yml. A workflow definition
is not completion: retain its actual three-job result before closing this task.

All stages read [one public exchange document](../../../profiles/exchange-examples.json),
including a Unicode profile name and speech. The companion changes only the
scripted profile's name and first offset; every other field must survive.
The four original individual conformance fixtures remain unchanged.

## Verified round trip — 2026-10-29

- Android 15 exported four selected reusable profiles through actual Activity
  controls and file streams. Export SHA-256:
  c47e2a96398e3b71fc84ba61b317c6e6c6694793492f604bba8773f17c94da5a.
- Xcode 16.4/iOS 18.5 imported those exact bytes through Files, cancelled one
  preview, confirmed another, edited the scripted profile name/first offset
  and exported all four selected rows through the native destination sheet.
  Export SHA-256:
  defb7cdf0a4c6acb8250dd7379e765c130262b7846d4584684fdb06a4b3272ef.
- A second Android 15 emulator imported the actual companion output and
  compared all fields with the expected edit. Existing reusable profiles,
  attached running-clock identity/progress and old profile/event identities
  survived; imported profile/event IDs were fresh. Invalid input and cancelled
  preview preserved prior saved data.
- Each Android job passed unit/lint/build and three emulator tests, followed
  by one direct application exchange case with explicit runner success. The
  Mac passed 24 host tests, 30 simulator units, three UI cases and real output
  inspection. The original shared conformance fixtures were not modified.
- This proves the stated application-file boundaries. Android's external
  picker result is stubbed in instrumentation; its physical picker has prior
  separate media evidence. No claim about audible timing, process termination,
  every provider or signed distribution follows from this pass.

## Outcome

Prove the actual two-app workflow rather than assuming matching JSON names are sufficient.

## Read first

Shared fixtures/specification; Android and iOS build/test instructions; both import/export workflows.

## Bounded work

- Export Android reusable profiles, edit them in the companion, then reimport to Android.
- Compare schedule/audio semantics and verify existing Android clock/profile identities remain intact.
- Include Unicode names/speech, completion-only, periodic and scripted cues and a failed/cancelled import.
- Run both full automated suites and record artifact/source identities and actual device/simulator observations.
- Record unsupported environments and remaining lifecycle/audio checks.

Execution checklist:

1. Identify source/artifacts and retain the existing Android profile/clock
   counts and IDs. Use harmless test profiles, not private diagnostic exports.
2. Export periodic, scripted, completion-only and speech-only examples from
   Android. Include Unicode and independent flag combinations.
3. Open in the companion, change a name and one cue offset/action, validate,
   export and import back to Android with explicit confirmation.
4. Compare every portable field. Newly imported copies must have new local
   IDs; existing profiles, attached running-clock IDs and progress survive.
5. Try an invalid file and cancel a valid preview. Neither operation silently
   changes saved user data. Exercise offline cache separately if the deferred
   network extension is later implemented; do not imply it exists now.
6. Run both automated suites on the actual candidate, then record each manual
   step as passed, failed or not run with its platform/build. Preserve a minimal
   shareable exchange file and expected semantics as regression evidence.

If either app/environment is unavailable, keep this task open. A Swift codec
unit pass plus Android codec unit pass is useful, but not a completed two-app
workflow. Keep any discovered product defect in its owning task with a test.

## Verification

Android testDebugUnitTest/lintDebug/assembleDebug; Swift unit/simulator checks; the documented manual round-trip checklist.

## Done when

Actual exchanged files reproduce the expected profile behavior and failed paths preserve existing data. Unit tests and human audio observations are reported separately.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 015-delivery.md.
