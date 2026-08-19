#!/usr/bin/env python3
"""`just watch-spike` — plan 0069 stage 1's first run leg.

Builds tools/watch-spike/app for the watchOS simulator (tools/iosenv.py's
`watchsimulator` env — GOOS=ios against the watch sysroot, which stage 0
proved yields a real watchOS Mach-O), bundles it as a watch-only app by
hand (tools/watchrun.py), and runs it on the shared Series 10 device,
asserting the app's `watchspike:` lines.

What it settles is written in app/main.go's header. The short version: a
Go binary runs on the watch, and which ObjC classes are actually there —
the answer decides whether the watch client can be a wata-ios-shaped
retained stage or has to be a WatchKit raster.

Stages (each runnable alone with --only): build, bundle, run.
Needs Xcode + a watchOS simulator runtime; not in ci.
"""

import argparse
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import iosenv  # noqa: E402
import watchrun  # noqa: E402

OUT = HERE / "out"
APP = OUT / "WataWatchSpike.app"
BUNDLE_ID = "net.wa-ta.watchspike"
BIN = "WataWatchSpike"

# Each line is a proof; see app/main.go's header. The class table is NOT
# asserted line by line — what is present is the finding, not a
# precondition — but the run must reach main, load the frameworks and get
# through the whole table.
EXPECT = [
    r"watchspike: go main entered",
    r"watchspike: runtime go[\d.]+ ios/arm64",
    r"watchspike: dlopen WatchKit ok",
    r"watchspike: frameworks \d+/\d+ loaded",
    r"watchspike: classes \d+/\d+ present",
    r"watchspike: WKApplicationMain resolved",
    r"watchspike: all checks passed",
]

# The `uiapp` leg's proofs: watchOS letting a Go binary own
# UIApplicationMain and put a real UIKit window on the panel. A returning
# UIApplicationMain is a finding, not a hang, so it prints and fails.
EXPECT_UIAPP = [
    r"watchspike: uiapp delegate class registered",
    r"watchspike: uiapp didFinishLaunching entered",
    r"watchspike: uiapp screen \d+x\d+",
    r"watchspike: uiapp window key and visible",
    r"watchspike: uiapp root adopted, 2 subviews",
    r"watchspike: all checks passed",
]

# The `wkapp` leg: WatchKit drives the lifecycle and the app builds its own
# UIKit window inside it. The launch callback firing is asserted separately
# from anything UIKit does after, so a half-answer still reports.
EXPECT_WKAPP = [
    r"watchspike: wkapp WKExtensionDelegate protocol resolved",
    r"watchspike: wkapp delegate class registered",
    r"watchspike: wkapp controller class registered",
    r"watchspike: wkapp rootInterfaceControllerClass asked",
    r"watchspike: wkapp applicationDidFinishLaunching",
    r"watchspike: wkapp controller willActivate",
    r"watchspike: wkapp screen \d+x\d+",
    r"watchspike: wkapp uikit window subviews=2",
    r"watchspike: wkapp frame pushed \d+x\d+ px",
    r"watchspike: all checks passed",
]

# The `net` leg: Go's own network stack on the watch — a dial and an HTTP
# round trip against a throwaway loopback server, and a TLS handshake
# against a real name (which also exercises DNS, the trust store and the
# clock). Nothing here is Apple's, so nothing the UIKit legs proved covers it.
EXPECT_NET = [
    r"watchspike: net clock 20\d\d-",
    r"watchspike: net dial 127\.0\.0\.1:\d+ ok",
    r"watchspike: net http 200 \d+ bytes",
    r"watchspike: net tls ok version=\w+ cipher=\w+",
    r"watchspike: all checks passed",
]

STAGES = ["build", "bundle", "run", "uiapp", "wkapp", "net"]


def stage_build():
    OUT.mkdir(exist_ok=True)
    env = iosenv.go_env("watchsimulator")
    t0 = time.time()
    watchrun.run(["go", "build", "-ldflags=-w", "-o", str(OUT / BIN), "."],
                 cwd=HERE / "app", env=env)
    print(f"watchspike: built {BIN} {(OUT / BIN).stat().st_size} bytes, "
          f"{time.time() - t0:.1f}s")


def stage_bundle():
    if not (OUT / BIN).exists():
        sys.exit("watchspike: no binary — run the build stage")
    # No storyboard: the delegate answers applicationRootInterfaceControllerClass,
    # which is watchOS's own stated alternative to an interface description
    # file, so nothing here is authored in Interface Builder.
    watchrun.bundle(APP, OUT / BIN, BUNDLE_ID,
                    delegate_class="WataWatchSpikeWKDelegate")
    print(f"watchspike: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("watchspike: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, timeout=120,
            screenshot=OUT / "screen.png")
        print(f"watchspike: launch-to-all-checks {elapsed:.2f}s")
        for line in lines:
            if "class " in line and "true" in line:
                pass  # the table is echoed by the pump already
        if missing:
            for m in missing:
                print("watchspike: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print("watchspike: PASS — Go ran on watchOS and read the objc runtime")
    finally:
        watchrun.shutdown(sc, udid)


def stage_uiapp():
    """The retained-stage probe: hand the process to UIApplicationMain."""
    if not APP.exists():
        sys.exit("watchspike: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT_UIAPP, timeout=120,
            screenshot=OUT / "uiapp.png", args=("uiapp",))
        print(f"watchspike: uiapp launch-to-all-checks {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("watchspike: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print(f"watchspike: uiapp PASS — a Go-owned UIKit window on watchOS; "
              f"screenshot at {OUT / 'uiapp.png'}")
    finally:
        watchrun.shutdown(sc, udid)


def stage_wkapp():
    """The hybrid probe: WatchKit lifecycle, app-built UIKit window."""
    if not APP.exists():
        sys.exit("watchspike: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT_WKAPP, timeout=120,
            screenshot=OUT / "wkapp.png", args=("wkapp",), settle=2.0)
        print(f"watchspike: wkapp launch-to-all-checks {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("watchspike: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print(f"watchspike: wkapp PASS — WatchKit lifecycle over a Go-built "
              f"UIKit tree; screenshot at {OUT / 'wkapp.png'}")
    finally:
        watchrun.shutdown(sc, udid)


def stage_net():
    """Go's network stack on the watch, against a throwaway local server."""
    if not APP.exists():
        sys.exit("watchspike: no .app — run the bundle stage")
    import http.server
    import socketserver
    import threading

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = b"wata watch probe\n"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    srv = socketserver.TCPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{port}/probe"
    print(f"watchspike: net probe server at {url}")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT_NET, timeout=120,
            args=("net", url))
        print(f"watchspike: net launch-to-all-checks {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("watchspike: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print("watchspike: net PASS — sockets, HTTP and TLS all work on watchOS")
    finally:
        srv.shutdown()
        watchrun.shutdown(sc, udid)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=STAGES, action="append",
                    help="run just these stages (default: all, in order)")
    args = ap.parse_args()
    for s in (args.only or STAGES):
        globals()["stage_" + s]()


if __name__ == "__main__":
    main()
