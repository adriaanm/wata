#!/usr/bin/env python3
"""`just watch-hello` — plan 0069 stage 1's product gate: the watch-spike's
proofs re-taken through the PRODUCT packages (go-pkgs/watchshell +
go-pkgs/iosui) instead of the spike's inlined objc calls.

Builds tools/watch-hello/app for the watch simulator (tools/iosenv.py's
`watchsimulator` env), bundles it by hand (tools/watchrun.py — no Xcode
project, no Swift, no storyboard), and runs it on the shared Series 10
device, asserting the app's `hello:` lines including the offscreen render
probe reading back the exact colours UIKit was told to paint.

That probe is the point: it is the same mechanism wata-ios's interptest
asserts with, running on a platform whose own headers say UILabel and
UIView do not exist there.

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
APP = OUT / "WataWatchHello.app"
BUNDLE_ID = "net.wa-ta.watchhello"
BIN = "WataWatchHello"

# Each line is a proof; see app/main.go's header.
EXPECT = [
    r"hello: calling WKApplicationMain",
    r"hello: window visible \d+x\d+",
    r"hello: root adopted, 4 subviews",
    r'hello: label class=UILabel text="wata watch hello"',
    r"hello: callback onmain round-trip",
    r"hello: probe \d+x\d+ top=ff0000 bottom=0000ff image=ff00ff",
    r"hello: offscreen pixel probe PASS",
    r"hello: all checks passed",
]

STAGES = ["build", "bundle", "run"]


def stage_build():
    OUT.mkdir(exist_ok=True)
    env = iosenv.go_env("watchsimulator")
    t0 = time.time()
    watchrun.run(["go", "build", "-ldflags=-w", "-o", str(OUT / BIN), "."],
                 cwd=HERE / "app", env=env)
    print(f"hello: built {BIN} {(OUT / BIN).stat().st_size} bytes, "
          f"{time.time() - t0:.1f}s")


def stage_bundle():
    if not (OUT / BIN).exists():
        sys.exit("hello: no binary — run the build stage")
    watchrun.bundle(APP, OUT / BIN, BUNDLE_ID,
                    delegate_class="WataWatchDelegate")
    print(f"hello: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("hello: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, timeout=120,
            screenshot=OUT / "screen.png", settle=2.0)
        print(f"hello: launch-to-all-checks {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("hello: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print(f"hello: PASS — watchshell + iosui drove UIKit on watchOS; "
              f"screenshot at {OUT / 'screen.png'}")
    finally:
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
