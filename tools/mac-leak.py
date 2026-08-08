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
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "macsmoke", os.path.join(REPO, "tools", "mac-smoke.py"))
ms = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ms)

GC = re.compile(r"gc \d+ @([\d.]+)s.*?->(\d+)->(\d+) MB")
SCHED = re.compile(r"SCHED\s+(\d+)ms:.*?threads=(\d+)")
# A traceback printed because of a SIGNAL is not spelled like runtime.Stack's:
# it carries the runtime's own registers first — `goroutine 12 gp=0x… m=nil
# mp=nil [select]:` — so anchoring the state to the goroutine number finds
# nothing and reports an empty dump for a dump that arrived in full.
# vmmap -summary rows: a region name (which may contain single spaces), then
# VIRTUAL / RESIDENT / DIRTY. DIRTY is the one that matters — it is the memory
# this process actually owns and cannot get back by dropping a clean page.
VMMAP_ROW = re.compile(r"^(.+?)\s{2,}(\S+)\s+(\S+)\s+(\S+)")
GOROUTINE = re.compile(r"^goroutine \d+[^\[]*\[([^\],]+)")
CREATED_BY = re.compile(r"^created by (\S+)")


def rss_kb(pid):
    r = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)],
                       capture_output=True, text=True)
    return int(r.stdout.strip() or 0)


def drain(sess):
    """Pull whatever the app has printed into sess.lines and return it.

    Headless, read_until does this as a side effect of driving the REPL. The
    windowed app is not driven, so nothing would ever collect its gctrace and
    schedtrace lines — and its pipe would eventually fill and block it."""
    import queue as _q
    got = []
    while True:
        try:
            line = sess.q.get_nowait()
        except _q.Empty:
            return got
        if line is None:
            return got
        got.append(line)
        sess.lines.append(line)


def heap_growth(tmp, binary):
    """`go tool pprof -base <first> … <last>`: what GREW, not what is there.

    A single heap profile is dominated by the app's ordinary steady-state
    objects and says nothing about a leak. Subtracting an early profile from a
    late one leaves only the difference, which is the question."""
    # heap.<n>.pprof — sort by n as a NUMBER. Lexicographically, heap.9 sorts
    # after heap.24, so a plain sort silently compares the first profile
    # against the tenth and reports a third of the run as if it were all of it.
    def idx(f):
        try:
            return int(f.rsplit(".", 2)[1])
        except (IndexError, ValueError):
            return -1
    profs = sorted((f for f in os.listdir(tmp) if f.endswith(".pprof")), key=idx)
    if len(profs) < 2:
        return [f"(only {len(profs)} heap profile(s) — the run is shorter "
                f"than two dump intervals; use more rounds)"]
    first, last = os.path.join(tmp, profs[0]), os.path.join(tmp, profs[-1])
    r = subprocess.run(
        ["go", "tool", "pprof", "-top", "-nodecount=15",
         "-sample_index=inuse_space", "-base", first, binary, last],
        capture_output=True, text=True)
    if r.returncode != 0:
        return [f"(pprof failed: {r.stderr.strip().splitlines()[-1:]})"]
    return [f"({len(profs)} profiles; {profs[0]} -> {profs[-1]})"] + \
        r.stdout.splitlines()


