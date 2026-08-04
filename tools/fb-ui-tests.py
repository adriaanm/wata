#!/usr/bin/env python3
"""The wata-fb UI golden oracle: scripted runs of the REAL frame loop.

    tools/fb-ui-tests.py                # every scenario, scoreboard
    tools/fb-ui-tests.py <scenario>     # one scenario
    tools/fb-ui-tests.py --update       # rewrite the goldens (review the diff!)

Each scenario is a sequence of PHASES — one `wata-fb uitest` run per user, in
order, against ONE freshly started wata-server. Sequential phases rather than
concurrent clients because wataclient's Runtime is a single-client-per-process
engine; the server is what carries state between them, exactly as
tools/wataclient-integ.sh does it. A fresh server per scenario is what keeps the
assertions independent (the server keeps state in memory for a whole run).

Every phase script (tools/fb-ui-scripts/) dumps PNG checkpoints of the live
160x128 pixel buffer through the same deterministic encoder `fbdump` uses. This
compares them byte-for-byte against tools/fb-ui-golden/. The goldens are
reviewed baselines: regenerate with --update and eyeball the images.

Hermetic — no device, no network beyond localhost.
"""

import os
import random
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(WATA, "tools", "fb-ui-scripts")
GOLDEN = os.path.join(WATA, "tools", "fb-ui-golden")
# A RANDOM port per run (FB_UI_PORT overrides): a fixed port collides with a
# sibling checkout running this same harness — whichever server binds second
# dies, and the survivor serves BOTH harnesses' phases, so redactions/sends
# from the foreign run corrupt this run's message counts mid-scenario.
PORT = int(os.environ.get("FB_UI_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"
PASSWORD = "testpass123"

# A scenario: a name and the phases to run in order, each (user, script file).
# A phase user of "-" means "start with no credentials": the driver is handed
# `-` in every credential slot and has to resume the session the config store
# holds. Each scenario gets its own config store (see run_scenario), so a
# resume phase can only see what an earlier phase of the same scenario wrote.
SCENARIOS = [
    {
        "name": "voice-alice-to-bob",
        "phases": [
            ("alice", "alice-send.txt"),
            ("bob", "bob-view.txt"),
        ],
    },
    {
        "name": "conversation-actions",
        "phases": [
            ("alice", "alice-convo.txt"),
            ("bob", "bob-play.txt"),
        ],
    },
    {
        "name": "settings-walk",
        "phases": [
            ("alice", "alice-settings.txt"),
            ("-", "alice-settings-restored.txt"),
        ],
    },
    {
        "name": "session-resume",
        "phases": [
            ("alice", "alice-login.txt"),
            ("-", "alice-resume.txt"),
        ],
    },
]


# ---- the sgo build environment -------------------------------------------------
# tools/sgo-env.sh + tools/emitdir.sh are the single source of truth for the
# toolchain and the emit layout; ask them rather than re-deriving either here.
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-fb)/$(binname wata-fb)" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("fb-ui-tests: sgo environment probe failed:\n" + out.stderr)
    sgo, fb, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, fb, server, env


def build(sgo, env):
    for module in ("wata-server", "wata-fb"):
        r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, module),
                           capture_output=True, text=True, env=env)
        if r.returncode != 0:
            sys.exit(f"fb-ui-tests: {module} build failed:\n{r.stdout}{r.stderr}")


# ---- the hermetic server -----------------------------------------------------
def our_listener(pid):
    """True when `pid` is the process listening on PORT. A wata-server that
    lost a bind race exits ZERO (the subset has no os.Exit facade), and a
    foreign squatter on the port would answer a bare readiness probe — the
    listener's identity is the only trustworthy readiness signal."""
    r = subprocess.run(["lsof", "-ti", f"tcp:{PORT}", "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return str(pid) in r.stdout.split()


def start_server(binary, log_path, env):
    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, f":{PORT}"], stdout=log, stderr=log, env=env)
    for _ in range(200):
        if proc.poll() is not None:
            return None, log
        if our_listener(proc.pid):
            try:
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions", timeout=0.5).read()
                return proc, log
            except (urllib.error.URLError, OSError):
                pass
        time.sleep(0.1)
    proc.kill()
    return None, log


