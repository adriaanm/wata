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
  6. the allowlist negative, against a server in the BOOTSTRAP state: an
     EMPTY allowlist — a fresh install that has approved nobody — listens
     anyway, and a FRESH DEVICE IDENTITY — minted by irohnet.EnsureKey into a
     config that was deployed with no secret at all (plan 0014 milestone 1),
     the same call wata-fb makes on first boot — is refused at accept, loudly;
  7. enrolment (plan 0021 milestone B + plan 0014): that same refused node
     announces itself, an admin approves it over the admin listener — the
     first entry in the previously-empty allowlist — and it is then accepted:
     by the same SERVER process and, in the second half, by the same CLIENT
     process, which is left running across the approval and has to redial its
     way in on its own retry cadence;
  8. un-enrolment (plan 0058): the node that just synced is revoked — the
     live listener stops admitting it, its node-minted session rows die
     (revoked_sessions >= 1), a fresh dial is refused loudly — and then
     recovered through the ordinary announce/approve loop, same server
     process throughout.

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
import irohkit  # noqa: E402
from irohkit import IROHNET, WATA, IrohError  # noqa: E402
from toolchain import build_env, prepare  # noqa: E402

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


# The provisioning and boot machinery is shared with tools/mac-iroh-smoke.py
# (tools/irohkit.py): keys, config files, the two-step `-tags iroh` build, and
# a server that announces itself. Only this file's verdict lines are local.
keygen = irohkit.keygen
mint_into = irohkit.mint_into
start_server = irohkit.start_server


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
def api(base, method, path, body=None, token=None, headers=None):
    """-> (status, parsed body). The enrolment leg's whole client."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
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


def wait_for_line(log_path, needle, proc, timeout_s):
    """Poll a running process's log until it prints `needle` (or it exits)."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if log_path.exists() and needle in log_path.read_text(errors="replace"):
            return True
        if proc.poll() is not None:
            return log_path.exists() and needle in log_path.read_text(errors="replace")
        time.sleep(0.2)
    return False


