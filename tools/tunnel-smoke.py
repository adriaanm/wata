#!/usr/bin/env python3
"""The iroh tunnel smoke (plan 0013 milestone 1): wata-server serving over an
EMBEDDED iroh listener + a wataclient session over an embedded iroh dial —
two processes, one machine, no real network (relay "none", loopback UDP
only). The server's ONLY TCP port is the admin listener the iroh mode also
brings up (plan 0021, WATA_LISTEN) — the wata traffic itself never touches
one.

Steps:
  1. the irohnet glue's own tests (go test -tags iroh: net/http over iroh,
     allowlist refusal at accept, deadline interruption, Close semantics);
  2. sgo-build both apps, then go-build them with `-tags iroh` (the only
     builds in the repo that need cargo — mklib.py stages the staticlib);
  3. provision two fresh node keys, allowlist the client's id on the server;
  4. boot wata-server with WATA_IROH_CONFIG, read its announce file;
  5. run integ scenarios (integ.scala) over iroh — a FRESH server per
     scenario, exactly like tools/wataclient-integ.sh;
  6. the allowlist negative: a FRESH DEVICE IDENTITY — minted by
     irohnet.EnsureKey into a config that was deployed with no secret at all
     (plan 0014 milestone 1), the same call wata-fb makes on first boot — is
     refused at accept, loudly;
  7. enrolment (plan 0021 milestone B): that same refused node announces
     itself, an admin approves it over the admin listener, and it is then
     accepted by the same server process — no restart.

Prints TUNNEL-SMOKE PASS / FAIL. Needs cargo (the Rust toolchain) on top of
the repo's usual prerequisites — this recipe and fb-deploy's successors are
the only places that do.
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
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
IROHNET = WATA / "go-pkgs" / "irohnet"
# a fresh server per scenario, the wataclient-integ.sh discipline.
SCENARIOS = ["login-syncing", "voice-to-bob"]
# The DUAL LISTENER (plan 0021): in iroh mode the server also serves the same
# mux over plain TCP, because no browser can dial iroh and /admin has to be
# reachable. Checked once, against the first scenario's server.
HTTP_PORT = int(os.environ.get("TUNNEL_SMOKE_HTTP_PORT") or random.randint(20000, 39999))
# The enrolment leg (plan 0021 milestone B) drives the admin API of the
# allowlist-negative server, which needs its own admin listener.
ENROLL_PORT = int(os.environ.get("TUNNEL_SMOKE_ENROLL_PORT") or random.randint(20000, 39999))


def run(cmd, env, cwd=None, **kw):
    return subprocess.run(cmd, env=env, cwd=cwd or WATA, **kw)


def fail(msg):
    print(f"tunnel-smoke: {msg}")
    print("TUNNEL-SMOKE FAIL")
    sys.exit(1)


def sgo_build(env, module):
    from toolchain import sgo_bin

    r = run([str(sgo_bin()), "build"], env, cwd=WATA / module, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail(f"sgo build ({module}) failed")


def keygen(env, keygen_bin):
    out = run([keygen_bin], env, capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"keygen failed: {out.stderr}")
    return json.loads(out.stdout)


def mint_into(env, keygen_bin, cfg_path):
    """The DEVICE-MINTED identity (plan 0014 milestone 1): irohnet.EnsureKey
    over a config that carries no secret. Same call wata-fb makes on first boot
    — `irohnet-keygen -config` exists so this harness drives that code path
    instead of hand-minting a key and pasting it into a file. Returns the node
    id; the secret never leaves the config."""
    out = run([keygen_bin, "-config", str(cfg_path)], env, capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"EnsureKey failed: {out.stderr}")
    return json.loads(out.stdout)["id"]


def start_server(server_bin, env, cfg_path, log_path, listen=None):
    log = open(log_path, "w")
    extra = {"WATA_LISTEN": listen} if listen else {}
    proc = subprocess.Popen(
        [server_bin],
        env={**env, "WATA_IROH_CONFIG": str(cfg_path), **extra},
        stdout=log,
        stderr=subprocess.STDOUT,
        cwd=WATA,
    )
    return proc, log


def dual_listener():
    """In iroh mode the SAME mux is also served over plain TCP (WATA_LISTEN),
    so a browser on the LAN can load /admin — the one thing iroh cannot carry.
    Asserted here because this harness owns the only real iroh server the gate
    boots."""
    url = f"http://127.0.0.1:{HTTP_PORT}/admin"
    for _ in range(100):
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                body = r.read().decode()
                ctype = r.headers.get("Content-Type", "")
                good = r.status == 200 and ctype.startswith("text/html") and "wata admin" in body
                print(f"tunnel-smoke: dual-listener /admin over TCP: {'PASS' if good else 'FAIL'}")
                if not good:
                    print(f"  status={r.status} content-type={ctype!r}")
                return good
        except (urllib.error.URLError, OSError):
            time.sleep(0.1)
    print("tunnel-smoke: dual-listener /admin over TCP: FAIL (never answered)")
    return False


# ---- the admin API, over the plain-TCP listener the iroh mode also serves ----
def api(base, method, path, body=None, token=None):
    """-> (status, parsed body). The enrolment leg's whole client."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}


