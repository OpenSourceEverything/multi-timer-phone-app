# MultiTimer Profiles — SwiftUI companion

The companion creates and edits portable reusable timer profiles. Android keeps
its timing engine. The list/editor supports repeating and scripted cues, all
eight audio flags, validated Save/Cancel, and local persistence. A visual
foreground preview shows up to 60 seconds/50 cues and stops on exit/inactivity.
It plays no audio. Local JSON import/export uses the system file pickers.

## Use the companion

1. Choose New profile, enter a name and repeating interval or scripted cues.
   Save validates the complete draft; Cancel discards it.
2. Reopen a row to edit. Preview schedule displays the first minute, capped at
   50 cues. Start/Stop controls this visual preview; no audio is played.
3. Import file opens a JSON document, then shows a selection preview. Only
   Import confirmation saves new copies. Equal names never replace saved rows.
4. Check circles beside saved rows, choose Export selected and choose a file
   destination. Only checked profiles are included. Transfer the JSON using
   your normal file-copy method; no account or network service is required.

The companion's Documents folder is visible in Files. Cancelling a picker or
unconfirmed preview leaves saved profiles unchanged. During a save, wait for
completion before retrying. If a provider export fails after writing begins,
its chosen destination may contain an incomplete document.

## Build and test

Use Xcode 16.4 or newer on macOS. From the repository root:

```sh
bash ios/MultiTimerProfiles/scripts/test.sh
```

The script lists the actual Xcode/SDK versions and installed simulators, chooses
an available iPhone runtime no newer than the selected simulator SDK, and runs
the shared scheme's unit and UI tests. No signing identity, Apple enrollment
or physical phone is used. Open MultiTimerProfiles.xcodeproj in Xcode for local
interactive use. Sources/Tests/UITests use Xcode's synchronized folders; root
profiles/fixtures/v1 is referenced directly and copied into the unit-test bundle.

On Linux, the [SwiftUI companion workflow](../../.github/workflows/ios.yml)
provides the hosted macOS route. Run it from GitHub Actions or with the GitHub
CLI after the workflow is published:

```sh
gh workflow run ios.yml
```

## Hosted compute and evidence

The repository is public and Actions is enabled. The workflow selects the
standard macos-15 ARM runner and Xcode 16.4, and runs only for public repos.
Manual runs can select the already-proven macos-15-intel route instead. Each
architecture has its own concurrency group so changing the runner does not
cancel an in-flight check on the other architecture.
[GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
and [billing rules](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
describe free standard public-repository compute; larger runners and stored
artifacts have different billing rules. This baseline adds neither artifact
upload nor cache storage. It purchases no hardware/services or enrollment.

[Runner image tooling](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md)
is a discovery aid; actual build/test logs establish which environment ran.
Hosted run 40850974667 on source 51a1eaa passed both smoke tests using Xcode
16.4 (16F6), iPhone SE (3rd generation) / iOS 18.5. A passing smoke test does
not complete the editor or cross-platform file workflow.

Run 40893514226 on 7a07a05 passed 24 portable host tests, 30 simulator units and
all three real UI cases. The UI imports/cancels/confirms, edits a scripted name
and offset, then selects/exports four profiles through Files. The host validates
the actual exported bytes. Complete two-app exchange has its separate gate in
task 014. All three jobs passed actual Android → SwiftUI edit/export → Android
reimport in run 40893550590 on the same source. Optional HTTP/cache remains deferred.

## Portable core tests

With a Swift 6.1-compatible toolchain on Linux or macOS:

```sh
swift test --package-path ios/MultiTimerProfiles
```

The package compiles the same codec/model/parser files and reads the unchanged
root fixtures. It excludes SwiftUI and cannot replace simulator/UI execution.
[Codec design](docs/codec-design.md) explains strict parsing and resource bounds.

UI tests retain actual simulator screenshots in the XCTest result. The hosted
script exports a bounded subset into the job log, without new artifact storage.
After downloading a run log, recover them into a fresh directory with:

```sh
python3 ios/MultiTimerProfiles/scripts/extract_screenshots.py run.log /tmp/new-capture-directory
```

Review the recovered images before publishing; UI screenshots establish rendered
interaction, not physical audio timing or actual Android round-trip acceptance.

For a one-time real simulator recording, manually run the workflow with
capture_demo=true. The recorder observes the UI tests, stops on completion or
failure, converts using macOS's built-in 640×480 preset and retains at most
16 MiB of MP4 in existing job logs. No extra artifact
storage is used. Recover a downloaded log to a new file with:

```sh
python3 ios/MultiTimerProfiles/scripts/demo_video.py extract run.log /tmp/companion-demo.mp4
```

Review the actual recording before publishing. Recording capture is independent
of the normal correctness gate and does not measure playback or timer accuracy.
