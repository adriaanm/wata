#!/usr/bin/env python3
"""`just ios-smoke` — the plan-0044 stage-4 gate: wata-ios booted in the
simulator against a live wata-server.

One fresh wata-server on a random localhost port (the simulator shares the
host's loopback), then the app launched on the shared simulator device with
alice's credentials in the environment (`SIMCTL_CHILD_*` is how simctl
hands env vars to the app). The assertion surface is the app's own printed
lines (launchd owns the process exit, the interptest rule):

    screen boot / paint boot lit=<n>      the boot screen ("starting up...")
                                          painted BEFORE the session connects
                                          — the ready hop draws it, and the
                                          offscreen render probe counts lit
                                          pixels on the live stage
    ready @alice:localhost                the login round-trip succeeded
    screen contacts / paint contacts …    the post-login applet (the contact
                                          list) painted — plan 0044's "the
                                          login applet paints", the applet a
                                          login lands on
    notify: noted "…" … badge=<n>         INBOUND (plan 0065): once the
                                          contact list has painted, bob sends
                                          a voice message into the family room
                                          from a host-side wata-tui, and the
                                          app's sync must see it arrive. The
                                          default notify mode is QUIET and iOS
                                          has no banner, so this printed
                                          decision line is the whole arrival
                                          surface — assert it, not a sound.
                                          The leg is TIMED: the delay from
                                          bob's `sent` to that line must stay
                                          inside simrun.LATENCY_BUDGET_S, so a
                                          delivery that slid from seconds to a
                                          minute fails the gate instead of
                                          passing it as "eventually".

Modelled on tools/mac-smoke.py's server handling; the simulator legs ride
tools/simrun.py (shared device, verdict-keyed retry) and the build reuses
`ios-interptest --only build --only bundle` (same app bundle, no argv).

Needs Xcode + an iOS simulator runtime; not in ci (mac-smoke's posture).
"""

import os
import pathlib
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import simrun  # noqa: E402

REPO = HERE.parent
APP = REPO / "wata-ios" / ".sgo" / "wata-ios" / "WataIos.app"
BUNDLE_ID = "net.wa-ta.ios"
PASSWORD = "testpass123"
PORT = int(os.environ.get("IOS_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

# NO ^/$ anchors: launch_and_expect searches the JOINED output without
# MULTILINE, and console-pty lines can carry a stray \r.
EXPECT = [
    r"screen boot",
    r"paint boot lit=[1-9]",
    r"ready @alice:localhost",
    r"screen contacts",
    r"paint contacts lit=[1-9]",
    simrun.NOTED_RE,
]
# the arrival is the success terminator; the failure lines end the run early so
# a broken login does not sit out the watchdog.
DONE_RES = (simrun.NOTED_RE, r"login failed", r"rejected", r"wata-ios: ")


def build_env():
    """the sgo environment + the server binary path (mac-smoke's probe)."""
    probe = (
        f'set -e; cd "{REPO}"; WATA="{REPO}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-server)/$(binname wata-server)" '
        '"$(emitdir wata-tui)/$(binname wata-tui)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("ios-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, server, tui, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, tui, env


def our_listener(pid):
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
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions",
                                       timeout=0.5).read()
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


def main():
    if sys.platform != "darwin":
        sys.exit("ios-smoke: macOS only")
    sgo, server_bin, tui_bin, env = build_env()

    for module in ("wata-server", "wata-tui"):
        r = subprocess.run([sgo, "build"], cwd=REPO / module, env=env)
        if r.returncode != 0:
            sys.exit(f"ios-smoke: {module} build failed")
    r = subprocess.run([str(HERE / "ios-interptest.py"),
                        "--only", "build", "--only", "bundle"])
    if r.returncode != 0:
        sys.exit("ios-smoke: wata-ios build/bundle failed")

    proc, log = start_server(server_bin, str(REPO / "wata-ios" / ".sgo" /
                                             "wata-ios" / "smoke-server.log"), env)
    if proc is None:
        sys.exit("ios-smoke: server never became ready")

    # simctl hands SIMCTL_CHILD_* through to the launched app's environment.
    os.environ["SIMCTL_CHILD_WATA_IOS_HS"] = BASE
    os.environ["SIMCTL_CHILD_WATA_IOS_USER"] = "alice"
    os.environ["SIMCTL_CHILD_WATA_IOS_PASS"] = PASSWORD
    # the REAL audio thread with macaudio's fake hardware ends (plan 0063):
    # no mic-permission prompt can block a harness, and the gate proves the
    # thread links, starts, and pumps on iOS
    os.environ["SIMCTL_CHILD_WATA_MAC_AUDIO"] = "fake"

    sent = {"ok": False, "at": None, "arrived": None}
    # the arrival line ENDS the run, and it lands before the sending tui has
    # exited — so the verdict must wait for the sender's own thread before
    # reading `sent`, or a healthy run reports a failed sender.
    sent_done = threading.Event()

    def send_inbound():
        """bob's half of the inbound leg, fired once the contact list has
        painted — by then the app is logged in and its sync is running, so the
        message really does arrive MID-SESSION rather than in the first
        snapshot (which announces nothing, by design)."""
        time.sleep(1.0)
        sent["ok"], sent["at"], _ = simrun.tui_send(tui_bin, env, BASE,
                                                    tag="ios-smoke")
        sent_done.set()

    def mark_arrival():
        sent["arrived"] = time.monotonic()

    sc = simrun.simctl()
    udid = simrun.ensure_device(sc)
    try:
        lines, elapsed, missing = simrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, done_res=DONE_RES, timeout=120,
            screenshot=APP.parent / "smoke-screen.png",
            on_match=[(r"paint contacts lit=", send_inbound),
                      (simrun.NOTED_RE, mark_arrival)])
        print(f"ios-smoke: launch-to-verdict {elapsed:.2f}s")
        sent_done.wait(180)
        if not sent["ok"]:
            print("ios-smoke: bob's tui never reported `sent` — the inbound "
                  "leg's SENDER failed, so a missing arrival proves nothing",
                  file=sys.stderr)
        fast = simrun.latency_ok("ios-smoke", sent["at"], sent["arrived"])
        if missing:
            for m in missing:
                print("ios-smoke: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        if not sent["ok"] or not fast:
            sys.exit(1)
        print("ios-smoke: PASS — wata-ios booted in the simulator, painted "
              "the boot screen, logged in, painted the contact list and saw "
              "bob's voice message arrive over sync")
    finally:
        simrun.shutdown(sc, udid)
        stop_server(proc, log)


if __name__ == "__main__":
    main()
