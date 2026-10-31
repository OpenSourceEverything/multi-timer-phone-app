# Share reusable profiles

Profile files let you copy a useful beep/speech schedule to another MultiTimer
installation. They contain templates, not running clocks or diagnostic logs.
Ordinary timers and file exchange do not require an account or network service.

The [illustrated user guide](user-guide/profile-sharing.md) includes real
screenshots and a 30-second export/import demonstration.
The [SwiftUI companion guide](user-guide/companion.md) covers editing the same
profiles in the native companion and returning them to Android.

## Export

1. Open **Reusable profiles** and choose the profiles you want to share.
2. Choose the export action, then select a destination in Android's file picker.
3. Share the resulting JSON file using your preferred file-transfer method.

Only the selected reusable templates are exported. Profiles owned by a specific
timer are not silently made public or converted into reusable templates.

## Import

1. Open **Reusable profiles**, choose **Import file**, and select a JSON file.
2. Review the profile names, schedules and enabled actions in the preview.
3. Choose the rows to import and confirm. Each selected row becomes a new copy.

Opening a file alone does not save it. Equal names are permitted: two imports
produce separate local copies rather than replacing an existing profile. A
clock already running keeps its existing attachments.

## Invalid or interrupted operations

- Cancel the picker or preview to leave stored profiles unchanged.
- Unsupported versions, malformed values and oversized files are rejected
  before confirmation. The v1 limit is 262,144 actual UTF-8 bytes.
- If Android recreates the screen during an unconfirmed import, reopen the
  file. A save already issued cannot be rolled back by closing the screen;
  inspect the reusable list before retrying so you do not add another copy.
- If exporting fails after writing begins, the chosen destination may contain
  an incomplete file. Do not treat it as a valid shared profile bundle.

The [format specification](profile-format.md) and
[shared examples](../profiles/fixtures/v1/README.md) explain the contract.
The [verification roadmap](tasks/profile-sharing/README.md) separates automated,
device and still-unperformed lifecycle checks.

## Implementation boundary

```text
Selected reusable profiles -> detached snapshots -> bounded JSON -> local file
Local file -> bounded read -> strict validation -> preview -> confirmation
  -> fresh local profile/event IDs -> append selected copies
```

The codec validates portable settings; the import coordinator owns selection
and confirmation. Storage appends new copies without replacing existing clocks
or profiles. File I/O runs on a worker. Activity recreation invalidates stale
screen callbacks; tests hold Loading and Saving at deterministic points.
Actual process termination requires the separate
[process-recovery check](tasks/profile-sharing/016-android-process-recovery.md).

## Companion ownership

The portable document is the shared boundary. Android keeps its Java clock
engine and Kotlin sharing coordinator; the SwiftUI companion edits profiles
without owning a background timer engine.

```text
Android selected reusable profiles -> Kotlin codec -> JSON file
  -> Swift bounded read/codec -> immutable import preview -> explicit confirm
  -> companion-local UUID + saved profile
  -> separate text draft -> validate -> atomic local save
  -> selected portable snapshots -> native file export -> JSON file
  -> Android preview/confirm -> fresh local profile/event IDs
```

The companion's MainActor model owns published UI state. File reading, encoding
and replacement run on workers; view rendering describes controls and bindings.
Request identities reject stale read/preview completions. A corrupt library
disables mutation instead of silently becoming an empty writable library.

Visual preview uses monotonic time with a 60-second horizon and 50-cue cap.
Stop, leaving the sheet and scene inactivity cancel its owned refresh task.
Displayed actions are not played audio. Both codecs consume the same root
fixtures and retain all eight independent action flags; local UUIDs and clock
progress never enter the portable document. HTTP/cache is a deferred extension.

The [combined verification task](tasks/profile-sharing/014-cross-platform-check.md)
separates codec tests, actual app file workflows, provider UI boundaries and
remaining lifecycle/audio qualification. A matching pair of codecs alone does
not prove the full exchange.
