#!/usr/bin/env python3
"""The device-command mailbox gate (plan 0020): devicecmd.scala over HTTP.

    tools/wata-cmd-smoke.py            # run it

Everything the mailbox promises is a property of the running system:

  * the ADMIN gate — queueing a command and reading a report are 401 without
    a token and 403 with a non-admin one; the device's own poll/report answer
    for any authenticated account;
  * take-once delivery — a poll hands over the queued commands, in queue
    order, and a second poll finds nothing;
  * the long-poll — a poll parked on an empty queue wakes when a command is
    queued, well before its own timeout;
  * reports — latest-wins per (user, op), a monotonic seq, 404 while none;
  * the trusted-header path is TCP-refused by construction — a forged
    `X-Wata-Node-Id` on a plain-TCP request is stripped before the handler
    runs (go-pkgs/irohnet StripNodeID), so without a bearer token the answer
    is the ordinary 401, never a node-id identity. The positive half (a real
    iroh peer's header naming its bound account) needs a real iroh listener
    and lives with tunnel-smoke's territory, not here;
  * in-memory only — a restart drops both the queue and the reports.
"""

import json
import os
import random
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = int(os.environ.get("CMD_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"
PASSWORD = "testpass123"

failures = []


def check(ok, what):
    print(("  ok   " if ok else "  FAIL ") + what)
    if not ok:
        failures.append(what)


# ---- the sgo build environment (tools/sgo-env.sh is the source of truth) ------
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" "$SGO" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("cmd-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, env


def build(sgo, env):
    r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, "wata-server"),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit(f"cmd-smoke: wata-server build failed:\n{r.stdout}{r.stderr}")


# ---- the server ---------------------------------------------------------------
def our_listener(pid):
    r = subprocess.run(["lsof", "-ti", f"tcp:{PORT}", "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return str(pid) in r.stdout.split()


def start_server(binary, log_path, env):
    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, f":{PORT}"], stdout=log, stderr=log, env=env)
    for _ in range(200):
        if proc.poll() is not None:
            break
        if our_listener(proc.pid):
            try:
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions", timeout=0.5).read()
                return proc, log
            except (urllib.error.URLError, OSError):
                pass
        time.sleep(0.1)
    proc.kill()
    log.close()
    sys.exit("cmd-smoke: server never became ready\n" + open(log_path).read())


def stop_server(proc, log):
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()


# ---- HTTP ---------------------------------------------------------------------
def parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def req(method, path, body=None, token=None, headers=None, timeout=20):
    """-> (status, parsed-json-or-text)."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, parse(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, parse(e.read().decode())


def login(user):
    st, j = req("POST", "/_matrix/client/v3/login",
                {"type": "m.login.password", "user": user, "password": PASSWORD})
    if st != 200:
        sys.exit(f"cmd-smoke: login {user} failed: {st} {j}")
    return j["access_token"]


# ---- the run ------------------------------------------------------------------
def run(tmp):
    sgo, server_bin, env = build_env()
    build(sgo, env)
    log_path = os.path.join(tmp, "server.log")
    proc, log = start_server(server_bin, log_path, env)
    try:
        alice = login("alice")   # the built-in admin
        bob = login("bob")       # non-admin: the device role

        # -- the admin gate on queue + report-read ------------------------------
        st, _ = req("POST", "/_wata/v1/cmd/bob", {"op": "wifi_scan"})
        check(st == 401, "queue without a token is 401")
        st, _ = req("POST", "/_wata/v1/cmd/alice", {"op": "wifi_scan"}, token=bob)
        check(st == 403, "queue with a non-admin token is 403")
        st, _ = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan")
        check(st == 401, "report-read without a token is 401")
        st, _ = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan", token=bob)
        check(st == 403, "report-read with a non-admin token is 403")

        # -- queue validation ---------------------------------------------------
        st, _ = req("POST", "/_wata/v1/cmd/nobody", {"op": "wifi_scan"}, token=alice)
        check(st == 404, "queue for an unknown user is 404")
        st, _ = req("POST", "/_wata/v1/cmd/bob", {"nop": True}, token=alice)
        check(st == 400, "queue without an op is 400")
        st, _ = req("POST", "/_wata/v1/cmd/bob", "not json", token=alice)
        check(st == 400, "queue with a non-object body is 400")

        # -- queue -> take-once poll, in order ----------------------------------
        st, j = req("POST", "/_wata/v1/cmd/bob", {"op": "wifi_scan"}, token=alice)
        check(st == 200 and j.get("queued") is True, "queue wifi_scan answers queued")
        st, j = req("POST", "/_wata/v1/cmd/@bob:localhost",
                    {"op": "wifi_join", "ssid": "HomeNet", "psk": "s3cret"}, token=alice)
        check(st == 200, "queue accepts a full mxid target")
        st, _ = req("GET", "/_wata/v1/cmd/poll")
        check(st == 401, "poll without a token is 401")
        st, j = req("GET", "/_wata/v1/cmd/poll?wait=0", token=bob)
        cmds = j.get("cmds", []) if st == 200 else []
        check(st == 200 and [c.get("op") for c in cmds] == ["wifi_scan", "wifi_join"],
              f"poll takes both commands in queue order (got {j})")
        check(cmds and cmds[-1].get("ssid") == "HomeNet" and cmds[-1].get("psk") == "s3cret",
              "the op's arguments ride the command body untouched")
        st, j = req("GET", "/_wata/v1/cmd/poll?wait=0", token=bob)
        check(st == 200 and j.get("cmds") == [], "a second poll finds nothing (take-once)")

        # -- the long-poll wakes on a queue -------------------------------------
        def late_queue():
            time.sleep(1.0)
            req("POST", "/_wata/v1/cmd/bob", {"op": "wifi_scan"}, token=alice)
        t = threading.Thread(target=late_queue)
        t.start()
        t0 = time.monotonic()
        st, j = req("GET", "/_wata/v1/cmd/poll?wait=15", token=bob, timeout=25)
        dt = time.monotonic() - t0
        t.join()
        check(st == 200 and [c.get("op") for c in j.get("cmds", [])] == ["wifi_scan"],
              "the parked poll hands over the late-queued command")
        check(dt < 8.0, f"the long-poll woke on the queue, not the timer ({dt:.1f}s)")

        # -- reports: 404 first, then latest-wins with a monotonic seq ----------
        st, _ = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan", token=alice)
        check(st == 404, "report-read before any report is 404")
        st, _ = req("POST", "/_wata/v1/cmd/report", {"result": {}}, token=bob)
        check(st == 400, "report without an op is 400")
        st, j = req("POST", "/_wata/v1/cmd/report",
                    {"op": "wifi_scan", "result": {"ok": True, "networks": []}}, token=bob)
        check(st == 200 and j.get("seq", 0) > 0, "report answers its seq")
        seq1 = j.get("seq", 0)
        st, j = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan", token=alice)
        check(st == 200 and j.get("op") == "wifi_scan" and j.get("seq") == seq1
              and j.get("result", {}).get("ok") is True,
              f"the admin reads back the report (got {j})")
        st, j = req("POST", "/_wata/v1/cmd/report",
                    {"op": "wifi_scan", "result": {"ok": False}}, token=bob)
        seq2 = j.get("seq", 0)
        check(st == 200 and seq2 > seq1, "a second report's seq is larger")
        st, j = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan", token=alice)
        check(st == 200 and j.get("seq") == seq2 and j.get("result", {}).get("ok") is False,
              "latest report wins per (user, op)")
        st, _ = req("GET", "/_wata/v1/cmd/bob/report", token=alice)
        check(st == 400, "report-read without an op is 400")

        # -- the trusted header grants nothing over TCP -------------------------
        forged = {"X-Wata-Node-Id": "a" * 64}
        st, _ = req("GET", "/_wata/v1/cmd/poll?wait=0", headers=forged)
        check(st == 401, "a forged node-id header over TCP is stripped: poll is 401")
        st, _ = req("POST", "/_wata/v1/cmd/report",
                    {"op": "wifi_scan", "result": {}}, headers=forged)
        check(st == 401, "a forged node-id header over TCP is stripped: report is 401")

        # -- in-memory only: a restart drops the mailbox ------------------------
        st, _ = req("POST", "/_wata/v1/cmd/bob", {"op": "wifi_scan"}, token=alice)
        check(st == 200, "a command is queued before the restart")
    finally:
        stop_server(proc, log)

    proc, log = start_server(server_bin, os.path.join(tmp, "server2.log"), env)
    try:
        alice = login("alice")
        bob = login("bob")
        st, j = req("GET", "/_wata/v1/cmd/poll?wait=0", token=bob)
        check(st == 200 and j.get("cmds") == [], "the restart dropped the queued command")
        st, _ = req("GET", "/_wata/v1/cmd/bob/report?op=wifi_scan", token=alice)
        check(st == 404, "the restart dropped the report")
    finally:
        stop_server(proc, log)


def main():
    tmp = tempfile.mkdtemp(prefix="cmd-smoke.")
    try:
        run(tmp)
    except Exception:
        print(f"cmd-smoke: scratch kept at {tmp}")
        raise
    if failures:
        print(f"cmd-smoke: scratch kept at {tmp}")
        print("cmd-smoke: FAIL")
        sys.exit(1)
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
    print("cmd-smoke: PASS")


if __name__ == "__main__":
    main()
