#!/usr/bin/env python3
"""`just ios-log` — pull wata-ios's on-device log and print it.

Plan 0064: the app tees its stdout+stderr into Documents/wata.log inside
its own sandbox (truncated at each launch), so a normal icon-tap run's
output is readable afterwards without a tethered console. This fetches it
over the CoreDevice tunnel:

  xcrun devicectl device copy from --domain-type appDataContainer \
      --domain-identifier <bundle-id> --source Documents/wata.log ...

Device: --device, else $WATA_DEVICE, else the single attached iPhone
(ios-device.py's pick). Bundle id: --bundle-id, else $WATA_BUNDLE_ID,
else net.wa-ta.ios (ios-device.py's convention — set WATA_BUNDLE_ID to
whatever the installed bundle actually uses).
"""

import argparse
import importlib.util
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


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", default=None,
                    help="device name/UDID (default: $WATA_DEVICE or the "
                         "single attached iPhone)")
    ap.add_argument("--bundle-id",
                    default=os.environ.get("WATA_BUNDLE_ID", "net.wa-ta.ios"),
                    help="app data container to read (default: "
                         "$WATA_BUNDLE_ID or net.wa-ta.ios)")
    ap.add_argument("--source", default="Documents/wata.log",
                    help="path inside the container (default: %(default)s)")
    ap.add_argument("--out", default=None,
                    help="save the log here instead of printing it")
    args = ap.parse_args(argv)

    device = args.device or ios_device.pick_device()
    with tempfile.TemporaryDirectory() as td:
        dest = pathlib.Path(args.out) if args.out else pathlib.Path(td) / "wata.log"
        cmd = ["xcrun", "devicectl", "device", "copy", "from",
               "--device", device,
               "--domain-type", "appDataContainer",
               "--domain-identifier", args.bundle_id,
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
