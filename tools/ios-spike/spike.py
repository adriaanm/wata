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
  run     create/boot a simulator device in a CUSTOM DEVICE SET, install,
          launch with a console pty, assert the spike lines, screenshot,
          and check the rendered colour.

The device set must live OUTSIDE ~/Library/Developer: with that path
symlinked to an external volume, CoreSimulatorService takes an unpromptable
TCC EPERM. Override with WATA_SIM_DEVSET.
"""

import argparse
import json
import os
import pathlib
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import zlib

HERE = pathlib.Path(__file__).resolve().parent
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


def run(cmd, **kw):
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run(cmd, check=True, **kw)


def sdk_paths(sdk):
    def x(*a):
        return subprocess.run(["xcrun", "--sdk", sdk, *a],
                              capture_output=True, text=True, check=True).stdout.strip()
    return x("--show-sdk-path"), x("--find", "clang")


def go_ios_build(sdk, minflag, out):
    """The GOOS=ios cross-build, one slice. This is exactly the env gomobile's
    appleEnv sets (x/mobile/cmd/gomobile/env.go) for the matching target,
    minus gomobile: `gomobile build -target=ios` itself just runs `go build`
    and hands the resulting executable to an Xcode project for packaging."""
    root, clang = sdk_paths(sdk)
    flags = f"-isysroot {root} {minflag}={MIN_IOS} -arch arm64"
    env = dict(os.environ,
               GOWORK="off", GOOS="ios", GOARCH="arm64", CGO_ENABLED="1",
               CC=clang, CXX=clang + "++",
               CGO_CFLAGS=flags, CGO_CXXFLAGS=flags, CGO_LDFLAGS=flags)
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
    if go_ios_build("iphonesimulator", "-mios-simulator-version-min",
                    OUT / BIN) != "IOSSIMULATOR":
        sys.exit("spike: binary is not an iOS-simulator Mach-O")
    # The device slice is COMPILED ONLY: no device and no signing identity are
    # available here, so nothing has executed it. It is built anyway because a
    # failure to even compile would be a real finding about (A) on hardware.
    if go_ios_build("iphoneos", "-miphoneos-version-min",
                    OUT / (BIN + "-device")) != "IOS":
        sys.exit("spike: device binary is not an iOS Mach-O")
    print("spike: NOTE the device slice is compiled, never executed "
          "(no hardware, no signing identity)")


def stage_bundle():
    """A `.app` by hand: no Xcode project, no gomobile, no team ID. The
    simulator needs a bundle, an Info.plist and *a* signature; ad-hoc
    (`codesign -s -`) is a signature."""
    if not (OUT / BIN).exists():
        sys.exit("spike: no binary — run the build stage")
    shutil.rmtree(APP, ignore_errors=True)
    APP.mkdir(parents=True)
    shutil.copy2(OUT / BIN, APP / BIN)
    info = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleExecutable": BIN,
        "CFBundleIdentifier": BUNDLE_ID,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": BIN,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleVersion": "1",
        "CFBundleSupportedPlatforms": ["iPhoneSimulator"],
        "DTPlatformName": "iphonesimulator",
        "LSRequiresIPhoneOS": True,
        "MinimumOSVersion": MIN_IOS,
        "UIDeviceFamily": [1],
        "UILaunchScreen": {},
        "UIRequiredDeviceCapabilities": ["arm64"],
        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
    }
    with open(APP / "Info.plist", "wb") as f:
        plistlib.dump(info, f)
    run(["codesign", "--force", "--sign", "-", "--timestamp=none", str(APP)])
    run(["codesign", "-dv", str(APP)])
    print(f"spike: bundled {APP}")


def simctl(devset):
    return ["xcrun", "simctl", "--set", devset]


def png_pixel(path, fx, fy):
    """Decode a PNG far enough to read one pixel at fractional (fx, fy).
    Stdlib only — no Pillow in this repo's toolchain."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, hdr = 8, b"", None
    while pos < len(data):
        ln, typ = struct.unpack(">I", data[pos:pos + 4])[0], data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            hdr = struct.unpack(">IIBBBBB", body)
        elif typ == b"IDAT":
            idat += body
        pos += 12 + ln
    w, h, depth, color, _, _, interlace = hdr
    if depth != 8 or interlace != 0 or color not in (2, 6):
        raise ValueError(f"unsupported PNG: depth={depth} color={color} interlace={interlace}")
    nch = 3 if color == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * nch
    prev = bytearray(stride)
    y = int(h * fy)
    for row in range(y + 1):
        ft = raw[row * (stride + 1)]
        line = bytearray(raw[row * (stride + 1) + 1: (row + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - nch] if i >= nch else 0
            b = prev[i]
            c = prev[i - nch] if i >= nch else 0
            if ft == 1:
                line[i] = (line[i] + a) & 0xFF
            elif ft == 2:
                line[i] = (line[i] + b) & 0xFF
            elif ft == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        prev = line
    x = int(w * fx) * nch
    return (w, h, tuple(prev[x:x + 3]))


def stage_run():
    if not APP.exists():
        sys.exit("spike: no .app — run the bundle stage")
    devset = os.environ.get("WATA_SIM_DEVSET", os.path.expanduser("~/.wata-simdevices"))
    pathlib.Path(devset).mkdir(parents=True, exist_ok=True)
    sc = simctl(devset)
    runtimes = json.loads(subprocess.run(sc + ["list", "runtimes", "-j"],
                                         capture_output=True, text=True,
                                         check=True).stdout)["runtimes"]
    ios = [r for r in runtimes if r.get("isAvailable")
           and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not ios:
        sys.exit("spike: no iOS simulator runtime installed")
    dev = subprocess.run(sc + ["create", "wata-ios-spike",
                               "com.apple.CoreSimulator.SimDeviceType.iPhone-17",
                               ios[-1]["identifier"]],
                         capture_output=True, text=True)
    if dev.returncode != 0:
        sys.exit("spike: simctl create failed\n" + dev.stderr)
    udid = dev.stdout.strip()
    print(f"spike: device {udid} on {ios[-1]['identifier']}")
    try:
        run(sc + ["bootstatus", udid, "-b"])
        run(sc + ["install", udid, str(APP)])
        t0 = time.time()
        proc = subprocess.Popen(sc + ["launch", "--console-pty", udid, BUNDLE_ID],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1)
        lines, done = [], threading.Event()

        def pump():
            for line in proc.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
                lines.append(line.rstrip("\n"))
                if "all checks passed" in line or "FAIL" in line:
                    done.set()
            done.set()

        th = threading.Thread(target=pump, daemon=True)
        th.start()
        done.wait(120)
        elapsed = time.time() - t0

        shot = OUT / "screen.png"
        subprocess.run(sc + ["io", udid, "screenshot", str(shot)], check=True)
        subprocess.run(sc + ["terminate", udid, BUNDLE_ID],
                       capture_output=True, text=True)
        proc.terminate()

        text = "\n".join(lines)
        missing = [p for p in EXPECT if not re.search(p, text)]
        w, h, rgb = png_pixel(shot, 0.5, 0.5)
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
        subprocess.run(sc + ["shutdown", udid], capture_output=True)
        subprocess.run(sc + ["delete", udid], capture_output=True)


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