def await_admin(base):
    for _ in range(100):
        try:
            urllib.request.urlopen(base + "/admin", timeout=1).read()
            return True
        except (urllib.error.URLError, OSError):
            time.sleep(0.1)
    return False


def enrol_leg(env, client_bin, cli_cfg_dir, srv_cfg, intruder_id, spare_id):
    """The approve loop end to end (plan 0021 milestone B): the node the
    server just refused announces itself, an admin approves it through the
    admin API, and the SAME key is then accepted — no server restart. The
    durable half (the config file's allowlist) and the live half (the new
    irohnet_server_allow FFI) are both asserted, since only the second one
    can make this run pass without a restart.

    Returns True when every check held."""
    base = f"http://127.0.0.1:{ENROLL_PORT}"
    good = True

    def check(cond, what):
        nonlocal good
        print(f"tunnel-smoke: enrolment: {'PASS' if cond else 'FAIL'} — {what}")
        if not cond:
            good = False
        return cond

    if not check(await_admin(base), "the admin listener answers"):
        return False
    status, body = api(base, "POST", "/_wata/v1/enroll",
                       {"nodeId": intruder_id, "nonce": "QR01"})
    check(status == 200, f"the refused node announces itself, unauthenticated (saw {status} {body})")
    _, login = api(base, "POST", "/_matrix/client/v3/login",
                   {"identifier": {"type": "m.id.user", "user": "alice"}, "password": "testpass123"})
    token = login.get("access_token")
    if not check(bool(token), "the admin logs in"):
        return False
    _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
    check(intruder_id in {p["node_id"] for p in listing.get("pending", [])},
          "the announce shows up in the pending list")

    # deny first, with a node nobody asked for: it must leave no trace.
    api(base, "POST", "/_wata/v1/enroll", {"nodeId": spare_id, "nonce": "QR02"})
    check(api(base, "POST", f"/_wata/v1/admin/enroll/{spare_id}/deny", None, token)[0] == 200,
          "deny answers 200")
    check(spare_id not in json.loads(srv_cfg.read_text())["allowlist"],
          "the denied node never reaches the allowlist file")

    status, body = api(base, "POST", f"/_wata/v1/admin/enroll/{intruder_id}/approve", None, token)
    check(status == 200, f"approve answers 200 (saw {status} {body})")
    check(body.get("live") is True,
          f"approve applied to the LIVE listener (note: {body.get('note')!r})")
    check(intruder_id in json.loads(srv_cfg.read_text())["allowlist"],
          "approve appended the node id to the server's iroh config allowlist")
    _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
    check(listing.get("pending") == [], "the approved row cleared")

    # the point of the whole leg: the SAME key, the SAME server process.
    r = subprocess.run(
        [str(client_bin), "integ", "login-syncing", "http://wata.iroh"],
        env={**env, "WATA_IROH_CONFIG": str(cli_cfg_dir)},
        cwd=WATA, capture_output=True, text=True, timeout=60,
    )
    if not check("INTEG PASS" in r.stdout, "the approved node is accepted with NO server restart"):
        print("---- approved-client output ----")
        print(r.stdout + r.stderr)
    return good


