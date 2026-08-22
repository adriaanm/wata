#!/usr/bin/env python3
"""`just watch-e2e` — plan 0069's stage-2 gate: wata-watch booted on the watch
simulator against a live wata-server, logging in, RECEIVING and SENDING.

tools/ios-smoke.py's twin, and deliberately the same shape: one fresh
wata-server on a random localhost port (the simulator shares the host's
loopback), the app launched on the shared Series 10 device with alice's
credentials in the environment, and then bob sends a voice message from a
host-side wata-tui once the contact list has painted — so the message
arrives MID-SESSION, through sync, rather than in the first snapshot.

The assertion surface is the app's own printed lines (WatchKit owns the
process exit, the interptest rule):

    metrics: wrist 208x248pt … grid=…  the stage was sized from the panel
                                        the device reported, and the grid
                                        and type derived from it
    screen boot / paint boot lit=<n>    the boot screen painted BEFORE the
                                        session connects, counted by the
                                        offscreen render probe on the live
                                        stage
    ready @alice:localhost              the login round-trip succeeded
    screen contacts / paint contacts    the post-login applet painted
    notify: noted "…" … badge=<n>       bob's message arrived over sync
    send: complete                      the watch RECORDED and SENT one of
                                        its own, from a held talk button
                                        (watchshell's ScriptIntents — the
                                        simulator cannot synthesize a long
                                        press on a UIKit recognizer)

and then, server-side, bob snapshots the family room and must see BOTH
messages. That last check is the one that cannot be faked by the client
believing its own send: `send: complete` is the app's word for it, and the
snapshot is the server's.

What this proves beyond watch-smoke and watch-interptest: the WHOLE client
runs on the watch. Not the stage, not the differ, not a probe — a real
Matrix session, from login through sync to an arrival, in Sgola, on a
platform Go does not target.

Environment the app reads (simctl forwards SIMCTL_CHILD_*): the same
WATA_IOS_* names the phone uses. They are not renamed because the files
that read them are wata-ios's, copied unchanged; renaming them would fork
the port for no gain.

Audio runs with macaudio's fake hardware ends (WATA_MAC_AUDIO=fake), so no
microphone prompt can block the harness — the REAL mic is proven separately
by `just watch-spike --only audio`.

Needs Xcode + a watchOS simulator runtime; not in ci (mac-smoke's posture).
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
import watchrun  # noqa: E402

REPO = HERE.parent
EMIT = REPO / "wata-watch" / ".sgo" / "wata-watch"
APP = EMIT / "WataWatchE2E.app"
BUNDLE_ID = "net.wa-ta.watche2e"
BIN = "wata-watch-sim"
PASSWORD = "testpass123"
PORT = int(os.environ.get("WATCH_E2E_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

# NO ^/$ anchors: launch_and_expect searches the JOINED output without
# MULTILINE, and console-pty lines can carry a stray \r.
EXPECT = [
    # the stage was built from a panel the device REPORTED, not from a
    # constant: a wrist, a non-zero size in points, and a grid derived from
    # it (plan 0071 step 2). A regression to a fixed 160x128 stage cannot
    # print this line.
    r"metrics: wrist [1-9][0-9.]*x[1-9][0-9.]*pt .* grid=[1-9][0-9]*x[1-9]",
    r"screen boot",
    r"paint boot lit=[1-9]",
    r"ready @alice:localhost",
    r"screen contacts",
    r"paint contacts lit=[1-9]",
    simrun.NOTED_RE,
    r"send: complete",
]
# The SEND is the terminator, not the arrival: it is scripted to happen after
# the contact list paints, so it is the last thing to land.
DONE_RES = (r"send: complete", r"send: failed", r"login failed", r"rejected",
            r"wata-watch: ")


def build_env():
    probe = (
        f'set -e; cd "{REPO}"; WATA="{REPO}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-server)/$(binname wata-server)" '
        '"$(emitdir wata-tui)/$(binname wata-tui)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("watch-e2e: sgo environment probe failed:\n" + out.stderr)
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


def family_msgs(tui_bin, env, base):
    """bob snapshots the family conversation and answers its message count.
    The server's word on what actually landed, as against the app's own
    `send: complete` — a client that thinks it sent and a server that has
    the message are different claims."""
    senv = dict(env, WATA_TUI_HS=base, WATA_TUI_USER="bob",
                WATA_TUI_PASS=PASSWORD)
    r = subprocess.run([str(tui_bin)], input="snap\nquit\n", text=True,
                       capture_output=True, env=senv, timeout=120)
    for line in r.stdout.split("\n"):
        # `conv 1 family - !room:host msgs=2 unplayed=1`
        if line.startswith("conv 1 family") and "msgs=" in line:
            for tok in line.split():
                if tok.startswith("msgs="):
                    return int(tok[len("msgs="):])
    return -1


def build_app(env):
    """sgo emits and native-builds, then the emitted module cross-builds for
    the watch simulator and is bundled — watch-interptest's two steps, with
    this gate's own bundle id so the two apps do not share a container."""
    import iosenv
    watchrun.run([str(REPO / "tools" / "sgo"), "build"],
                 cwd=REPO / "wata-watch", env=env)
    watchrun.run(["go", "build", "-ldflags=-w", "-o", str(EMIT / BIN), "."],
                 cwd=EMIT, env=iosenv.go_env("watchsimulator"))
    watchrun.bundle(APP, EMIT / BIN, BUNDLE_ID,
                    delegate_class="WataWatchDelegate")


