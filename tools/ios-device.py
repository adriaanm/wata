#!/usr/bin/env python3
"""`just ios-device` — build, sign, and install wata-ios on a physical iPhone.

Plan 0061 stage 1. The emitted wata-ios module (an `sgo build` product)
compiled GOOS=ios GOARCH=arm64 against the iphoneos sysroot, bundled by hand
as WataIos.app (no Xcode project), signed with the owner's development
identity, installed over the CoreDevice tunnel with devicectl.

Stages (each runnable alone with --only):
  build     sgo build wata-ios, then go build the emitted module for iphoneos
  bundle    lay out out/WataIos.app with a device Info.plist
  sign      embed the provisioning profile, codesign with entitlements
  install   devicectl install onto the attached iPhone

The sign stage needs what only the owner can mint:
  - WATA_TEAM_ID          the Apple Developer team (portal: Membership)
  - a development profile for the bundle id (default net.wa-ta.ios) and the
    target device, at tools/ios-device/WataIos.mobileprovision or $WATA_PROFILE

The install stage targets $WATA_DEVICE, or auto-picks when exactly one
iPhone is attached (`xcrun devicectl list devices`).
"""

import argparse
import json
import os
import pathlib
import plistlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import iosenv  # noqa: E402
import simrun  # noqa: E402

REPO = HERE.parent
EMIT = REPO / "wata-ios" / ".sgo" / "wata-ios"
OUT = HERE / "ios-device" / "out"
APP = OUT / "WataIos.app"
BIN = "WataIos"
BUNDLE_ID = os.environ.get("WATA_BUNDLE_ID", "net.wa-ta.ios")
TEAM_ID = os.environ.get("WATA_TEAM_ID", "")


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def build():
    run([REPO / "tools" / "sgo", "build"], cwd=REPO / "wata-ios")
    OUT.mkdir(parents=True, exist_ok=True)
    env = iosenv.go_env("iphoneos")
    run(["go", "build", "-o", OUT / BIN, "."], cwd=EMIT, env=env)


def bundle():
    """Same hand-rolled .app as simrun.bundle, with the device platform
    strings, unsigned (sign is its own stage)."""
    shutil.rmtree(APP, ignore_errors=True)
    APP.mkdir(parents=True)
    shutil.copy2(OUT / BIN, APP / BIN)
    info = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleDisplayName": "Wata",
        "CFBundleExecutable": BIN,
        "CFBundleIdentifier": BUNDLE_ID,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": BIN,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleVersion": "1",
        "CFBundleSupportedPlatforms": ["iPhoneOS"],
        "DTPlatformName": "iphoneos",
        "LSRequiresIPhoneOS": True,
        "MinimumOSVersion": simrun.MIN_IOS,
        # For plan 0061 stage 4 (real audio); harmless while the stub runs.
        "NSMicrophoneUsageDescription":
            "Wata records your voice while you hold the talk button.",
        # the wata:// scheme — the enroll page's configure link bounces back
        # into the app through it (plan 0062 stage 3).
        "CFBundleURLTypes": [
            {"CFBundleURLName": BUNDLE_ID, "CFBundleURLSchemes": ["wata"]},
        ],
        "UIDeviceFamily": [1],
        "UILaunchScreen": {},
        "UIRequiredDeviceCapabilities": ["arm64"],
        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
    }
    (APP / "Info.plist").write_bytes(plistlib.dumps(info))
    print(f"bundled {APP} (unsigned)")


def sign():
    if not TEAM_ID:
        raise SystemExit(
            "WATA_TEAM_ID is not set; the entitlements stamp the team id.\n"
            "Find yours on developer.apple.com/account (Membership)."
        )
    profile = pathlib.Path(os.environ.get(
        "WATA_PROFILE", HERE / "ios-device" / "WataIos.mobileprovision"))
    if not profile.exists():
        raise SystemExit(
            f"no provisioning profile at {profile}.\n"
            f"Portal: an iOS App Development profile for {BUNDLE_ID} and the\n"
            "target device; drop it there or point WATA_PROFILE at it."
        )
    shutil.copy(profile, APP / "embedded.mobileprovision")
    # Minimal claims: what the app uses today. The PTT + aps-environment
    # entitlements join in plan 0061 stage 4 with the real audio.
    ents = {
        "application-identifier": f"{TEAM_ID}.{BUNDLE_ID}",
        "com.apple.developer.team-identifier": TEAM_ID,
        "get-task-allow": True,
    }
    resolved = OUT / "WataIos.resolved.entitlements"
    resolved.write_bytes(plistlib.dumps(ents))
    identity = os.environ.get("WATA_SIGN_IDENTITY", "Apple Development")
    run(["codesign", "--force", "--timestamp=none", "--sign", identity,
         "--entitlements", resolved, "--generate-entitlement-der", APP])
    run(["codesign", "--display", "--entitlements", "-", APP])


def pick_device():
    dev = os.environ.get("WATA_DEVICE")
    if dev:
        return dev
    with tempfile.NamedTemporaryFile(suffix=".json") as tf:
        subprocess.run(["xcrun", "devicectl", "list", "devices",
                        "--json-output", tf.name],
                       check=True, capture_output=True)
        devices = json.load(open(tf.name))["result"]["devices"]
    phones = [d for d in devices
              if d.get("hardwareProperties", {}).get("deviceType") == "iPhone"]
    if len(phones) != 1:
        names = [d.get("deviceProperties", {}).get("name") for d in phones]
        raise SystemExit(
            f"expected exactly one iPhone, saw {names or 'none'} — set WATA_DEVICE")
    d = phones[0]
    print(f"device: {d['deviceProperties']['name']} ({d['identifier']})")
    return d["identifier"]


def install():
    run(["xcrun", "devicectl", "device", "install", "app",
         "--device", pick_device(), APP])


STAGES = {"build": build, "bundle": bundle, "sign": sign, "install": install}


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=list(STAGES))
    args = ap.parse_args(argv)
    if args.only:
        STAGES[args.only]()
        return 0
    for stage in STAGES.values():
        stage()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
