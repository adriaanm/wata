#!/usr/bin/env python3
"""The wata-mac IROH gate (plan 0034): the mac client dialing a homeserver it
has no TCP route to.

    tools/mac-iroh-smoke.py         # run it
    MAC_IROH_SMOKE_KEEP=1 …         # keep the scratch dir on success

The mac client exists for a parent who is AWAY — a different network, behind a
NAT, no port forwarding — so the transport, not the window, is the thing that
makes it a client rather than a demo. This smoke runs exactly that shape:

  - one fresh wata-server in iroh mode. It also brings up the plain-TCP admin
    listener the mode always serves (plan 0021 — no browser can dial iroh),
    on a random free port, and NOTHING in this run touches it: both clients
    are aimed at `http://wata.iroh`, a host that resolves nowhere, which the
    smoke asserts before it starts. A fallback to TCP cannot silently rescue
    a broken iroh path here; it can only fail.
  - three provisioned node keys (server, the mac, the tui), with both client
    ids in the server's allowlist;
  - alice's headless wata-mac dials `http://wata.iroh` over that transport.
    Its NATIVE hierarchy showing the contact list is the assertion: login,
    sync and the room state all completed over iroh, or there is no list.
  - bob (a tui session over the SAME transport) sends a voice message
    mid-session, and the differ patches exactly the badge into the family row.

  - THE NEGATIVE, and the reason this client follows wata-fb's failure policy
    rather than wata-tui's: with `WATA_IROH_CONFIG` pointed at a config that
    cannot produce a client, the boot screen must say `transport unavailable`
    / `check config`. A silent downgrade to plain TCP would leave the parent
    reading `waiting for network` forever against a transport that was never
    coming up, so the absence of that string is asserted too.

Needs cargo (the Rust staticlib) on top of the repo's usual prerequisites, and
macOS. Standalone like mac-smoke — not in ci.
"""

import importlib.util
import os
import random
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import irohkit  # noqa: E402
from irohkit import IrohError  # noqa: E402
from toolchain import build_env, prepare  # noqa: E402

WATA = TOOLS.parent
FIXTURE = WATA / "go-pkgs" / "audio" / "testdata" / "tui-foreign.ogg"
PASSWORD = "testpass123"
# The homeserver URL over iroh: the host part is a placeholder the iroh dialer
# never resolves — every connection goes to the configured peer node id.
HS = "http://wata.iroh"
# The admin listener iroh mode always spawns beside the iroh one (plan 0021).
# Random, and unused by this smoke: it exists only so the server does not
# collide with whatever holds the default :8008 on the machine.
ADMIN_PORT = int(os.environ.get("MAC_IROH_SMOKE_PORT") or random.randint(20000, 39999))

BOB_SCRIPT = f"""snap
send 1 {FIXTURE}
quit
"""


def load_mac_smoke():
    """mac-smoke.py's session driver and assertion helpers, reused verbatim:
    the headless command protocol is the same protocol, and a second copy of
    it would drift. (The filename has a hyphen, hence the explicit load.)"""
    spec = importlib.util.spec_from_file_location("macsmoke", TOOLS / "mac-smoke.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def tui_env(env, cfg):
    return dict(env, WATA_TUI_HS=HS, WATA_TUI_USER="bob", WATA_TUI_PASS=PASSWORD,
                WATA_IROH_CONFIG=str(cfg))


def positive(ms, c, mac_bin, env, cli_cfg, tui_bin, bob_cfg):
    """alice over iroh: the contact list, then bob's message arriving."""
    sess = ms.MacSession(mac_bin, env, hs=HS,
                         extra_env={"WATA_IROH_CONFIG": str(cli_cfg)})
    try:
        sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 90)
        c.line(sess.lines, lambda l: l == "ready @alice:localhost",
               f"alice: never logged in over iroh, got {sess.lines!r}")

        first = sess.cmd("wait 800", lambda l: l == "waited 800")
        c.line(first, lambda l: l == "tree set", "first wait: no `tree set`")

        # the contact list as a NATIVE hierarchy — everything behind it (login,
        # sync, room state) travelled over iroh streams.
        t1 = ms.tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 119 24 8 "WATA"',
               "tree 1: no WATA title label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 103 36 8 "Family"',
               "tree 1: no Family row label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 95 18 8 "Bob"',
               "tree 1: no Bob row label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 7 102 8 "UP/DN sel OK open"',
               "tree 1: no footer legend")
        c.ok(not any('"1"' in l for l in t1), "tree 1: unplayed badge already present")

        # bob sends a voice message over the SAME transport, mid-session.
        bob = subprocess.run([tui_bin], input=BOB_SCRIPT, capture_output=True,
                             text=True, env=tui_env(env, bob_cfg), timeout=180)
        boblines = (bob.stdout + bob.stderr).splitlines()
        c.line(boblines, lambda l: l == "ready @bob:localhost",
               f"bob: never logged in over iroh, got {boblines!r}")
        c.line(boblines, lambda l: l.startswith("sent "), "bob: no `sent` line")

        # it arrives over alice's iroh sync, and patches exactly its row.
        arrival = sess.cmd("wait 8000", lambda l: l == "waited 8000")
        patches = [l for l in arrival if l.startswith("patch ")]
        c.ok(patches == ['patch insert [0.2.0] 2 badge:text(25,2,"1",65504)'],
             f"arrival: want exactly the badge insert, got {patches!r}")
        t2 = ms.tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t2, lambda l: l.strip() == 'NSTextField 150 103 6 8 "1"',
               "tree 2: no unplayed badge label")
    except TimeoutError as e:
        c.failed.append(str(e))
    finally:
        sess.quit()
    c.line(sess.lines, lambda l: l == "bye", "alice: no clean `bye`")
    return sess.lines


