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

REPO = pathlib.Path(__file__).resolve().parent.parent
# the same voice fixture mac-smoke's inbound leg sends.
FIXTURE = REPO / "go-pkgs" / "audio" / "testdata" / "tui-foreign.ogg"
# the arrival the app prints when a message lands with the default notify mode
# (MODE_QUIET): iOS has no banner, so `notify: noted` IS the arrival surface.
# No ^/$ anchors — see launch_and_expect.
NOTED_RE = r'notify: noted ".*" ".*" badge=[1-9]'


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
        # the wata:// scheme (plan 0062): registered in the simulator too so
        # `simctl openurl` can drive the configure edge in the smokes.
        "CFBundleURLTypes": [
            {"CFBundleURLName": bundle_id, "CFBundleURLSchemes": ["wata"]},
        ],
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


def approve_scheme(udid, scheme, bundle_id, ds=None):
    """Pre-approve a custom URL scheme so `simctl openurl` delivers instead
    of parking on the system's "Open in <app>?" consent alert (which no
    harness is there to tap). The approval store is a per-device plist lsd
    reads at boot — so returns True if the entry was ADDED, in which case a
    booted device must be rebooted before the approval takes."""
    key = f"com.apple.CoreSimulator.CoreSimulatorBridge-->{scheme}"
    p = pathlib.Path(ds or devset()) / udid / "data" / "Library" / \
        "Preferences" / "com.apple.launchservices.schemeapproval.plist"
    approvals = {}
    if p.exists():
        with open(p, "rb") as f:
            approvals = plistlib.load(f)
    if approvals.get(key) == bundle_id:
        return False
    approvals[key] = bundle_id
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "wb") as f:
        plistlib.dump(approvals, f)
    print(f"simrun: approved scheme {scheme} -> {bundle_id} (device reboot needed)")
    return True


def fire_on_match(on_match):
    """A per-launch line hook: `on_match` is a sequence of (regex, fn) pairs,
    and the returned callable runs each fn ONCE, on its own thread, the first
    time a printed line matches. That is how a harness acts on the app from
    outside mid-run (the outside party bob sends a message once the contact
    list has painted) without blocking the output pump."""
    fired = set()

    def feed(line):
        for i, (pat, fn) in enumerate(on_match):
            if i not in fired and re.search(pat, line):
                fired.add(i)
                threading.Thread(target=fn, daemon=True).start()
    return feed


def launch_and_expect(sc, udid, app_path, bundle_id, expect,
                      done_res=(r"all checks passed", r"FAIL"),
                      timeout=90, screenshot=None, args=(), on_match=(),
                      settle=0.0):
    """Boot (if needed), install, launch with a console pty, pump the app's
    stdout until a done marker (or the watchdog timeout), screenshot if
    asked, terminate. Returns (lines, elapsed_seconds, missing_patterns) —
    the caller judges. `expect` is a list of regexes that must each match
    somewhere in the output. `args` become the app's argv (simctl passes
    everything after the bundle id through to the process). `on_match` is
    fire_on_match's (regex, fn) pairs, fresh for each launch."""
    run(sc + ["bootstatus", udid, "-b"])
    run(sc + ["install", udid, str(app_path)])
    t0 = time.time()
    proc = subprocess.Popen(sc + ["launch", "--console-pty", udid, bundle_id]
                            + list(args),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1)
    lines, done = [], threading.Event()
    feed = fire_on_match(on_match)

    def pump():
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            lines.append(line.rstrip("\n"))
            feed(line)
            if any(re.search(m, line) for m in done_res):
                done.set()
        done.set()

    threading.Thread(target=pump, daemon=True).start()
    if not done.wait(timeout):
        print(f"simrun: watchdog — no done marker within {timeout}s", file=sys.stderr)
    elapsed = time.time() - t0

    if screenshot is not None:
        # A done marker can beat the COMPOSITOR: the app prints its last
        # proof as soon as its own work is finished, which on a fast device
        # is before the frame it just built has been drawn, so a screenshot
        # taken right here catches a blank panel and reads as "nothing was
        # displayed". `settle` waits that out. (Found on watchOS, where the
        # whole run is ~0.6s; the offscreen render probe passed the entire
        # time, which is exactly why it is not evidence of what is on
        # screen.)
        if settle:
            time.sleep(settle)
        subprocess.run(sc + ["io", udid, "screenshot", str(screenshot)], check=True)
    subprocess.run(sc + ["terminate", udid, bundle_id],
                   capture_output=True, text=True)
    proc.terminate()

    # re.M so an anchored pattern (^...$) means "some LINE is exactly this",
    # not "the whole capture starts with this" — without it, any line printed
    # before the expected one silently un-matches every anchored expectation.
    text = "\n".join(lines)
    missing = [p for p in expect if not re.search(p, text, re.M)]
    return lines, elapsed, missing


