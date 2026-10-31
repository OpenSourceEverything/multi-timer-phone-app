# 013 — Exchange profile files on iOS

Status: 013A complete. Hosted run 40893514226 on 7a07a05 passed 24 host tests,
30 simulator units and all three actual UI cases. The final collector validated
the actual system-exported four-profile file and its edited fields. Optional
013B network/cache remains deferred.
Dependencies: 012 and the shared format from 004.

The implementation uses coordinated, security-scoped bounded reads; immutable
preview/request identity; explicit selected-row confirmation; fresh local IDs;
and selected-only export through FileDocument. Tests verify malformed/oversized,
missing/denied reads, cancelled/stale confirmation, identity preservation and
the actual import/editor/export controls. A POSIX denial test is not coverage
of every external provider's revoked grant.

The host prepares only public Documents examples in a disposable simulator.
The real picker is driven directly; exported bytes are read from the resolved
post-test app container, not regenerated with the codec. No product fixture-load
backdoor, networking, signing identity or physical phone is used.
This acceptance is distinct from task 014's complete two-app exchange.

Execute as two separately verified subchunks:

- 013A: local file import/export, preview/confirmation and preservation of user
  data. This is the prerequisite for the first two-app exchange demonstration.
- 013B: optional HTTPS download/cache. Deferred along with Android task 009;
  do not implement or mark complete as part of 013A.

## Outcome

Import and export portable profile documents using the shared JSON format.

## Read first

Swift codec/editor; Android file exchange behavior; companion build instructions.

## Bounded work

- Integrate local file import/export with a preview and explicit confirmation.
- Use bounded reads, scoped file access and safe writes; keep file work outside
  view rendering and preserve existing user data on failure or cancellation.
- Test malformed/oversized input, cancelled picker, access failure and valid
  export/import.

Suggested implementation order:

1. Add native file import/export around the same codec. Handle granted file
   access and close/release resources. Bound reads before decoding, show a
   preview and save only after explicit confirmation.
2. Keep the portable codec separate from file coordination and the editor.
3. Make cancellation/access failures leave saved profiles unchanged.
4. Run an actual local file exchange. Exported bytes must decode on Android,
   not merely round-trip in Swift.

Minimum tests: cancelled picker, file access denied, malformed/oversized file,
valid import/export and preservation of existing data. Record unavailable
platform checks as pending.

Deferred 013B retains its separate HTTPS/cache tests: HTTP failure, timeout,
redirect downgrade, unknown length, cancellation, stale success, corrupt cache
and failed replacement preserving old data. It is not required by task 014.

## Verification

Run Swift tests/simulator build and demonstrate real local file import/export.
Record 013B network/cache checks separately if the optional extension is later
authorized; missing network behavior remains deferred.

## Done when

The companion exchanges valid documents through local files; failed operations
preserve current user data. This does not imply an HTTP client or offline cache.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 014-cross-platform-check.md.
