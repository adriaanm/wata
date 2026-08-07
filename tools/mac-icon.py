#!/usr/bin/env python3
"""Build tools/wata.iconset from the artwork (plan 0037, slice 1).

    tools/mac-icon.py            # regenerate the .iconset
    just mac-icon

Source art: `tools/wata-icon-src.png` — a pixel-art handset, screen lit
with the app's name, on black. This crops it to the device (the source
also carries a decorative sparkle, which is not part of the icon),
scales it, and composites it onto a rounded plate, writing the ten PNGs
`iconutil` wants. `tools/mac-app.py` turns those into the .icns.

The BACKGROUND-AS-ALPHA rule is what makes this work: macOS does not
round app icons the way iOS does, so a full-bleed black square really
would show up as a black square in the Dock. Every source pixel darker
than `INK_FLOOR` is treated as transparent, and the plate shows through
— including the dark pixels INSIDE the device (its grille, its shadowed
side), which is why the plate is near-black rather than a colour: those
holes have to be invisible.

Dependencies: `sips` for the crop and the resample, which is a macOS
built-in exactly like the `iconutil` this feeds. PNG is read and written
by hand (zlib + struct), the stdlib-only approach tools/fb-shot.py uses.
The PNGs are committed so no build pays for this; the generator is kept
so the icon stays something you change by editing values.
"""

import os
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "wata-icon-src.png")
OUT = os.path.join(HERE, "wata.iconset")

# the device in the source, without the decorative sparkle to its right
CROP_X, CROP_Y, CROP_W, CROP_H = 322, 8, 444, 545

PLATE = (11, 15, 12)      # near-black: the art's own background, so the
                          # dark pixels inside the device vanish into it
INK_FLOOR = 30            # darker than this in the source == background
ART_FRACTION = 0.86       # how much of the icon the device fills
CORNER = 0.225            # macOS plate radius, as a fraction of the size


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"mac-icon: {' '.join(cmd)} failed:\n{r.stdout}{r.stderr}")


def read_png(path):
    """(w, h, rows-of-RGB) for the 8-bit non-interlaced PNGs sips writes."""
    d = open(path, "rb").read()
    pos, idat, w, h, ct = 8, b"", 0, 0, 2
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        tag, data = d[pos + 4:pos + 8], d[pos + 8:pos + 8 + ln]
        if tag == b"IHDR":
            w, h, _, ct = struct.unpack(">IIBB", data[:10])
        elif tag == b"IDAT":
            idat += data
        pos += 12 + ln
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride, prev, rows, i = w * ch, bytearray(w * ch), [], 0

    def paeth(a, b, c):
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if pa <= pb and pa <= pc else (b if pb <= pc else c)

    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if f:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                b = prev[x]
                c = prev[x - ch] if x >= ch else 0
                if f == 1:
                    line[x] = (line[x] + a) & 255
                elif f == 2:
                    line[x] = (line[x] + b) & 255
                elif f == 3:
                    line[x] = (line[x] + ((a + b) >> 1)) & 255
                else:
                    line[x] = (line[x] + paeth(a, b, c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, rows, ch


def write_png(path, n, raw):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    open(path, "wb").write(png)


def in_plate(x, y, n, r):
    """the rounded square, sampled at pixel centres."""
    px, py = x + 0.5, y + 0.5
    cx = min(max(px, r), n - r)
    cy = min(max(py, r), n - r)
    dx, dy = px - cx, py - cy
    return dx * dx + dy * dy <= r * r


def build(n, tmp):
    """one icon: device art scaled to ART_FRACTION of n, centred on the plate."""
    art_h = max(1, int(round(n * ART_FRACTION)))
    art_w = max(1, int(round(art_h * CROP_W / CROP_H)))
    cropped = os.path.join(tmp, "crop.png")
    scaled = os.path.join(tmp, f"s{n}.png")
    sh(["sips", "--cropOffset", str(CROP_Y), str(CROP_X),
        "-c", str(CROP_H), str(CROP_W), SRC, "--out", cropped])
    sh(["sips", "-z", str(art_h), str(art_w), cropped, "--out", scaled])
    aw, ah, rows, ch = read_png(scaled)

    ox, oy = (n - aw) // 2, (n - ah) // 2
    r = n * CORNER
    out = bytearray()
    for y in range(n):
        out.append(0)                                  # PNG filter: none
        for x in range(n):
            if not in_plate(x, y, n, r):
                out += b"\0\0\0\0"
                continue
            px, py = x - ox, y - oy
            pix = None
            if 0 <= px < aw and 0 <= py < ah:
                o = px * ch
                p = rows[py][o:o + 3]
                if max(p) >= INK_FLOOR:
                    pix = p
            out += bytes((pix[0], pix[1], pix[2], 255)) if pix else \
                bytes((PLATE[0], PLATE[1], PLATE[2], 255))
    return bytes(out)


def main():
    if not os.path.exists(SRC):
        sys.exit(f"mac-icon: no artwork at {SRC}")
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for base in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                n = base * scale
                name = f"icon_{base}x{base}{'@2x' if scale == 2 else ''}.png"
                write_png(os.path.join(OUT, name), n, build(n, tmp))
                print("  " + name)
    print("mac-icon: wrote " + os.path.relpath(OUT, os.path.dirname(HERE)))


main()
