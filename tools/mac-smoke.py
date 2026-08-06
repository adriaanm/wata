#!/usr/bin/env python3
"""The wata-mac gate: the headless AppKit client against one fresh wata-server.

    tools/mac-smoke.py          # run it
    MAC_SMOKE_KEEP=1 …          # keep the scratch dir on success

Alice runs wata-mac in WATA_MAC_HEADLESS mode — the retained NSView stage on
its own locked thread, no window, no runloop — driven over stdin exactly the
way tui-smoke drives the tui. The smoke asserts three layers at once:

  - the NATIVE hierarchy (`tree` walks the live NSViews: classes, frames,
    label strings) shows the contact list the fb bodies describe;
  - a message bob sends MID-SESSION (via the tui) arrives over sync and the
    printed differ script patches EXACTLY the row it names — the unplayed
    badge inserted into the family row, nothing else;
  - the key path (`key <name> press/release` feeds macOS virtual key codes
    through nativeui's real translation table) opens the conversation, whose
    native tree then shows bob's message row.

Hermetic — no device, no window, no network beyond localhost. macOS only.
"""

import os
import queue
import random
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(WATA, "go-pkgs", "audio", "testdata", "tui-foreign.ogg")
PASSWORD = "testpass123"
# A random port per run (MAC_SMOKE_PORT overrides): the same collision
# reasoning as tui-smoke.
PORT = int(os.environ.get("MAC_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

BOB_SCRIPT = f"""snap
send 1 {FIXTURE}
quit
"""


# ---- the sgo build environment (tools/sgo-env.sh is the source of truth) ----
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-mac)/$(binname wata-mac)" '
        '"$(emitdir wata-tui)/$(binname wata-tui)" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("mac-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, mac, tui, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, mac, tui, server, env


def build(sgo, env):
    for module in ("wata-server", "wata-tui", "wata-mac"):
        r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, module),
                           capture_output=True, text=True, env=env)
        if r.returncode != 0:
            sys.exit(f"mac-smoke: {module} build failed:\n{r.stdout}{r.stderr}")


# ---- the hermetic server (pid-matched listener, as tui-smoke) ---------------
def our_listener(pid):
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


