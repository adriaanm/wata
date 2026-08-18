#!/usr/bin/env python3
"""The machinery both APNs gates need: a fake Apple, a throwaway key, and a
wata-server booted against them.

`wata-push-smoke.py` (tier 2, alert pushes) and `wata-ptt-smoke.py` (tier 3,
PushToTalk channel pushes) ask different questions of the same server, and
everything up to the first assertion is identical: stand up an HTTP server
that records what Apple would have received, mint an ES256 key so the
provider JWT verifies as one, build wata-server through `sgo`, boot it with
`WATA_APNS_*` pointed at the fake, and talk Matrix to it. That is here; each
smoke keeps only its own assertions.

Real APNs is HTTP/2-over-TLS and `FakeAPNs` is plaintext HTTP/1.1, which Go's
net/http speaks to an `http://` host without being asked. What these gates
test is the SERVER's behavior — who gets pushed, with what, and what happens
to a registration when Apple rejects it. The wire shape (HTTP/2, the JWT's
own claims) is gated by `go-pkgs/apns`'s Go tests instead.
"""

import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "testpass123"


class Checks:
    """the pass/fail tally, and the smoke's own verdict line."""

    def __init__(self, name):
        self.name = name
        self.failures = []

    def __call__(self, ok, what):
        print(("  ok   " if ok else "  FAIL ") + what)
        if not ok:
            self.failures.append(what)

    def verdict(self, tmp):
        if self.failures:
            print(f"{self.name}: scratch kept at {tmp}")
            print(f"{self.name}: FAIL")
            sys.exit(1)
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
        print(f"{self.name}: PASS")


class Push:
    """one push the fake received, as the server sent it."""

    def __init__(self, token, payload, headers):
        self.token = token
        self.payload = payload
        self.auth = headers.get("authorization", "")
        self.topic = headers.get("apns-topic", "")
        self.push_type = headers.get("apns-push-type", "")

    @property
    def aps(self):
        return self.payload.get("aps", {}) if isinstance(self.payload, dict) else {}

    def key(self, name, default=None):
        return self.payload.get(name, default) if isinstance(self.payload, dict) else default


class FakeAPNs:
    """Apple, minus Apple: records every push and answers per device token.

    A token in `gone` is answered 410 Unregistered (an uninstalled app), one
    in `bad` 400 BadDeviceToken (what an ephemeral channel token answers once
    its channel is gone). Everything else is accepted.
    """

    def __init__(self):
        self.lock = threading.Lock()
        self.pushes = []
        self.gone = set()
        self.bad = set()
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
                    fake.pushes.append(Push(token, payload, self.headers))
                    status, body = 200, b""
                    if token in fake.gone:
                        status, body = 410, b'{"reason":"Unregistered"}'
                    elif token in fake.bad:
                        status, body = 400, b'{"reason":"BadDeviceToken"}'
                self.send_response(status)
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

    def tokens(self, pushes):
        return sorted(p.token for p in pushes)

    def stop(self):
        self.httpd.shutdown()


def make_key(tmp, who):
    """a throwaway P-256 .p8 Auth Key — the JWT has to verify as ES256, but
    nobody checks whose key it is except Apple."""
    path = os.path.join(tmp, "AuthKey_TEST.p8")
    r = subprocess.run(
        f'openssl ecparam -genkey -name prime256v1 -noout | openssl pkcs8 -topk8 -nocrypt -out "{path}"',
        shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"{who}: could not mint a test key:\n" + r.stderr)
    return path


def build_env(who):
    """the sgo driver, the server binary's path, and the environment to build
    and run it in (tools/sgo-env.sh is the source of truth for all three)."""
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" "$SGO" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"{who}: sgo environment probe failed:\n" + out.stderr)
    sgo, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, env


def build(sgo, env, who):
    r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, "wata-server"),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit(f"{who}: wata-server build failed:\n{r.stdout}{r.stderr}")


class Server:
    """one wata-server process, and the Matrix/wata calls a smoke makes to it."""

    def __init__(self, binary, port, who):
        self.binary = binary
        self.port = port
        self.base = f"http://127.0.0.1:{port}"
        self.who = who
        self.proc = None
        self.log = None
        self.txn = 0

    # -- lifecycle -------------------------------------------------------------
    def _our_listener(self, pid):
        r = subprocess.run(["lsof", "-ti", f"tcp:{self.port}", "-sTCP:LISTEN"],
                           capture_output=True, text=True)
        return str(pid) in r.stdout.split()

    def start(self, log_path, env):
        self.log = open(log_path, "wb")
        self.proc = subprocess.Popen([self.binary, f":{self.port}"],
                                     stdout=self.log, stderr=self.log, env=env)
        for _ in range(200):
            if self.proc.poll() is not None:
                break
            if self._our_listener(self.proc.pid):
                try:
                    urllib.request.urlopen(self.base + "/_matrix/client/versions", timeout=0.5).read()
                    return self
                except (urllib.error.URLError, OSError):
                    pass
            time.sleep(0.1)
        self.proc.kill()
        self.log.close()
        sys.exit(f"{self.who}: server never became ready\n" + open(log_path).read())

    def stop(self):
        if self.proc is None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()
        self.proc = None

    # -- HTTP ------------------------------------------------------------------
    def req(self, method, path, body=None, token=None, timeout=20):
        """-> (status, parsed-json-or-text)."""
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method)
        if token:
            r.add_header("Authorization", "Bearer " + token)
        if data is not None:
            r.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                return resp.status, _parse(resp.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, _parse(e.read().decode())

    def login(self, user):
        st, j = self.req("POST", "/_matrix/client/v3/login",
                         {"type": "m.login.password", "user": user, "password": PASSWORD})
        if st != 200:
            sys.exit(f"{self.who}: login {user} failed: {st} {j}")
        return j["access_token"]

    def send(self, token, room, text):
        self.txn += 1
        st, j = self.req("PUT", f"/_matrix/client/v3/rooms/{room}/send/m.room.message/t{self.txn}",
                         {"msgtype": "m.text", "body": text}, token=token)
        if st != 200:
            sys.exit(f"{self.who}: send failed: {st} {j}")
        return j["event_id"]


def _parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return raw
