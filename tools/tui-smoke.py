#!/usr/bin/env python3
"""The wata-tui gate: two scripted tui sessions against one fresh wata-server.

    tools/tui-smoke.py          # run it
    TUI_SMOKE_KEEP=1 …          # keep the scratch dir on success

Bob sends a canned Ogg to alice; alice then walks the REPL — snap, msgs, play,
raw, snap again — and every assertion is made on the printed lines, which is
what the line-oriented REPL exists for (docs/plans/0016-tui-port.md).

The play leg is the sharp one: `$WATA_TUI_PLAYER` points at a stub that records
its argv and copies the file it was handed, so the harness byte-compares what
came back out of the media repo against what went in, and the following `snap`
proves the receipt the play sent turned the message played server-side.

Hermetic — no device, no network beyond localhost.
"""

import os
import random
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(WATA, "go-pkgs", "audio", "testdata", "tui-foreign.ogg")
PASSWORD = "testpass123"
# A RANDOM port per run (TUI_SMOKE_PORT overrides): a fixed port collides with
# a sibling checkout running this same harness, and the survivor would serve
# both runs' sessions — foreign sends corrupt this run's message counts.
PORT = int(os.environ.get("TUI_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

# The player stub: record the argv, keep a copy of the file it was given.
PLAYER = """#!/bin/sh
echo "$@" >> "$TUI_SMOKE_ARGV"
cp "$1" "$TUI_SMOKE_PLAYED"
"""

BOB_SCRIPT = """send @alice:localhost {ogg}
quit
"""

ALICE_SCRIPT = """wait 3000
snap
msgs 1
play 1 1
wait 4000
snap
raw GET /_matrix/client/v3/account/whoami
quit
"""


# ---- the sgo build environment -------------------------------------------------
# tools/sgo-env.sh + tools/emitdir.sh are the single source of truth for the
# toolchain and the emit layout; ask them rather than re-deriving either here.
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-tui)/$(binname wata-tui)" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("tui-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, tui, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, tui, server, env


def build(sgo, env):
    for module in ("wata-server", "wata-tui"):
        r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, module),
                           capture_output=True, text=True, env=env)
        if r.returncode != 0:
            sys.exit(f"tui-smoke: {module} build failed:\n{r.stdout}{r.stderr}")


# ---- the hermetic server -----------------------------------------------------
def our_listener(pid):
    """True when `pid` is the process listening on PORT. A wata-server that
    lost a bind race exits ZERO (the subset has no os.Exit facade), and a
    foreign squatter on the port would answer a bare readiness probe — the
    listener's identity is the only trustworthy readiness signal."""
    r = subprocess.run(["lsof", "-ti", f"tcp:{PORT}", "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return str(pid) in r.stdout.split()


def start_server(binary, log_path, env):
    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, f":{PORT}"], stdout=log, stderr=log, env=env)
    for _ in range(200):
        if proc.poll() is not None:
            return None, log
        if our_listener(proc.pid):
            try:
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions", timeout=0.5).read()
                return proc, log
            except (urllib.error.URLError, OSError):
                pass
        time.sleep(0.1)
    proc.kill()
    return None, log


def stop_server(proc, log):
    if proc is not None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
    log.close()


# ---- one tui session -----------------------------------------------------------
def session(tui, user, script, env, tmp):
    """Run one tui with `script` on stdin; returns its output lines."""
    senv = dict(env, WATA_TUI_HS=BASE, WATA_TUI_USER=user, WATA_TUI_PASS=PASSWORD,
                WATA_TUI_TMPDIR=tmp, WATA_TUI_PLAYER=os.path.join(tmp, "player.sh"),
                TUI_SMOKE_ARGV=os.path.join(tmp, "argv.txt"),
                TUI_SMOKE_PLAYED=os.path.join(tmp, "played.ogg"))
    r = subprocess.run([tui], input=script, capture_output=True, text=True,
                       env=senv, timeout=180)
    return (r.stdout + r.stderr).splitlines()


# ---- assertions ----------------------------------------------------------------
class Checks:
    def __init__(self):
        self.failed = []

    def ok(self, cond, what):
        if not cond:
            self.failed.append(what)

    def line(self, lines, pred, what):
        self.ok(any(pred(ln) for ln in lines), what)


def field(line, key):
    """`key=value` out of a printed line, or None."""
    for tok in line.split():
        if tok.startswith(key + "="):
            return tok[len(key) + 1:]
    return None


