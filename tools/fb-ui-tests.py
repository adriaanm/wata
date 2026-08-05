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

import json
import os
import random
import shutil
import signal
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
# The admin base URL an enrolment QR encodes (the "iroh" scenarios). FIXED and
# deliberately not this run's server: the QR's module grid is a function of the
# exact bytes of this string, so a random port would make the frame unpinnable
# — and nothing in the gate may reach a real address.
ADMIN_URL = "http://192.168.1.4:8008"

# A scenario: a name and the phases to run in order, each (user, script file).
# A phase user of "-" means "start with no credentials": the driver is handed
# `-` in every credential slot and has to resume the session the config store
# holds. Each scenario gets its own config store (see run_scenario), so a
# resume phase can only see what an earlier phase of the same scenario wrote.
# Optional keys:
#   "users": ["alice", "bob", "charlie"] — write a $WATA_USERS accounts file
#       for this scenario's server (password PASSWORD, displayname the
#       capitalized localpart, matching the built-in alice/bob pair). Without
#       it the server boots its compiled-in two accounts.
#   "hooks": True — start the scenario's server with WATA_TEST_HOOKS=1 (the
#       fail-on-demand media hook; scripts arm it with the `failnext`
#       directive). Every scenario's server is probed for the hook route
#       right after readiness, so each run asserts the route EXISTS exactly
#       when the env var says so (404 otherwise — the production surface).
#   "password": "…" — what the phases log in with (default PASSWORD). A wrong
#       one is how the auth-rejected leg is provoked.
#   "late_server": <seconds> — start the phase with NO server, and boot one
#       that many seconds in. The client must survive the gap on its own and
#       proceed into a session when the server appears, with no restart (plan
#       0022): this is the boot-before-the-network case the device hits every
#       morning, and a server that moved or was down at boot.
#   "stop_server_after": <seconds> — SIGSTOP the server that far into the
#       phase: it keeps accepting (the kernel backlog does) and never answers,
#       which is the HUNG case a connection refusal does not exercise. Pair it
#       with "http_timeout_ms" so the per-request deadline fires inside the
#       test rather than 30s later.
#   "http_timeout_ms": <ms> — WATA_HTTP_TIMEOUT_MS for the phases.
#   "iroh": True — write a device iroh config for the phases and point
#       WATA_IROH_CONFIG + WATA_ADMIN_URL at it, i.e. run them as a handset
#       configured to speak iroh (plan 0014). That is what turns the Enroll
#       settings row on and what gives the enrolment QR an admin URL to
#       encode; the URL is a FIXED unroutable one so the frame is a golden and
#       nothing in the gate touches a real network. The config carries no
#       secretKey on purpose — a device mints its own, and in this stub-
#       transport build that mint fails loudly rather than inventing a key,
#       which is why the scripts pin the identity with `enrolid`.
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
        "name": "dm-roundtrip",
        "phases": [
            ("bob", "bob-family-bootstrap.txt"),
            ("alice", "alice-dm-send.txt"),
            ("bob", "bob-dm-roundtrip.txt"),
            ("alice", "alice-dm-verify.txt"),
        ],
    },
    {
        "name": "family-three",
        "users": ["alice", "bob", "charlie"],
        "phases": [
            ("alice", "family3-alice.txt"),
            ("bob", "family3-bob.txt"),
            ("charlie", "family3-charlie.txt"),
            ("alice", "family3-alice2.txt"),
        ],
    },
    {
        "name": "badges-across-restart",
        "phases": [
            ("bob", "bob-family-bootstrap.txt"),
            ("alice", "alice-badges-send.txt"),
            ("bob", "bob-badges-view.txt"),
            ("-", "bob-badges-resume.txt"),
        ],
    },
    {
        "name": "send-play-failed",
        "hooks": True,
        "phases": [
            ("alice", "alice-fail.txt"),
        ],
    },
    {
        "name": "outbox-restart",
        "hooks": True,
        "phases": [
            ("alice", "alice-outbox-queue.txt"),
            ("-", "alice-outbox-deliver.txt"),
        ],
    },
    {
        "name": "playing-hung",
        "stop_server_after": 6.0,
        "http_timeout_ms": 1500,
        "phases": [
            ("alice", "alice-play-hung.txt"),
        ],
    },
    {
        "name": "early-boot",
        "phases": [
            ("alice", "alice-boot.txt"),
        ],
    },
    {
        "name": "conn-status",
        "phases": [
            ("alice", "alice-conn-status.txt"),
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
    {
        "name": "snake",
        "phases": [
            ("alice", "alice-snake.txt"),
        ],
    },
    {
        "name": "boot-retry",
        "late_server": 4.0,
        "phases": [
            ("alice", "alice-boot-retry.txt"),
        ],
    },
    {
        "name": "auth-rejected",
        "password": "not-the-password",
        "phases": [
            ("alice", "alice-auth-rejected.txt"),
        ],
    },
    {
        "name": "hung-server",
        "stop_server_after": 6.0,
        "http_timeout_ms": 1500,
        "phases": [
            ("alice", "alice-hung-server.txt"),
        ],
    },
    {
        # The wrong password keeps the session from ever going live, which is
        # what keeps the wata applet on the boot screen the QR takes over.
        "name": "enroll",
        "iroh": True,
        "password": "not-the-password",
        "phases": [
            ("alice", "alice-enroll.txt"),
        ],
    },
    {
        "name": "disconnect-quit",
        "phases": [
            ("alice", "alice-disconnect-quit.txt"),
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


def hook_gate_status():
    """POST the test-hook route; returns the HTTP status (the route answers
    200 when registered, and falls through to the Matrix 404 catch-all when
    the server was started without WATA_TEST_HOOKS=1)."""
    req = urllib.request.Request(
        f"{BASE}/_wata/v1/test/fail", data=b'{"count": 0}',
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def stop_server(proc, log):
    if proc is not None:
        # a SIGSTOP'd server (the hung-server leg) ignores SIGTERM until it
        # runs again, so wake it first.
        try:
            proc.send_signal(signal.SIGCONT)
        except OSError:
            pass
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
    hooks = bool(scenario.get("hooks"))
    server_env = dict(env)
    if hooks:
        server_env["WATA_TEST_HOOKS"] = "1"
    users = scenario.get("users")
    if users:
        upath = os.path.join(outdir, "users.json")
        with open(upath, "w") as f:
            json.dump([{"user": u, "password": PASSWORD,
                        "displayname": u.capitalize()} for u in users], f)
        server_env["WATA_USERS"] = upath
    late = scenario.get("late_server")
    # `srv` is a one-slot holder so a timer firing mid-phase (the late start)
    # can replace what the teardown has to stop.
    srv = {"proc": None, "log": None, "err": None}
    if late is None:
        srv["proc"], srv["log"] = start_server(server_bin, logs, server_env)
        if srv["proc"] is None:
            stop_server(srv["proc"], srv["log"])
            return False, "server never became ready"
        gate = check_hook_gate(hooks)
        if gate is not None:
            stop_server(srv["proc"], srv["log"])
            return False, gate
    # Every scenario gets its own session store, so a run never reads or writes
    # the operator's real /etc/wata/config.json and phases only see each other.
    env = dict(env, WATA_FB_CONFIG=os.path.join(outdir, "config.json"))
    if scenario.get("iroh"):
        ipath = os.path.join(outdir, "iroh.json")
        with open(ipath, "w") as f:
            json.dump({"peer": "b" * 64, "relay": "none",
                       "adminUrl": ADMIN_URL}, f)
        env["WATA_IROH_CONFIG"] = ipath
        env["WATA_ADMIN_URL"] = ADMIN_URL
    if scenario.get("http_timeout_ms"):
        env["WATA_HTTP_TIMEOUT_MS"] = str(scenario["http_timeout_ms"])
    password0 = scenario.get("password", PASSWORD)
    try:
        for phase in scenario["phases"]:
            user, script = phase[0], phase[1]
            path = os.path.join(SCRIPTS, script)
            password = password0 if user != "-" else "-"
            tail = run_phase(fb, path, user, password, outdir, env,
                             phase_timers(scenario, srv, server_bin, logs, server_env, hooks))
            if srv["err"]:
                return False, srv["err"]
            passed = any(line.startswith("UITEST PASS") for line in tail)
            if not passed:
                return False, f"phase {user}/{script}: " + " | ".join(tail[-6:])
    finally:
        stop_server(srv["proc"], srv["log"])
    return compare(scenario, outdir, update)


def check_hook_gate(hooks):
    """The test-hook gate, asserted on EVERY server this harness boots: the
    route exists exactly when the scenario opted into WATA_TEST_HOOKS=1.
    Returns None when it holds, else the failure message."""
    want = 200 if hooks else 404
    got = hook_gate_status()
    if got != want:
        return f"test-hook gate: POST /_wata/v1/test/fail = {got}, want {want}"
    return None


def phase_timers(scenario, srv, server_bin, logs, server_env, hooks):
    """The things that happen TO the server while a phase is running: a late
    start, or a SIGSTOP that turns it into a hung peer. Each is (delay, fn)."""
    out = []
    late = scenario.get("late_server")
    if late is not None:
        def start_late():
            srv["proc"], srv["log"] = start_server(server_bin, logs, server_env)
            if srv["proc"] is None:
                srv["err"] = "late server never became ready"
                return
            srv["err"] = check_hook_gate(hooks)
        out.append((late, start_late))
    stop_after = scenario.get("stop_server_after")
    if stop_after is not None:
        def stop_it():
            if srv["proc"] is not None:
                srv["proc"].send_signal(signal.SIGSTOP)
        out.append((stop_after, stop_it))
    return out


def run_phase(fb, path, user, password, outdir, env, timers):
    """One `wata-fb uitest` run, with the scenario's server timers firing
    against the wall clock while it runs. Returns its output lines."""
    # output goes to a file, not a pipe: this driver polls the process rather
    # than blocking on a read, and a full pipe buffer would deadlock it.
    outfile = os.path.join(outdir, "phase.log")
    with open(outfile, "w") as fh:
        proc = subprocess.Popen(
            [fb, "uitest", path, BASE, user, password, outdir],
            stdout=fh, stderr=subprocess.STDOUT, text=True, env=env)
        t0 = time.time()
        pending = sorted(timers, key=lambda t: t[0])
        while proc.poll() is None and time.time() - t0 < 300:
            while pending and time.time() - t0 >= pending[0][0]:
                pending.pop(0)[1]()
            time.sleep(0.05)
        if proc.poll() is None:
            proc.kill()
            return ["phase timed out after 300s"]
    return open(outfile).read().strip().splitlines()


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
