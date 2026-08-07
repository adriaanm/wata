#!/usr/bin/env python3
"""Did the handset's startup chirp actually make a sound? Measured, not assumed.

    tools/chirp-check.py              # exits 0 if the chirp was heard
    tools/chirp-check.py --mic Yeti --seconds 8

Restarts the app on the device (tty1 respawns it, and it chirps once its mixer
is up) while recording the room on this Mac, then compares the chirp's band
against the same band in a baseline recorded moments earlier, and against a
neighbouring band in the SAME recording. A speaker route that reads right in
`amixer` but plays nothing fails here — which is the whole reason the chirp
exists (AUDIO-ROUTE-REAPPLY).

This is the chirp's counterpart to bq268-alpine's `just speaker-check`, which
cannot judge it: that one plays its own sine through `speaker-test` on hw:0,0,
a device wata holds while it plays, and its band test assumes a single tone.
The chirp is a broadband bleep played by the app itself.

The chirp is ~0.6s inside a multi-second recording, so a whole-recording RMS
would dilute it away: both recordings are scanned in short hops and compared
at their LOUDEST window.

Needs ffmpeg + sox on the host (`brew install ffmpeg sox`), an input device
matching --mic placed near the handset, and the device as ssh host
$BQ268_HOST (default bq268). A speaker this cannot hear from where the mic is
standing is indistinguishable from a silent one — read a FAIL with that in
mind and check the mic before believing it.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

HOST = os.environ.get("BQ268_HOST", "bq268")

# The asset's own energy lives between ~400 and ~2600 Hz, peaking around
# 800-1000 and 1600-2000; above 3.4kHz it is ~4x quieter. So the chirp band is
# what we listen for and the higher band is the in-recording control.
CHIRP_BAND = (700, 2600)
NEIGHBOUR_BAND = (3500, 7000)
WINDOW = 0.5      # seconds per measurement window
HOP = 0.25        # window step
MIN_VS_QUIET = 2.5      # the loudest chirp-band window vs the baseline's
MIN_VS_NEIGHBOUR = 1.3  # ... and vs the neighbouring band under it


def mic_index(name: str) -> str:
    out = subprocess.run(["ffmpeg", "-f", "avfoundation", "-list_devices", "true",
                          "-i", ""], capture_output=True, text=True).stderr
    audio = out.split("AVFoundation audio devices:")[-1]
    for line in audio.splitlines():
        m = re.search(r"\[(\d+)\] (.+)$", line)
        if m and name.lower() in m.group(2).lower():
            return m.group(1)
    sys.exit(f"chirp-check: no input device matching {name!r}\n{audio}")


def record(idx: str, seconds: float, path: str) -> subprocess.Popen:
    return subprocess.Popen(
        ["ffmpeg", "-y", "-f", "avfoundation", "-i", f":{idx}",
         "-t", str(seconds), "-ac", "1", "-ar", "44100", path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def band_rms(path: str, band, start: float = None) -> float:
    trim = ["trim", str(start), str(WINDOW)] if start is not None else []
    out = subprocess.run(["sox", path, "-n", "sinc", f"{band[0]}-{band[1]}",
                          "stat", *trim], capture_output=True, text=True).stderr
    m = re.search(r"RMS\s+amplitude:\s+([0-9.]+)", out)
    return float(m.group(1)) if m else 0.0


def loudest(path: str, seconds: float):
    """the window with the most chirp-band energy: (rms, start, neighbour rms)."""
    best = (0.0, 0.0, 0.0)
    start = 0.0
    while start + WINDOW <= seconds:
        rms = band_rms(path, CHIRP_BAND, start)
        if rms > best[0]:
            best = (rms, start, band_rms(path, NEIGHBOUR_BAND, start))
        start += HOP
    return best


def reboot() -> None:
    # ssh dies with the reboot; that is the success signal, not a failure.
    subprocess.run(["ssh", "-o", "ConnectTimeout=10", f"root@{HOST}", "reboot"],
                   check=False)


def restart_app() -> None:
    # The bracket keeps the pattern from matching the ssh command line that
    # carries it — `pkill -f 'wata-fb ui'` kills its own remote shell.
    subprocess.run(["ssh", "-o", "ConnectTimeout=10", f"root@{HOST}",
                    "pkill -f 'wata-fb[ ]ui'"], check=False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mic", default="Yeti")
    ap.add_argument("--seconds", type=float, default=8.0,
                    help="how long to listen after the restart")
    ap.add_argument("--no-restart", action="store_true",
                    help="negative control: listen without making the app "
                         "chirp, which must FAIL")
    ap.add_argument("--cold-boot", action="store_true",
                    help="reboot the device instead of restarting the app, and "
                         "listen through the whole boot — the case where the "
                         "codec can reset the route as the Q6 comes up")
    args = ap.parse_args()
    if args.cold_boot and args.seconds == ap.get_default("seconds"):
        args.seconds = 90.0        # this device's runlevel is ~40s

    idx = mic_index(args.mic)
    tmp = tempfile.mkdtemp(prefix="chirp-check.")
    base, heard = os.path.join(tmp, "base.wav"), os.path.join(tmp, "chirp.wav")

    print(f"chirp-check: baseline ({args.seconds}s of the room, device quiet)")
    record(idx, args.seconds, base).wait()
    quiet = loudest(base, args.seconds)[0]

    if args.no_restart:
        what = "listening with NOTHING played (negative control)"
    elif args.cold_boot:
        what = f"rebooting {HOST} and listening through the whole boot"
    else:
        what = f"restarting the app on {HOST} and listening"
    print(f"chirp-check: {what}")
    rec = record(idx, args.seconds, heard)
    if args.cold_boot:
        reboot()
    elif not args.no_restart:
        restart_app()      # tty1 respawns it; the chirp follows its mixer setup
    rec.wait()
    loud, at, neighbour = loudest(heard, args.seconds)

    vs_quiet = loud / quiet if quiet > 0 else float("inf")
    vs_neighbour = loud / neighbour if neighbour > 0 else float("inf")
    print(f"chirp-check: loudest {CHIRP_BAND[0]}-{CHIRP_BAND[1]}Hz window at "
          f"+{at:.2f}s = {loud:.6f}  "
          f"neighbouring band={neighbour:.6f} ({vs_neighbour:.1f}x)  "
          f"baseline={quiet:.6f} ({vs_quiet:.1f}x)")
    if vs_quiet >= MIN_VS_QUIET and vs_neighbour >= MIN_VS_NEIGHBOUR:
        print("chirp-check: PASS — the handset said hello")
        return
    print(f"chirp-check: FAIL — nothing heard (needs {MIN_VS_QUIET}x over the "
          f"baseline and {MIN_VS_NEIGHBOUR}x over the neighbouring band)")
    # The route is what silences this device, and it is invisible from here.
    print(subprocess.run(
        ["ssh", "-o", "ConnectTimeout=10", f"root@{HOST}",
         'tail -3 /tmp/wata.log; ls -la /opt/wata/chirp.ogg; '
         'for c in "RX2 MIX1 INP1" "Ext Spk Switch" "RX2 Digital Volume"; do '
         'printf "%-24s " "$c"; amixer -c 0 cget name="$c" 2>/dev/null | '
         'awk "/: values=/{print}"; done'],
        capture_output=True, text=True).stdout)
    sys.exit(1)


if __name__ == "__main__":
    main()
