#!/usr/bin/env python3
"""Rebuild wata-fb/assets/chirp.ogg — the handset's startup bleep.

The source is a walkie-talkie activation recording the owner picked. It holds
TWO bleeps with silence between them: the first runs 100-560ms, the second
starts at 1200ms. We ship 0.00-0.60s — the whole first bleep with a short
tail, no trailing silence, and deliberately not the second one (two bleeps
read as a message arriving, one reads as "ready").

The output format is the device's own — Ogg/Opus, mono, 48kHz — so the chirp
plays through the same reader/decoder/playback path as a voice message.

`-page_duration 20000` puts ONE 20ms Opus packet in each Ogg page. Our reader
(`Ogg.readFrames`) treats a page's whole payload as a single frame, so an
ffmpeg-default file, which packs many packets per page, is read as one
2000-byte "frame" claiming 20ms. That reader limitation is tracked separately
(OGG-MULTI-PACKET-PAGE); until it is fixed the flag is what keeps the asset
inside what our own reader understands, and `wata-fb oggforeign` on the
committed asset is the check that says so.

The output is byte-stable across runs, so a regenerated asset that differs
from the committed one is a real change. The asset is committed precisely so
a build never needs the source, which lives only on the owner's machine.

    tools/make-chirp.py [--source PATH] [--out PATH] [--check]

`--check` regenerates to a temporary file and compares, without writing.
"""

import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCE = pathlib.Path.home() / "Downloads" / \
    "walkie-talkie-radio-signal-activation-bosnow-1-00-02.mp3"
OUT = REPO / "wata-fb" / "assets" / "chirp.ogg"

# The trim and the encode, recorded here rather than in someone's shell
# history. `-vn` is load-bearing: the mp3 carries an embedded PNG cover, and
# without it ffmpeg tries to encode a video stream into the ogg and dies with
# "Default encoder for format ogg (codec theora) is probably disabled".
TRIM_SECONDS = "0.60"
#
# `-fflags +bitexact` is what makes the output byte-stable: without it the Ogg
# muxer picks a RANDOM stream serial number per run (and stamps its own vendor
# string into the comment header), so two encodes of the same audio differ in
# the serial and every page CRC. bitexact pins the serial to `serial_offset`
# (0) and drops the vendor string; the audio bytes are identical either way.
ENCODE = ["-t", TRIM_SECONDS, "-ac", "1", "-ar", "48000",
          "-c:a", "libopus", "-b:a", "24k", "-application", "audio",
          "-page_duration", "20000", "-fflags", "+bitexact",
          "-flags:a", "+bitexact"]


def encode(source: pathlib.Path, out: pathlib.Path) -> None:
    if not source.exists():
        sys.exit(
            f"make-chirp: source not found: {source}\n"
            "  It lives on the owner's machine only; the encoded asset is\n"
            f"  committed at {OUT.relative_to(REPO)} so no build needs it.\n"
            "  Pass --source PATH if you have the file elsewhere.")
    if shutil.which("ffmpeg") is None:
        sys.exit("make-chirp: ffmpeg not on PATH (brew install ffmpeg)")
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-vn", "-i", str(source),
                    *ENCODE, str(out)], check=True)


def describe(path: pathlib.Path) -> str:
    data = path.read_bytes()
    return f"{len(data)} bytes  sha256 {hashlib.sha256(data).hexdigest()}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", type=pathlib.Path, default=SOURCE)
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--check", action="store_true",
                    help="regenerate to a temp file and diff, writing nothing")
    args = ap.parse_args()

    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            probe = pathlib.Path(tmp) / "chirp.ogg"
            encode(args.source, probe)
            if probe.read_bytes() != args.out.read_bytes():
                sys.exit(f"make-chirp: {args.out} differs from a fresh encode\n"
                         f"  committed: {describe(args.out)}\n"
                         f"  fresh:     {describe(probe)}")
            print(f"make-chirp: {args.out} matches a fresh encode "
                  f"({describe(args.out)})")
        return

    encode(args.source, args.out)
    print(f"make-chirp: wrote {args.out} — {describe(args.out)}")


if __name__ == "__main__":
    main()
