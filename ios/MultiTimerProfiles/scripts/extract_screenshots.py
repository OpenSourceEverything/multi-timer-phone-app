#!/usr/bin/env python3
"""Recover bounded simulator PNGs from a downloaded companion job log.

Choose a new output directory for each run. Existing files are never replaced;
screenshots must be visually reviewed before copying them into product docs.
"""

import argparse
import base64
import pathlib
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    current = None
    chunks = []
    recovered = []
    with args.log.open(encoding="utf-8-sig") as stream:
        for line in stream:
            begin = re.search(r"MULTITIMER_SCREENSHOT_BEGIN (\d+) (.+)$", line.rstrip())
            if begin:
                name = pathlib.Path(begin.group(2)).name
                if not name.endswith(".png"):
                    raise ValueError("Unexpected screenshot filename")
                current = (begin.group(1), name)
                chunks = []
                continue
            if current is None:
                continue
            if "MULTITIMER_SCREENSHOT_END " + current[0] in line:
                data = base64.b64decode("".join(chunks), validate=True)
                if len(data) > 750_000 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("Invalid or oversized capture")
                destination = args.output / (current[0] + "-" + current[1])
                with destination.open("xb") as output:
                    output.write(data)
                recovered.append(destination)
                current = None
                continue
            # gh run view prefixes each captured line with job/step/timestamp.
            match = re.search(r"(?:^|\s)([A-Za-z0-9+/=]+)\s*$", line)
            if not match:
                raise ValueError("Unexpected data inside capture")
            chunks.append(match.group(1))
            if sum(map(len, chunks)) > 1_000_000:
                raise ValueError("Encoded capture exceeds its log limit")
    if current is not None or not recovered:
        raise ValueError("No complete simulator captures found")
    for destination in recovered:
        print(destination)


if __name__ == "__main__":
    main()
