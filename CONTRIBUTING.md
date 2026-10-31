# Contributing

MultiTimer favors straightforward code that a new contributor can trace from a
screen action through clock state, storage and audible output. Preserve working
behavior before introducing new abstractions.

## Code readability

- Use names that identify the concept and unit, such as elapsedMillis rather
  than an unexplained number. Prefer small, single-purpose methods and explicit
  control flow over dense expressions or unnecessary indirection.
- Explain each class's responsibility and its relationship to callers.
- Document meaningful method contracts: inputs and outputs, validation, state
  mutation, ownership and failure behavior. Include thread or lifecycle
  requirements where Android makes them important.
- Comment the logical steps of non-obvious algorithms: why the step exists,
  what state it preserves and why a tempting alternative would be incorrect.
  Pay particular attention to clock sources, rounding, overflow, cue-window
  boundaries, profile identity, migration and asynchronous callbacks.
- Explain side effects at their boundary: writing storage, emitting a cue,
  starting a service or updating a view. An in-memory state change is not proof
  an Android service ran or that a caller heard audio.
- Keep comments consistent with the implementation. Do not repeat obvious
  assignments, narrate edit history or promise guarantees the code cannot make.

Detailed comments are welcome. Readability is judged by whether a contributor
can follow the behavior, not by the number of comment lines. Language basics
and exhaustive syntax tutorials belong in separate teaching material rather
than repeated beside every assignment.

## Tests

- Name the behavior and describe the relevant starting state, action and
  expected outcome. Explain unusual timestamps and fixtures.
- Supply deterministic time inputs for clock arithmetic; do not use sleeps to
  claim real-time accuracy.
- Assert externally meaningful state and boundary behavior rather than source
  layout, helper names or incidental implementation details.
- Keep malformed fixtures visibly intentional. Verify that rejected operations
  preserve prior valid state where that is part of the contract.
- Separate model/store reconstruction from actual Android process recreation,
  service recovery and audible timing. Those need device or instrumentation
  evidence as well.

## Verification

With the JDK and Android SDK configured, run:

```sh
bash gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

On Windows use gradlew.bat with the same task names. Record failures and lint
warnings rather than treating an APK or an old report as a complete pass.
Never commit signing keys, passwords, local.properties or private device data.
