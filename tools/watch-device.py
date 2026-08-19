#!/usr/bin/env python3
"""`just watch-device` — build, sign, and install wata-watch on a real Apple Watch.

Plan 0069's hardware leg, and tools/ios-device.py's twin. The emitted
wata-watch module (an `sgo build` product) compiled GOOS=ios GOARCH=arm64
against the WATCHOS sysroot, bundled by hand as WataWatch.app (no Xcode
project, no Swift, no storyboard), signed with the owner's development
identity, installed over the CoreDevice tunnel with devicectl.

Stages (each runnable alone with --only):
  build     sgo build wata-watch, then go build the emitted module for watchos
  bundle    lay out out/WataWatch.app with a device Info.plist
  sign      embed the provisioning profile, codesign with entitlements
  install   devicectl install onto the paired Apple Watch

WHY arm64 AND NOT arm64_32: Go has no watchOS target and no 32-bit ARM
Darwin target. watchOS 26 is the first release running full arm64 (Series
9/10/Ultra 2 and later), so the floor is not a compatibility choice — it is
the first version Go can reach at all. Everything older is arm64_32 and out
of reach forever. See tools/iosenv.py's MIN_WATCHOS.

THE PROFILE IS THE PART ONLY THE OWNER CAN DO, and the iOS profile does not
work: profiles carry a Platform list, and the tree's WataHello.mobileprovision
is ['iOS', 'xrOS', 'visionOS'] — no watchOS. The sign stage checks this and
prints the portal steps rather than failing on a codesign message that names
neither the platform nor the profile. Set WATA_WATCH_PROFILE to point at one.

THE WATCH MUST ALSO BE SET UP FOR DEVELOPMENT, which is likewise the owner's:
Developer Mode on the watch (Settings -> Privacy & Security -> Developer
Mode), then paired to Xcode. `xcrun devicectl list devices` is the check —
the watch appears there or this script has nothing to install onto.

THE MICROPHONE PROMPT IS EXPECTED, NOT A BUG. On the first press the system
asks for permission, showing the NSMicrophoneUsageDescription below; the app
blocks until it is answered. Without that key the process does not prompt, it
SIGABRTs, with a crash report that names neither the key nor audio (plan
0069's stage 3 section).
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
import watchrun  # noqa: E402

REPO = HERE.parent
EMIT = REPO / "wata-watch" / ".sgo" / "wata-watch"
OUT = HERE / "watch-device" / "out"
APP = OUT / "WataWatch.app"
BIN = "WataWatch"
DELEGATE = "WataWatchDelegate"  # go-pkgs/watchshell's synthesized delegate


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def profile_path():
    """$WATA_WATCH_PROFILE, else the watch profile's conventional home.

    Deliberately NOT falling back to the iOS profiles the way ios-device.py
    does: a profile without watchOS in its Platform list cannot sign this app,
    so falling back would only trade a clear message for a confusing one.
    """
    if p := os.environ.get("WATA_WATCH_PROFILE"):
        return pathlib.Path(p)
    return HERE / "watch-device" / "WataWatch.mobileprovision"


def profile_plist():
    p = profile_path()
    if not p.exists():
        return {}
    r = subprocess.run(["security", "cms", "-D", "-i", str(p)],
                       capture_output=True)
    if r.returncode != 0 or not r.stdout:
        return {}
    try:
        return plistlib.loads(r.stdout)
    except Exception:
        return {}


def profile_team():
    ids = profile_plist().get("TeamIdentifier") or []
    return ids[0] if ids else ""


def profile_bundle_id():
    app_id = profile_plist().get("Entitlements", {}).get(
        "application-identifier", "")
    team = profile_team()
    prefix = team + "."
    return app_id[len(prefix):] if team and app_id.startswith(prefix) else ""


def bundle_id():
    return (os.environ.get("WATA_WATCH_BUNDLE_ID") or profile_bundle_id()
            or "net.wa-ta.watch")


def team_id():
    return os.environ.get("WATA_TEAM_ID") or profile_team()


def portal_steps(why):
    """The exact thing to do at developer.apple.com. Printed instead of a
    codesign error, because none of codesign's messages name the platform."""
    return (
        f"{why}\n\n"
        f"  What to mint (developer.apple.com/account):\n"
        f"    1. Identifiers -> new App ID for {bundle_id()}.\n"
        f"    2. Devices -> add the watch (Xcode registers it once the watch\n"
        f"       is paired and in Developer Mode).\n"
        f"    3. Profiles -> new *watchOS App Development* profile for that\n"
        f"       App ID and that watch. The platform matters: an iOS profile\n"
        f"       cannot sign a watch app, whatever its bundle id.\n"
        f"    4. Download it to {profile_path()}\n"
        f"       (or point WATA_WATCH_PROFILE at it).\n")


