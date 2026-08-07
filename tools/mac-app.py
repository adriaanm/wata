#!/usr/bin/env python3
"""Assemble Wata.app from the sgo-built wata-mac binary (plan 0037, slice 1).

    just mac-app                 # build the binary, then the bundle
    tools/mac-app.py --open      # ...and launch it

No Xcode project and no actool: the bundle is a directory with a known
shape, the icon comes from an .iconset through `iconutil`, and the
signature is ad-hoc. Same approach as ~/g/utv's scripts/bundle-app.sh.

WHY A BUNDLE AT ALL — every one of these is impossible without one:

  - UNUserNotificationCenter refuses to post without a bundle identifier;
  - Keychain ACLs key to the code signature, so only a stable signed
    bundle stops macOS re-prompting after every rebuild (plan 0036);
  - the microphone needs NSMicrophoneUsageDescription in Info.plist, and
    unbundled the TCC grant is attributed to the terminal, not to Wata;
  - a parent double-clicks an icon. They do not run `just mac`.

The ad-hoc signature (`--sign -`) is stable for a given binary but
CHANGES when the binary changes, so a rebuild still re-prompts the
Keychain. That is inherent to ad-hoc signing; a Developer ID identity
fixes it and is out of scope (plan 0037).
"""

import argparse
import os
import plistlib
import shutil
import subprocess
import sys

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_NAME = "Wata"
BUNDLE_ID = "com.adriaanm.wata"
ICONSET = os.path.join(WATA, "tools", "wata.iconset")


def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit(f"mac-app: {' '.join(cmd)} failed:\n{r.stdout}{r.stderr}")
    return r.stdout


def binary_path():
    """ask tools/sgo-env.sh where the emitted binary lands, as the smokes do."""
    probe = (f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; '
             '. tools/emitdir.sh; printf "%s\\n%s\\n" "$SGO" '
             '"$(emitdir wata-mac)/$(binname wata-mac)"')
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("mac-app: sgo environment probe failed:\n" + out.stderr)
    return out.stdout.strip().split("\n")


def info_plist(version):
    return {
        "CFBundleExecutable": APP_NAME,
        "CFBundleIdentifier": BUNDLE_ID,
        "CFBundleName": APP_NAME,
        "CFBundleDisplayName": APP_NAME,
        "CFBundlePackageType": "APPL",
        "CFBundleVersion": version,
        "CFBundleShortVersionString": "0.1",
        "CFBundleIconFile": "AppIcon",
        "LSMinimumSystemVersion": "13.0",
        "LSApplicationCategoryType": "public.app-category.social-networking",
        # PTT records: without this key the first capture attempt kills the
        # app outright rather than prompting.
        "NSMicrophoneUsageDescription":
            "Wata records the voice messages you send to your family.",
        # The transport reaches the family's server directly over the local
        # network when it can, rather than through a relay.
        "NSLocalNetworkUsageDescription":
            "Wata connects to your family's server on this network.",
        # A window app, not a background agent.
        "LSUIElement": False,
    }


def build_icon(resources):
    """an .icns from the .iconset, if there is one. An app with no icon is
    still an app — a missing icon must not fail the build."""
    if not os.path.isdir(ICONSET):
        print(f"mac-app: no {os.path.relpath(ICONSET, WATA)} — building without an icon")
        return
    sh(["iconutil", "-c", "icns", ICONSET,
        "-o", os.path.join(resources, "AppIcon.icns")])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(WATA, "wata-mac", ".sgo", "Wata.app"))
    ap.add_argument("--open", action="store_true", help="launch it when built")
    ap.add_argument("--no-build", action="store_true", help="bundle what is already built")
    args = ap.parse_args()

    sgo, binary = binary_path()
    if not args.no_build:
        r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, "wata-mac"))
        if r.returncode != 0:
            sys.exit("mac-app: wata-mac build failed")
    if not os.path.exists(binary):
        sys.exit(f"mac-app: no binary at {binary} — run `just mac-build`")

    app = args.out
    contents = os.path.join(app, "Contents")
    macos, resources = os.path.join(contents, "MacOS"), os.path.join(contents, "Resources")
    shutil.rmtree(app, ignore_errors=True)
    os.makedirs(macos)
    os.makedirs(resources)

    shutil.copy2(binary, os.path.join(macos, APP_NAME))
    version = sh(["git", "-C", WATA, "rev-parse", "--short", "HEAD"]).strip() or "0"
    with open(os.path.join(contents, "Info.plist"), "wb") as f:
        plistlib.dump(info_plist(version), f)
    build_icon(resources)

    # Ad-hoc, and DEEP so the nested binary is covered too. `--force` because
    # the copied binary may carry the toolchain's own signature.
    sh(["codesign", "--force", "--deep", "--sign", "-", app])
    sh(["codesign", "--verify", "--deep", app])
    print(f"mac-app: built {app} ({BUNDLE_ID}, version {version})")

    if args.open:
        subprocess.run(["open", app])


main()
