#!/usr/bin/env python3
"""The APNs push gate (plan 0065 tier 2): push.scala over HTTP, no Apple.

    tools/wata-push-smoke.py            # run it

The whole point of the tier's design is that it gates with NO Apple
credentials: the APNs host is configuration (`WATA_APNS_HOST`), so a local
fake standing in for Apple sees every push the server would have sent, and a
throwaway ES256 key signs the provider JWT. Nothing here needs a developer
account, a phone, or the portal.

What the run asserts, all of it a property of the running server:

  * registration — `POST /_wata/v1/push/register` needs a token (401 without,
    400 with no `token`), and stores the row against the CALLING session;
  * the fan-out — a message landing in a room pushes to every joined member's
    registered devices, with the alert title/body, `interruption-level:
    time-sensitive`, a badge count, and the room and event ids as custom keys;
  * the sender's own device does NOT get a push (its OTHER sessions do);
  * re-registration overwrites, so a device that got a new token is pushed to
    once, at the new token;
  * 410 Gone DELETES the registration — the next message pushes to nobody;
  * unregister drops it too;
  * the registration survives a restart (it is journaled);
  * with NO APNs configuration the server behaves exactly as it did before:
    registrations are still accepted, messages still send, and nothing is
    pushed anywhere.
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = int(os.environ.get("PUSH_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"
PASSWORD = "testpass123"

failures = []


def check(ok, what):
    print(("  ok   " if ok else "  FAIL ") + what)
    if not ok:
        failures.append(what)


# ---- the fake APNs -------------------------------------------------------------
class FakeAPNs:
    """Apple, minus Apple: records every push and answers per device token.

    Real APNs is HTTP/2-over-TLS; this is plaintext HTTP/1.1, which Go's
    net/http speaks to an http:// host without being asked. What is under test
    here is the server's fan-out, not the transport — go-pkgs/apns's own tests
    assert the wire shape against an HTTP/2 fake.
    """

    def __init__(self):
        self.lock = threading.Lock()
        self.pushes = []          # [(device_token, payload, auth_header)]
        self.gone = set()         # tokens to answer 410 for
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                token = self.path.rsplit("/", 1)[-1]
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n)
                try:
                    payload = json.loads(raw)
                except ValueError:
                    payload = raw.decode("utf-8", "replace")
                with fake.lock:
                    fake.pushes.append((token, payload, self.headers.get("authorization", "")))
                    dead = token in fake.gone
                body = b'{"reason":"Unregistered"}' if dead else b""
                self.send_response(410 if dead else 200)
                self.send_header("apns-id", "fake")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def log_message(self, *a):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def take(self, want=1, timeout=10.0):
        """wait for `want` pushes (or the timeout), then take and clear them."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.lock:
                if len(self.pushes) >= want:
                    break
            time.sleep(0.05)
        # a beat for a push we did NOT want, so "exactly one" is a real check
        time.sleep(0.4)
        with self.lock:
            got, self.pushes = self.pushes, []
            return got

    def stop(self):
        self.httpd.shutdown()


def make_key(tmp):
    """a throwaway P-256 .p8 Auth Key — the JWT has to verify as ES256, but
    nobody checks whose key it is except Apple."""
    path = os.path.join(tmp, "AuthKey_TEST.p8")
    r = subprocess.run(
        f'openssl ecparam -genkey -name prime256v1 -noout | openssl pkcs8 -topk8 -nocrypt -out "{path}"',
        shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("push-smoke: could not mint a test key:\n" + r.stderr)
    return path


# ---- the sgo build environment (tools/sgo-env.sh is the source of truth) ------
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" "$SGO" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("push-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, env


def build(sgo, env):
    r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, "wata-server"),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit(f"push-smoke: wata-server build failed:\n{r.stdout}{r.stderr}")


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
    sys.exit("push-smoke: server never became ready\n" + open(log_path).read())


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


