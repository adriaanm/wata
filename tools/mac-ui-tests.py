#!/usr/bin/env python3
"""The mac failure-scenario suite (plan 0045 slice 5): every non-happy path
an adult meets, driven through the headless REPL and judged the way
mac-smoke judges — native tree dumps, the printed lines, and the title
seam (`title` reads back what `macshell.SetTitle` last recorded).

    tools/mac-ui-tests.py               # every scenario, scoreboard
    tools/mac-ui-tests.py <scenario>    # one scenario

Scenarios (the plan's list; slice 1's HTTP timeout is what makes the hung
ones terminate):

    wrong-password        a refused password says so, calmly, at boot
    unreachable-at-login  a dead address reads "can't reach server"
    hung-server           a server that ACCEPTS and never answers fails
                          rounds instead of freezing the pump
    mid-session-loss      the title says "reconnecting…", then "offline"
                          past the (shortened) ceiling
    send-fail             an upload 500 flashes SEND FAILED, and the flash
                          clears on its own timer
    recording-error       a denied mic flashes MIC FAILED (not SEND
                          FAILED) and banners the fix once per run

The login SHEET's reason line is windowed-only chrome and is pinned by
go-pkgs/macshell/login_test.go (nativeui-tests); what this suite pins is
the session-level truth those reasons are derived from. Hermetic — one
fresh server per scenario that needs one, localhost only. macOS only;
run by `just mac-ui-tests`, not by ci (same posture as mac-smoke)."""

import importlib.util
import json
import os
import random
import signal
import sys
import tempfile
import time
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

spec = importlib.util.spec_from_file_location(
    "macsmoke", os.path.join(WATA, "tools", "mac-smoke.py"))
macsmoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(macsmoke)

PASSWORD = macsmoke.PASSWORD


# ---- scenario plumbing -------------------------------------------------------
class Scene:
    """one scenario's resources: a port, maybe a server, session configs."""

    def __init__(self, name, outdir, env, server_bin, hooks=False, server=True):
        self.name = name
        self.dir = os.path.join(outdir, name)
        os.makedirs(self.dir, exist_ok=True)
        self.env = dict(env)
        self.server_bin = server_bin
        self.port = random.randint(20000, 39999)
        self.base = f"http://127.0.0.1:{self.port}"
        macsmoke.PORT = self.port  # start_server/our_listener read these
        macsmoke.BASE = self.base
        self.sproc, self.slog = None, None
        if server:
            senv = dict(env, WATA_TEST_HOOKS="1") if hooks else env
            self.sproc, self.slog = macsmoke.start_server(
                self.server_bin, os.path.join(self.dir, "server.log"), senv)
            if self.sproc is None:
                raise RuntimeError(f"{name}: server never came up")

    def session(self, mac, n=0, **extra):
        e = {"WATA_MAC_CONFIG": os.path.join(self.dir, f"config-{n}.json")}
        e.update(extra)
        return macsmoke.MacSession(mac, self.env, hs=self.base, extra_env=e)

    def failnext(self, n):
        urllib.request.urlopen(urllib.request.Request(
            f"{self.base}/_wata/v1/test/fail",
            data=json.dumps({"count": n}).encode(),
            headers={"Content-Type": "application/json"}), timeout=5).read()

    def stop_server(self):
        if self.sproc is not None:
            macsmoke.stop_server(self.sproc, self.slog)
            self.sproc = None

    def sigstop_server(self):
        os.kill(self.sproc.pid, signal.SIGSTOP)

    def close(self):
        if self.sproc is not None:
            try:
                os.kill(self.sproc.pid, signal.SIGCONT)
            except ProcessLookupError:
                pass
            self.stop_server()


def tree(sess):
    return "\n".join(sess.cmd("tree", lambda l: l == "tree end", 15))


def title(sess):
    return sess.cmd("title", lambda l: l.startswith("title "), 15)[-1]


def wait_ms(sess, ms):
    sess.cmd(f"wait {ms}", lambda l: l == f"waited {ms}", ms / 1000 + 30)


def wait_tree(sess, pred, tries=30, step=300):
    """poll the native tree until pred holds; returns the last dump."""
    t = tree(sess)
    for _ in range(tries):
        if pred(t):
            return t
        wait_ms(sess, step)
        t = tree(sess)
    return t


def register(scene, mac):
    """phase 0 where needed: a good login so alice EXISTS server-side."""
    s = scene.session(mac, n=0)
    s.read_until(lambda l: l.startswith("ready "), 60)
    s.quit()


# ---- the scenarios -----------------------------------------------------------
def sc_wrong_password(scene, mac):
    fails = []
    register(scene, mac)
    s = scene.session(mac, n=1, WATA_MAC_PASS="not-the-password",
                      WATA_MAC_CONNECT_MS="8000")
    got = s.read_until(lambda l: l in ("login failed",) or l.startswith("ready "), 60)
    if got[-1] != "login failed":
        fails.append(f"wrong password logged in: {got[-1]!r}")
    t = wait_tree(s, lambda t: "account rejected" in t, tries=10)
    if "account rejected" not in t:
        fails.append("boot screen does not say `account rejected`")
    if title(s) != "title Wata":
        fails.append(f"title not calm pre-everLive: {title(s)!r}")
    s.quit()
    return fails


