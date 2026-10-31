# 016 — Verify import recovery after process termination

Status: Pending isolated-emulator follow-up. Dependency: 008.
Activity recreation is verified; actual process termination is a separate check.
This follow-up does not block the Android file-sharing/documentation checkpoint.

## Outcome

Characterize saved profiles and user-visible recovery when the application
process ends during import. Report any uncertain save result accurately.

## Scope

- Use a disposable emulator and an external driver or separate test process.
  A test inside the terminated app cannot serve as its own surviving observer.
- Identify the original and relaunched process IDs to prove a process restart.
  Hold worker stages deterministically; avoid guessed sleep/rotation timing.
- Terminate during unconfirmed Loading/Preview, relaunch, and verify no import
  was committed or confirmation replayed.
- Exercise termination around Saving, then inspect persisted data after a new
  process starts. Distinguish an issued SharedPreferences update from a
  completed durable write; do not assume either rollback or crash atomicity.
- Verify pre-existing profiles and clocks survive. Reopening the app must not
  automatically repeat an import. Where the prior save result is uncertain,
  make the user inspect existing profiles before retrying.
- Keep timer-service/process-exit diagnostics in task 002. This task owns the
  profile-import boundary only.

## Verification and completion

Record the Android version, tested build, termination method, process IDs,
persisted before/after state and visible recovery behavior. Any discovered
defect needs a regression test and an appropriate product fix before its case
is marked passed. Device reboot remains a distinct test condition.

Never perform these destructive lifecycle checks on a daily-use phone.