def negative(ms, c, mac_bin, env, tmp):
    """A configured transport that cannot be brought up must be NAMED. This is
    the whole point of following wata-fb's policy: the tui's silent downgrade
    to DefaultClient would show `waiting for network` here, forever."""
    bad = tmp / "no-such-config.json"  # deliberately absent: NewHTTPClient errors
    sess = ms.MacSession(mac_bin, env, hs=HS, extra_env={
        "WATA_IROH_CONFIG": str(bad),
        # the print is all this bound decides; both drivers keep pumping either
        # way, so the boot screen is reachable without waiting out the default.
        "WATA_MAC_CONNECT_MS": "3000",
    })
    try:
        sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
        c.line(sess.lines, lambda l: l == "login failed",
               f"negative: the client claimed to be ready, got {sess.lines!r}")
        c.line(sess.lines, lambda l: l.startswith("irohnet: client init failed"),
               f"negative: the failed init was not loud, got {sess.lines!r}")

        sess.cmd("wait 500", lambda l: l == "waited 500")
        t = ms.tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t, lambda l: l.strip().endswith('"transport unavailable"'),
               f"negative: the boot screen does not name the transport, got {t!r}")
        c.line(t, lambda l: l.strip().endswith('"check config"'),
               f"negative: no `check config` second line, got {t!r}")
        c.ok(not any("waiting for network" in l for l in t),
             "negative: the boot screen blamed the network for an unbringable transport")
    except TimeoutError as e:
        c.failed.append(str(e))
    finally:
        sess.quit()
    return sess.lines


def run(tmp):
    prepare()
    env = build_env()
    ms = load_mac_smoke()
    c = ms.Checks()

    print("mac-iroh-smoke: staging the irohnet staticlib (cargo)…")
    irohkit.stage_staticlib(env)
    print("mac-iroh-smoke: building wata-server, wata-tui, wata-mac (sgo, then -tags iroh)…")
    server_bin = irohkit.build_iroh_app(env, "wata-server")
    tui_bin = irohkit.build_iroh_app(env, "wata-tui")
    mac_bin = irohkit.build_iroh_app(env, "wata-mac")
    keygen_bin = irohkit.build_keygen(env, tmp / "irohnet-keygen")

    srv_key = irohkit.keygen(env, keygen_bin)
    mac_key = irohkit.keygen(env, keygen_bin)
    tui_key = irohkit.keygen(env, keygen_bin)

    # The client URL can ONLY work over iroh: if `wata.iroh` resolved, a plain
    # TCP client could reach something and the whole gate would be moot.
    try:
        socket.getaddrinfo("wata.iroh", 80)
        c.ok(False, "the homeserver host `wata.iroh` RESOLVES here — "
                    "this run cannot prove anything about the transport")
    except socket.gaierror:
        pass

    announce = tmp / "announce.json"
    srv_cfg = irohkit.server_config(tmp / "server.json", srv_key["secretKey"],
                                    [mac_key["id"], tui_key["id"]], announce)
    proc, log = irohkit.start_server(server_bin, env, srv_cfg, tmp / "server.log",
                                     listen=f":{ADMIN_PORT}")
    try:
        ann = irohkit.await_announce(announce, proc)
        mac_cfg = irohkit.client_config(tmp / "mac.json", mac_key["secretKey"], ann)
        bob_cfg = irohkit.client_config(tmp / "bob.json", tui_key["secretKey"], ann)

        alice = positive(ms, c, mac_bin, env, mac_cfg, tui_bin, bob_cfg)
        (tmp / "alice.log").write_text("\n".join(alice) + "\n")
        bad = negative(ms, c, mac_bin, env, tmp)
        (tmp / "negative.log").write_text("\n".join(bad) + "\n")
    finally:
        irohkit.stop_server(proc, log)
    return c.failed


def main():
    if sys.platform != "darwin":
        sys.exit("mac-iroh-smoke: macOS only")
    tmp = Path(tempfile.mkdtemp(prefix="mac-iroh-smoke-"))
    try:
        failed = run(tmp)
    except IrohError as e:
        print(f"mac-iroh-smoke: {e}")
        print(f"mac-iroh-smoke: scratch kept at {tmp}")
        print("MAC-IROH-SMOKE FAIL")
        return 1
    if failed:
        print(f"mac-iroh-smoke: scratch kept at {tmp}")
        for f in failed:
            print(f"FAIL: {f}")
        print("MAC-IROH-SMOKE FAIL")
        return 1
    if os.environ.get("MAC_IROH_SMOKE_KEEP"):
        print(f"mac-iroh-smoke: scratch kept at {tmp}")
    else:
        subprocess.run(["rm", "-rf", str(tmp)])
    print("PASS mac-iroh-smoke (embedded iroh, relay none, an unresolvable homeserver host)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