def main():
    if sys.platform != "darwin":
        sys.exit("watch-e2e: macOS only")
    sgo, server_bin, tui_bin, env = build_env()

    for module in ("wata-server", "wata-tui"):
        r = subprocess.run([sgo, "build"], cwd=REPO / module, env=env)
        if r.returncode != 0:
            sys.exit(f"watch-e2e: {module} build failed")
    build_app(env)

    proc, log = start_server(server_bin, str(EMIT / "e2e-server.log"), env)
    if proc is None:
        sys.exit("watch-e2e: server never became ready")

    os.environ["SIMCTL_CHILD_WATA_IOS_HS"] = BASE
    os.environ["SIMCTL_CHILD_WATA_IOS_USER"] = "alice"
    os.environ["SIMCTL_CHILD_WATA_IOS_PASS"] = PASSWORD
    os.environ["SIMCTL_CHILD_WATA_MAC_AUDIO"] = "fake"
    # The SEND leg's finger: hold the talk button from t=6s for 1.5s, by which
    # time the contact list has painted and the family row is selected. The
    # simulator cannot synthesize a long press on a UIKit recognizer, so this
    # rides watchshell's ScriptIntents onto the same queue the gestures use.
    # It proves the send arc above the recognizer and nothing about whether a
    # real long press is DELIVERED — that is WATCH-INPUT-DELIVERY, and only a
    # wrist settles it.
    # $WATCH_E2E_INTENTS overrides the script — a way to drive the OTHER
    # intents (`down@5000,down:3@5300,talk@6000+1500` shows a nudge and a
    # flick reaching the app with different magnitudes) without editing this
    # file. It is a DEBUG seam, not a second gate: navigating moves the
    # selection off the family row, so the talk hold then sends somewhere
    # else and the server-side family check fails the run — which is the
    # check working, not a defect.
    os.environ["SIMCTL_CHILD_WATA_WATCH_SCRIPT_INTENTS"] = (
        os.environ.get("WATCH_E2E_INTENTS") or "talk@6000+1500")

    sent = {"ok": False, "at": None, "arrived": None}
    # The arrival line ENDS the run, and it lands before the sending tui has
    # exited — so the verdict waits on the sender's own thread before reading
    # `sent`, or a healthy run reports a failed sender.
    sent_done = threading.Event()

    def send_inbound():
        time.sleep(1.0)
        sent["ok"], sent["at"], _ = simrun.tui_send(tui_bin, env, BASE,
                                                    tag="watch-e2e")
        sent_done.set()

    def mark_arrival():
        sent["arrived"] = time.monotonic()

    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, done_res=DONE_RES, timeout=180,
            screenshot=EMIT / "e2e-screen.png", settle=2.0,
            on_match=[(r"paint contacts lit=", send_inbound),
                      (simrun.NOTED_RE, mark_arrival)])
        print(f"watch-e2e: launch-to-verdict {elapsed:.2f}s")
        sent_done.wait(180)
        if not sent["ok"]:
            print("watch-e2e: bob's tui never reported `sent` — the inbound "
                  "leg's SENDER failed, so a missing arrival proves nothing",
                  file=sys.stderr)
        fast = simrun.latency_ok("watch-e2e", sent["at"], sent["arrived"])
        if missing:
            for m in missing:
                print("watch-e2e: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        if not sent["ok"] or not fast:
            sys.exit(1)
        n = family_msgs(tui_bin, env, BASE)
        if n < 2:
            print(f"watch-e2e: the family room holds {n} messages, expected "
                  "2 (bob's and the watch's) — the watch printed `send: "
                  "complete` but the server does not have it",
                  file=sys.stderr)
            sys.exit(1)
        print(f"watch-e2e: server-side check — family room holds {n} messages")
        print("watch-e2e: PASS — wata-watch logged in, painted the contact "
              "list, saw bob's voice message arrive over sync, and SENT one "
              "of its own from a held talk button, on the watch simulator")
    finally:
        watchrun.shutdown(sc, udid)
        stop_server(proc, log)


if __name__ == "__main__":
    main()
