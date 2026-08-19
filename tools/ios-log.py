#!/usr/bin/env python3
"""`just ios-log` — pull wata-ios's on-device log and print it.

Plan 0064: the app tees its stdout+stderr into Documents/wata.log inside
its own sandbox (truncated at each launch), so a normal icon-tap run's
output is readable afterwards without a tethered console. This fetches it
over the CoreDevice tunnel:

  xcrun devicectl device copy from --domain-type appDataContainer \
      --domain-identifier <bundle-id> --source Documents/wata.log ...

Device: --device, else $WATA_DEVICE, else the single attached iPhone
(ios-device.py's pick).

Bundle id: --bundle-id, else $WATA_BUNDLE_ID, else ASK THE PHONE — the
installed app is whatever identity it was signed with, and a wrong guess
fails with `ContainerLookupErrorDomain error -1`, which names neither the
bundle it looked for nor the ones that exist. The app currently rides
net.wa-ta.hello's identity on the owner's phone while net.wa-ta.ios is
the convention (IOS-ON-DEVICE), so guessing was wrong half the time and
the error read as a broken log rather than a wrong name.
"""

import argparse
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

# ios-device.py's name has a dash, so it is loaded by path.
_spec = importlib.util.spec_from_file_location("ios_device", HERE / "ios-device.py")
ios_device = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ios_device)


def wata_bundle(device):
    """The wata app installed on `device`, asked rather than assumed.

    A `net.wa-ta.*` bundle is ours by construction (the reverse domain is the
    project's), so one match is the answer and several is a question for the
    caller — a phone holding both the hello's identity and a proper one cannot
    be guessed between.
    """
    with tempfile.TemporaryDirectory() as td:
        out = pathlib.Path(td) / "apps.json"
        # No --quiet here: `info apps` rejects it (exit 1), unlike `copy from`.
        subprocess.run(["xcrun", "devicectl", "device", "info", "apps",
                        "--device", device, "--json-output", str(out)],
                       check=True, stdout=subprocess.DEVNULL)
        apps = json.loads(out.read_text())["result"]["apps"]
    ours = sorted({a["bundleIdentifier"] for a in apps
                   if (a.get("bundleIdentifier") or "").startswith("net.wa-ta.")})
    if len(ours) == 1:
        print(f"bundle: {ours[0]} (installed on the phone)", file=sys.stderr)
        return ours[0]
    if not ours:
        raise SystemExit("no net.wa-ta.* app is installed on this phone — run "
                         "`just ios-device` first, or pass --bundle-id")
    raise SystemExit("several wata apps are installed (" + ", ".join(ours) +
                     "); pass --bundle-id or set WATA_BUNDLE_ID")


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", default=None,
                    help="device name/UDID (default: $WATA_DEVICE or the "
                         "single attached iPhone)")
    ap.add_argument("--bundle-id",
                    default=os.environ.get("WATA_BUNDLE_ID"),
                    help="app data container to read (default: "
                         "$WATA_BUNDLE_ID, else the wata app installed on the "
                         "phone)")
    ap.add_argument("--source", default="Documents/wata.log",
                    help="path inside the container (default: %(default)s)")
    ap.add_argument("--out", default=None,
                    help="save the log here instead of printing it")
    args = ap.parse_args(argv)

    device = args.device or ios_device.pick_device()
    bundle_id = args.bundle_id or wata_bundle(device)
    with tempfile.TemporaryDirectory() as td:
        dest = pathlib.Path(args.out) if args.out else pathlib.Path(td) / "wata.log"
        cmd = ["xcrun", "devicectl", "device", "copy", "from",
               "--device", device,
               "--domain-type", "appDataContainer",
               "--domain-identifier", bundle_id,
               "--source", args.source,
               "--destination", str(dest), "--quiet"]
        print("+", " ".join(cmd), file=sys.stderr)
        subprocess.run(cmd, check=True)
        if args.out:
            print(f"saved {dest}", file=sys.stderr)
        else:
            sys.stdout.write(dest.read_text(errors="replace"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
