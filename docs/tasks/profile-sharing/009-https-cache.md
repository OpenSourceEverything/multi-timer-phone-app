# 009 — Download profiles and retain a valid offline copy

Status: Deferred optional extension. Dependencies: 008.

Local file sharing, Android documentation/demo and the initial SwiftUI local
workflow do not wait for this feature. Deferral is not a completed HTTP/cache
implementation; all requirements below remain open.

## Outcome

Offer an optional HTTPS profile catalog without making local timing depend on a connection.

## Read first

ProfileBundleCodec; import coordinator; AppStore; AndroidManifest.xml; the current public profile-format document.

## Bounded work

- Add a small asynchronous transport with connect/read timeouts, cancellation, response-size bounds and HTTPS validation.
- Decode/validate before replacing the last valid cache. Write a replacement atomically at the file boundary where supported.
- Display cached profiles when offline and explain failed refreshes without discarding useful data.
- Treat catalog items as documents to preview/confirm, not instructions to mutate stored running clocks.
- Test HTTP errors, truncated data, malformed JSON, oversized/slow responses, cancellation, corrupt cache and previous-cache preservation using a controllable fake/local test server.
- Provide a documented sample endpoint; no accounts or paid backend are needed.

Suggested implementation order:

1. Keep one bounded HTTPS document fetch, not an account system, remote command
   interface or new backend. Use a small transport interface and an injected
   connection/fake for tests; prefer the platform client unless a concrete
   requirement justifies an extra dependency.
2. Add explicit connect/read timeouts and cancellation that closes/disconnects
   the request. Do not merely set a UI flag while a blocked worker continues.
   Accept only successful statuses; reject HTTPS-to-HTTP redirects and limit
   redirect count. Keep normal TLS certificate/hostname verification enabled.
3. Enforce streamed byte limits even when Content-Length is absent or false.
   Validate through the same codec before offering a preview or caching bytes.
4. Store the validated document in app-private files. Write a temporary sibling
   and replace atomically using the platform file API; failure leaves the old
   cache. A late cancelled response must not replace a newer request's result.
5. Distinguish loaded-from-cache, refreshed and refresh-failed states. A cached
   catalog never auto-imports or rewrites running clocks. Invalid cache is
   reported/ignored safely rather than trusted as a previously valid input.

Test transport logic separately from live TLS. A local/fake HTTP connection in
a unit test is not permission to relax production HTTPS requirements. Use the
shared sample files at a real public HTTPS URL only after they are published;
do not invent an endpoint or perform a Git push as an implicit setup step.
Record real fetch/offline checks separately from deterministic failure tests.

## Verification

Run transport/cache/coordinator tests, full unit suite, lint and debug assembly; verify one real HTTPS fetch and an offline retry on the identified build.

## Done when

A failed refresh preserves validated local data and ordinary timers remain offline. Describe this as HTTP client integration rather than claiming a backend service.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 014-cross-platform-check.md (after the companion tasks).
