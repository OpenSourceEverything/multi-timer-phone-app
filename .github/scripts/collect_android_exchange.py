#!/usr/bin/env python3
"""Require a passing app test before transporting its bounded public export.

am instrument may exit zero for a failed test, so shell success alone cannot
authorize the next platform. Check the runner result and exact public fields.
"""

import base64
import hashlib
import json
import os
import pathlib
import re
import sys


def main():
    result = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
    if not re.search(r"OK \(1 test\)", result) or "FAILURES!!!" in result:
        raise ValueError("Android exchange instrumentation did not pass exactly one test")
    matches = re.findall(r"INSTRUMENTATION_STATUS: multitimer_export_base64=([A-Za-z0-9+/=]+)", result)
    if len(matches) != 1:
        raise ValueError("Expected one actual Android export")
    raw = base64.b64decode(matches[0], validate=True)
    if len(raw) > 262_144:
        raise ValueError("Android export exceeds portable size limit")
    source = pathlib.Path(__file__).resolve().parents[2] / "profiles/exchange-examples.json"
    expected = json.loads(source.read_text(encoding="utf-8"))["profiles"]
    actual = json.loads(raw)
    if set(actual) != {"format", "version", "profiles"} or actual["format"] != "com.ose.multitimer.profiles" or actual["version"] != 1:
        raise ValueError("Unexpected Android document envelope")
    if sorted(actual["profiles"], key=lambda p: p["name"]) != sorted(expected, key=lambda p: p["name"]):
        raise ValueError("Android export must contain exactly the four public examples")
    print("Android export SHA-256:", hashlib.sha256(raw).hexdigest())
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("profile_bundle=" + matches[0] + "\n")


if __name__ == "__main__":
    main()
