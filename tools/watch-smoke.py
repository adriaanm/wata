#!/usr/bin/env python3
"""`just watch-smoke` — plan 0069 stage 2's gate: `wata-watch` run on the
watch simulator.

Builds wata-watch twice — the pinned `sgo` emits (and native-builds) the
app, then a plain `go build` of the EMITTED module for the watch simulator
(tools/iosenv.py's `watchsimulator` env, the same two-step ios-interptest
uses) — bundles it by hand as a watch-only app (tools/watchrun.py: no Xcode
project, no Swift, no storyboard) and runs it on the shared Series 10
device.

The verdict is the app's own printed lines, the interptest discipline:
WatchKit owns the process exit, so an exit code says nothing.

What this proves that watch-hello does not: the code on the panel is
SGOLA — compiled through sgo to Go, over the same generated UIKit bindings
the phone uses, on a platform whose headers say those classes are not
there.

Stages (each runnable alone with --only): build, bundle, run.
Needs Xcode + a watchOS simulator runtime; not in ci.
"""

import argparse
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import iosenv  # noqa: E402
import watchrun  # noqa: E402

REPO = HERE.parent
MODULE = REPO / "wata-watch"
EMIT = MODULE / ".sgo" / "wata-watch"  # <module>/.sgo/<emitname>, sgo.build's
BIN = "wata-watch-sim"
APP = EMIT / "WataWatch.app"
BUNDLE_ID = "net.wa-ta.watch"

EXPECT = [
    r"watch: ready \d+x\d+",
    r"watch: painted 3 views",
    r"watch: probe ff0000",
    r"watch: all checks passed",
]

STAGES = ["build", "bundle", "run"]


def stage_build():
    # sgo emits the Go module (and native-builds it — the type gate) ...
    watchrun.run([str(REPO / "tools" / "sgo"), "build"], cwd=MODULE)
    # ... then the emitted module cross-builds for the watch simulator.
    env = iosenv.go_env("watchsimulator")
    t0 = time.time()
    watchrun.run(["go", "build", "-ldflags=-w", "-o", str(EMIT / BIN), "."],
                 cwd=EMIT, env=env)
    print(f"watch-smoke: built {BIN} {(EMIT / BIN).stat().st_size} bytes, "
          f"{time.time() - t0:.1f}s")


def stage_bundle():
    if not (EMIT / BIN).exists():
        sys.exit("watch-smoke: no binary — run the build stage")
    watchrun.bundle(APP, EMIT / BIN, BUNDLE_ID,
                    delegate_class="WataWatchDelegate")
    print(f"watch-smoke: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("watch-smoke: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, timeout=120,
            screenshot=EMIT / "screen.png", settle=2.0)
        print(f"watch-smoke: launch-to-verdict {elapsed:.2f}s")
        if missing:
            for m in missing:
                print("watch-smoke: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print(f"watch-smoke: PASS — Sgola painted the watch's panel; "
              f"screenshot at {EMIT / 'screen.png'}")
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
