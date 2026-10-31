# 010 — Establish the SwiftUI companion build

Status: Complete; actual hosted native build and both smoke tests passed.
Dependencies: 004. Android documentation/demo checkpoint 015A is complete.

Environment check — 2026-10-29: the repository is public and Actions is enabled.
The new workflow uses the standard macos-15-intel runner with Xcode 16.4; it
discovers a compatible installed simulator instead of guessing a device name.
The native app has unit/UI smoke targets and directly references root fixtures.
Local Bash/Python/XML/YAML checks pass; this Linux host cannot compile SwiftUI.
Hosted run 40850974667 on commit 51a1eaa executed xcodebuild test successfully:
Xcode 16.4 (16F6), iPhone SE (3rd generation), iOS 18.5. The shared-fixture
unit smoke test and real app-launch UI smoke test both passed (2 tests, no
failures). Android regression run 40850974686 also passed on the same source.
The initial clean simulator build took roughly thirteen minutes; the run logs
retain actual SDK/destination and XCTest summary evidence.

Cost boundary: GitHub documents free standard public-repository runner compute.
This slice adds no artifact upload, cache storage, signing identity, enrollment
or billing-setting change. See ios/MultiTimerProfiles/README.md for primary
references and reproduction commands.

## Outcome

Create a small native companion under ios/MultiTimerProfiles with a build and simulator test route.

## Read first

docs/profile-format.md; profiles/fixtures/v1; README.md; current Apple Xcode and GitHub macOS-runner documentation.

## Bounded work

- Check available macOS/Xcode access locally or through an authorized CI route; record what actually runs.
- Add the minimal SwiftUI target and unit-test target with a reproducible project/build command.
- Keep shared contract/fixtures in profiles/ rather than duplicating divergent formats in each app.
- Add simulator checks without distribution signing. A workflow file remains pending until hosted execution passes.
- Keep Android-only development/build commands usable on Linux/Windows.

Suggested implementation order:

1. Record the host and available tools. A Linux Swift compiler can help test
   platform-independent code, but does not establish an iOS SwiftUI build.
   If no macOS/Xcode execution route is available, record that concrete need
   and continue the independent Android chain. Do not mark this task complete.
2. Create the smallest native app/test project with a shared build scheme.
   Use one reproducible Xcode project (or a justified project generator with
   its exact setup command), not a large dependency/build framework.
3. Add a smoke test and include the root profiles/fixtures resources directly.
   Document how that reference survives a clean checkout without private paths.
4. List available schemes and simulator destinations. Record an actual
   xcodebuild build/test invocation using the selected available simulator;
   do not guess a device name/runtime that is absent on the runner.
5. CI configuration is optional setup until a real authorized run exists.
   Keep build-only/simulator operation independent of release credentials.

## Verification

Build and run the unit test target using xcodebuild with an available simulator destination. Record Xcode/runtime versions and build output.

## Done when

A real build/test succeeds against an identified Xcode/simulator environment.
If it is unavailable, record Environment needed and leave the task open while
independent Android work continues. Source or a workflow file alone is not
iOS verification and does not satisfy this completion gate.

Follow CONTRIBUTING.md for comments, method contracts and behavior-focused
tests. Keep evidence accurate; leave an unperformed check pending.

Next task: 011-swift-codec.md.
