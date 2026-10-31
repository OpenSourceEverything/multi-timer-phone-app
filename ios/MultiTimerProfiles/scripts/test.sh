#!/usr/bin/env bash
# Run from any directory on a Mac with Xcode. Everything is local to this
# checkout's ignored build directory; no signing account or phone is selected.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
build_dir="${MULTITIMER_IOS_BUILD_DIR:-$project_dir/build}"
mkdir -p "$build_dir"

# Record real installed tooling/destinations before choosing one. A runner
# image name by itself is not proof of an available SDK or simulator runtime.
xcodebuild -version
xcrun swift --version
xcodebuild -showsdks
xcrun simctl list runtimes
xcrun simctl list devices available
xcodebuild -list -project "$project_dir/MultiTimerProfiles.xcodeproj"

# Exercise the same portable sources on the host before paying simulator
# startup time for a compile/schema failure. This is supplemental to XCTest UI.
if [[ -f "$project_dir/Package.swift" ]]; then
    swift test --package-path "$project_dir" --scratch-path "$build_dir/SwiftPM"
fi

sdk_version="$(xcrun --sdk iphonesimulator --show-sdk-version)"
selection="$(xcrun simctl list devices available --json | python3 "$script_dir/select_simulator.py" "$sdk_version")"
IFS=$'\t' read -r simulator_id simulator_name simulator_runtime simulator_state <<< "$selection"
printf 'Selected simulator: %s / %s / %s\n' "$simulator_name" "$simulator_runtime" "$simulator_id"
if [[ "$simulator_state" != Booted ]]; then
    xcrun simctl boot "$simulator_id"
fi
xcrun simctl bootstatus "$simulator_id" -b

# Build once, then separate unit and UI execution. Seed the actual installed
# app container after unit tests instead of assuming their host's Documents
# directory is the one exposed to the later UI-test application.
common=(-project "$project_dir/MultiTimerProfiles.xcodeproj" -scheme MultiTimerProfiles
        -destination "platform=iOS Simulator,id=$simulator_id"
        -parallel-testing-enabled NO -derivedDataPath "$build_dir/DerivedData"
        # Build the chosen simulator architecture only. Intel and ARM runners
        # provide separate route checks; a universal binary is unnecessary here.
        CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=YES)
xcodebuild build-for-testing "${common[@]}"

# Optional presentation capture observes only this disposable simulator. Stop
# the owned recorder even if testing fails; no orphan recording keeps running.
capture_pid=""
stop_capture() {
    if [[ -n "$capture_pid" ]]; then
        kill -INT "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" || true
        capture_pid=""
    fi
}
trap stop_capture EXIT

run_test_target() {
    local target="$1"
    local result_bundle="$build_dir/$target-$(date -u +%Y%m%dT%H%M%SZ).xcresult"
    local test_status=0
    if [[ "$target" == MultiTimerProfilesUITests && "${MULTITIMER_CAPTURE_DEMO:-false}" == true ]]; then
        xcrun simctl io "$simulator_id" recordVideo --codec=h264 "$build_dir/companion-demo.mp4" \
            > "$build_dir/companion-demo-recording.log" 2>&1 &
        capture_pid=$!
    fi
    xcodebuild test-without-building "${common[@]}" \
        "-only-testing:$target" -resultBundlePath "$result_bundle" || test_status=$?
    stop_capture
    xcrun xcresulttool get test-results summary --path "$result_bundle" || true
    local attachments="$result_bundle-attachments"
    mkdir -p "$attachments"
    if xcrun xcresulttool export attachments --path "$result_bundle" --output-path "$attachments"; then
        python3 -u "$script_dir/log_screenshots.py" "$attachments"
    fi
    return "$test_status"
}

run_test_target MultiTimerProfilesTests
app="$build_dir/DerivedData/Build/Products/Debug-iphonesimulator/MultiTimerProfiles.app"
# Inspect the generated plist, not only project settings. These must be actual
# booleans for Files to expose the app's Documents folder.
python3 - "$app/Info.plist" <<'PY'
import plistlib,sys
with open(sys.argv[1], "rb") as stream: info=plistlib.load(stream)
for key in ("UIFileSharingEnabled", "LSSupportsOpeningDocumentsInPlace"):
    print(key, repr(info.get(key)))
    if info.get(key) is not True: raise SystemExit("Missing boolean Files capability: " + key)
PY
xcrun simctl install "$simulator_id" "$app"
container="$(xcrun simctl get_app_container "$simulator_id" com.ose.multitimer.profiles data)"
python3 "$script_dir/prepare_picker_files.py" "$container"
xcrun simctl launch "$simulator_id" com.ose.multitimer.profiles
run_test_target MultiTimerProfilesUITests
# Read the document actually written by the native exporter, not a new codec
# encoding. This joins the UI test's fields to the next platform's input.
container="$(xcrun simctl get_app_container "$simulator_id" com.ose.multitimer.profiles data)"
python3 "$script_dir/collect_exchange.py" "$container"
if [[ "${MULTITIMER_CAPTURE_DEMO:-false}" == true ]]; then
    # Simulator's full-resolution H.264 recording exceeded the initial log
    # budget. Use macOS's built-in converter; no package/hardware purchase or
    # artifact storage is needed, and every frame still comes from the test.
    /usr/bin/avconvert --source "$build_dir/companion-demo.mp4" \
        --output "$build_dir/companion-demo-share.mp4" --preset Preset640x480
    python3 -u "$script_dir/demo_video.py" pack "$build_dir/companion-demo-share.mp4"
fi