# ---- the interactive headless session ---------------------------------------
class MacSession:
    """wata-mac headless, driven line by line: write a command, read until its
    terminator. Every line the app prints is kept for the final assertions."""

    def __init__(self, binary, env):
        senv = dict(env, WATA_MAC_HS=BASE, WATA_MAC_USER="alice",
                    WATA_MAC_PASS=PASSWORD, WATA_MAC_HEADLESS="1",
                    WATA_MAC_SCALE="1")
        self.proc = subprocess.Popen([binary], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True,
                                     env=senv)
        self.lines = []
        self.q = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        for line in self.proc.stdout:
            self.q.put(line.rstrip("\n"))
        self.q.put(None)

    def read_until(self, stop, timeout=60):
        """collect lines until one satisfies `stop`; returns them (inclusive)."""
        got = []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = self.q.get(timeout=deadline - time.monotonic())
            except queue.Empty:
                break
            if line is None:
                break
            got.append(line)
            self.lines.append(line)
            if stop(line):
                return got
        raise TimeoutError(f"mac-smoke: never saw the expected line; got {got!r}")

    def cmd(self, line, stop, timeout=60):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()
        return self.read_until(stop, timeout)

    def quit(self):
        try:
            self.proc.stdin.write("quit\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError):
            pass
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        # drain whatever the app printed on its way out (`bye` included).
        while True:
            try:
                line = self.q.get(timeout=2)
            except queue.Empty:
                break
            if line is None:
                break
            self.lines.append(line)


# ---- assertions --------------------------------------------------------------
class Checks:
    def __init__(self):
        self.failed = []

    def ok(self, cond, what):
        if not cond:
            self.failed.append(what)

    def line(self, lines, pred, what):
        self.ok(any(pred(ln) for ln in lines), what)


def tree_of(lines):
    """the hierarchy block out of a `tree` command's lines (drop `tree end`)."""
    return [ln for ln in lines if ln != "tree end"]


def run(tmp):
    sgo, mac_bin, tui_bin, server_bin, env = build_env()
    build(sgo, env)

    proc, log = start_server(server_bin, os.path.join(tmp, "server.log"), env)
    if proc is None:
        return ["server never became ready"], []

    c = Checks()
    sess = None
    try:
        sess = MacSession(mac_bin, env)
        sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
        c.line(sess.lines, lambda l: l == "ready @alice:localhost",
               "alice: no `ready @alice:localhost`")

        # the first frame mounts the whole tree.
        first = sess.cmd("wait 800", lambda l: l == "waited 800")
        c.line(first, lambda l: l == "tree set", "first wait: no `tree set`")

        # the contact list as a NATIVE hierarchy: title, connectivity, the
        # family row (selected: an NSBox highlight), bob's DM row, the footer
        # — and no unplayed badge yet.
        t1 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 119 24 8 "WATA"',
               "tree 1: no WATA title label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 138 119 18 8 "NET"',
               "tree 1: no NET connectivity label")
        c.line(t1, lambda l: l.strip() == 'NSBox 0 103 160 8',
               "tree 1: no selection highlight NSBox on the family row")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 103 36 8 "Family"',
               "tree 1: no Family row label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 95 18 8 "Bob"',
               "tree 1: no Bob row label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 0 7 102 8 "UP/DN sel OK open"',
               "tree 1: no footer legend")
        c.ok(not any('"1"' in l for l in t1), "tree 1: unplayed badge already present")

        # bob sends a voice message to the family room MID-SESSION, via the tui.
        tenv = dict(env, WATA_TUI_HS=BASE, WATA_TUI_USER="bob", WATA_TUI_PASS=PASSWORD)
        bob = subprocess.run([tui_bin], input=BOB_SCRIPT, capture_output=True,
                             text=True, env=tenv, timeout=120)
        boblines = (bob.stdout + bob.stderr).splitlines()
        c.line(boblines, lambda l: l == "ready @bob:localhost", "bob: no `ready`")
        c.line(boblines, lambda l: l.startswith("sent "), "bob: no `sent` line")

        # the message arrives over sync; the differ patches EXACTLY the rows
        # it changes: the unplayed badge inserted into the family row (child 2
        # of row 0 of the rows group at path [2]), and nothing else.
        arrival = sess.cmd("wait 6000", lambda l: l == "waited 6000")
        patches = [l for l in arrival if l.startswith("patch ")]
        c.ok(patches == ['patch insert [0.2.0] 2 badge:text(25,2,"1",65504)'],
             f"arrival: want exactly the badge insert, got {patches!r}")

        # and the native tree now shows it, retained (no rebuild: same frames).
        t2 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t2, lambda l: l.strip() == 'NSTextField 150 103 6 8 "1"',
               "tree 2: no unplayed badge label")
        c.line(t2, lambda l: l.strip() == 'NSTextField 0 103 36 8 "Family"',
               "tree 2: family row label gone")

        # the key path: OK (a real kVK code through the real translation
        # table) opens the family conversation; its native tree shows bob's
        # message row and the conversation footer.
        sess.cmd("key enter press", lambda l: l == "key ok")
        sess.cmd("key enter release", lambda l: l == "key ok")
        opened = sess.cmd("wait 500", lambda l: l == "waited 500")
        c.line(opened, lambda l: l.startswith("patch insert [0] 0 header:text(0,0,\"Family\""),
               "open: no conversation header patch")
        t3 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t3, lambda l: l.strip() == 'NSTextField 0 119 36 8 "Family"',
               "tree 3: no conversation header")
        c.line(t3, lambda l: re.fullmatch(r'NSTextField \d+ 103 \d+ 8 "Bob"', l.strip()),
               "tree 3: no message row sender")
        c.line(t3, lambda l: l.strip() == 'NSTextField 0 7 144 8 "OK play hold=fav red=del"',
               "tree 3: no conversation footer")
    except TimeoutError as e:
        c.failed.append(str(e))
    finally:
        if sess is not None:
            sess.quit()
        stop_server(proc, log)

    if sess is not None:
        c.line(sess.lines, lambda l: l == "bye", "alice: no clean `bye`")
        c.ok(sess.proc.returncode == 0,
             f"alice: exit code {sess.proc.returncode}")
        with open(os.path.join(tmp, "alice.log"), "w") as f:
            f.write("\n".join(sess.lines) + "\n")
    return c.failed, sess.lines if sess else []


def main():
    if sys.platform != "darwin":
        sys.exit("mac-smoke: macOS only")
    tmp = tempfile.mkdtemp(prefix="mac-smoke-")
    failed, _ = run(tmp)
    if failed:
        print(f"mac-smoke: scratch kept at {tmp}")
        for f in failed:
            print(f"FAIL: {f}")
        sys.exit(1)
    if os.environ.get("MAC_SMOKE_KEEP"):
        print(f"mac-smoke: scratch kept at {tmp}")
    else:
        subprocess.run(["rm", "-rf", tmp])
    print("PASS mac-smoke")


if __name__ == "__main__":
    main()