def stop_server(proc, log):
    if proc is not None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
    log.close()


# ---- one scenario --------------------------------------------------------------
def run_scenario(scenario, fb, server_bin, env, outdir, update):
    """Returns (ok, message). Frames land in outdir; goldens are compared here."""
    logs = os.path.join(outdir, f"server-{scenario['name']}.log")
    proc, log = start_server(server_bin, logs, env)
    if proc is None:
        stop_server(proc, log)
        return False, "server never became ready"
    # Every scenario gets its own session store, so a run never reads or writes
    # the operator's real /etc/wata/config.json and phases only see each other.
    env = dict(env, WATA_FB_CONFIG=os.path.join(outdir, "config.json"))
    try:
        for user, script in scenario["phases"]:
            path = os.path.join(SCRIPTS, script)
            password = PASSWORD if user != "-" else "-"
            r = subprocess.run(
                [fb, "uitest", path, BASE, user, password, outdir],
                capture_output=True, text=True, env=env, timeout=300)
            tail = (r.stdout + r.stderr).strip().splitlines()
            passed = any(line.startswith("UITEST PASS") for line in tail)
            if not passed:
                return False, f"phase {user}/{script}: " + " | ".join(tail[-6:])
    finally:
        stop_server(proc, log)
    return compare(scenario, outdir, update)


def compare(scenario, outdir, update):
    """Byte-compare every checkpoint this scenario produced against its golden."""
    produced = sorted(f for f in os.listdir(outdir) if f.endswith(".png"))
    if not produced:
        return False, "no checkpoints produced"
    bad = []
    for name in produced:
        got = os.path.join(outdir, name)
        want = os.path.join(GOLDEN, name)
        if update:
            os.makedirs(GOLDEN, exist_ok=True)
            shutil.copyfile(got, want)
            continue
        if not os.path.exists(want):
            bad.append(f"{name}: no golden (regenerate with --update, then review)")
        elif open(got, "rb").read() != open(want, "rb").read():
            bad.append(f"{name}: MISMATCH vs {os.path.relpath(want, WATA)}")
    if bad:
        return False, "; ".join(bad)
    verb = "updated" if update else "byte-identical"
    return True, f"{len(produced)} checkpoints {verb}"


def main():
    args = [a for a in sys.argv[1:]]
    update = "--update" in args
    args = [a for a in args if a != "--update"]
    only = args[0] if args else None

    sgo, fb, server_bin, env = build_env()
    build(sgo, env)

    rows, failed = [], 0
    tmp = tempfile.mkdtemp(prefix="fb-ui-tests.")
    try:
        for scenario in SCENARIOS:
            if only and scenario["name"] != only:
                continue
            outdir = os.path.join(tmp, scenario["name"])
            os.makedirs(outdir, exist_ok=True)
            t0 = time.time()
            ok, msg = run_scenario(scenario, fb, server_bin, env, outdir, update)
            dt = time.time() - t0
            rows.append(("ok   " if ok else "FAIL ", scenario["name"], f"{dt:.0f}s", msg))
            if not ok:
                failed += 1
    finally:
        if failed == 0 and not os.environ.get("FB_UI_KEEP"):
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            print(f"\nfb-ui-tests: frames kept at {tmp}")

    print("\n==== wata-fb UI golden oracle (fresh wata-server per scenario) ====")
    for status, name, dt, msg in rows:
        print(f"  {status} {name} ({dt})  {msg}")
    print(f"  {len(rows) - failed} passed, {failed} failed")
    if failed:
        sys.exit(1)
    print("fb-ui-tests: ALL GREEN")


if __name__ == "__main__":
    main()
