#!/usr/bin/env python3
"""Does wata-mac notice a message arriving while nobody is looking? (plan 0037 slice 4)

    tools/mac-notify-smoke.py
    MAC_NOTIFY_KEEP=1 …          # keep the scratch dir on success

Alice runs wata-mac headless against one fresh wata-server; bob (a tui
session) sends her voice messages. What is asserted is the DECISION the pump
prints, one line per arrival:

    notify: play|banner|suppressed "<title>" "<body>" badge=<n>

not that macOS drew anything. Whether a banner reached the screen is macOS's
business and is not observable from a harness; whether this client would have
asked for one, naming whom, at what badge count, is entirely ours — and it is
the part that can be wrong.

Six cases, in order:

  1. NOT FRONTMOST, quiet mode — a banner naming bob, badge 1.
  2. FRONTMOST — suppressed. An app the user is already looking at must not
     banner over itself; this is the case a naive implementation gets wrong,
     and it is asserted before the interesting ones so a regression cannot
     hide behind them.
  3. A DM room created by its FIRST message announces (per-conversation
     priming would silence exactly the arrival most worth having), the badge
     ADDS UP across conversations, and it CLEARS when the last message
     is played (`key enter` on the message really plays it under
     WATA_MAC_AUDIO=fake, and the receipt comes back through /sync).
  4. WALKIE-TALKIE mode (`mode play`) — the arrival plays instead of
     bannering, and the message really becomes PLAYED: the next arrival's
     badge reads 1, not 2. That is the whole chain — ActPlay reached the audio
     thread, the thread finished, the receipt went out and came back through
     /sync — and it is evidence a printed decision line cannot fake. (The play
     triangle is not available here: it is drawn on a message row, and the app
     is sitting on the contact list, which is the point of the mode.)
  5. The mode PERSISTS: the session is restarted against the same config file
     and the next arrival still plays, with nothing in the environment saying
     so.
  6. A restart does NOT re-announce the backlog it starts with — the first
     sight of a conversation primes the marks. A client that bannered every
     unplayed message on every launch would pass cases 1-5.

Hermetic — no device, no window, no network beyond localhost. macOS only,
not in ci (like mac-smoke, whose harness this reuses).
"""