def build():
    run([REPO / "tools" / "sgo", "build"], cwd=REPO / "wata-watch")
    OUT.mkdir(parents=True, exist_ok=True)
    env = iosenv.go_env("watchos")
    run(["go", "build", "-ldflags=-w", "-o", OUT / BIN, "."], cwd=EMIT, env=env)
    # Say what platform actually got stamped: the whole watch port rests on
    # clang stamping WATCHOS while the Go toolchain thinks it is building for
    # iOS, and that is worth re-checking on every build rather than trusting.
    run(["vtool", "-show-build", OUT / BIN])


def bundle():
    """The same hand-rolled .app watchrun.bundle makes for the simulator, with
    the device platform strings, unsigned (sign is its own stage)."""
    shutil.rmtree(APP, ignore_errors=True)
    APP.mkdir(parents=True)
    shutil.copy2(OUT / BIN, APP / BIN)
    info = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleDisplayName": "Wata",
        "CFBundleExecutable": BIN,
        "CFBundleIdentifier": bundle_id(),
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": BIN,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleVersion": "1",
        "CFBundleSupportedPlatforms": ["WatchOS"],
        "DTPlatformName": "watchos",
        "MinimumOSVersion": watchrun.MIN_WATCHOS,
        # 4 is the watch; the two WK keys make this a watchOS 7+ single-target
        # app that runs with no paired iPhone app at all.
        "UIDeviceFamily": [4],
        "WKApplication": True,
        "WKWatchOnly": True,
        # WatchKit reads the delegate under BOTH keys depending on version;
        # watchrun.bundle sets both for the same reason.
        "WKExtensionDelegateClassName": DELEGATE,
        "WKApplicationDelegateClassName": DELEGATE,
        # Required. Without it the first mic use is a SIGABRT, not a prompt.
        "NSMicrophoneUsageDescription":
            "wata records voice messages to send to your family.",
    }
    (APP / "Info.plist").write_bytes(plistlib.dumps(info))
    print(f"bundled {APP} (unsigned)")


def sign():
    profile = profile_path()
    if not profile.exists():
        raise SystemExit(portal_steps(
            f"no provisioning profile at {profile}."))
    plat = profile_plist().get("Platform") or []
    if "watchOS" not in plat:
        raise SystemExit(portal_steps(
            f"{profile.name} is for {plat or 'an unknown platform'}, "
            f"not watchOS.\n  A profile's Platform list is checked at install "
            f"time, so this would\n  fail on the watch even though codesign "
            f"succeeds here."))
    if not team_id():
        raise SystemExit(
            "no team id: set WATA_TEAM_ID, or use a profile this script can "
            "decode.\nFind yours on developer.apple.com/account (Membership).")
    shutil.copy(profile, APP / "embedded.mobileprovision")
    # Claim only what the profile grants — an entitlement beyond it is a
    # codesign failure whose message names nothing useful. The watch app needs
    # none of the phone's push/PTT entitlements: it is a plain app.
    ents = {
        "application-identifier": f"{team_id()}.{bundle_id()}",
        "com.apple.developer.team-identifier": team_id(),
        "get-task-allow": True,
    }
    resolved = OUT / "WataWatch.resolved.entitlements"
    resolved.write_bytes(plistlib.dumps(ents))
    identity = os.environ.get("WATA_SIGN_IDENTITY", "Apple Development")
    run(["codesign", "--force", "--timestamp=none", "--sign", identity,
         "--entitlements", resolved, "--generate-entitlement-der", APP])
    run(["codesign", "--display", "--entitlements", "-", APP])


def pick_device():
    dev = os.environ.get("WATA_WATCH_DEVICE")
    if dev:
        return dev
    with tempfile.NamedTemporaryFile(suffix=".json") as tf:
        subprocess.run(["xcrun", "devicectl", "list", "devices",
                        "--json-output", tf.name],
                       check=True, capture_output=True)
        devices = json.load(open(tf.name))["result"]["devices"]
    watches = [d for d in devices
               if d.get("hardwareProperties", {}).get("deviceType")
               == "appleWatch"]
    if len(watches) != 1:
        names = [d.get("deviceProperties", {}).get("name") for d in watches]
        raise SystemExit(
            f"expected exactly one Apple Watch, saw {names or 'none'}.\n"
            "  If none: the watch needs Developer Mode (Settings -> Privacy &\n"
            "  Security -> Developer Mode, then restart) and to be paired to\n"
            "  Xcode. It shows up in `xcrun devicectl list devices` when it is\n"
            "  ready. Otherwise set WATA_WATCH_DEVICE to the identifier.")
    d = watches[0]
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
    # REPEATABLE, unlike ios-device.py's single --only: `--only build --only
    # bundle` there silently keeps the last one and runs only that, which
    # looks like a successful build of a binary that was never rebuilt.
    ap.add_argument("--only", choices=list(STAGES), action="append",
                    help="run just these stages (default: all, in order)")
    args = ap.parse_args(argv)
    for name in (args.only or list(STAGES)):
        STAGES[name]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
