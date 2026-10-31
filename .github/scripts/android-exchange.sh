#!/usr/bin/env bash
# Disposable hosted emulator only. Never select an attached personal phone.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ]]; then
    echo 'This verification script is restricted to the hosted test emulator.' >&2
    exit 1
fi
serial=emulator-5554
# PATH changes in an earlier Actions shell do not persist into this step.
# Gradle locates SDK tools itself; this direct instrumentation call must do so.
adb_tool="${ANDROID_HOME:?Android SDK location is required}/platform-tools/adb"
if [[ ! -x "$adb_tool" ]]; then
    echo "adb was not found at $adb_tool" >&2
    exit 1
fi
if [[ "${MULTITIMER_REQUIRE_EXCHANGE_INPUT:-}" == true && -z "${MULTITIMER_EXCHANGE_INPUT_BASE64:-}" ]]; then
    echo 'The companion job supplied no export; refusing a substitute self-round-trip.' >&2
    exit 1
fi
if [[ "$("$adb_tool" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'Expected the disposable emulator; refusing instrumentation.' >&2
    exit 1
fi
bash gradlew --no-daemon assembleDebug assembleDebugAndroidTest
"$adb_tool" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_tool" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
arguments=(-e class com.ose.multitimer.ProfileFileExchangeTest)
if [[ -n "${MULTITIMER_EXCHANGE_INPUT_BASE64:-}" ]]; then
    arguments+=(-e exchangeInputBase64 "$MULTITIMER_EXCHANGE_INPUT_BASE64")
fi
result_file="$RUNNER_TEMP/multitimer-exchange-result.txt"
"$adb_tool" -s "$serial" shell am instrument -w -r "${arguments[@]}" \
    com.ose.multitimer.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_file"
python3 .github/scripts/collect_android_exchange.py "$result_file"
