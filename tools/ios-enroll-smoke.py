#!/usr/bin/env python3
"""`just ios-enroll-smoke` — plan 0062's simulator gate: the whole enrol
flow, from a fresh install to a device-logged-in contact list, with no
password anywhere.

One iroh-mode wata-server on the host (TCP admin on localhost — the
simulator shares the host's loopback), the app launched with a FRESH
sandbox config dir and no credentials, then the harness plays the two
outside parties:

  the admin page's "Add this phone" link   simctl openurl with the
                                           wata://configure URL composed
                                           from GET /_wata/v1/enroll/server
                                           (the same card the page reads)
  the approving owner                      POST …/enroll/{id}/approve with
                                           {"user": "phone"} once the app's
                                           announce parks its pending row

Asserted, in order, off the app's own printed lines: the setup screen, the
configure claim + session restart, the not-allowlisted announce landing
200, then `ready @phone:` (device-login — no password exists for the
account by construction), the painted contact list, and finally INBOUND
(plan 0065): bob sends a voice message into the family room from a
host-side wata-tui over the server's plain-TCP admin listener, and the
app — whose own transport is IROH, the one the phone actually runs — must
print the `notify: noted` arrival within simrun's latency budget. That
last leg is the transport half of plan 0065's measurement: `ios-smoke`
runs the same leg over plain HTTP, so the pair says what the iroh
transport costs a message's delivery under the sync long-poll — the
reported symptom is delay, not loss, and a gate that only proved
"eventually" could not see it.

Needs Xcode + a simulator runtime + the ios-sim irohnet archive
(go-pkgs/irohnet/mklib.py ios-sim); not in ci.
"""

import json
import os
import pathlib
import random
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import iosenv   # noqa: E402
import simrun   # noqa: E402