def req(method, path, body=None, token=None, timeout=20):
    """-> (status, parsed-json-or-text)."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
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
        sys.exit(f"push-smoke: login {user} failed: {st} {j}")
    return j["access_token"]


TXN = [0]


def send(token, room, text):
    TXN[0] += 1
    st, j = req("PUT", f"/_matrix/client/v3/rooms/{room}/send/m.room.message/t{TXN[0]}",
                {"msgtype": "m.text", "body": text}, token=token)
    if st != 200:
        sys.exit(f"push-smoke: send failed: {st} {j}")
    return j["event_id"]


# ---- the run ------------------------------------------------------------------
def run(tmp):
    sgo, server_bin, base_env = build_env()
    build(sgo, base_env)
    fake = FakeAPNs()
    key = make_key(tmp)
    journal = os.path.join(tmp, "wata.jsonl")

    env = dict(base_env)
    env.update({
        "WATA_APNS_KEY": key,
        "WATA_APNS_KEY_ID": "KEYID123",
        "WATA_APNS_TEAM_ID": "TEAMID456",
        "WATA_APNS_BUNDLE_ID": "com.example.wata",
        "WATA_APNS_HOST": fake.url,
        "WATA_LOG": journal,
    })

    proc, log = start_server(server_bin, os.path.join(tmp, "server.log"), env)
    room = None
    try:
        alice = login("alice")
        bob = login("bob")

        # -- registration -------------------------------------------------------
        st, _ = req("POST", "/_wata/v1/push/register", {"platform": "ios", "token": "T", "env": "sandbox"})
        check(st == 401, "register without a token is 401")
        st, _ = req("POST", "/_wata/v1/push/register", {"platform": "ios"}, token=bob)
        check(st == 400, "register without a device token is 400")
        st, j = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "bobphone", "env": "sandbox"}, token=bob)
        check(st == 200 and j.get("registered") is True, f"bob's phone registers (got {j})")
        st, _ = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "alicephone", "env": "sandbox"}, token=alice)
        check(st == 200, "alice's own session registers too")

        st, j = req("POST", "/_wata/v1/dm/bob", token=alice)
        room = j["room_id"]

        # -- the fan-out --------------------------------------------------------
        ev = send(alice, room, "hello bob")
        got = fake.take(1)
        check(len(got) == 1, f"one message, one push (got {len(got)})")
        if got:
            tok, payload, auth = got[0]
            check(tok == "bobphone", f"the push went to bob's phone, not the sender's (got {tok})")
            aps = payload.get("aps", {}) if isinstance(payload, dict) else {}
            check(aps.get("alert", {}).get("title") == "Alice",
                  f"the alert names the sender (got {aps.get('alert')})")
            check(aps.get("alert", {}).get("body") == "hello bob", "the alert carries the message")
            check(aps.get("interruption-level") == "time-sensitive",
                  "the push is time-sensitive")
            check(aps.get("badge") == 1, f"the badge counts bob's unplayed messages (got {aps.get('badge')})")
            check(payload.get("room_id") == room and payload.get("event_id") == ev,
                  "the room and event ids ride the payload")
            check(auth.startswith("bearer "), "the request carries a provider JWT")

        # -- the sender's own device is excluded, its other sessions are not -----
        alice2 = login("alice")
        st, _ = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "alicepad", "env": "sandbox"}, token=alice2)
        check(st == 200, "alice registers a second session")
        send(alice, room, "again")
        got = fake.take(2)
        check(sorted(t for t, _, _ in got) == ["alicepad", "bobphone"],
              f"the sender's OTHER session is pushed, its own is not (got {[t for t, _, _ in got]})")

        # -- re-registration overwrites ------------------------------------------
        st, _ = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "bobphone2", "env": "sandbox"}, token=bob)
        check(st == 200, "bob re-registers with a new token")
        send(alice, room, "new token")
        got = fake.take(2)
        check(sorted(t for t, _, _ in got) == ["alicepad", "bobphone2"],
              f"the old token is gone, not doubled (got {[t for t, _, _ in got]})")

        # -- 410 Gone deletes the registration ------------------------------------
        with fake.lock:
            fake.gone.add("bobphone2")
        send(alice, room, "to a dead token")
        got = fake.take(2)
        check(sorted(t for t, _, _ in got) == ["alicepad", "bobphone2"],
              "the dead token is pushed to once")
        send(alice, room, "after the 410")
        got = fake.take(1)
        check([t for t, _, _ in got] == ["alicepad"],
              f"the 410 deleted bob's registration (got {[t for t, _, _ in got]})")

        # -- unregister ------------------------------------------------------------
        st, j = req("POST", "/_wata/v1/push/unregister", {}, token=alice2)
        check(st == 200 and j.get("registered") is False, "alice's second session unregisters")
        send(alice, room, "after the unregister")
        got = fake.take(0, timeout=1.0)
        check(got == [], f"nothing is pushed to an unregistered device (got {got})")

        # -- registrations survive a restart ---------------------------------------
        st, _ = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "bobphone3", "env": "sandbox"}, token=bob)
        check(st == 200, "bob registers again before the restart")
    finally:
        stop_server(proc, log)

    proc, log = start_server(server_bin, os.path.join(tmp, "server2.log"), env)
    try:
        # a fresh login mints a fresh device, so alice's FIRST session's
        # registration is no longer "the sender's own device" and is pushed to
        # as well — which is itself the journal replay being asserted.
        alice = login("alice")
        send(alice, room, "after the restart")
        got = fake.take(2)
        check(sorted(t for t, _, _ in got) == ["alicephone", "bobphone3"],
              f"the registrations survived the restart (got {sorted(t for t, _, _ in got)})")
    finally:
        stop_server(proc, log)

    # -- no APNs configuration: exactly the behavior of a server without pushes --
    plain = dict(base_env)
    plain["WATA_LOG"] = journal
    proc, log = start_server(server_bin, os.path.join(tmp, "server3.log"), plain)
    try:
        alice = login("alice")
        bob = login("bob")
        st, _ = req("POST", "/_wata/v1/push/register",
                    {"platform": "ios", "token": "unused", "env": "sandbox"}, token=bob)
        check(st == 200, "registration is still accepted with no APNs configured")
        send(alice, room, "no pusher here")
        got = fake.take(0, timeout=1.5)
        check(got == [], f"nothing is pushed with no APNs configured (got {got})")
        st, j = req("GET", "/_matrix/client/v3/sync?timeout=0", token=bob)
        check(st == 200, "the server is otherwise unchanged")
        errs = [ln for ln in open(os.path.join(tmp, "server3.log")) if "apns" in ln.lower()]
        check(errs == [], f"no APNs noise in the log (got {errs})")
    finally:
        stop_server(proc, log)
        fake.stop()


def main():
    tmp = tempfile.mkdtemp(prefix="push-smoke.")
    try:
        run(tmp)
    except Exception:
        print(f"push-smoke: scratch kept at {tmp}")
        raise
    if failures:
        print(f"push-smoke: scratch kept at {tmp}")
        print("push-smoke: FAIL")
        sys.exit(1)
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
    print("push-smoke: PASS")


if __name__ == "__main__":
    main()
