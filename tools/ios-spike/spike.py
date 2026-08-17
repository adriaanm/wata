#!/usr/bin/env python3
"""The iOS architecture spike (IOS-CLIENT-ASSEMBLY), end to end.

Answers one question: can the iOS client be wata-mac's shape — ONE pure-Go
binary driving UIKit through purego/objc — rather than phone-spike's shape
(gomobile bind + a Swift shell that owns the UI)? This script is the
reproduction: it builds a Go binary with no Swift and no ObjC source, wraps
it in a `.app` by hand (no Xcode project, no gomobile), runs it in the iOS
simulator, and asserts on what the Go side prints from inside UIKit
callbacks plus the pixels UIKit actually rendered.

Stages (each runnable alone with --only):

  build   `go build` for GOOS=ios/arm64 against the iphonesimulator SDK —
          the same env gomobile uses, without gomobile.
  bundle  hand-rolled `.app`: Info.plist + the binary + `codesign -s -`.
  run     reuse-or-create the shared simulator device (tools/simrun.py —
          always in the custom device set), install, launch with a console
          pty, assert the spike lines, screenshot, and check the rendered
          colour.

The simulator driving lives in tools/simrun.py, shared with the ios-hello
harness; see its docstring for the custom-device-set rule (WATA_SIM_DEVSET).
"""

import argparse
import pathlib
import re
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import iosenv  # noqa: E402  (tools/iosenv.py — the shared GOOS=ios build env)
import simrun  # noqa: E402  (tools/simrun.py — the shared simulator driver)

OUT = HERE / "out"
APP = OUT / "WataIosSpike.app"
BUNDLE_ID = "dev.wata.iosspike"
BIN = "WataIosSpike"
MIN_IOS = "17.0"

# What the Go side must print. Each line is a proof:
#   dlopen        — purego.Dlopen of a system framework inside an iOS process
#   getclass      — the ObjC runtime sees the framework's classes afterwards
#   registerclass — class synthesis works (objc_allocateClassPair et al)
#   didFinish...  — UIApplicationMain ran and UIKit called into Go
#   layoutSubviews— UIKit drives a synthesized UIView subclass
#   nstimer       — the main runloop calls back into Go
#   control-action— UIKit target-action dispatch reaches a Go method
EXPECT = [
    r"spike: dlopen ok /System/Library/Frameworks/UIKit\.framework/UIKit handle=0x[0-9a-f]+",
    r"spike: getclass ok UIApplication UIWindow=true UILabel=true",
    r"spike: registerclass ok WataAppDelegate=0x[0-9a-f]+",
    r"spike: calling UIApplicationMain",
    r"spike: callback didFinishLaunching app=<UIApplication",
    r"spike: window visible",
    r"spike: callback layoutSubviews on WataSpikeView",
    r"spike: callback nstimer on the main runloop",
    r"spike: callback control-action from <UIButton",
    r"spike: all checks passed",
]

STAGES = ["build", "bundle", "run"]


run = simrun.run


def go_ios_build(sdk, out):
    """The GOOS=ios cross-build, one slice. The env (tools/iosenv.py) is
    exactly what gomobile's appleEnv sets (x/mobile/cmd/gomobile/env.go) for
    the matching target, minus gomobile: `gomobile build -target=ios` itself
    just runs `go build` and hands the resulting executable to an Xcode
    project for packaging."""
    env = iosenv.go_env(sdk, MIN_IOS)
    t0 = time.time()
    run(["go", "build", "-tags", "ios", "-ldflags=-w", "-o", str(out), "."],
        cwd=HERE / "app", env=env)
    plat = subprocess.run(["vtool", "-show-build", str(out)],
                          capture_output=True, text=True, check=True).stdout
    kind = re.search(r"platform (\S+)", plat).group(1)
    print(f"spike: built {out.name} {out.stat().st_size} bytes, "
          f"platform {kind}, {time.time()-t0:.1f}s")
    return kind


# ---- stages -------------------------------------------------------------------

def stage_build():
    OUT.mkdir(exist_ok=True)
    if go_ios_build("iphonesimulator", OUT / BIN) != "IOSSIMULATOR":
        sys.exit("spike: binary is not an iOS-simulator Mach-O")
    # The device slice is COMPILED ONLY: no device and no signing identity are
    # available here, so nothing has executed it. It is built anyway because a
    # failure to even compile would be a real finding about (A) on hardware.
    if go_ios_build("iphoneos", OUT / (BIN + "-device")) != "IOS":
        sys.exit("spike: device binary is not an iOS Mach-O")
    print("spike: NOTE the device slice is compiled, never executed "
          "(no hardware, no signing identity)")


def stage_bundle():
    if not (OUT / BIN).exists():
        sys.exit("spike: no binary — run the build stage")
    simrun.bundle(APP, OUT / BIN, BUNDLE_ID, MIN_IOS)
    print(f"spike: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("spike: no .app — run the bundle stage")
    sc = simrun.simctl()
    udid = simrun.ensure_device(sc)
    shot = OUT / "screen.png"
    try:
        lines, elapsed, missing = simrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, timeout=120, screenshot=shot)
        w, h, rgb = simrun.png_pixel(shot, 0.5, 0.5)
        print(f"spike: screenshot {w}x{h}, centre pixel rgb{rgb}")
        blue = rgb[2] > 200 and rgb[0] < 60 and rgb[1] < 60
        print(f"spike: launch-to-all-checks {elapsed:.2f}s")
        if missing or not blue:
            for m in missing:
                print("spike: MISSING " + m, file=sys.stderr)
            if not blue:
                print(f"spike: centre pixel {rgb} is not the blue Go painted", file=sys.stderr)
            sys.exit(1)
        print("spike: PASS — a pure-Go binary drove UIKit inside the iOS simulator")
    finally:
        simrun.shutdown(sc, udid)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=STAGES, action="append",
                    help="run just these stages (default: all, in order)")
    args = ap.parse_args()
    for s in (args.only or STAGES):
        globals()["stage_" + s]()


if __name__ == "__main__":
    main()
