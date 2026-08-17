#!/usr/bin/env python3
"""`just ios-hello` — the plan-0044 stage-2 gate: the ios-spike's proofs
re-taken through the PRODUCT packages (go-pkgs/iosshell + go-pkgs/iosui)
instead of the spike's inlined objc calls.

Builds tools/ios-hello/app for the simulator (tools/iosenv.py), bundles it
by hand, and runs it on the shared simulator device (tools/simrun.py — the
custom device set, reuse-or-create). Asserts the app's `hello:` lines,
including the offscreen render probe reading back the exact colours UIKit
was told to paint — the mechanism stage 3's interptest will assert with.

Stages (each runnable alone with --only): build, bundle, run.
Needs Xcode + an iOS simulator runtime; not in ci.
"""

import argparse
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import iosenv  # noqa: E402
import simrun  # noqa: E402

OUT = HERE / "out"
APP = OUT / "WataIosHello.app"
BUNDLE_ID = "net.wa-ta.ioshello"
BIN = "WataIosHello"

# Each line is a proof; see tools/ios-hello/app/main.go's header.
EXPECT = [
    r"hello: calling UIApplicationMain",
    r"hello: window visible \d+x\d+",
    r"hello: root adopted, 4 subviews",
    r'hello: label class=UILabel text="wata ios hello"',
    r"hello: callback onmain round-trip",
    r"hello: probe \d+x\d+ top=ff0000 bottom=0000ff image=ff00ff",
    r"hello: offscreen pixel probe PASS",
    r"hello: all checks passed",
]

STAGES = ["build", "bundle", "run"]


def stage_build():
    OUT.mkdir(exist_ok=True)
    env = iosenv.go_env("iphonesimulator")
    t0 = time.time()
    simrun.run(["go", "build", "-tags", "ios", "-ldflags=-w",
                "-o", str(OUT / BIN), "."], cwd=HERE / "app", env=env)
    print(f"hello: built {BIN} {(OUT / BIN).stat().st_size} bytes, "
          f"{time.time() - t0:.1f}s")


def stage_bundle():
    if not (OUT / BIN).exists():
        sys.exit("hello: no binary — run the build stage")
    simrun.bundle(APP, OUT / BIN, BUNDLE_ID)
    print(f"hello: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("hello: no .app — run the bundle stage")
    sc = simrun.simctl()
    udid = simrun.ensure_device(sc)
    try:
        lines, elapsed, missing = simrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, timeout=90,
            screenshot=OUT / "screen.png")
        print(f"hello: launch-to-all-checks {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("hello: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print("hello: PASS — iosshell + iosui drove UIKit in the simulator")
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
