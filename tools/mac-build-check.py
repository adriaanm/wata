#!/usr/bin/env python3
"""Compile wata-mac, so `ci` notices when a shared source stops building here.

wata-mac's screens ARE wata-fb's screens: applets, display, paint, netstatus,
input, syscall and audiothread ride in as symlinks (docs/design/wata-mac.md).
So an edit to one of those files is an edit to two apps, and the second one is
invisible from the fb side — `stubs.scala` exists to fail loudly when the
shared code reaches for a device object the mac has no hardware for, but only
if something compiles wata-mac.

`ci` could not, until this: the app's godeps are darwin-only (AppKit,
AudioToolbox, the Keychain), so a Linux ci cannot build it at all. This script
makes that a SKIP with a reason rather than an omission, and a real compile
wherever the platform allows one. It builds only — the windowed and headless
behaviour stay with `just mac-smoke`, which needs a live server.
"""

import os
import platform
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main() -> int:
    if platform.system() != "Darwin":
        print(f"mac-build-check: SKIP — wata-mac is darwin-only "
              f"(this is {platform.system()}); the shared-source tripwire "
              f"needs a macOS run")
        return 0

    r = subprocess.run(["../tools/sgo", "build"],
                       cwd=os.path.join(ROOT, "wata-mac"),
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.stdout.write(r.stdout)
        sys.stderr.write(r.stderr)
        print("\nmac-build-check: FAIL — wata-mac does not compile.\n"
              "If you changed a file under wata-fb/src, check whether it is one\n"
              "of the symlinked shared sources: the mac compiles those too, and\n"
              "wata-mac/src/main/scala/stubs.scala is where a device-only object\n"
              "it reaches for gets its off-device stand-in.")
        return 1

    print("mac-build-check: PASS — wata-mac compiles (shared sources in step)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
