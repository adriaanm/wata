#!/usr/bin/env python3
"""Can wata-mac's Devices window do what wata-tui's admin commands do? (plan 0037 slice 5)

    tools/mac-devices-smoke.py
    MAC_DEVICES_KEEP=1 …         # keep the scratch dir on success

Alice — the admin account — runs wata-mac headless against one fresh
wata-server, with a harness thread playing bob's handset over the server's
device-command mailbox (the same fake tui-smoke's wifi leg uses) and the
enrolment API announcing two handsets.

Every step drives the REAL chrome: `dev sel` moves a popup, `dev psk` types
into the window's NSSecureTextField, `dev click join` calls the same function
the Join button's action calls. What is asserted is the DECISION and the
REQUEST, not that macOS drew a window:

  1. the scan reaches the handset, and its report becomes the window's list;
  2. the join carries the ssid the popup is pointing at and the EXACT PSK
     that was typed — read from the device side, which is the only place it
     legitimately arrives — while no printed line anywhere holds it;
  3. `wifi off` carries its minutes, and the verdict comes back;
  4. the approve/deny sentence names the device and the account BEFORE the
     click, the approve request binds that account, and the denied handset
     leaves the pending list.

The password assertion is the point of the whole slice: the harness greps
every line the app printed for the PSK it typed, and a hit fails the run.

Hermetic — no device, no window, no network beyond localhost. macOS only,
not in ci (like mac-smoke, whose harness this reuses).
"""

