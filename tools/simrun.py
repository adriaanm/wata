"""Driving a hand-rolled .app in the iOS simulator, shared by the harnesses.

The pieces `tools/ios-spike/spike.py` proved, importable: bundle a Go
binary into a `.app` (Info.plist + ad-hoc codesign), keep every simctl
call inside the CUSTOM DEVICE SET (with ~/Library/Developer symlinked to
an external volume, CoreSimulatorService takes an unpromptable TCC EPERM
on the default set — never chase that grant; override the set with
WATA_SIM_DEVSET), reuse-or-create a named device, launch with a console
pty and assert on the app's printed lines, and read a pixel out of a
screenshot PNG with nothing but the stdlib.

A device is REUSED if one with the requested name already exists in the
set (a smoke device costs ~0.1 GiB — creating a fresh one per run wastes
that and the boot time); it is shut down after a run, never deleted.
"""

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

MIN_IOS = "17.0"
DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
DEVICE_NAME = "wata-ios"  # the one simulator device every harness shares


def run(cmd, **kw):
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run(cmd, check=True, **kw)


def devset():
    """The custom device set's path, created if absent."""
    ds = os.environ.get("WATA_SIM_DEVSET", os.path.expanduser("~/.wata-simdevices"))
    pathlib.Path(ds).mkdir(parents=True, exist_ok=True)
    return ds


def simctl(ds=None):
    return ["xcrun", "simctl", "--set", ds or devset()]


def bundle(app_path, binary, bundle_id, min_ios=MIN_IOS):
    """A `.app` by hand at app_path: no Xcode project, no gomobile, no team
    ID. The simulator needs a bundle, an Info.plist and *a* signature;
    ad-hoc (`codesign -s -`) is a signature."""
    app_path = pathlib.Path(app_path)
    binary = pathlib.Path(binary)
    shutil.rmtree(app_path, ignore_errors=True)
    app_path.mkdir(parents=True)
    shutil.copy2(binary, app_path / binary.name)
    info = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleExecutable": binary.name,
        "CFBundleIdentifier": bundle_id,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": binary.name,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleVersion": "1",
        "CFBundleSupportedPlatforms": ["iPhoneSimulator"],
        "DTPlatformName": "iphonesimulator",
        "LSRequiresIPhoneOS": True,
        "MinimumOSVersion": min_ios,
        "UIDeviceFamily": [1],
        "UILaunchScreen": {},
        "UIRequiredDeviceCapabilities": ["arm64"],
        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
    }
    with open(app_path / "Info.plist", "wb") as f:
        plistlib.dump(info, f)
    run(["codesign", "--force", "--sign", "-", "--timestamp=none", str(app_path)])
    run(["codesign", "-dv", str(app_path)])
    return app_path


def latest_ios_runtime(sc):
    """The newest available iOS runtime's identifier, or exit."""
    runtimes = json.loads(subprocess.run(sc + ["list", "runtimes", "-j"],
                                         capture_output=True, text=True,
                                         check=True).stdout)["runtimes"]
    ios = [r for r in runtimes if r.get("isAvailable")
           and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not ios:
        sys.exit("simrun: no iOS simulator runtime installed")
    return ios[-1]["identifier"]


def ensure_device(sc, name=DEVICE_NAME, device_type=DEVICE_TYPE):
    """The udid of the named device in the set, creating it only if it does
    not exist yet."""
    listing = json.loads(subprocess.run(sc + ["list", "devices", "-j"],
                                        capture_output=True, text=True,
                                        check=True).stdout)["devices"]
    for devs in listing.values():
        for d in devs:
            if d.get("name") == name and d.get("isAvailable"):
                print(f"simrun: reusing device {name} {d['udid']}")
                return d["udid"]
    rt = latest_ios_runtime(sc)
    dev = subprocess.run(sc + ["create", name, device_type, rt],
                         capture_output=True, text=True)
    if dev.returncode != 0:
        sys.exit("simrun: simctl create failed\n" + dev.stderr)
    udid = dev.stdout.strip()
    print(f"simrun: created device {name} {udid} on {rt}")
    return udid


def launch_and_expect(sc, udid, app_path, bundle_id, expect,
                      done_res=(r"all checks passed", r"FAIL"),
                      timeout=90, screenshot=None, args=()):
    """Boot (if needed), install, launch with a console pty, pump the app's
    stdout until a done marker (or the watchdog timeout), screenshot if
    asked, terminate. Returns (lines, elapsed_seconds, missing_patterns) —
    the caller judges. `expect` is a list of regexes that must each match
    somewhere in the output. `args` become the app's argv (simctl passes
    everything after the bundle id through to the process)."""
    run(sc + ["bootstatus", udid, "-b"])
    run(sc + ["install", udid, str(app_path)])
    t0 = time.time()
    proc = subprocess.Popen(sc + ["launch", "--console-pty", udid, bundle_id]
                            + list(args),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1)
    lines, done = [], threading.Event()

    def pump():
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            lines.append(line.rstrip("\n"))
            if any(re.search(m, line) for m in done_res):
                done.set()
        done.set()

    threading.Thread(target=pump, daemon=True).start()
    if not done.wait(timeout):
        print(f"simrun: watchdog — no done marker within {timeout}s", file=sys.stderr)
    elapsed = time.time() - t0

    if screenshot is not None:
        subprocess.run(sc + ["io", udid, "screenshot", str(screenshot)], check=True)
    subprocess.run(sc + ["terminate", udid, bundle_id],
                   capture_output=True, text=True)
    proc.terminate()

    text = "\n".join(lines)
    missing = [p for p in expect if not re.search(p, text)]
    return lines, elapsed, missing


def shutdown(sc, udid):
    """Shut the device down (kept in the set for the next run)."""
    subprocess.run(sc + ["shutdown", udid], capture_output=True)


def png_pixel(path, fx, fy):
    """Decode a PNG far enough to read one pixel at fractional (fx, fy).
    Stdlib only — no Pillow in this repo's toolchain."""
    data = pathlib.Path(path).read_bytes()
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
