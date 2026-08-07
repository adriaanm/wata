#!/usr/bin/env python3
"""Grab the device panel as a PNG — what the handset is showing, right now.

    tools/fb-shot.py                 # -> /tmp/wata-fb-shot.png
    tools/fb-shot.py shot.png        # somewhere else

Reads /dev/fb0 over ssh (160x128 RGB565, the ST7735S panel) and writes a PNG
with the standard library only — nothing to install on either side. The panel
is readable while wata-fb owns it, so this is a live view, not a takeover.
"""

import base64
import os
import struct
import subprocess
import sys
import zlib

HOST = os.environ.get("BQ268_HOST", "bq268")
W, H = 160, 128


def grab() -> bytes:
    out = subprocess.run(
        ["ssh", "-o", "ConnectTimeout=15", HOST,
         f"dd if=/dev/fb0 bs={W * H * 2} count=1 2>/dev/null | base64"],
        check=True, capture_output=True, text=True).stdout
    data = base64.b64decode(out)
    if len(data) < W * H * 2:
        sys.exit(f"fb-shot: short read ({len(data)} bytes)")
    return data


def png(data: bytes) -> bytes:
    raw = bytearray()
    for y in range(H):
        raw.append(0)  # filter: none
        for x in range(W):
            v = struct.unpack_from("<H", data, (y * W + x) * 2)[0]
            raw += bytes((((v >> 11) & 31) * 255 // 31,
                          ((v >> 5) & 63) * 255 // 63,
                          (v & 31) * 255 // 31))

    def chunk(tag: bytes, body: bytes) -> bytes:
        c = tag + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw)))
            + chunk(b"IEND", b""))


def main() -> None:
    dest = sys.argv[1] if len(sys.argv) > 1 else "/tmp/wata-fb-shot.png"
    with open(dest, "wb") as f:
        f.write(png(grab()))
    print(dest)


if __name__ == "__main__":
    main()