def vmmap_regions(pid):
    """{region name: dirty KB} from vmmap's summary table.

    RSS says the process grew; this says which mapping did, which is the
    difference between a thread stack, a malloc zone and the Go heap."""
    r = subprocess.run(["vmmap", "-summary", str(pid)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return {}
    out = {}
    started = False
    for line in r.stdout.splitlines():
        if line.startswith("REGION TYPE"):
            started = True
            continue
        if not started:
            continue
        if not line.strip() or line.startswith("="):
            if out:
                break
            continue
        m = VMMAP_ROW.match(line)
        if m:
            out[m.group(1).strip()] = kb(m.group(4))
    return out


def kb(s):
    """vmmap sizes: '1234K', '12.3M', '1.0G', or a bare byte count."""
    s = s.strip()
    mult = {"K": 1, "M": 1024, "G": 1024 * 1024}.get(s[-1:], None)
    try:
        return float(s[:-1]) * mult if mult else float(s) / 1024
    except ValueError:
        return 0


def sigquit_dump(sess):
    """SIGQUIT the app and collect the goroutine dump it prints on the way out.

    Go writes the dump to stderr, which MacSession merges into stdout, so it
    arrives on the same queue as the app's own lines."""
    import queue as _q
    import signal
    try:
        os.kill(sess.proc.pid, signal.SIGQUIT)
    except OSError:
        return []
    out = []
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            line = sess.q.get(timeout=max(0.1, deadline - time.monotonic()))
        except _q.Empty:
            break
        if line is None:
            break
        out.append(line)
    return out


def summarize_goroutines(dump):
    """Group the dump by CREATION SITE, which is what names a leak.

    A dump is hundreds of frames and unreadable as a list. What identifies a
    goroutine that never finishes is where it was started and what it is
    blocked on, and a leak shows up as one such pair with a large count —
    everything else appears once or twice."""
    sites = {}
    state = None
    pending_state = None
    for i, line in enumerate(dump):
        m = GOROUTINE.match(line)
        if m:
            pending_state = m.group(1)
            state = pending_state
            continue
        m = CREATED_BY.match(line)
        if m and state is not None:
            key = (m.group(1), state)
            sites[key] = sites.get(key, 0) + 1
            state = None
    return sorted(sites.items(), key=lambda kv: -kv[1])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rounds", type=int, default=200,
                    help="800ms rounds of idle frames (default 200 — fewer "
                         "than ~150 cannot contain a scavenger reclaim, so a "
                         "short run reads GROWING on the sawtooth's rising edge)")
    ap.add_argument("--windowed", action="store_true",
                    help="run the REAL windowed app (opens a window) instead "
                         "of the headless harness — the shape the owner's "
                         "26 GB happened in. No REPL, so rounds are wall time")
    ap.add_argument("--heap", action="store_true",
                    help="dump heap profiles during the run and print what "
                         "GREW between the first and last — names the site")
    ap.add_argument("--vmmap", action="store_true",
                    help="vmmap the app early and late and diff the regions — "
                         "says WHICH mapping grows, which separates thread "
                         "stacks from a malloc zone")
    ap.add_argument("--goroutines", action="store_true",
                    help="SIGQUIT the app at the end and group the dump by "
                         "creation site — names the leaking goroutine directly")
    args = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        sgo, mac_bin, _tui, server_bin, env = ms.build_env()
        ms.build(sgo, env)
        proc, log = ms.start_server(server_bin, os.path.join(tmp, "server.log"), env)
        if proc is None:
            sys.exit("mac-leak: server never became ready")
        sess = None
        rss = []
        dump = []
        vm_early = {}
        vm_late = {}
        heap_report = []
        try:
            extra = {
                "GODEBUG": "gctrace=1,schedtrace=4000",
                "WATA_MAC_CONFIG": os.path.join(tmp, "config.json"),
            }
            # SIGQUIT prints only the signalled goroutine unless told otherwise,
            # and the whole question here is the OTHER ones.
            if args.goroutines:
                extra["GOTRACEBACK"] = "all"
            if args.heap:
                extra["WATA_MAC_HEAP_PROFILE"] = os.path.join(tmp, "heap")
                extra["WATA_MAC_HEAP_EVERY"] = "20"
            if args.windowed:
                # The real app: NSApplication.run, a window, a drained main
                # queue. It has no REPL, so a round is wall time rather than a
                # driven frame — and the frames are the runloop's own, which is
                # the point. MacSession sets HEADLESS=1; "" turns it back off.
                extra["WATA_MAC_HEADLESS"] = ""
            sess = ms.MacSession(mac_bin, env, extra_env=extra)
            if not args.windowed:
                sess.read_until(
                    lambda l: l in ("ready @alice:localhost", "login failed"), 60)
            else:
                # let it get through login and put a window up before round 0
                time.sleep(12)
                if sess.proc.poll() is not None:
                    sys.exit("mac-leak: the windowed app exited during startup; "
                             "its output:\n  " + "\n  ".join(drain(sess)[-20:]))
            for i in range(args.rounds):
                if args.windowed:
                    time.sleep(0.8)
                    drain(sess)
                else:
                    sess.cmd("wait 800", lambda l: l == "waited 800", timeout=30)
                rss.append(rss_kb(sess.proc.pid))
                # a few rounds in, so startup's own allocation is not counted
                # as growth; and at the end, before anything is torn down.
                if args.vmmap and i == 4:
                    vm_early = vmmap_regions(sess.proc.pid)
                if args.vmmap and i == args.rounds - 1:
                    vm_late = vmmap_regions(sess.proc.pid)
            if args.heap:
                heap_report = heap_growth(tmp, mac_bin)
            if args.goroutines:
                dump = sigquit_dump(sess)
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
            print(f"  RSS          {rss[0] / 1024:.1f} MB -> {rss[-1] / 1024:.1f} MB"
                  f"   (peak {max(rss) / 1024:.1f}, trough after peak "
                  f"{min(rss[rss.index(max(rss)):]) / 1024:.1f})")
        if heaps:
            print(f"  Go live heap {heaps[0][1]} MB -> {heaps[-1][1]} MB "
                  f"({len(heaps)} GCs)")
            # The series, not just the endpoints: a bounded working set
            # plateaus, a leak keeps climbing, and first-vs-last cannot tell
            # them apart. This is the number that matters most here — RSS is a
            # sawtooth the scavenger drives, but live heap AFTER a collection
            # is what the app is genuinely holding on to.
            hstep = max(1, len(heaps) // 10)
            print("  live heap after each GC (MB): " + " ".join(
                str(h[1]) for h in heaps[::hstep]))
        else:
            print("  Go live heap  (no GC ran — the Go heap is not the growth)")
        if threads:
            print(f"  OS threads   {threads[0][1]} -> {threads[-1][1]}")

        # RSS here is a SAWTOOTH: it climbs for a couple of minutes and then
        # the scavenger hands pages back, dropping it to roughly where it
        # started. So first-vs-last over a short run measures whichever edge
        # the run happened to land on — 45 rounds sit entirely on a rising
        # edge and read GROWING every time. What distinguishes a leak from the
        # sawtooth is whether the TROUGHS rise, so compare the low-water mark
        # of the first quarter against that of the last, and say plainly when
        # the run was too short to contain a reclaim at all.
        # The Go live heap gets the first word, because it is the only number
        # here that a collection has already filtered: if it keeps climbing
        # across many GCs, the app is holding objects, full stop. An earlier
        # version of this verdict looked only at RSS and printed "sawtooth,
        # not a leak" over a run whose live heap went 1 MB -> 35 MB.
        verdict = "steady"
        note = ""
        leaking = ""
        if len(heaps) >= 6:
            early = min(h[1] for h in heaps[:len(heaps) // 3])
            late = min(h[1] for h in heaps[-len(heaps) // 3:])
            if late - early >= 4:
                leaking = (f"  (live heap after GC climbs {early} MB -> "
                           f"{late} MB across {len(heaps)} collections — a "
                           f"collection already removed everything "
                           f"unreachable, so this is retention)")
        if leaking:
            verdict, note = "LEAKING (Go heap)", leaking
        elif rss:
            q = max(1, len(rss) // 4)
            base, tail = min(rss[:q]), min(rss[-q:])
            reclaimed = max(rss) - min(rss[rss.index(max(rss)):]) > 2048
            if tail - base > 2048:
                verdict = "GROWING"
                note = (f"  (low-water {base / 1024:.1f} MB -> "
                        f"{tail / 1024:.1f} MB: the troughs rise)")
            elif not reclaimed:
                verdict = "INCONCLUSIVE"
                note = ("  (no reclaim happened in this run — RSS only ever "
                        "climbed, which is what a rising edge looks like too. "
                        "Run more rounds: the sawtooth's period is minutes.)")
            else:
                note = (f"  (peak {max(rss) / 1024:.1f} MB was reclaimed and "
                        f"the troughs did not rise — sawtooth, not a leak)")
        print("  VERDICT:", verdict)
        if note:
            print(note)

        if args.heap:
            print("\n== Go heap: what GREW between the first and last profile ==")
            for line in heap_report:
                print("  " + line)

        if args.vmmap:
            print(f"\n== dirty memory by region, round 4 -> {args.rounds - 1} ==")
            if not vm_late:
                print("  (vmmap produced nothing — it needs the app to still "
                      "be running, and may prompt for developer-tool access)")
            deltas = sorted(
                ((name, vm_late.get(name, 0) - vm_early.get(name, 0))
                 for name in set(vm_early) | set(vm_late)),
                key=lambda kv: -abs(kv[1]))
            for name, d in deltas[:12]:
                if abs(d) < 16:
                    continue
                print(f"  {d:+10.0f} KB  {name}")
            total_d = sum(d for _, d in deltas)
            print(f"  {total_d:+10.0f} KB  (all regions)")

        if args.goroutines:
            total = sum(1 for l in dump if GOROUTINE.match(l))
            print(f"\n== goroutines at the end of the run: {total} ==")
            if not total:
                print(f"  (no goroutines parsed out of {len(dump)} lines of "
                      f"output — if that count is large the dump arrived and "
                      f"this parser is what failed, not the probe)")
            for (site, state), n in summarize_goroutines(dump)[:15]:
                print(f"  {n:4d}  [{state}]  created by {site}")
            print("\n  A leak is a creation site with a count far above the "
                  "others.\n  Counts of 1-2 are the app's ordinary long-lived "
                  "goroutines.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