def main():
    prepare()
    env = build_env()
    tmp = Path(tempfile.mkdtemp(prefix="tunnel-smoke."))

    # ---- 1. the glue's own tests -------------------------------------------
    print("tunnel-smoke: staging the irohnet staticlib (cargo)…")
    if run([sys.executable, "mklib.py"], env, cwd=IROHNET).returncode != 0:
        fail("mklib.py failed")
    print("tunnel-smoke: irohnet glue tests (go test -tags iroh)…")
    if run(["go", "test", "-tags", "iroh", "-count=1", "-timeout", "180s", "./"], env, cwd=IROHNET).returncode != 0:
        fail("irohnet glue tests failed")

    # ---- 2. builds ---------------------------------------------------------
    print("tunnel-smoke: building wata-server + wata-fb (sgo, then -tags iroh)…")
    sgo_build(env, "wata-server")
    sgo_build(env, "wata-fb")
    srv_emit = WATA / "wata-server" / ".sgo" / "wata-server"
    fb_emit = WATA / "wata-fb" / ".sgo" / "wata-fb"
    server_bin = srv_emit / "wata-server-iroh"
    client_bin = fb_emit / "wata-fb-iroh"
    keygen_bin = tmp / "irohnet-keygen"
    for out, cwd, pkg in [
        (server_bin, srv_emit, "."),
        (client_bin, fb_emit, "."),
        (keygen_bin, IROHNET, "./cmd/irohnet-keygen"),
    ]:
        r = run(["go", "build", "-tags", "iroh", "-o", str(out), pkg], env, cwd=cwd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout + r.stderr)
            fail(f"go build -tags iroh failed in {cwd}")

    # ---- 3. provisioning ---------------------------------------------------
    srv_key = keygen(env, str(keygen_bin))
    cli_key = keygen(env, str(keygen_bin))

    ok = True
    for scenario in SCENARIOS:
        sdir = tmp / scenario
        sdir.mkdir()
        announce = sdir / "announce.json"
        srv_cfg = sdir / "server.json"
        srv_cfg.write_text(json.dumps({
            "secretKey": srv_key["secretKey"],
            "relay": "none",
            "allowlist": [cli_key["id"]],
            "announceFile": str(announce),
        }))

        # ---- 4. boot the server over iroh (fresh state: fresh cwd? the
        # server keeps state in memory; a fresh process is a fresh store) ----
        listen = f":{HTTP_PORT}" if scenario == SCENARIOS[0] else None
        proc, log = start_server(str(server_bin), env, srv_cfg, sdir / "server.log", listen)
        try:
            for _ in range(100):
                if announce.exists():
                    break
                if proc.poll() is not None:
                    print((sdir / "server.log").read_text())
                    fail(f"server exited before announcing ({scenario})")
                time.sleep(0.1)
            else:
                fail(f"server never announced ({scenario})")
            if scenario == SCENARIOS[0] and not dual_listener():
                ok = False
            ann = json.loads(announce.read_text())
            v4 = [a for a in ann["addrs"] if not a.startswith("[")]
            cli_cfg = sdir / "client.json"
            cli_cfg.write_text(json.dumps({
                "secretKey": cli_key["secretKey"],
                "relay": "none",
                "peer": ann["id"],
                "peerAddrs": v4,
            }))

            # ---- 5. the client session over iroh ---------------------------
            r = subprocess.run(
                [str(client_bin), "integ", scenario, "http://wata.iroh"],
                env={**env, "WATA_IROH_CONFIG": str(cli_cfg)},
                cwd=WATA,
                capture_output=True,
                text=True,
                timeout=120,
            )
            passed = f"INTEG PASS {scenario}" in r.stdout
            print(f"tunnel-smoke: {scenario}: {'PASS' if passed else 'FAIL'}")
            if not passed:
                print("---- client output ----")
                print(r.stdout + r.stderr)
                print("---- server log ----")
                print((sdir / "server.log").read_text())
                ok = False
        finally:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
            log.close()

    # ---- 6. the allowlist NEGATIVE (plan 0013 milestone 5): a server that
    # allowlists only cli_key must refuse a different node id at accept
    # (QUIC close 401 "not allowlisted", before any stream). The intruder's
    # integ run must NOT pass — and the refusal should be the loud kind.
    if ok:
        sdir = tmp / "allowlist-negative"
        sdir.mkdir()
        announce = sdir / "announce.json"
        srv_cfg = sdir / "server.json"
        srv_cfg.write_text(json.dumps({
            "secretKey": srv_key["secretKey"],
            "relay": "none",
            "allowlist": [cli_key["id"]],
            "announceFile": str(announce),
        }))
        spare_key = keygen(env, str(keygen_bin))
        # this server also serves the admin listener (WATA_LISTEN): the
        # enrolment leg approves the very node it is about to refuse.
        proc, log = start_server(str(server_bin), env, srv_cfg, sdir / "server.log",
                                 listen=f":{ENROLL_PORT}")
        try:
            for _ in range(100):
                if announce.exists():
                    break
                time.sleep(0.1)
            ann = json.loads(announce.read_text())
            v4 = [a for a in ann["addrs"] if not a.startswith("[")]
            # A FRESH DEVICE, provisioned the way one really is (plan 0014):
            # the config names the family's server and carries NO secret; the
            # key is minted into it on first use and only the public id comes
            # back out.
            bad_cfg = sdir / "intruder.json"
            bad_cfg.write_text(json.dumps({
                "relay": "none",
                "peer": ann["id"],
                "peerAddrs": v4,
                "adminUrl": f"http://127.0.0.1:{ENROLL_PORT}",
            }))
            intruder_id = mint_into(env, str(keygen_bin), bad_cfg)
            minted = json.loads(bad_cfg.read_text())
            check_mint = [
                (len(minted.get("secretKey", "")) == 64, "the mint wrote a secret key into the config"),
                (minted.get("peer") == ann["id"] and minted.get("adminUrl", "").startswith("http"),
                 "the mint preserved every other field"),
                ((bad_cfg.stat().st_mode & 0o777) == 0o600, "the config holding the secret is 0600"),
                (mint_into(env, str(keygen_bin), bad_cfg) == intruder_id,
                 "minting again is idempotent — the identity is stable across boots"),
            ]
            for good, what in check_mint:
                print(f"tunnel-smoke: device-minted identity: {'PASS' if good else 'FAIL'} — {what}")
                if not good:
                    ok = False
            r = subprocess.run(
                [str(client_bin), "integ", "login-syncing", "http://wata.iroh"],
                env={**env, "WATA_IROH_CONFIG": str(bad_cfg)},
                cwd=WATA, capture_output=True, text=True, timeout=60,
            )
            refused = "INTEG PASS" not in r.stdout
            combined = r.stdout + r.stderr
            loud = "not allowlisted" in combined
            print(f"tunnel-smoke: allowlist-negative: "
                  f"{'PASS' if refused else 'FAIL'} (refusal loud: {loud})")
            if not refused:
                print("---- intruder output (should have been refused!) ----")
                print(combined)
                ok = False
            if refused and not loud:
                # The refusal must carry its reason, not read as a generic
                # closed connection (plan 0013 M5, [IROH-REFUSAL-LOUD]) —
                # irohnet_client_dial formats "server refused: 401 not
                # allowlisted" and Dialer.DialContext logs it once. A
                # regression back to a bare "connection closed"/"{e:?}" dump
                # must fail the gate, not just print a quieter marker.
                print("---- intruder output (refused, but not loud) ----")
                print(combined)
                fail("allowlist-negative: refusal was not loud (no 'not allowlisted' reason reached the client)")
            # ---- 7. enrolment (plan 0021 M B): the refused node announces,
            # an admin approves it, and it is admitted by the SAME process.
            if refused and not enrol_leg(env, client_bin, bad_cfg, srv_cfg,
                                         intruder_id, spare_key["id"]):
                ok = False
        finally:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
            log.close()

    if ok:
        shutil.rmtree(tmp, ignore_errors=True)
        print("TUNNEL-SMOKE PASS (embedded iroh, relay none, + the admin TCP listener)")
        return 0
    print(f"tunnel-smoke: artifacts kept at {tmp}")
    print("TUNNEL-SMOKE FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
