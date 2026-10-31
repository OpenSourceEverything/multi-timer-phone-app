#!/usr/bin/env python3
"""Transport one bounded actual simulator MP4 through existing CI logs.

No extra artifact/cache upload is introduced. Extract to a new local filename,
review the recording and identify its source before publishing it in the guide.
This helper transports existing video bytes; it never generates demonstration
frames or transforms a failing test into evidence of a passing workflow.
"""

import argparse
import base64
import hashlib
import pathlib
import re
import textwrap

MAXIMUM_BYTES = 16 * 1024 * 1024


def verify(data):
    """Reject oversized/non-MP4 payloads before logging or writing them."""
    if len(data) > MAXIMUM_BYTES or len(data) < 12 or data[4:8] != b"ftyp":
        raise ValueError("Expected an actual MP4 no larger than 16 MiB")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("pack", "extract"))
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path, nargs="?")
    args = parser.parse_args()
    if args.operation == "pack":
        if args.source.stat().st_size > MAXIMUM_BYTES:
            raise ValueError("Converted simulator recording exceeds the 16 MiB log cap")
        raw = args.source.read_bytes()
        verify(raw)
        print("MULTITIMER_VIDEO_BEGIN", hashlib.sha256(raw).hexdigest())
        for line in textwrap.wrap(base64.b64encode(raw).decode("ascii"), 4000):
            print(line)
        print("MULTITIMER_VIDEO_END")
        return
    if args.destination is None:
        parser.error("extract requires a new destination filename")
    checksum = None
    chunks = []
    complete = False
    for line in args.source.read_text(encoding="utf-8-sig").splitlines():
        begin = re.search(r"MULTITIMER_VIDEO_BEGIN ([a-f0-9]{64})$", line)
        if begin:
            if checksum is not None:
                raise ValueError("Expected exactly one simulator recording")
            checksum = begin[1]
            continue
        if checksum is None or complete:
            continue
        if line.endswith("MULTITIMER_VIDEO_END"):
            complete = True
            continue
        match = re.search(r"(?:^|\s)([A-Za-z0-9+/=]+)\s*$", line)
        if not match:
            raise ValueError("Unexpected text inside recording")
        chunks.append(match[1])
        if sum(map(len, chunks)) > (MAXIMUM_BYTES + 2) // 3 * 4:
            raise ValueError("Encoded recording exceeds its log cap")
    if not complete:
        raise ValueError("No complete recording in log")
    raw = base64.b64decode("".join(chunks), validate=True)
    verify(raw)
    if hashlib.sha256(raw).hexdigest() != checksum:
        raise ValueError("Recording checksum mismatch")
    with args.destination.open("xb") as output:
        output.write(raw)
    print(args.destination, len(raw), "bytes; SHA-256", checksum)


if __name__ == "__main__":
    main()