import importlib.util
import json
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("ms", os.path.join(WATA, "tools", "mac-smoke.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)

# The password the window is handed. Long and distinctive so the "it never
# reaches a log line" grep cannot pass by accident.
PSK = "correct-horse-battery-staple-77"

NETWORKS = [
    {"ssid": "youbetcha", "signal": -52, "secured": True},
    {"ssid": "cafe-guest", "signal": -71, "secured": False},
]

# two announced handsets: one gets approved and bound, one gets denied
NODE_APPROVE = "3f2a9c1d4e5b6a7f8091a2b3c4d5e6f70123456789abcdef0123456789abcdef"
NODE_DENY = "aa11bb22cc33dd44ee55ff6607182930415263748596a7b8c9d0e1f203142536"


def http_json(method, path, body=None, token=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(ms.BASE + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def handset(token, seen, stop):
    """bob's handset, over plain HTTP: long-poll the command mailbox and
    answer whatever arrives, recording each command for the harness."""
    deadline = time.monotonic() + 180
    while not stop.is_set() and time.monotonic() < deadline:
        st, j = http_json("GET", "/_wata/v1/cmd/poll?wait=5", token=token, timeout=15)
        if st != 200:
            time.sleep(0.5)
            continue
        for cmd in j.get("cmds", []):
            seen.append(cmd)
            op = cmd.get("op")
            if op == "wifi_scan":
                result = {"ok": True, "networks": NETWORKS}
            elif op == "wifi_join":
                result = {"ok": True, "detail": "joined " + cmd.get("ssid", "")}
            elif op == "wifi_off":
                result = {"ok": True,
                          "detail": "wifi off for %s min; auto-restore armed"
                                    % cmd.get("minutes")}
            else:
                continue
            http_json("POST", "/_wata/v1/cmd/report",
                      {"op": op, "result": result}, token=token)


def dev_line(lines, prefix):
    """the last `dev: <prefix>…` line, or None."""
    for l in reversed(lines):
        if l.startswith("dev: " + prefix):
            return l
    return None


def run(tmp):
    sgo, mac_bin, tui_bin, server_bin, env = ms.build_env()
    ms.build(sgo, env)

    # Approving writes the node id into an allowlist file. With no iroh
    # transport there is nothing to apply it to live, which the server answers
    # honestly ("live": false) — the durable half is what this smoke is about.
    allowlist = os.path.join(tmp, "iroh.json")
    with open(allowlist, "w") as f:
        json.dump({"allowlist": []}, f)
    senv = dict(env, WATA_ENROLL_ALLOWLIST=allowlist)

    log_path = os.path.join(tmp, "server.log")
    proc, log = ms.start_server(server_bin, log_path, senv)
    if proc is None:
        return ["server never became ready"]

    c = ms.Checks()
    sess, stop, dev = None, threading.Event(), None
    seen = []
    try:
        st, j = http_json("POST", "/_matrix/client/v3/login",
                          {"type": "m.login.password", "user": "bob",
                           "password": ms.PASSWORD})
        if st != 200:
            return ["bob login failed (%d)" % st]
        dev = threading.Thread(target=handset, args=(j["access_token"], seen, stop))
        dev.start()

        # two handsets announce themselves, unauthenticated, as a real one does
        for node, nonce in ((NODE_APPROVE, "AB12"), (NODE_DENY, "CD34")):
            st, _ = http_json("POST", "/_wata/v1/enroll", {"nodeId": node, "nonce": nonce})
            c.ok(st == 200, "announce of %s… answered %d" % (node[:8], st))

        sess = ms.MacSession(mac_bin, env)
        sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
        c.line(sess.lines, lambda l: l == "ready @alice:localhost", "alice: no `ready`")
        # a few frames, so the snapshot has bob in it and the handset picker
        # has been published to the chrome
        sess.cmd("wait 2000", lambda l: l == "waited 2000")
        sess.cmd("dev show", lambda l: l == "dev shown")

        # ---- 1. the scan reaches the handset, and its report fills the list --
        sess.cmd("dev sel handset 0", lambda l: l.startswith("dev sel"))
        got = sess.cmd("dev click scan", lambda l: l == "dev done scan", timeout=90)
        c.ok(any(l.startswith("dev: scan @bob:localhost") for l in got),
             "scan was not aimed at the selected handset: %r" % got)
        c.ok(any(l == "dev: net 1 youbetcha signal=-52 secured=true" for l in got),
             "the scan report did not become the window's first row: %r" % got)
        c.ok(any(l == "dev: net 2 cafe-guest signal=-71 secured=false" for l in got),
             "the open network row is wrong: %r" % got)
        c.ok(any(cmd.get("op") == "wifi_scan" for cmd in seen),
             "no wifi_scan reached the handset")

        # ---- 2. the join carries the ssid and the exact PSK ------------------
        sess.cmd("dev sel network 0", lambda l: l.startswith("dev sel"))
        sess.cmd("dev psk", lambda l: l == "psk?")
        sess.cmd(PSK, lambda l: l == "dev psk set")
        got = sess.cmd("dev click join", lambda l: l == "dev done join", timeout=120)
        c.ok(any(l == "dev: join @bob:localhost youbetcha psk=31 chars" for l in got),
             "the join line does not name the network and the password's SHAPE: %r" % got)
        joins = [cmd for cmd in seen if cmd.get("op") == "wifi_join"]
        c.ok(len(joins) == 1, "want exactly one wifi_join at the handset, got %d" % len(joins))
        if joins:
            c.ok(joins[0].get("ssid") == "youbetcha",
                 "the join carried ssid %r" % joins[0].get("ssid"))
            # the one place the password legitimately arrives
            c.ok(joins[0].get("psk") == PSK,
                 "the handset got psk %r, not what was typed" % joins[0].get("psk"))

        # a second join must find the field EMPTY — TakePSK clears it, so a
        # password cannot be silently reused on a network it was not typed for
        sess.cmd("dev sel network 1", lambda l: l.startswith("dev sel"))
        sess.cmd("dev click join", lambda l: l == "dev done join", timeout=120)
        joins = [cmd for cmd in seen if cmd.get("op") == "wifi_join"]
        c.ok(len(joins) == 2 and joins[1].get("psk") == "",
             "the second join reused the first password: %r" % (joins[1:] or None))

        # ---- 3. the cellular-fallback switch --------------------------------
        got = sess.cmd("dev click off", lambda l: l == "dev done off", timeout=90)
        c.ok(any(l == "dev: off @bob:localhost 10" for l in got),
             "the wifi-off request does not carry its window: %r" % got)
        offs = [cmd for cmd in seen if cmd.get("op") == "wifi_off"]
        c.ok(len(offs) == 1 and offs[0].get("minutes") == 10,
             "the handset got %r" % (offs[0] if offs else None))
        c.ok(any("auto-restore armed" in l for l in got),
             "the handset's verdict did not come back: %r" % got)

        # ---- 4. approve and deny --------------------------------------------
        got = sess.cmd("dev click refresh", lambda l: l == "dev done refresh", timeout=60)
        pend = [l for l in got if l.startswith("dev: pending ")]
        c.ok(len(pend) == 2, "want both announced handsets pending, got %r" % pend)

        sess.cmd("dev sel pending 0", lambda l: l.startswith("dev sel"))
        got = sess.cmd("dev decision", lambda l: l.startswith("dev decision"))
        say = got[-1]
        c.ok("type the account" in say,
             "an approval with no account typed does not say so: %r" % say)
        sess.cmd("dev acct kid", lambda l: l.startswith("dev acct"))
        got = sess.cmd("dev decision", lambda l: l.startswith("dev decision"))
        say = got[-1]
        # the whole decision, before the irreversible click
        first = pend[0].split()[2]
        c.ok(first[:12] in say and "as kid" in say and "enrolled again" in say,
             "the decision sentence does not name the device, the account and "
             "what Deny costs: %r" % say)

        got = sess.cmd("dev click approve", lambda l: l == "dev done approve", timeout=60)
        c.ok(any(l.startswith("dev: approve ok") and " kid" in l for l in got),
             "the approve did not land: %r" % got)
        # the durable half: the node id is in the allowlist file, bound to an
        # account that now exists
        with open(allowlist) as f:
            allowed = json.load(f).get("allowlist", [])
        approved = first
        c.ok(any(a.startswith(approved[:12]) for a in allowed),
             "the approved node is not in the allowlist file: %r" % allowed)
        st, j = http_json("GET", "/_wata/v1/admin/enroll", token=sess_token())
        c.ok(any(b.get("user") == "kid" for b in j.get("bindings", [])),
             "the approval bound no account: %r" % j.get("bindings"))

        # the refresh the approve triggers must drop the approved row
        left = dev_line(sess.lines, "pending ")
        sess.cmd("dev sel pending 0", lambda l: l.startswith("dev sel"))
        got = sess.cmd("dev click deny", lambda l: l == "dev done deny", timeout=60)
        c.ok(any(l.startswith("dev: deny ok") for l in got), "the deny did not land: %r" % got)
        got = sess.cmd("dev click refresh", lambda l: l == "dev done refresh", timeout=60)
        c.ok(any(l == "dev: pending none" for l in got),
             "handsets are still waiting after an approve and a deny: %r" % got)

    finally:
        stop.set()
        if dev is not None:
            dev.join(timeout=30)
        if sess is not None:
            sess.quit()
        ms.stop_server(proc, log)

    # ---- the invariant the slice exists for --------------------------------
    # The password went into a secure field, into one request body, and
    # nowhere else. Not a status line, not a decision line, not an error.
    leaked = [l for l in sess.lines if PSK in l or "correct-horse" in l]
    c.ok(not leaked, "the password reached the app's output: %r" % leaked)
    with open(log_path) as f:
        server_log = f.read()
    c.ok(PSK not in server_log, "the password reached the server's log")

    return c.failed


# The admin token the enrolment assertions read with. Minted lazily so a run
# that fails before it is needed does not pay for a login.
_TOKEN = []


def sess_token():
    if not _TOKEN:
        st, j = http_json("POST", "/_matrix/client/v3/login",
                          {"type": "m.login.password", "user": "alice",
                           "password": ms.PASSWORD})
        _TOKEN.append(j.get("access_token", "") if st == 200 else "")
    return _TOKEN[0]


def main():
    keep = os.environ.get("MAC_DEVICES_KEEP")
    tmp = tempfile.mkdtemp(prefix="mac-devices-smoke-")
    try:
        failures = run(tmp)
    except Exception as e:  # a harness crash is a failure, not a traceback
        failures = ["harness: %s: %s" % (type(e).__name__, e)]
    if failures:
        print("mac-devices-smoke: FAIL (%d)" % len(failures))
        for f in failures:
            print("  - " + f)
        print("  scratch: " + tmp)
        return 1
    print("mac-devices-smoke: ok")
    if not keep:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
    else:
        print("  scratch: " + tmp)
    return 0


if __name__ == "__main__":
    sys.exit(main())
