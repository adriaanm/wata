#!/usr/bin/env python3
"""`just watch-interptest` — plan 0069's stage gate: `wata-watch interptest`
run on the watch simulator.

This is tools/ios-interptest.py's twin, and deliberately runs the SAME
suite: wata-ios's interptest.scala is copied into wata-watch unchanged
except for one dropped case (plan 0067's PTT target rule, which belongs to
the phone's PushToTalk binding). So a green run here says the retained
stage, wataui's differ, the glyph and pixel tables and the offscreen render
probe all behave on watchOS exactly as they do on iOS — on a platform whose
own headers say UIView and UILabel are not available.

Builds wata-watch twice — the pinned `sgo` emits (and native-builds) the
app, then a plain `go build` of the EMITTED module for the watch simulator
(tools/iosenv.py's `watchsimulator` env) — bundles it by hand
(tools/watchrun.py: no Xcode project, no Swift, no storyboard) and launches
it with argv `interptest` on the shared Series 10 device.

The verdict is the app's own printed line: WatchKit owns the process's
exit, so an exit code says nothing. Console capture is lossy, so
simrun.launch_expect_verdict retries a run whose output never reached a
done marker; see its docstring.

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
APP = EMIT / "WataWatchIT.app"
BUNDLE_ID = "net.wa-ta.watchit"

PASS_RE = r"^interptest: PASS$"
DONE_RES = (r"interptest: (PASS|FAIL)",)

STAGES = ["build", "bundle", "run"]


def stage_build():
    # sgo emits the Go module (and native-builds it — the type gate) ...
    watchrun.run([str(REPO / "tools" / "sgo"), "build"], cwd=MODULE)
    # ... then the emitted module cross-builds for the watch simulator.
    env = iosenv.go_env("watchsimulator")
    t0 = time.time()
    watchrun.run(["go", "build", "-ldflags=-w", "-o", str(EMIT / BIN), "."],
                 cwd=EMIT, env=env)
    print(f"watch-interptest: built {BIN} {(EMIT / BIN).stat().st_size} "
          f"bytes, {time.time() - t0:.1f}s")


def stage_bundle():
    if not (EMIT / BIN).exists():
        sys.exit("watch-interptest: no binary — run the build stage")
    # A DIFFERENT bundle id from watch-smoke's: the two apps are the same
    # binary in different argv modes, and sharing an id would make each
    # install overwrite the other's container.
    watchrun.bundle(APP, EMIT / BIN, BUNDLE_ID,
                    delegate_class="WataWatchDelegate")
    print(f"watch-interptest: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("watch-interptest: no .app — run the bundle stage")
    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, [PASS_RE], done_res=DONE_RES,
            timeout=120, args=["interptest"])
        print(f"watch-interptest: launch-to-verdict {elapsed:.2f}s")
        fails = [l for l in lines if l.startswith("interptest: FAIL")]
        for f in fails:
            print("watch-interptest: " + f, file=sys.stderr)
        if missing or fails:
            for m in missing:
                print("watch-interptest: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print("watch-interptest: PASS — the retained UIKit stage mirrors "
              "wataui's applyAll on watchOS")
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