REPO = HERE.parent
IROHNET = REPO / "go-pkgs" / "irohnet"
EMIT = REPO / "wata-ios" / ".sgo" / "wata-ios"
APP = EMIT / "WataIosEnroll.app"
BUNDLE_ID = "net.wa-ta.ios"
PORT = int(os.environ.get("IOS_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

EXPECT = [
    r"screen setup",
    r"enrol: configured",
    r"enrol: announce .* -> 200",
    r"session restarting \(configured\)",
    r"ready @phone:",
    r"paint contacts lit=[1-9]",
    simrun.NOTED_RE,
]
# NOT "login failed": the pre-approve session legitimately fails its first
# login (401 not allowlisted) and keeps pumping — that is the arc, not an end.
DONE_RES = (simrun.NOTED_RE, r"rejected", r"irohnet: client init failed")


def sh(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    r = subprocess.run([str(c) for c in cmd], **kw)
    if r.returncode != 0:
        sys.exit(f"ios-enroll-smoke: {' '.join(str(c) for c in cmd)} failed")
    return r


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def build_env():
    probe = (
        f'set -e; cd "{REPO}"; WATA="{REPO}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" "$SGO" '
        '"$(emitdir wata-tui)/$(binname wata-tui)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("ios-enroll-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, tui, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, tui, env


def main():
    if sys.platform != "darwin":
        sys.exit("ios-enroll-smoke: macOS only")
    if not (IROHNET / "clib" / "ios_sim" / "libirohnet_ffi.a").exists():
        sys.exit("ios-enroll-smoke: no simulator irohnet archive — run "
                 "go-pkgs/irohnet/mklib.py ios-sim first")
    sgo, tui_bin, env = build_env()
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="ios-enroll-smoke."))

    # ---- builds: host lib + iroh server + keygen, sim lib + iroh app -------
    sh([sys.executable, IROHNET / "mklib.py"], cwd=IROHNET)
    sh([sgo, "build"], cwd=REPO / "wata-server", env=env)
    sh([sgo, "build"], cwd=REPO / "wata-tui", env=env)   # bob, the inbound leg
    srv_emit = REPO / "wata-server" / ".sgo" / "wata-server"
    server_bin = srv_emit / "wata-server-iroh"
    keygen_bin = tmp / "keygen"
    sh(["go", "build", "-tags", "iroh", "-o", server_bin, "."],
       cwd=srv_emit, env=env)
    sh(["go", "build", "-tags", "iroh", "-o", keygen_bin,
        "./cmd/irohnet-keygen"], cwd=IROHNET, env=env)
    sh([sgo, "build"], cwd=REPO / "wata-ios", env=env)
    sh([sys.executable, IROHNET / "mklib.py", "activate", "ios-sim"],
       cwd=IROHNET)
    sim_env = iosenv.go_env("iphonesimulator")
    if env.get("GOTOOLCHAIN"):
        sim_env["GOTOOLCHAIN"] = env["GOTOOLCHAIN"]
    # -a: Go's cache is blind to .a swaps (the activate step just changed one)
    sh(["go", "build", "-a", "-tags", "iroh", "-ldflags=-w",
        "-o", EMIT / "WataIosEnroll", "."], cwd=EMIT, env=sim_env)
    simrun.bundle(APP, EMIT / "WataIosEnroll", BUNDLE_ID)

    # ---- the iroh server (TCP admin on localhost for the page's side) ------
    key = json.loads(subprocess.run([str(keygen_bin)], capture_output=True,
                                    text=True, check=True).stdout)
    srv_cfg = tmp / "server.json"
    srv_cfg.write_text(json.dumps({
        "secretKey": key["secretKey"], "relay": "none", "allowlist": [],
        "announceFile": str(tmp / "announce.json"),
    }))
    log = open(tmp / "server.log", "wb")
    senv = {**env, "WATA_IROH_CONFIG": str(srv_cfg),
            "WATA_LISTEN": f"127.0.0.1:{PORT}"}
    server = subprocess.Popen([str(server_bin), f"127.0.0.1:{PORT}"],
                              stdout=log, stderr=log, env=senv, cwd=tmp)
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(BASE + "/_matrix/client/versions",
                                       timeout=0.5).read()
                break
            except (urllib.error.URLError, OSError):
                if server.poll() is not None:
                    print((tmp / "server.log").read_bytes().decode(errors="replace"))
                    sys.exit("ios-enroll-smoke: server died at startup")
                time.sleep(0.1)
        else:
            sys.exit("ios-enroll-smoke: server never became ready")

        # the owner's admin token (default users: alice is the admin pair's)
        st, body = req("POST", "/_matrix/client/v3/login", {
            "identifier": {"type": "m.id.user", "user": "alice"},
            "password": "testpass123"})
        if st != 200:
            sys.exit(f"ios-enroll-smoke: admin login failed ({st}: {body})")
        token = body["access_token"]

        # the server's card — the same fetch the admin page does
        st, card = req("GET", "/_wata/v1/enroll/server")
        if st != 200 or not card.get("peer"):
            sys.exit(f"ios-enroll-smoke: enroll/server card failed ({st}: {card})")
        v4 = [a for a in card["peerAddrs"] if a.startswith("127.0.0.1:")]
        link = "wata://configure?" + urllib.parse.urlencode({
            "peer": card["peer"], "relay": card["relay"], "admin": BASE,
            "addrs": ",".join(v4)})

        # ---- the app: fresh sandbox, no credentials ------------------------
        appstate = tmp / "appstate"
        os.environ["SIMCTL_CHILD_WATA_IOS_CONFIG"] = str(appstate / "config.json")
        os.environ["SIMCTL_CHILD_WATA_ADMIN_URL"] = BASE
        # the connect wait blocks painting, and the enrol announce rides on a
        # painted frame — shorten it so the arc reaches the pump quickly
        os.environ["SIMCTL_CHILD_WATA_IOS_CONNECT_MS"] = "3000"
        # the REAL audio thread with macaudio's fake hardware ends (plan 0063):
        # no mic-permission prompt can block a harness, and the gate proves the
        # thread links, starts, and pumps on iOS
        os.environ["SIMCTL_CHILD_WATA_MAC_AUDIO"] = "fake"
        for k in ("WATA_IOS_HS", "WATA_IOS_USER", "WATA_IOS_PASS"):
            os.environ.pop("SIMCTL_CHILD_" + k, None)

        sc = simrun.simctl()
        udid = simrun.ensure_device(sc)
        # the wata:// consent alert would otherwise park the openurl below
        if simrun.approve_scheme(udid, "wata", BUNDLE_ID):
            simrun.shutdown(sc, udid)
        simrun.run(sc + ["bootstatus", udid, "-b"])
        simrun.run(sc + ["install", udid, str(APP)])
        t0 = time.time()
        proc = subprocess.Popen(sc + ["launch", "--console-pty", udid, BUNDLE_ID],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1)
        lines, done = [], threading.Event()
        acted = {"configure": False, "approve": False, "inbound": False}
        # the arrival line ENDS the run, and it lands before the sending tui
        # has exited — so the verdict waits for the sender's own thread before
        # reading `sent`, or a healthy run reports a failed sender.
        sent = {"ok": False, "at": None, "arrived": None}
        sent_done = threading.Event()

        def send_link():
            """deliver the configure link, then FOREGROUND the app: `simctl
            openurl` hands the URL over without activating the target (a real
            Safari tap does both), and a backgrounded app is suspended before
            it can act on it. A second bare `simctl launch` activates the
            already-running process without restarting it."""
            subprocess.run(sc + ["openurl", udid, link],
                           capture_output=True, text=True)
            time.sleep(0.5)
            subprocess.run(sc + ["launch", udid, BUNDLE_ID],
                           capture_output=True, text=True)

        def approve_when_pending():
            """the owner's half: poll for the app's pending row, approve it
            with a NEW localpart — the passwordless create-and-bind — then
            re-foreground the app the way the returning owner would (the
            enrol bounce left it suspended behind Safari; re-opening the
            configure link both wakes it and restarts onto the approved
            transport)."""
            for _ in range(120):
                st2, e = req("GET", "/_wata/v1/admin/enroll", token=token)
                rows = e.get("pending") or []
                if st2 == 200 and rows:
                    nid = rows[0]["node_id"]
                    st3, r3 = req("POST", f"/_wata/v1/admin/enroll/{nid}/approve",
                                  {"user": "phone"}, token=token)
                    print(f"ios-enroll-smoke: approve {nid[:8]}… -> {st3} {r3}",
                          flush=True)
                    time.sleep(1)
                    send_link()
                    return
                time.sleep(0.5)
            print("ios-enroll-smoke: no pending row ever appeared", flush=True)

        def send_inbound():
            """the inbound leg: bob sends into the family room (which the
            approve joined @phone to) once the contact list has painted, so
            the message lands MID-SESSION on a running iroh sync rather than
            in the first snapshot, which announces nothing by design."""
            time.sleep(1.0)
            sent["ok"], sent["at"], _ = simrun.tui_send(
                tui_bin, env, BASE, tag="ios-enroll-smoke")
            sent_done.set()

        def pump():
            for line in proc.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
                lines.append(line.rstrip("\n"))
                if not acted["configure"] and "screen setup" in line:
                    acted["configure"] = True
                    threading.Thread(target=send_link, daemon=True).start()
                if not acted["approve"] and "enrol: configured" in line:
                    acted["approve"] = True
                    threading.Thread(target=approve_when_pending,
                                     daemon=True).start()
                if not acted["inbound"] and "paint contacts lit=" in line:
                    acted["inbound"] = True
                    threading.Thread(target=send_inbound, daemon=True).start()
                if sent["arrived"] is None and re.search(simrun.NOTED_RE, line):
                    sent["arrived"] = time.monotonic()
                if any(re.search(m, line) for m in DONE_RES):
                    done.set()
            done.set()

        threading.Thread(target=pump, daemon=True).start()
        if not done.wait(240):
            print("ios-enroll-smoke: watchdog — no done marker within 240s",
                  file=sys.stderr)
        elapsed = time.time() - t0
        subprocess.run(sc + ["io", udid, "screenshot",
                             str(EMIT / "enroll-screen.png")], check=False)
        subprocess.run(sc + ["terminate", udid, BUNDLE_ID],
                       capture_output=True, text=True)
        proc.terminate()
        simrun.shutdown(sc, udid)

        text = "\n".join(lines)
        missing = [p for p in EXPECT if not re.search(p, text)]
        print(f"ios-enroll-smoke: launch-to-verdict {elapsed:.2f}s")
        if acted["inbound"]:
            sent_done.wait(180)
        if acted["inbound"] and not sent["ok"]:
            print("ios-enroll-smoke: bob's tui never reported `sent` — the "
                  "inbound leg's SENDER failed, so a missing arrival proves "
                  "nothing", file=sys.stderr)
        fast = simrun.latency_ok("ios-enroll-smoke", sent["at"], sent["arrived"])
        if missing:
            for m in missing:
                print("ios-enroll-smoke: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        if not sent["ok"] or not fast:
            sys.exit(1)
        print("ios-enroll-smoke: PASS — fresh install, configure link, "
              "announce, approve, device-login, contacts painted, and bob's "
              "voice message arrived over the iroh sync; no password anywhere")
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
        log.close()


if __name__ == "__main__":
    main()
