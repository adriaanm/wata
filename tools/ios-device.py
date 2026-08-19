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

The sign stage needs a development profile for the target device, which only
the owner can mint. It is looked for at $WATA_PROFILE, then
tools/ios-device/WataIos.mobileprovision, then the PTT hello's
tools/bindgen/hello/WataHello.mobileprovision — the app rides that identity
today (IOS-ON-DEVICE), and it is the only profile in the tree granting
push-to-talk. Everything else is READ OFF the profile rather than configured:
the team id and the bundle id both come from it, so the Info.plist, the
entitlements and the signature cannot disagree. $WATA_TEAM_ID and
$WATA_BUNDLE_ID still override, for a profile this script cannot decode.

The install stage targets $WATA_DEVICE, or auto-picks when exactly one
iPhone is attached (`xcrun devicectl list devices`).

ENTITLEMENTS ARE READ OFF THE PROFILE, NEVER ASSERTED (plan 0065). Claiming
an entitlement the profile does not grant makes codesign fail with a message
that names neither the capability nor the portal, so this script decodes the
embedded profile and claims only what it finds: `aps-environment` (tier 2's
push token) and `com.apple.developer.push-to-talk` with the `push-to-talk`
and `audio` background modes (tier 3's channel). What is missing is REPORTED,
with the portal step that would grant it, and the build goes on — an app
without those entitlements still runs, and says so in its own log
(`push: remote registration failed`, `ptt: no channel manager: …`). Set
WATA_IOS_REQUIRE_PTT=1 to turn the report into a refusal instead.
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

PTT_ENT = "com.apple.developer.push-to-talk"
APS_ENT = "aps-environment"


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def profile_path():
    """$WATA_PROFILE, else the wata-ios profile, else the PTT hello's.

    The fallback is not a shortcut: net.wa-ta.ios has never been minted, so the
    app on the owner's phone rides net.wa-ta.hello's identity (IOS-ON-DEVICE),
    and the hello profile is the only one in the tree that grants
    push-to-talk. Falling back makes `just ios-device` work with no environment
    at all until a proper profile exists — before this, the same install took
    three env vars nobody could guess and failed three different ways
    (unset team id, a bundle id disagreeing with the profile, an Info.plist
    bundled before the profile was visible).
    """
    if p := os.environ.get("WATA_PROFILE"):
        return pathlib.Path(p)
    own = HERE / "ios-device" / "WataIos.mobileprovision"
    if own.exists():
        return own
    hello = HERE / "bindgen" / "hello" / "WataHello.mobileprovision"
    if hello.exists():
        return hello
    return own  # the sign stage reports the absence with the portal steps


def profile_plist():
    """The whole decoded profile, or {}. `security cms -D` unwraps the CMS."""
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
    """The team the profile was issued to. Read rather than configured: it is
    stamped into the entitlements, and a WATA_TEAM_ID disagreeing with the
    profile is a codesign failure whose message names neither."""
    ids = profile_plist().get("TeamIdentifier") or []
    return ids[0] if ids else ""


def profile_bundle_id():
    """The bundle id the profile is FOR (`<team>.<bundle id>`, minus the team).

    Taking it from the profile is what keeps the Info.plist and the signature
    talking about the same app: a bundle id that disagrees with the profile
    installs as an unsigned app or not at all.
    """
    app_id = profile_plist().get("Entitlements", {}).get("application-identifier", "")
    team = profile_team()
    prefix = team + "."
    return app_id[len(prefix):] if team and app_id.startswith(prefix) else ""


def bundle_id():
    """$WATA_BUNDLE_ID, else the id the profile is for, else the convention.

    Read off the profile by default so the Info.plist and the signature cannot
    disagree — the app currently rides net.wa-ta.hello (IOS-ON-DEVICE) while
    net.wa-ta.ios is where it is going.
    """
    return (os.environ.get("WATA_BUNDLE_ID") or profile_bundle_id()
            or "net.wa-ta.ios")


def team_id():
    """$WATA_TEAM_ID, else the team the profile was issued to."""
    return os.environ.get("WATA_TEAM_ID") or profile_team()


def profile_grants():
    """The entitlements the provisioning profile GRANTS, or {} when there is
    no profile (or it cannot be decoded — the sign stage reports that itself).
    """
    return profile_plist().get("Entitlements", {})


def ptt_granted(grants):
    """Does the profile carry BOTH entitlements the PushToTalk channel needs?

    `aps-environment` is not optional for PTT: without it PTChannelManager
    refuses to instantiate at all (`missingPushServerEnvironment`, observed on
    hardware — tools/bindgen/hello/README.md).
    """
    return bool(grants.get(PTT_ENT)) and bool(grants.get(APS_ENT))


def report_missing(grants):
    """Say exactly what the profile lacks and what would grant it. Returns the
    list of missing entitlements."""
    missing = [e for e in (APS_ENT, PTT_ENT) if not grants.get(e)]
    if not missing:
        return missing
    print(
        f"\nnote: {profile_path().name} does not grant: {', '.join(missing)}\n"
        f"      The app builds, installs and runs without them; what does NOT\n"
        f"      work is push ({APS_ENT}) and the PushToTalk channel\n"
        f"      ({PTT_ENT} — and PTT needs both).\n"
        f"      To grant them: developer.apple.com/account → Identifiers →\n"
        f"      {bundle_id()} → enable Push Notifications and Push to Talk, then\n"
        f"      regenerate the development profile and download it over\n"
        f"      {profile_path()}.\n"
        f"      Set WATA_IOS_REQUIRE_PTT=1 to make this a hard failure.\n")
    if os.environ.get("WATA_IOS_REQUIRE_PTT") == "1":
        raise SystemExit("WATA_IOS_REQUIRE_PTT=1 and the profile is missing "
                         + ", ".join(missing))
    return missing


def build():
    run([REPO / "tools" / "sgo", "build"], cwd=REPO / "wata-ios")
    OUT.mkdir(parents=True, exist_ok=True)
    # the DEVICE irohnet archive (plan 0062: the phone's transport is iroh);
    # -a because Go's build cache is blind to the .a swap activate just did
    irohnet = REPO / "go-pkgs" / "irohnet"
    run([sys.executable, irohnet / "mklib.py", "activate", "ios"], cwd=irohnet)
    env = iosenv.go_env("iphoneos")
    run(["go", "build", "-a", "-tags", "iroh", "-o", OUT / BIN, "."],
        cwd=EMIT, env=env)


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
        "CFBundleIdentifier": bundle_id(),
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
            {"CFBundleURLName": bundle_id(), "CFBundleURLSchemes": ["wata"]},
        ],
        "UIDeviceFamily": [1],
        "UILaunchScreen": {},
        "UIRequiredDeviceCapabilities": ["arm64"],
        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
    }
    # Background modes ride the ENTITLEMENT, not a wish: `push-to-talk` is
    # meaningless (and, declared alone, misleading in the bundle) without
    # com.apple.developer.push-to-talk, and `audio` is what keeps the capture
    # graph alive across the transmission the framework starts. Declared only
    # when the profile grants the capability, so a build for a profile without
    # it is byte-for-byte the bundle this script has always produced.
    if ptt_granted(profile_grants()):
        info["UIBackgroundModes"] = ["audio", "push-to-talk"]
        print("Info.plist: UIBackgroundModes = audio, push-to-talk")
    (APP / "Info.plist").write_bytes(plistlib.dumps(info))
    print(f"bundled {APP} (unsigned)")


