#!/usr/bin/env python3
"""Does wata-mac grow while it sits idle?

The app was found paused by macOS at 26 GB after days of running (2026-08-08).
This measures the growth in a form small enough to iterate on: it reuses
tools/mac-smoke.py's harness so the app is in exactly the shape the gate runs
it in, drives nothing but `wait` — pure idle frames, no input, no messages —
and samples three numbers that together say WHERE the growth is.

  RSS            the process's resident size: grows if anything leaks
  Go live heap   from GODEBUG=gctrace=1: grows only if the leak is Go objects
  OS threads     from GODEBUG=schedtrace: grows if goroutines are pinning Ms

Reading them together is the point. A leak in the Go heap moves the second
number; an ObjC or cgo leak moves only the first; threads that climb while the
heap stays flat mean goroutines that never finish, which is a different bug
from either. Run it before and after a candidate fix — the verdict line is a
comparison, not an absolute.

    tools/mac-leak.py [--rounds N]

Each round is 800ms of frames, so the default 60 is ~48 seconds of idle app.

CAVEAT, and it matters: this runs HEADLESS, where nothing drives an
NSApplication runloop. Work handed to the main dispatch queue is therefore
never drained here, which is a growth source the windowed app does not have —
and conversely the windowed app draws a real view tree this does not. A
finding here is a lead, not a proof about the app the owner runs.
"""
import argparse
import importlib.util
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "macsmoke", os.path.join(REPO, "tools", "mac-smoke.py"))
ms = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ms)

GC = re.compile(r"gc \d+ @([\d.]+)s.*?->(\d+)->(\d+) MB")
SCHED = re.compile(r"SCHED\s+(\d+)ms:.*?threads=(\d+)")


def rss_kb(pid):
    r = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)],
                       capture_output=True, text=True)
    return int(r.stdout.strip() or 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rounds", type=int, default=60,
                    help="800ms rounds of idle frames (default 60)")
    args = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        sgo, mac_bin, _tui, server_bin, env = ms.build_env()
        ms.build(sgo, env)
        proc, log = ms.start_server(server_bin, os.path.join(tmp, "server.log"), env)
        if proc is None:
            sys.exit("mac-leak: server never became ready")
        sess = None
        rss = []
        try:
            sess = ms.MacSession(mac_bin, env, extra_env={
                "GODEBUG": "gctrace=1,schedtrace=4000",
                "WATA_MAC_CONFIG": os.path.join(tmp, "config.json"),
            })
            sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
            for i in range(args.rounds):
                sess.cmd("wait 800", lambda l: l == "waited 800", timeout=30)
                rss.append(rss_kb(sess.proc.pid))
        finally:
            lines = sess.lines if sess else []
            if sess:
                try:
                    sess.proc.kill()
                except OSError:
                    pass
            ms.stop_server(proc, log)

        heaps = [(float(m.group(1)), int(m.group(3)))
                 for m in (GC.search(l) for l in lines) if m]
        threads = [(int(m.group(1)), int(m.group(2)))
                   for m in (SCHED.search(l) for l in lines) if m]

        print(f"== wata-mac idle growth ({args.rounds} rounds "
              f"= ~{args.rounds * 0.8:.0f}s of frames) ==")
        if rss:
            step = max(1, len(rss) // 8)
            for i in range(0, len(rss), step):
                print(f"  round {i:3d}  rss {rss[i] / 1024:8.1f} MB"
                      f"   (+{(rss[i] - rss[0]) / 1024:.1f})")
            print(f"  RSS          {rss[0] / 1024:.1f} MB -> {rss[-1] / 1024:.1f} MB")
        if heaps:
            print(f"  Go live heap {heaps[0][1]} MB -> {heaps[-1][1]} MB "
                  f"({len(heaps)} GCs)")
        else:
            print("  Go live heap  (no GC ran — the Go heap is not the growth)")
        if threads:
            print(f"  OS threads   {threads[0][1]} -> {threads[-1][1]}")

        grew = rss and (rss[-1] - rss[0]) > 2048  # >2 MB over the run
        print("  VERDICT:", "GROWING" if grew else "steady")
    return 0


if __name__ == "__main__":
    sys.exit(main())
