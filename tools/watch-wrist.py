#!/usr/bin/env python3
"""`just watch-wrist` — plan 0069's hardware legs on the real Apple Watch.

The two open device questions (WATCH-INPUT-DELIVERY, WATCH-AUDIO) need the
owner's fingers, so this is not a gate: it is the scriptable half of a wrist
session, in stages the owner's actions fall between. The app under test is
whatever `just watch-device` installed; the server is a LAN wata-server the
watch reaches over wifi, and the evidence is the app's own sandbox log
pulled off the watch afterwards plus the server's word on what landed.

  serve    build wata-server + wata-tui, run the server on 0.0.0.0:<port>
           (prints the LAN base), foreground until Ctrl-C
  launch   devicectl-launch the app with the LAN base + alice's credentials
           as argv — the config PERSISTS in the sandbox, so later icon-tap
           launches rejoin the same session with no arguments
  send     bob sends a voice message into the family room (host-side tui)
  log      pull the app's sandbox log off the watch and print it
  check    bob snapshots the family room server-side, prints msgs=N

A full wrist session: `serve` in one terminal; `launch`; raise the wrist and
watch the contact list paint; `send` and expect the watch to notify (and in
walkie-talkie mode play) bob's message; tap / turn the crown / swipe / long
press and speak; then `log` must show the matching `input:` lines (delivery),
`audio: recorded`/`audio: playback done` (the real-audio round trip), and
`check` must count the wrist's message server-side.

Port: $WATCH_WRIST_PORT, default 28008 — FIXED, not random, because `serve`
and `launch` are separate invocations that must agree.
"""

import argparse
import importlib.util
import json
import os
import pathlib
import signal
import socket
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
import simrun  # noqa: E402

_spec = importlib.util.spec_from_file_location("watch_device", HERE / "watch-device.py")
watch_device = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(watch_device)

PORT = int(os.environ.get("WATCH_WRIST_PORT") or 28008)
PASSWORD = "testpass123"
SERVER_LOG = REPO / "wata-watch" / ".sgo" / "wrist-server.log"


def lan_ip():
    """The Mac's LAN address — what the watch must dial. A UDP connect
    resolves the outbound interface without sending anything."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 1))
        return s.getsockname()[0]
    finally:
        s.close()


def base():
    return f"http://{lan_ip()}:{PORT}"


def build_env():
    probe = (
        f'set -e; cd "{REPO}"; WATA="{REPO}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" '
        '"$(emitdir wata-server)/$(binname wata-server)" '
        '"$(emitdir wata-tui)/$(binname wata-tui)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("watch-wrist: sgo environment probe failed:\n" + out.stderr)
    server, tui, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return server, tui, env


def sgo_build(module, env):
    r = subprocess.run([str(REPO / "tools" / "sgo"), "build"],
                       cwd=REPO / module, env=env)
    if r.returncode != 0:
        sys.exit(f"watch-wrist: {module} build failed")


def serve(args):
    server_bin, _tui, env = build_env()
    sgo_build("wata-server", env)
    sgo_build("wata-tui", env)
    SERVER_LOG.parent.mkdir(parents=True, exist_ok=True)
    log = open(SERVER_LOG, "wb")
    proc = subprocess.Popen([server_bin, f":{PORT}"], stdout=log, stderr=log,
                            env=env)
    print(f"watch-wrist: server up at {base()}  (log: {SERVER_LOG})")
    print("watch-wrist: Ctrl-C stops it")
    try:
        proc.wait()
        sys.exit(f"watch-wrist: server EXITED rc={proc.returncode} — see "
                 f"{SERVER_LOG}")
    except KeyboardInterrupt:
        proc.send_signal(signal.SIGTERM)
        proc.wait(10)


def launch(args):
    dev = watch_device.pick_device()
    bid = watch_device.bundle_id()
    b = args.base or base()
    cmd = ["xcrun", "devicectl", "device", "process", "launch",
           "--terminate-existing", "--device", dev, bid,
           b, "alice", PASSWORD]
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)
    print(f"watch-wrist: launched against {b} — raise the wrist; the contact "
          "list should paint once alice's login lands")


def send(args):
    _server, tui, env = build_env()
    ok, _at, _lines = simrun.tui_send(tui, env, args.base or base(),
                                      tag="watch-wrist")
    if not ok:
        sys.exit("watch-wrist: bob's tui never reported `sent`")


def log(args):
    dev = watch_device.pick_device()
    bid = watch_device.bundle_id()
    subprocess.run([sys.executable, str(HERE / "ios-log.py"),
                    "--device", dev, "--bundle-id", bid], check=True)


def check(args):
    _server, tui, env = build_env()
    b = args.base or base()
    senv = dict(env, WATA_TUI_HS=b, WATA_TUI_USER="bob",
                WATA_TUI_PASS=PASSWORD)
    r = subprocess.run([tui], input="snap\nquit\n", text=True,
                       capture_output=True, env=senv, timeout=120)
    for line in r.stdout.split("\n"):
        if line.startswith("conv 1 family") and "msgs=" in line:
            print(line)
            return
    sys.exit("watch-wrist: no family conversation in bob's snapshot:\n" +
             r.stdout)


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="stage", required=True)
    for name, fn in (("serve", serve), ("launch", launch), ("send", send),
                     ("log", log), ("check", check)):
        p = sub.add_parser(name)
        p.set_defaults(fn=fn)
        if name in ("launch", "send", "check"):
            p.add_argument("--base", default=None,
                           help="server base URL (default: this Mac's LAN "
                                f"IP, port {PORT})")
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