def sign():
    if not team_id():
        raise SystemExit(
            "WATA_TEAM_ID is not set; the entitlements stamp the team id.\n"
            "Find yours on developer.apple.com/account (Membership)."
        )
    profile = profile_path()
    if not profile.exists():
        raise SystemExit(
            f"no provisioning profile at {profile}.\n"
            f"Portal: an iOS App Development profile for {bundle_id()} and the\n"
            "target device; drop it there or point WATA_PROFILE at it."
        )
    shutil.copy(profile, APP / "embedded.mobileprovision")
    # What the app uses, INTERSECTED with what the profile grants: an
    # entitlement claimed beyond the profile is a codesign failure that names
    # nothing useful, so the two push entitlements are added only when they
    # are there and their absence is reported with the portal step.
    ents = {
        "application-identifier": f"{team_id()}.{bundle_id()}",
        "com.apple.developer.team-identifier": team_id(),
        "get-task-allow": True,
    }
    grants = profile_grants()
    if grants.get(APS_ENT):
        # the value is the profile's ("development" for a dev profile); it
        # decides which APNs host mints this install's token.
        ents[APS_ENT] = grants[APS_ENT]
        print(f"entitlement: {APS_ENT} = {grants[APS_ENT]}")
    if grants.get(PTT_ENT):
        ents[PTT_ENT] = True
        print(f"entitlement: {PTT_ENT}")
    report_missing(grants)
    if ptt_granted(grants) and "push-to-talk" not in plistlib.loads(
            (APP / "Info.plist").read_bytes()).get("UIBackgroundModes", []):
        # bundle ran before the profile gained the capability: the app would
        # sign with the entitlement and still be unable to run in the
        # background. Say so rather than shipping the mismatch silently.
        raise SystemExit(
            "the profile grants push-to-talk but the bundled Info.plist has no\n"
            "push-to-talk background mode — re-run the bundle stage\n"
            "(tools/ios-device.py --only bundle) and sign again.")
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
    # REPEATABLE: `--only build --only bundle` used to keep only the LAST
    # one and run just that, so a build stage silently did not happen and the
    # bundle wrapped whatever binary was already on disk — a stale app that
    # installs and runs and is not the code you just wrote.
    ap.add_argument("--only", choices=list(STAGES), action="append",
                    help="run just these stages (default: all, in order)")
    args = ap.parse_args(argv)
    for name in (args.only or list(STAGES)):
        STAGES[name]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
