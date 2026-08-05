#!/usr/bin/env python3
"""Build the PushToTalk hello app (plan 0026's hardware gate).

Three stages, each runnable on its own:

  archive   hellopt/ -> libhello.a + libhello.h   (GOOS=ios, -buildmode=c-archive)
  app       ios/main.m + the archive -> out/WataHello.app  (unsigned)
  sign      embed a provisioning profile and codesign        (needs the owner)

`archive` and `app` are unattended and need only Xcode — that is the leg CI-ish
verification can reach. `sign` needs a provisioning profile carrying the
restricted `com.apple.developer.push-to-talk` entitlement, which Apple grants
per team on request; without one the app cannot be installed, no matter how it
is built. README.md has the owner's steps.

    tools/bindgen/hello/build.py                 # archive + app
    tools/bindgen/hello/build.py --sign          # …and sign it
    tools/bindgen/hello/build.py --install       # …and install it on the device
"""

from __future__ import annotations

import argparse
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
APP = OUT / "WataHello.app"

TEAM_ID = os.environ.get("WATA_TEAM_ID", "YAURQZ84XZ")
BUNDLE_ID = os.environ.get("WATA_BUNDLE_ID", "com.adriaanm.watahello")
MIN_IOS = "16.0"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    print("+ " + " ".join(cmd))
    return subprocess.run(cmd, check=True, **kw)


def xcrun(*args: str) -> str:
    return subprocess.run(["xcrun", *args], capture_output=True, text=True, check=True).stdout.strip()


def archive() -> None:
    """Compile the Go side into a static archive for the device."""
    OUT.mkdir(exist_ok=True)
    sdk = xcrun("--sdk", "iphoneos", "--show-sdk-path")
    flags = f"-isysroot {sdk} -target arm64-apple-ios{MIN_IOS}"
    env = dict(
        os.environ,
        GOWORK="off",
        GOFLAGS="-mod=mod",
        GOOS="ios",
        GOARCH="arm64",
        CGO_ENABLED="1",
        CC=xcrun("--sdk", "iphoneos", "-f", "clang"),
        CGO_CFLAGS=flags,
        CGO_LDFLAGS=flags,
    )
    run(
        ["go", "build", "-buildmode=c-archive", "-o", str(OUT / "libhello.a"), "."],
        cwd=HERE / "hellopt",
        env=env,
    )


def app() -> None:
    """Compile the ObjC shell against the archive and lay out the bundle."""
    if not (OUT / "libhello.a").exists():
        archive()
    if APP.exists():
        shutil.rmtree(APP)
    APP.mkdir(parents=True)
    sdk = xcrun("--sdk", "iphoneos", "--show-sdk-path")
    run([
        xcrun("--sdk", "iphoneos", "-f", "clang"),
        "-isysroot", sdk,
        "-target", f"arm64-apple-ios{MIN_IOS}",
        "-fobjc-arc",
        "-Wall",
        "-I", str(OUT),
        str(HERE / "ios" / "main.m"),
        str(OUT / "libhello.a"),
        "-framework", "UIKit",
        "-framework", "Foundation",
        "-framework", "PushToTalk",
        "-framework", "AVFAudio",
        "-o", str(APP / "WataHello"),
    ])
    info = plistlib.loads((HERE / "ios" / "Info.plist").read_bytes())
    info["CFBundleIdentifier"] = BUNDLE_ID
    (APP / "Info.plist").write_bytes(plistlib.dumps(info))
    print(f"built {APP} (unsigned)")


def sign() -> None:
    """Embed the profile and codesign. The profile is the owner's to provide."""
    profile = Path(os.environ.get("WATA_PROFILE", HERE / "WataHello.mobileprovision"))
    if not profile.exists():
        raise SystemExit(
            f"no provisioning profile at {profile}.\n"
            "It must carry the com.apple.developer.push-to-talk entitlement — see README.md.\n"
            "Point WATA_PROFILE at it, or drop it there."
        )
    shutil.copy(profile, APP / "embedded.mobileprovision")
    ents = plistlib.loads((HERE / "ios" / "WataHello.entitlements").read_bytes())
    ents["application-identifier"] = f"{TEAM_ID}.{BUNDLE_ID}"
    ents["com.apple.developer.team-identifier"] = TEAM_ID
    resolved = OUT / "WataHello.resolved.entitlements"
    resolved.write_bytes(plistlib.dumps(ents))
    identity = os.environ.get("WATA_SIGN_IDENTITY", "Apple Development")
    run([
        "codesign", "--force", "--timestamp=none",
        "--sign", identity,
        "--entitlements", str(resolved),
        "--generate-entitlement-der",
        str(APP),
    ])
    run(["codesign", "--display", "--entitlements", "-", str(APP)])


def install() -> None:
    """Install onto the attached device (Xcode 15+ devicectl)."""
    devices = subprocess.run(
        ["xcrun", "devicectl", "list", "devices"], capture_output=True, text=True, check=True
    ).stdout
    print(devices)
    run(["xcrun", "devicectl", "device", "install", "app", "--device", os.environ.get(
        "WATA_DEVICE", "00000000-0000000000000000"), str(APP)])


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=["archive", "app", "sign", "install"])
    ap.add_argument("--sign", action="store_true", help="also codesign")
    ap.add_argument("--install", action="store_true", help="also install on the device")
    args = ap.parse_args(argv)

    if args.only:
        {"archive": archive, "app": app, "sign": sign, "install": install}[args.only]()
        return 0
    archive()
    app()
    if args.sign or args.install:
        sign()
    if args.install:
        install()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
