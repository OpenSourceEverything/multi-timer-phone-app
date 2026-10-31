#!/usr/bin/env python3
"""Validate the actual system-exported example file and hand it to the next job.

Only four public examples are permitted. A job output transports their bounded
bytes; no account, app-private library or diagnostic data is uploaded.
"""

import base64
import hashlib
import json
import os
import pathlib
import sys


def main():
    documents = pathlib.Path(sys.argv[1]) / "Documents"
    source = pathlib.Path(__file__).resolve().parents[3] / "profiles/exchange-examples.json"
    expected = json.loads(source.read_text(encoding="utf-8"))["profiles"]
    expected[1]["name"] = "Exchange example edited"
    expected[1]["events"][0]["offsetMillis"] = 1000
    # XCTest can reinstall the app between phases; the driver resolves its
    # container again after UI execution. List only our disposable Documents
    # filenames so a missing export remains diagnosable without a library dump.
    print("Post-UI Documents entries:", sorted(path.name for path in documents.iterdir()))
    outputs = list(documents.glob("multitimer-profiles*.json"))
    if len(outputs) != 1:
        raise ValueError("Expected exactly one real system export; found " + str(outputs))
    raw = outputs[0].read_bytes()
    if len(raw) > 262_144:
        raise ValueError("System export exceeds the portable byte limit")
    actual = json.loads(raw)
    if set(actual) != {"format", "version", "profiles"} or actual["format"] != "com.ose.multitimer.profiles" or actual["version"] != 1:
        raise ValueError("Unexpected system-exported envelope")
    if sorted(actual["profiles"], key=lambda p: p["name"]) != sorted(expected, key=lambda p: p["name"]):
        raise ValueError("The UI export did not preserve every expected field and edit")
    print("Verified system-exported profiles:", len(actual["profiles"]))
    print("SwiftUI export SHA-256:", hashlib.sha256(raw).hexdigest())
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("profile_bundle=" + base64.b64encode(raw).decode("ascii") + "\n")


if __name__ == "__main__":
    main()
