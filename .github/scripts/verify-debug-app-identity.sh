#!/usr/bin/env bash
set -euo pipefail

# Check the built debug APK itself so CI catches a second package or launcher.
# Unit tests cannot see Gradle's merged application ID or Android manifest.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"

if [[ ! -f "$APK" ]]; then
  echo "Debug APK not found: $APK" >&2
  exit 1
fi
if [[ ! -x "$AAPT" ]]; then
  echo "Android aapt tool not found: $AAPT" >&2
  exit 1
fi

BADGING="$("$AAPT" dump badging "$APK")"

# This must be the package that owns the user's timers and profiles.
if ! grep -Eq "^package: name='com\\.ose\\.multitimer' " <<< "$BADGING"; then
  echo "Debug APK does not use the main package com.ose.multitimer." >&2
  exit 1
fi

# The debug build must look like the ordinary app and expose one app icon.
if ! grep -Fxq "application-label:'Multi Timer'" <<< "$BADGING"; then
  echo "Debug APK does not use the Multi Timer app label." >&2
  exit 1
fi
LAUNCHER_COUNT="$(grep -c '^launchable-activity:' <<< "$BADGING" || true)"
if [[ "$LAUNCHER_COUNT" -ne 1 ]]; then
  echo "Expected one launcher activity; found $LAUNCHER_COUNT." >&2
  exit 1
fi

echo "Debug APK identity verified: com.ose.multitimer, Multi Timer, one launcher."
