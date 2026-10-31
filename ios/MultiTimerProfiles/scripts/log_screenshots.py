#!/usr/bin/env python3
"""Retain a few simulator PNGs in existing job logs without artifact storage.

Input must be the disposable simulator's exported XCTest attachments, never a
personal device directory. Explicit count/byte caps keep logs reviewable.
"""

import base64
import pathlib
import sys
import textwrap


def main():
    directory = pathlib.Path(sys.argv[1])
    images = sorted(directory.rglob("*.png"))
    for index, image in enumerate(images[:8]):
        if image.stat().st_size > 750_000:
            print("Skipping oversized simulator capture:", image.name)
            continue
        print("MULTITIMER_SCREENSHOT_BEGIN", index, image.name)
        print("\n".join(textwrap.wrap(base64.b64encode(image.read_bytes()).decode("ascii"), 4000)))
        print("MULTITIMER_SCREENSHOT_END", index)


if __name__ == "__main__":
    main()