def sc_unreachable(scene, mac):
    fails = []
    s = scene.session(mac, n=0, WATA_MAC_CONNECT_MS="4000",
                      WATA_HTTP_TIMEOUT_MS="1500")
    got = s.read_until(lambda l: l == "login failed" or l.startswith("ready "), 60)
    if got[-1] != "login failed":
        fails.append(f"dead address logged in: {got[-1]!r}")
    t = wait_tree(s, lambda t: "can't reach server" in t, tries=10)
    if "can't reach server" not in t:
        fails.append("boot screen does not say `can't reach server`")
    s.quit()
    return fails


def sc_hung_server(scene, mac):
    fails = []
    scene.sigstop_server()  # accepts (listen backlog), never answers
    s = scene.session(mac, n=0, WATA_MAC_CONNECT_MS="6000",
                      WATA_HTTP_TIMEOUT_MS="1500")
    got = s.read_until(lambda l: l == "login failed" or l.startswith("ready "), 60)
    if got[-1] != "login failed":
        fails.append(f"hung server logged in: {got[-1]!r}")
    t = wait_tree(s, lambda t: "can't reach server" in t, tries=10)
    if "can't reach server" not in t:
        fails.append("hung login does not read `can't reach server`")
    # the REPL answering at all is the frozen-pump assertion — pre slice 1
    # this session would never have printed `login failed`.
    s.quit()
    return fails


def sc_mid_session_loss(scene, mac):
    fails = []
    s = scene.session(mac, n=0, WATA_MAC_OFFLINE_MS="4000",
                      WATA_HTTP_TIMEOUT_MS="1500")
    s.read_until(lambda l: l.startswith("ready "), 60)
    wait_ms(s, 300)
    if title(s) != "title Wata":
        fails.append(f"healthy title: {title(s)!r}")
    scene.stop_server()
    wait_ms(s, 3000)
    got = title(s)
    if got != "title Wata — reconnecting…":
        fails.append(f"after loss: {got!r}")
    wait_ms(s, 5000)  # past the shortened ceiling
    got = title(s)
    if got != "title Wata — offline":
        fails.append(f"past ceiling: {got!r}")
    # the stage stays on the live UI — loss is chrome's to announce, the
    # kid's grid does not fall back to a boot screen (everLive).
    if "can't reach server" in tree(s):
        fails.append("stage fell back to the boot screen")
    s.quit()
    return fails


def sc_send_fail(scene, mac):
    fails = []
    s = scene.session(mac, n=0)
    s.read_until(lambda l: l.startswith("ready "), 60)
    wait_ms(s, 500)
    scene.failnext(1)
    s.cmd("key space press", lambda l: l == "key ok", 10)
    wait_ms(s, 700)
    s.cmd("key space release", lambda l: l == "key ok", 10)
    t = wait_tree(s, lambda t: "SEND FAILED" in t, tries=15)
    if "SEND FAILED" not in t:
        fails.append("no SEND FAILED flash")
    t = wait_tree(s, lambda t: "SEND FAILED" not in t, tries=15)
    if "SEND FAILED" in t:
        fails.append("the flash never cleared")
    s.quit()
    return fails


def sc_recording_error(scene, mac):
    fails = []
    s = scene.session(mac, n=0, WATA_MAC_MIC_FAIL="1")
    s.read_until(lambda l: l.startswith("ready "), 60)
    s.cmd("key space press", lambda l: l == "key ok", 10)
    wait_ms(s, 700)
    s.cmd("key space release", lambda l: l == "key ok", 10)
    t = wait_tree(s, lambda t: "MIC FAILED" in t, tries=10)
    if "MIC FAILED" not in t:
        fails.append("no MIC FAILED flash")
    if "SEND FAILED" in t:
        fails.append("a mic failure still reads SEND FAILED")
    if not any(l.startswith("mic: banner") for l in s.lines):
        fails.append("no `mic: banner` line")
    # a second failure repeats the flash but not the banner
    s.cmd("key space press", lambda l: l == "key ok", 10)
    wait_ms(s, 700)
    s.cmd("key space release", lambda l: l == "key ok", 10)
    wait_ms(s, 500)
    if sum(1 for l in s.lines if l.startswith("mic: banner")) != 1:
        fails.append("the banner is not once-per-run")
    s.quit()
    return fails


# name -> (fn, needs_server, needs_hooks)
SCENARIOS = [
    ("wrong-password", sc_wrong_password, True, False),
    ("unreachable-at-login", sc_unreachable, False, False),
    ("hung-server", sc_hung_server, True, False),
    ("mid-session-loss", sc_mid_session_loss, True, False),
    ("send-fail", sc_send_fail, True, True),
    ("recording-error", sc_recording_error, True, False),
]


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    table = [r for r in SCENARIOS if only is None or r[0] == only]
    if not table:
        sys.exit(f"mac-ui-tests: no scenario named {only!r} "
                 f"(have: {', '.join(n for n, *_ in SCENARIOS)})")
    sgo, mac, tui, server, env = macsmoke.build_env()
    macsmoke.build(sgo, env)
    outdir = tempfile.mkdtemp(prefix="mac-ui-tests-")
    rows, failed = [], 0
    for name, fn, needs_server, hooks in table:
        scene = Scene(name, outdir, env, server, hooks=hooks, server=needs_server)
        try:
            fails = fn(scene, mac)
        except Exception as e:  # a wedged scenario is a failure, not an abort
            fails = [f"exception: {e}"]
        finally:
            scene.close()
        rows.append((name, fails))
        failed += bool(fails)
        mark = "PASS" if not fails else "FAIL"
        print(f"{mark} {name}")
        for f in fails:
            print(f"      {f}")
    print(f"\nmac-ui-tests: {len(rows) - failed}/{len(rows)} scenarios green"
          f" (logs: {outdir})")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
