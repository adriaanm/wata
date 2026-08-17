#!/usr/bin/env python3
"""`just ios-interptest` — the plan-0044 stage-3 gate: `wata-ios interptest`
run in the simulator.

Builds wata-ios twice — the pinned `sgo` emits (and native-builds) the app,
then a plain `go build` of the EMITTED module for the simulator
(tools/iosenv.py, the same two-step fb-deploy uses for its iroh build) —
bundles it by hand and runs it on the shared simulator device
(tools/simrun.py) with argv `interptest`. The verdict is the app's own
printed line, the `wata-mac interptest` discipline: `interptest: PASS`
present and no FAIL lines, or this exits non-zero.

Console capture is lossy (cold-runtime first launch, pty attach racing a
fast app) — simrun.launch_expect_verdict retries a run whose output never
reached a done marker; see its docstring.

Stages (each runnable alone with --only): build, bundle, run.
Needs Xcode + an iOS simulator runtime; not in ci.
"""

import argparse
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import iosenv  # noqa: E402
import simrun  # noqa: E402

REPO = HERE.parent
MODULE = REPO / "wata-ios"
EMIT = MODULE / ".sgo" / "wata-ios"  # <module>/.sgo/<emitname>, sgo.build's
BIN = "wata-ios-sim"
APP = EMIT / "WataIos.app"
BUNDLE_ID = "dev.wata.ios"

PASS_RE = r"^interptest: PASS$"
DONE_RES = (r"interptest: (PASS|FAIL)",)

STAGES = ["build", "bundle", "run"]


def stage_build():
    # sgo emits the Go module (and native-builds it — the type gate) ...
    simrun.run([str(REPO / "tools" / "sgo"), "build"], cwd=MODULE)
    # ... then the emitted module cross-builds for the simulator.
    env = iosenv.go_env("iphonesimulator")
    t0 = time.time()
    simrun.run(["go", "build", "-ldflags=-w", "-o", str(EMIT / BIN), "."],
               cwd=EMIT, env=env)
    print(f"ios-interptest: built {BIN} {(EMIT / BIN).stat().st_size} bytes, "
          f"{time.time() - t0:.1f}s")


def stage_bundle():
    if not (EMIT / BIN).exists():
        sys.exit("ios-interptest: no binary — run the build stage")
    simrun.bundle(APP, EMIT / BIN, BUNDLE_ID)
    print(f"ios-interptest: bundled {APP}")


def stage_run():
    if not APP.exists():
        sys.exit("ios-interptest: no .app — run the bundle stage")
    sc = simrun.simctl()
    udid = simrun.ensure_device(sc)
    try:
        lines, elapsed, missing = simrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, [PASS_RE], done_res=DONE_RES,
            timeout=90, args=["interptest"])
        print(f"ios-interptest: launch-to-verdict {elapsed:.2f}s")
        fails = [l for l in lines if l.startswith("interptest: FAIL")]
        for f in fails:
            print("ios-interptest: " + f, file=sys.stderr)
        if missing or fails:
            for m in missing:
                print("ios-interptest: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        print("ios-interptest: PASS — the retained UIKit interpreter "
              "mirrors wataui's applyAll in the simulator")
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
