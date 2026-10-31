#!/usr/bin/env python3
"""Seed harmless example documents into an identified simulator container.

The shell driver obtains the container with simctl after installing this build.
No phone, existing app library or user-selected document is touched.
"""

import json
import base64
import os
import pathlib
import sys


def main():
    documents = pathlib.Path(sys.argv[1]) / "Documents"
    source = pathlib.Path(__file__).resolve().parents[3] / "profiles/exchange-examples.json"
    bundle = json.loads(source.read_text(encoding="utf-8"))
    # A combined CI run supplies bytes actually written by Android's UI flow.
    # Reject anything but these public examples before logging or opening it.
    encoded = os.environ.get("MULTITIMER_EXCHANGE_INPUT_BASE64", "")
    if os.environ.get("MULTITIMER_REQUIRE_EXCHANGE_INPUT") == "true" and not encoded:
        raise ValueError("The Android job supplied no export; refusing sample substitution")
    if encoded:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 262_144 or json.loads(raw) != bundle:
            raise ValueError("Android export must match the four public exchange examples")
    else:
        raw = json.dumps(bundle, ensure_ascii=False).encode("utf-8")
    documents.mkdir(parents=True, exist_ok=True)
    (documents / "Exchange example.json").write_bytes(raw)
    (documents / "Malformed example.json").write_text("invalid JSON", encoding="utf-8")
    print("Prepared simulator picker documents:", sorted(p.name for p in documents.iterdir()))


if __name__ == "__main__":
    main()