def launch_expect_verdict(sc, udid, app_path, bundle_id, expect,
                          done_res=(r"all checks passed", r"FAIL"),
                          timeout=90, screenshot=None, args=(), attempts=2,
                          on_match=(), settle=0.0):
    """launch_and_expect, retried while the done marker never arrives.

    `--console-pty` capture is lossy two ways: a cold-booted fresh runtime's
    first launch can produce nothing within the watchdog, and the pty attach
    can race a fast app and drop its lines entirely. Emptiness is not the
    tell for either — simctl's own `<bundle>: <pid>` line still lands in the
    capture — so the discriminator is the done marker: a run that never
    reached one is a broken capture and is retried on the now-warm device; a
    run that did (PASS or FAIL alike) is a verdict and is judged as-is."""
    for attempt in range(1, attempts + 1):
        lines, elapsed, missing = launch_and_expect(
            sc, udid, app_path, bundle_id, expect, done_res=done_res,
            timeout=timeout, screenshot=screenshot, args=args,
            on_match=on_match, settle=settle)
        if attempt == attempts or \
                any(re.search(m, l) for l in lines for m in done_res):
            return lines, elapsed, missing
        print("simrun: no done marker in the capture — lost console output, "
              "retrying on the warm device", file=sys.stderr)


def tui_send(tui_bin, env, base, user="bob", password="testpass123",
             conv="1", fixture=FIXTURE, timeout=180, tag="inbound"):
    """The outside party: `user` sends a voice message into conversation
    `conv` (1 = the family room) through a host-side wata-tui, exactly the
    mechanism mac-smoke's inbound leg uses. The simulator shares the host's
    loopback, so a plain-HTTP `base` reaches the same server the app is
    talking to — the app's own transport is the thing under test, not the
    sender's.

    Returns (ok, sent_at, lines), `sent_at` being the monotonic clock reading
    at the moment the tui reported `sent` — the start of the delivery latency
    the gates bound. The tui's output is streamed rather than collected at
    exit so that timestamp is the send, not the process teardown."""
    script = f"snap\nsend {conv} {fixture}\nquit\n"
    senv = dict(env, WATA_TUI_HS=base, WATA_TUI_USER=user,
                WATA_TUI_PASS=password)
    proc = subprocess.Popen([str(tui_bin)], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, env=senv, bufsize=1)
    proc.stdin.write(script)
    proc.stdin.flush()
    proc.stdin.close()
    lines, sent_at = [], None
    for line in proc.stdout:
        line = line.rstrip("\n")
        if line.startswith("sent ") and sent_at is None:
            sent_at = time.monotonic()
        lines.append(line)
        print(f"{tag}: {user}| {line}", flush=True)
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        print(f"{tag}: {user}'s tui timed out", flush=True)
    return sent_at is not None, sent_at, lines


# The delivery bound both inbound legs assert, sent -> the app's `notify:`
# line. Measured over four runs on this Mac (two per transport): 0.00s and
# -0.01s on plain HTTP, -0.01s and -0.02s over iroh — i.e. BELOW the
# measurement's own resolution on both, because the server wakes the sync
# long-poll the moment the event lands rather than the client waiting out a
# poll interval, and iroh costs nothing measurable over loopback.
#
# 15s is therefore not a fit to those numbers — nothing that small can be fit
# — but a deliberately loose ceiling: three orders of magnitude of headroom
# for a loaded machine, still far under the failure this exists to catch, a
# delivery sliding from instant to tens of seconds. Tighten it only with a
# distribution to argue from.
LATENCY_BUDGET_S = 15.0


def latency_ok(tag, sent_at, arrived_at, budget=LATENCY_BUDGET_S):
    """Print and judge the sent -> arrival delay: from the sending tui saying
    `sent` to the app printing its `notify:` line. The two endpoints are read
    off two different pipes, so the resolution is ~0.1s and a small NEGATIVE
    reading just means the arrival beat the sender's own stdout out of the
    kernel — it is delivery inside the noise, not time travel.

    A missing endpoint is not a latency failure — the caller's own
    MISSING/sender checks own that — so it only reports here."""
    if sent_at is None or arrived_at is None:
        print(f"{tag}: no latency measurement (send or arrival missing)",
              file=sys.stderr)
        return True
    dt = arrived_at - sent_at
    print(f"{tag}: inbound latency {dt:.2f}s (±0.1s, budget {budget:.0f}s)",
          flush=True)
    if dt > budget:
        print(f"{tag}: SLOW — the message took {dt:.2f}s to surface, over the "
              f"{budget:.0f}s budget", file=sys.stderr)
        return False
    return True


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
