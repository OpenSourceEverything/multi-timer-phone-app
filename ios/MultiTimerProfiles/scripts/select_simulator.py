#!/usr/bin/env python3
"""Select an existing available iPhone runtime compatible with Xcode's SDK.

Input is simctl's JSON, not scraped display text. The caller provides the SDK
version so a newer runtime installed beside an older Xcode is not chosen by
accident. This script selects only; it neither creates nor erases devices.
"""

import json
import re
import sys


def select_device(payload, sdk_version):
    """Return the newest compatible iPhone as (UDID, name, runtime, state)."""
    sdk = tuple(int(part) for part in sdk_version.split("."))
    sdk = (sdk + (0, 0, 0))[:3]
    candidates = []
    for runtime, devices in payload["devices"].items():
        match = re.search(r"\.iOS-(\d+)-(\d+)(?:-(\d+))?$", runtime)
        if not match:
            continue
        version = tuple(int(part or 0) for part in match.groups())
        if version > sdk:
            continue
        for device in devices:
            if device.get("isAvailable") and device["name"].startswith("iPhone"):
                candidates.append((version, device["name"], runtime, device))
    if not candidates:
        raise ValueError("No installed available iPhone simulator matches SDK " + sdk_version)
    _, name, runtime, device = max(candidates, key=lambda row: (row[0], row[1]))
    return device["udid"], name, runtime, device["state"]


if __name__ == "__main__":
    print("\t".join(select_device(json.load(sys.stdin), sys.argv[1])))