def enrol_leg(env, client_bin, cli_cfg_dir, srv_cfg, intruder_id, spare_id, sdir):
    """The approve loop end to end (plan 0021 milestone B + plan 0027): the
    node the server just refused announces itself, an admin approves it
    through the admin API WITH AN ACCOUNT — the inline create-and-bind: the
    name is new, so the approve creates a passwordless account and binds it
    to the node id in one step — and the SAME key is then accepted, no server
    restart. The durable half (the config file's allowlist), the live half
    (irohnet_server_allow), and the binding are all asserted.

    THE CLIENT IS NOT RESTARTED EITHER (plan 0014, [FB-REDIAL-AFTER-REFUSAL]),
    AND IT CARRIES NO CREDENTIALS (plan 0027). The approval lands while a
    `wata-fb integ refused-then-provisioned` process is running and refused;
    that process has to redial its way in on the sync loop's own cadence,
    trade its proven node id for a session through POST /_wata/v1/device-login
    (there is no password to fall back on), reach an authenticated sync AS the
    bound account (WATA_EXPECT_USER pins whose token came back), and take its
    QR screen down (`Enrol.refused()` reads false again). That is the
    zero-manual-steps acceptance arc: enrol -> approve-with-account ->
    device-login -> sync, one client process end to end.

    Once the id is in the allowlist, "already enrolled" has to be an ANSWER
    rather than an empty pending list ([ADMIN-ENROLL-ALREADY-DONE]): the
    re-announce a page performs on a reload — and on the refresh right after a
    successful approve — must say so instead of reading as an expiry.

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

    # device-login over the admin TCP listener of an IROH-mode process: 403,
    # even carrying a forged trusted header — the dual listener serves through
    # the same strip as a plain-TCP deployment (plan 0027's TCP half, proven
    # against the very server whose iroh listener will honor the real thing).
    status, body = api(base, "POST", "/_wata/v1/device-login", {},
                       headers={"X-Wata-Node-Id": intruder_id})
    check(status == 403,
          f"device-login over TCP is 403 even with a forged node-id header (saw {status} {body})")

    # THE CLIENT IS ALREADY RUNNING when the approval lands: start it, wait for
    # it to report the refusal it is sitting on, and only then approve. It has
    # NO credentials — after admission, only device-login can produce its
    # session, and it must belong to the account bound below.
    log_path = sdir / "redial-client.log"
    log = log_path.open("w")
    proc = subprocess.Popen(
        [str(client_bin), "integ", "refused-then-provisioned", "http://wata.iroh"],
        env={**env, "WATA_IROH_CONFIG": str(cli_cfg_dir),
             "WATA_EXPECT_USER": "@kid1:localhost"},
        cwd=WATA, stdout=log, stderr=subprocess.STDOUT, text=True,
    )
    try:
        if not check(wait_for_line(log_path, "INTEG REFUSED", proc, 60),
                     "the running client reports the refusal it is sitting on"):
            print(log_path.read_text(errors="replace"))
            return False

        # approve WITH the inline create-and-bind: "kid1" names no existing
        # account, so this one call creates it (passwordless) and binds it.
        status, body = api(base, "POST", f"/_wata/v1/admin/enroll/{intruder_id}/approve",
                           {"user": "kid1"}, token)
        check(status == 200, f"approve answers 200 (saw {status} {body})")
        check(body.get("live") is True,
              f"approve applied to the LIVE listener (note: {body.get('note')!r})")
        check(body.get("user_id") == "@kid1:localhost",
              f"approve created and bound the inline account (saw {body})")
        check(intruder_id in json.loads(srv_cfg.read_text())["allowlist"],
              "approve appended the node id to the server's iroh config allowlist")
        _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
        check(listing.get("pending") == [], "the approved row cleared")
        check(intruder_id in (listing.get("allowlisted") or []),
              "the listing reports the approved id as allowlisted (the page's 'already enrolled')")
        check({b["node_id"]: b["user"] for b in listing.get("bindings", [])}.get(intruder_id) == "kid1",
              "the listing reports the binding beside the enrolled id")
        check("kid1" in {u.get("user") for u in listing.get("users", [])},
              "the inline-created account is on the roster the picker reads")
        _, again = api(base, "POST", "/_wata/v1/enroll",
                       {"nodeId": intruder_id, "nonce": "QR01"})
        check(again.get("allowlisted") is True and again.get("pending") is False,
              f"re-announcing an enrolled id answers 'already enrolled', not an expiry (saw {again})")
        _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
        check(listing.get("pending") == [],
              "and it leaves no pending row behind — there is nothing left to decide")

        # the point of the whole leg: the SAME key, the SAME server process,
        # and the SAME client process that was refused a moment ago — now
        # syncing as the account the approve bound, with no credential ever
        # typed anywhere (the device-login positive).
        try:
            proc.wait(timeout=240)
        except subprocess.TimeoutExpired:
            proc.kill()
        out = log_path.read_text(errors="replace")
        if not check("INTEG PASS" in out,
                     "the approved node device-logs-in as the bound account, "
                     "with NO restart — of the server OR the client"):
            print("---- approved-client output ----")
            print(out)
    finally:
        if proc.poll() is None:
            proc.kill()
        log.close()
    if good:
        good = revoke_leg(env, client_bin, cli_cfg_dir, srv_cfg, intruder_id,
                          base, token)
    return good


def revoke_leg(env, client_bin, cli_cfg_dir, srv_cfg, node, base, token):
    """Un-enrolment over the REAL transport (plan 0058) — the half the TCP
    admin smoke cannot reach. The node that just synced is revoked: the live
    listener must stop admitting it (irohnet.Disallow, `live: true`), the
    node-minted session rows must die (`revoked_sessions` counts REAL
    device-login rows here — the TCP smoke can only ever see 0), and a FRESH
    DIAL from the same key must be refused with the ordinary loud refusal.
    Then the recovery arc the plan promises: re-announce, approve again with
    the still-existing account, and the same key is back in — no restart of
    the server anywhere in the story.

    The token-dead-on-TCP half of a revocation rides the same
    `Store.dropDevice` that account removal uses, which the admin smoke
    already pins mid-session; what is asserted here is that the node-minted
    rows are FOUND (the count), which is the plan-0058 half.

    Returns True when every check held."""
    good = True

    def check(cond, what):
        nonlocal good
        print(f"tunnel-smoke: un-enrol: {'PASS' if cond else 'FAIL'} — {what}")
        if not cond:
            good = False
        return cond

    # session visibility (plan 0059), the half only a real device-login can
    # show: the node that just synced has a session, so the enroll listing
    # carries its last-seen (the TCP admin smoke asserts the absence half).
    _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
    seen = {r["node_id"]: r["age_ms"] for r in listing.get("last_seen", [])}
    check(node in seen and seen[node] >= 0,
          f"the enroll listing reports the node's last-seen — its session spoke (saw {seen.get(node)})")

    status, body = api(base, "POST", f"/_wata/v1/admin/enroll/{node}/revoke", None, token)
    check(status == 200, f"revoke answers 200 (saw {status} {body})")
    check(body.get("live") is True,
          f"revoke applied to the LIVE listener (note: {body.get('note')!r})")
    check(body.get("revoked_sessions", 0) >= 1,
          f"the node-minted session row was found and revoked (saw {body.get('revoked_sessions')})")
    check(node not in json.loads(srv_cfg.read_text())["allowlist"],
          "the id left the server's iroh config allowlist")
    _, listing = api(base, "GET", "/_wata/v1/admin/enroll", None, token)
    check(node not in (listing.get("allowlisted") or []),
          "the listing no longer reports it as enrolled")
    check(node not in {b["node_id"] for b in listing.get("bindings", [])},
          "the binding is dropped")
    check(node not in {r["node_id"] for r in listing.get("last_seen", [])},
          "its last_seen row is gone with its sessions")

    r = subprocess.run(
        [str(client_bin), "integ", "login-syncing", "http://wata.iroh"],
        env={**env, "WATA_IROH_CONFIG": str(cli_cfg_dir)},
        cwd=WATA, capture_output=True, text=True, timeout=60,
    )
    combined = r.stdout + r.stderr
    check("INTEG PASS" not in r.stdout and "not allowlisted" in combined,
          "a FRESH dial from the revoked key is refused at the transport, loudly")

    # the recovery: exactly today's arc, from zero — announce, approve (the
    # account still exists, so the approve binds it as-is), fresh dial in.
    status, body = api(base, "POST", "/_wata/v1/enroll", {"nodeId": node, "nonce": "RV01"})
    check(status == 200 and body.get("pending") is True and body.get("allowlisted") is False,
          f"the revoked node's re-announce is pending again, not 'already enrolled' (saw {body})")
    status, body = api(base, "POST", f"/_wata/v1/admin/enroll/{node}/approve",
                       {"user": "kid1"}, token)
    check(status == 200 and body.get("live") is True,
          f"re-approval works and applies live (saw {status} {body})")
    r = subprocess.run(
        [str(client_bin), "integ", "login-syncing", "http://wata.iroh"],
        env={**env, "WATA_IROH_CONFIG": str(cli_cfg_dir)},
        cwd=WATA, capture_output=True, text=True, timeout=60,
    )
    if not check("INTEG PASS" in r.stdout,
                 "after re-approval the same key is admitted again — no server restart"):
        print("---- re-admitted client output ----")
        print(r.stdout + r.stderr)
    return good


def main():
    prepare()
    env = build_env()
    tmp = Path(tempfile.mkdtemp(prefix="tunnel-smoke."))

    # ---- 1. the glue's own tests -------------------------------------------
    print("tunnel-smoke: staging the irohnet staticlib (cargo)…")
    irohkit.stage_staticlib(env)
    print("tunnel-smoke: irohnet glue tests (go test -tags iroh)…")
    # 360s: the suite includes the aged-refusal leg (aged_refusal_test.go),
    # which holds a refused client open for a real minute before approving it.
    if run(["go", "test", "-tags", "iroh", "-count=1", "-timeout", "360s", "./"], env, cwd=IROHNET).returncode != 0:
        fail("irohnet glue tests failed")

    # ---- 2. builds ---------------------------------------------------------
    print("tunnel-smoke: building wata-server + wata-fb (sgo, then -tags iroh)…")
    server_bin = irohkit.build_iroh_app(env, "wata-server")
    client_bin = irohkit.build_iroh_app(env, "wata-fb")
    keygen_bin = irohkit.build_keygen(env, tmp / "irohnet-keygen")

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
        # An EMPTY allowlist — the BOOTSTRAP state of a fresh install, which
        # has approved nobody yet. The server must come up and listen anyway
        # (the announce below proves it), refuse every peer with the ordinary
        # loud refusal, and admit the first node the enrolment leg approves
        # into that empty list live, same process. A guard that refused to
        # even start on an empty allowlist forced installs to ship a
        # placeholder node id; this leg pins its absence.
        srv_cfg.write_text(json.dumps({
            "secretKey": srv_key["secretKey"],
            "relay": "none",
            "allowlist": [],
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
                                         intruder_id, spare_key["id"], sdir):
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
    try:
        sys.exit(main())
    except IrohError as e:
        fail(str(e))
