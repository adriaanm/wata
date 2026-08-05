#!/usr/bin/env python3
"""The iroh tunnel smoke (plan 0013 milestone 1): wata-server serving over an
EMBEDDED iroh listener + a wataclient session over an embedded iroh dial —
two processes, one machine, no real network (relay "none", loopback UDP
only, no TCP port anywhere).

Steps:
  1. the irohnet glue's own tests (go test -tags iroh: net/http over iroh,
     allowlist refusal at accept, deadline interruption, Close semantics);
  2. sgo-build both apps, then go-build them with `-tags iroh` (the only
     builds in the repo that need cargo — mklib.py stages the staticlib);
  3. provision two fresh node keys, allowlist the client's id on the server;
  4. boot wata-server with WATA_IROH_CONFIG, read its announce file;
  5. run integ scenarios (integ.scala) over iroh — a FRESH server per
     scenario, exactly like tools/wataclient-integ.sh.

Prints TUNNEL-SMOKE PASS / FAIL. Needs cargo (the Rust toolchain) on top of
the repo's usual prerequisites — this recipe and fb-deploy's successors are
the only places that do.
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
IROHNET = WATA / "go-pkgs" / "irohnet"
# a fresh server per scenario, the wataclient-integ.sh discipline.
SCENARIOS = ["login-syncing", "voice-to-bob"]


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


def start_server(server_bin, env, cfg_path, log_path):
    log = open(log_path, "w")
    proc = subprocess.Popen(
        [server_bin],
        env={**env, "WATA_IROH_CONFIG": str(cfg_path)},
        stdout=log,
        stderr=subprocess.STDOUT,
        cwd=WATA,
    )
    return proc, log


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
        proc, log = start_server(str(server_bin), env, srv_cfg, sdir / "server.log")
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
        intruder_key = keygen(env, str(keygen_bin))
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
        proc, log = start_server(str(server_bin), env, srv_cfg, sdir / "server.log")
        try:
            for _ in range(100):
                if announce.exists():
                    break
                time.sleep(0.1)
            ann = json.loads(announce.read_text())
            v4 = [a for a in ann["addrs"] if not a.startswith("[")]
            bad_cfg = sdir / "intruder.json"
            bad_cfg.write_text(json.dumps({
                "secretKey": intruder_key["secretKey"],
                "relay": "none",
                "peer": ann["id"],
                "peerAddrs": v4,
            }))
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
        finally:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
            log.close()

    if ok:
        shutil.rmtree(tmp, ignore_errors=True)
        print("TUNNEL-SMOKE PASS (embedded iroh, relay none, no TCP port)")
        return 0
    print(f"tunnel-smoke: artifacts kept at {tmp}")
    print("TUNNEL-SMOKE FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