import importlib.util
import os
import re
import subprocess
import sys
import tempfile

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("ms", os.path.join(WATA, "tools", "mac-smoke.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)

NOTIFY = re.compile(r'^notify: (\w+) "([^"]*)" "([^"]*)" badge=(\d+)')


def bob_send(tui_bin, env, conv):
    """bob sends one voice message to conversation `conv` (1 = family, 2 = the
    DM with alice), through the tui, and we wait for its `sent` line."""
    tenv = dict(env, WATA_TUI_HS=ms.BASE, WATA_TUI_USER="bob", WATA_TUI_PASS=ms.PASSWORD)
    r = subprocess.run([tui_bin], input=f"snap\nsend {conv} {ms.FIXTURE}\nquit\n",
                       capture_output=True, text=True, env=tenv, timeout=120)
    return any(l.startswith("sent ") for l in (r.stdout + r.stderr).splitlines())


def decisions(lines):
    """(what, title, body, badge) for every decision line in `lines`."""
    out = []
    for l in lines:
        m = NOTIFY.match(l)
        if m:
            out.append((m.group(1), m.group(2), m.group(3), int(m.group(4))))
    return out


def run(tmp):
    sgo, mac_bin, tui_bin, server_bin, env = ms.build_env()
    ms.build(sgo, env)
    proc, log = ms.start_server(server_bin, os.path.join(tmp, "server.log"), env)
    if proc is None:
        return ["server never became ready"]

    c = ms.Checks()
    cfg = os.path.join(tmp, "config.json")
    sess = None
    try:
        # A real config file (not the shared scratch one MacSession defaults
        # to): case 5 restarts the app against it, which is the whole test.
        sess = ms.MacSession(mac_bin, env, extra_env={"WATA_MAC_CONFIG": cfg})
        sess.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
        c.line(sess.lines, lambda l: l == "ready @alice:localhost", "alice: no `ready`")
        # the first frames prime the marks off whatever is already there
        sess.cmd("wait 1500", lambda l: l == "waited 1500")
        sess.cmd("front 0", lambda l: l == "front 0")

        # ---- 1. not frontmost, quiet: a banner naming bob --------------------
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the family message")
        got = decisions(sess.cmd("wait 8000", lambda l: l == "waited 8000"))
        c.ok(got == [("banner", "Bob", "sent a voice message to Family", 1)],
             f"1 not frontmost: want one banner naming Bob at badge 1, got {got!r}")

        # ---- 2. frontmost: suppressed ---------------------------------------
        sess.cmd("front 1", lambda l: l == "front 1")
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the second family message")
        got = decisions(sess.cmd("wait 8000", lambda l: l == "waited 8000"))
        c.ok(got == [("suppressed", "Bob", "sent a voice message to Family", 2)],
             f"2 frontmost: want it suppressed at badge 2, got {got!r}")
        sess.cmd("front 0", lambda l: l == "front 0")

        # ---- 3. the badge adds up across conversations, then clears ---------
        c.ok(bob_send(tui_bin, env, 2), "bob: no `sent` line for the DM")
        got = decisions(sess.cmd("wait 8000", lambda l: l == "waited 8000"))
        c.ok(got == [("banner", "Bob", "sent you a voice message", 3)],
             f"3 a DM: want a banner with no place at badge 3, got {got!r}")

        # play all three, then check the badge really came back to 0 — a
        # count that only ever grows is the bug every unread badge has once.
        # The badge is read back through a fourth arrival, since it is the
        # decision line that reports it.
        play_all(sess)
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the badge-probe message")
        got = decisions(sess.cmd("wait 8000", lambda l: l == "waited 8000"))
        c.ok([g[3] for g in got] == [1],
             f"3 the badge did not come back to 0 before this arrival "
             f"(it would have read 4); got {got!r}")
        # and play that one too, so the walkie-talkie case starts from quiet
        play_one(sess)

        # ---- 4. walkie-talkie mode plays instead of bannering ---------------
        sess.cmd("mode play", lambda l: l == "notify: mode play")
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the walkie-talkie message")
        got = decisions(sess.cmd("wait 10000", lambda l: l == "waited 10000"))
        c.ok([g[0] for g in got] == ["play"] and [g[3] for g in got] == [1],
             f"4 walkie-talkie: want exactly one `play` decision at badge 1, got {got!r}")
        # and it really played: the next arrival's badge is 1 again, so the
        # previous one was receipted rather than piling up.
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the second walkie-talkie message")
        got = decisions(sess.cmd("wait 10000", lambda l: l == "waited 10000"))
        c.ok(got and got[0][0] == "play" and got[0][3] == 1,
             f"4 walkie-talkie: the auto-played message was never receipted "
             f"(the badge should be back to 1, not 2); got {got!r}")
    except TimeoutError as e:
        c.failed.append(str(e))
    finally:
        if sess is not None:
            sess.quit()

    # ---- 5/6. the mode persists, and a restart does not re-announce ---------
    # One message arrives while the app is DOWN, so the restart really does
    # open with unplayed backlog — case 6 is vacuous otherwise. Then a fresh
    # process against the SAME config file, with nothing in the environment
    # about notifications.
    c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the offline message")
    sess2 = None
    try:
        sess2 = ms.MacSession(mac_bin, env, extra_env={"WATA_MAC_CONFIG": cfg})
        sess2.read_until(lambda l: l in ("ready @alice:localhost", "login failed"), 60)
        boot = sess2.cmd("wait 6000", lambda l: l == "waited 6000")
        sess2.cmd("front 0", lambda l: l == "front 0")
        # 6. it starts with unplayed backlog and must announce NONE of it.
        c.ok(decisions(boot) == [],
             f"6 a restart re-announced its backlog: {decisions(boot)!r}")
        # 5. the stored mode is still `play`.
        c.ok(bob_send(tui_bin, env, 1), "bob: no `sent` line for the post-restart message")
        got = decisions(sess2.cmd("wait 10000", lambda l: l == "waited 10000"))
        c.ok([g[0] for g in got] == ["play"],
             f"5 the walkie-talkie mode did not survive a restart, got {got!r}")
    except TimeoutError as e:
        c.failed.append(str(e))
    finally:
        if sess2 is not None:
            sess2.quit()
        ms.stop_server(proc, log)

    with open(os.path.join(tmp, "alice.log"), "w") as f:
        f.write("\n".join((sess.lines if sess else []) + (sess2.lines if sess2 else [])) + "\n")
    return c.failed


def play_all(sess):
    """open each conversation and play its messages: OK opens, OK plays, Back
    returns. Three messages, two conversations, in the order the rows sit."""
    def press(name):
        sess.cmd(f"key {name} press", lambda l: l == "key ok")
        sess.cmd(f"key {name} release", lambda l: l == "key ok")
    # family (row 0): open, play the two messages
    press("enter")
    sess.cmd("wait 500", lambda l: l == "waited 500")
    for _ in range(2):
        press("enter")
        sess.cmd("wait 8000", lambda l: l == "waited 8000")
        press("down")
    press("esc")
    sess.cmd("wait 500", lambda l: l == "waited 500")
    # bob's DM (row 1): down, open, play the one message
    press("down")
    press("enter")
    sess.cmd("wait 500", lambda l: l == "waited 500")
    press("enter")
    sess.cmd("wait 8000", lambda l: l == "waited 8000")
    press("esc")
    sess.cmd("wait 1000", lambda l: l == "waited 1000")


def play_one(sess):
    """open the family thread, play its newest message, come back. `up` first
    because play_all leaves the cursor on bob's DM row, and a contact list
    cursor is where the previous navigation left it, not where you assume."""
    def press(name):
        sess.cmd(f"key {name} press", lambda l: l == "key ok")
        sess.cmd(f"key {name} release", lambda l: l == "key ok")
    press("up")
    press("enter")
    sess.cmd("wait 500", lambda l: l == "waited 500")
    press("enter")
    sess.cmd("wait 8000", lambda l: l == "waited 8000")
    press("esc")
    sess.cmd("wait 1000", lambda l: l == "waited 1000")


def main():
    if sys.platform != "darwin":
        sys.exit("mac-notify-smoke: macOS only")
    tmp = tempfile.mkdtemp(prefix="mac-notify-smoke-")
    failed = run(tmp)
    if failed:
        print(f"mac-notify-smoke: scratch kept at {tmp}")
        for f in failed:
            print(f"FAIL: {f}")
        sys.exit(1)
    if os.environ.get("MAC_NOTIFY_KEEP"):
        print(f"mac-notify-smoke: scratch kept at {tmp}")
    else:
        subprocess.run(["rm", "-rf", tmp])
    print("PASS mac-notify-smoke")


if __name__ == "__main__":
    main()