def run(tmp):
    sgo, tui, server_bin, env = build_env()
    build(sgo, env)

    with open(os.path.join(tmp, "player.sh"), "w") as f:
        f.write(PLAYER)
    os.chmod(os.path.join(tmp, "player.sh"), 0o755)

    proc, log = start_server(server_bin, os.path.join(tmp, "server.log"), env)
    if proc is None:
        return ["server never became ready"], [], []
    try:
        bob = session(tui, "bob", BOB_SCRIPT.format(ogg=FIXTURE), env, tmp)
        alice = session(tui, "alice", ALICE_SCRIPT, env, tmp)
    finally:
        stop_server(proc, log)

    c = Checks()
    # bob: logged in and the send completed.
    c.line(bob, lambda l: l == "ready @bob:localhost", "bob: no `ready @bob:localhost`")
    c.line(bob, lambda l: l.startswith("sent "), "bob: no `sent` line")

    c.line(alice, lambda l: l == "ready @alice:localhost", "alice: no `ready` line")

    # snap #1: the DM with bob, one message, one unplayed.
    convs = [l for l in alice if l.startswith("conv ")]
    c.ok(len(convs) == 2, f"alice: want 2 conv lines (one per snap), got {len(convs)}")
    if len(convs) == 2:
        first, second = convs
        c.ok(first.startswith("conv 1 dm @bob:localhost !"),
             f"alice: first snap's conversation is not bob's DM: {first!r}")
        c.ok(field(first, "msgs") == "1" and field(first, "unplayed") == "1",
             f"alice: first snap wants msgs=1 unplayed=1: {first!r}")
        # snap #2, after play: the receipt landed, nothing unplayed.
        c.ok(field(second, "msgs") == "1" and field(second, "unplayed") == "0",
             f"alice: second snap wants msgs=1 unplayed=0: {second!r}")

    # msgs: exactly bob's one message, not yet played at listing time.
    msgs = [l for l in alice if l.startswith("msg ")]
    c.ok(len(msgs) == 1, f"alice: want 1 msg line, got {len(msgs)}")
    if msgs:
        c.ok(msgs[0].startswith("msg 1 @bob:localhost"), f"alice: msg sender: {msgs[0]!r}")
        c.ok(field(msgs[0], "played") == "false", f"alice: msg already played: {msgs[0]!r}")
        c.ok((field(msgs[0], "mxc") or "").startswith("mxc://"),
             f"alice: msg has no mxc url: {msgs[0]!r}")

    # play: downloaded, handed to the player, receipt sent.
    plays = [l for l in alice if l.startswith("play ")]
    c.ok(len(plays) == 1, f"alice: want 1 play line, got {len(plays)}")
    want = os.path.getsize(FIXTURE)
    if plays:
        c.ok(field(plays[0], "bytes") == str(want),
             f"alice: play downloaded {field(plays[0], 'bytes')} bytes, want {want}")
    c.line(alice, lambda l: l == "player ok", "alice: player did not exit zero")
    c.line(alice, lambda l: l.startswith("marked $"), "alice: no `marked` line")

    # the player stub saw the file, and it is the Ogg bob sent, byte for byte.
    argv = os.path.join(tmp, "argv.txt")
    played = os.path.join(tmp, "played.ogg")
    c.ok(os.path.exists(argv), "player stub was never invoked")
    if os.path.exists(argv):
        given = open(argv).read().split()
        c.ok(len(given) == 1 and given[0].startswith(tmp) and given[0].endswith(".ogg"),
             f"player argv is not one Ogg under the scratch dir: {given}")
    c.ok(os.path.exists(played) and open(played, "rb").read() == open(FIXTURE, "rb").read(),
         "the played Ogg differs from the one bob sent")

    # raw: the admin escape hatch answers authenticated.
    raws = [l for l in alice if l.startswith("raw ")]
    c.ok(len(raws) == 1 and raws[0].startswith("raw 200 "),
         f"alice: raw whoami did not answer 200: {raws}")
    c.line(alice, lambda l: '"user_id":"@alice:localhost"' in l,
           "alice: raw whoami body is not alice's")

    c.line(alice, lambda l: l == "bye", "alice: no `bye` (quit did not run)")
    return c.failed, bob, alice


def main():
    tmp = tempfile.mkdtemp(prefix="tui-smoke.")
    try:
        failed, bob, alice = run(tmp)
    except Exception as e:  # noqa: BLE001 — report the scratch dir, then re-raise
        print(f"tui-smoke: scratch kept at {tmp}")
        raise e
    if failed:
        print("---- bob ----")
        print("\n".join(bob))
        print("---- alice ----")
        print("\n".join(alice))
        print("\n==== wata-tui smoke ====")
        for f in failed:
            print(f"  FAIL {f}")
        print(f"tui-smoke: scratch kept at {tmp}")
        sys.exit(1)
    if not os.environ.get("TUI_SMOKE_KEEP"):
        shutil.rmtree(tmp, ignore_errors=True)
    else:
        print(f"tui-smoke: scratch kept at {tmp}")
    print("\n".join(alice))
    print("tui-smoke: PASS")


if __name__ == "__main__":
    main()
