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
    underline + badge inserted into the family row, nothing else;
  - the key path (`key <name> press/release` feeds macOS virtual key codes
    through nativeui's real translation table) opens the conversation, whose
    native tree then shows bob's message row;
  - AUDIO, both directions (plan 0033), under `WATA_MAC_AUDIO=fake`: OK plays
    bob's message — the differ shows the play triangle and then the played
    check — and PTT records one, which the differ shows as the recording
    overlay counting up and then alice's own row plus the SENT flash, and
    which a fresh bob session then reads back off the server.

The fake backend replaces the microphone and the speaker and NOTHING else:
the Opus codec, the period sizes, the Ogg framing, the mailbox protocol and
every UI state are the real ones, so an unattended run needs no TCC grant and
makes no noise while still exercising the whole path.

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
    terminator. Every line the app prints is kept for the final assertions.

    `hs` and `extra_env` are the seams tools/mac-iroh-smoke.py drives it
    through: the same session object, aimed at a homeserver reached over the
    iroh transport instead of localhost TCP."""

    def __init__(self, binary, env, hs=None, extra_env=None):
        # A harness supplies its own credentials, so it must leave the
        # developer's keychain and state directory alone: no items keyed by a
        # random scratch port piling up in the login keychain, no config file
        # written under ~/Library. tools/mac-creds-smoke.py is the one smoke
        # that deliberately does not set these — the stores are its subject.
        senv = dict(env, WATA_MAC_HS=hs or BASE, WATA_MAC_USER="alice",
                    WATA_MAC_PASS=PASSWORD, WATA_MAC_HEADLESS="1",
                    WATA_MAC_SCALE="1", WATA_MAC_AUDIO="fake",
                    WATA_MAC_NO_KEYCHAIN="1",
                    WATA_MAC_CONFIG=os.path.join(
                        tempfile.gettempdir(), "wata-mac-smoke", "config.json"))
        # applied last, so a caller may OVERRIDE any of the above — which is
        # how mac-notify-smoke gets its own config file to restart against.
        senv.update(extra_env or {})
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

        # the contact screen is plan 0070's ROLODEX (plan 0077 stage 3): at
        # rest, ONE full-bleed card in the contact's colour — the selected
        # (family) card as an NSBox filling the stage, the name at the
        # DISPLAY role in its box, the state line under it — and nothing
        # else: no WATA title, no footer key legend, no connectivity element
        # while the link is healthy. Bob's card is culled off-panel at rest,
        # which is why he does not appear. (AppKit frames are bottom-left
        # origin, so wata's y=0 prints as 128-h.)
        t1 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t1, lambda l: l.strip() == 'NSBox 0 0 160 128',
               "tree 1: no full-bleed family card NSBox")
        c.line(t1, lambda l: l.strip() == 'NSTextField 2 53 156 49 "Family"',
               "tree 1: no full-bleed Family name label")
        c.line(t1, lambda l: l.strip() == 'NSTextField 2 30 156 21 "no messages"',
               "tree 1: no state line under the name")
        c.ok(not any('"WATA"' in l for l in t1),
             "tree 1: the WATA title chrome is back on the rolodex")
        c.ok(not any('UP/DN' in l for l in t1),
             "tree 1: the footer key legend is back on the rolodex")
        c.ok(not any('"NET"' in l for l in t1),
             "tree 1: connectivity element shown on a healthy link")
        c.ok(not any('unheard' in l for l in t1), "tree 1: unheard band already present")

        # bob sends a voice message to the family room MID-SESSION, via the tui.
        tenv = dict(env, WATA_TUI_HS=BASE, WATA_TUI_USER="bob", WATA_TUI_PASS=PASSWORD)
        bob = subprocess.run([tui_bin], input=BOB_SCRIPT, capture_output=True,
                             text=True, env=tenv, timeout=120)
        boblines = (bob.stdout + bob.stderr).splitlines()
        c.line(boblines, lambda l: l == "ready @bob:localhost", "bob: no `ready`")
        c.line(boblines, lambda l: l.startswith("sent "), "bob: no `sent` line")

        # the message arrives over sync; the differ patches EXACTLY what the
        # card changes: the unheard band (plan 0070's one-element count/bar)
        # and its count label inserted into the family card, and the state
        # line reworded — and nothing else. The card itself is untouched.
        arrival = sess.cmd("wait 6000", lambda l: l == "waited 6000")
        patches = [l for l in arrival if l.startswith("patch ")]
        c.ok(patches == ['patch insert [0.0.0] 1 band:fill(0,0,160,21,r=0,65504,a=255)',
                         'patch insert [0.0.0] 3 count:label(2,1,156,19,"1 unheard",'
                         'caption,medium,center,0,a=255)',
                         'patch set [0.0.0.4] label(2,77,156,21,"just now",'
                         'name,medium,center,0,a=255)'],
             f"arrival: want exactly the band + count inserts and the state set, got {patches!r}")

        # and the native tree now shows it, retained (no rebuild: same frames).
        t2 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t2, lambda l: l.strip() == 'NSBox 0 107 160 21',
               "tree 2: no unheard band NSBox across the card's top")
        c.line(t2, lambda l: l.strip() == 'NSTextField 2 108 156 19 "1 unheard"',
               "tree 2: no unheard count label in the band")
        c.line(t2, lambda l: l.strip() == 'NSTextField 2 53 156 49 "Family"',
               "tree 2: family card name gone")

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

        # ---- audio: PLAY (plan 0033) -----------------------------------------
        # OK on the selected message really plays it: the runtime fetches the
        # ogg, the action loop hands `AcPlay` to the audio thread this client
        # forks, the thread decodes every Opus frame and plays the pcm against
        # the fake backend's clock, and `AePlaybackDone` returns through the
        # ONE audio drain. The row carries the DELIVERED check from the start
        # (plan 0051), so both play edges are `patch set` on that same glyph:
        # the play triangle (0x90 = 144) the instant OK is released, then back
        # to the check (0x80 = 128) when the audio ends. The HEARD check
        # (`mark2`) is then INSERTED a moment later, when the receipt has been
        # round-tripped through /sync — seeing it is the end-to-end proof of
        # the runtime's `UiEvent`/audio drains, since nothing else creates
        # that node. Nothing here is optimistic; mark2 means the server
        # recorded the receipt.
        sess.cmd("key enter press", lambda l: l == "key ok")
        sess.cmd("key enter release", lambda l: l == "key ok")
        played = sess.cmd("wait 8000", lambda l: l == "waited 8000")
        pp = [l for l in played if l.startswith("patch ")]
        c.ok(pp[:1] == ['patch set [0.1.0.1] glyph(0,17,144,0)'],
             f"play: want the play-triangle patch first, got {pp!r}")
        c.line(pp, lambda l: l == 'patch set [0.1.0.1] glyph(0,17,128,0)',
               f"play: the mark never returned to the delivered check, got {pp!r}")
        c.line(pp, lambda l: l == 'patch insert [0.1.0] 2 mark2:glyph(6,17,128,0)',
               f"play: no heard-check insert after the receipt round-trip, got {pp!r}")
        t4 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t4, lambda l: l.strip() == 'NSTextField 0 103 6 8 "✓"',
               "tree 4: no delivered check on the message row")
        c.line(t4, lambda l: l.strip() == 'NSTextField 6 103 6 8 "✓"',
               "tree 4: no heard check on the message row")

        # ---- audio: RECORD ----------------------------------------------------
        # PTT (space) holds the microphone open: `AcRecordStart` reaches the
        # audio thread, the fake capture yields real periods on a real clock,
        # both 960-sample subframes of each are Opus-encoded, and release
        # (`AcRecordStop`) frames them as Ogg and uploads. The recording
        # overlay is the frame-by-frame proof it is running.
        sess.cmd("key space press", lambda l: l == "key ok")
        held = sess.cmd("wait 1200", lambda l: l == "waited 1200")
        hp = [l for l in held if l.startswith("patch ")]
        # the overlay inserts with the meter at its level-0 sliver (width 1):
        # PTT press resets the level, and the first capture period has not
        # ticked yet on the frame that shows the bar.
        c.ok(hp[:1] == ['patch insert [] 1 rec:group[bar:rect(0,104,160,24,63488) '
                        'lvl:rect(4,113,1,6,2016) time:text(9,14,"REC 0.0s",65535)]'],
             f"record: want the recording overlay inserted first, got {hp[:1]!r}")
        c.line(hp, lambda l: l == 'patch set [1.2] text(9,14,"REC 1.0s",65535)',
               "record: the overlay's elapsed time never reached 1.0s")
        # the capture meter (plan 0042): the fake mic is a constant-amplitude
        # 16000 sine, so its peak-of-period level is constant — 16000*32/32767
        # = 15, width max(1, 15*152/32) = 71 — and the meter widens EXACTLY
        # once. A flat bar here would be the dead-mic signature this meter
        # exists to show.
        c.line(hp, lambda l: l == 'patch set [1.1] rect(4,113,71,6,2016)',
               f"record: the meter never showed the fake mic's level 15, got {hp!r}")
        t5 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.line(t5, lambda l: l.strip() == 'NSBox 0 0 160 24',
               "tree 5: no recording bar while PTT is held")
        c.line(t5, lambda l: l.strip() == 'NSBox 4 9 71 6',
               "tree 5: no capture-meter rect at the fake mic's level")
        c.line(t5, lambda l: re.fullmatch(r'NSTextField 54 7 48 8 "REC 1\.\ds"', l.strip()),
               "tree 5: no recording elapsed-time label")

        sess.cmd("key space release", lambda l: l == "key ok")
        sent = sess.cmd("wait 8000", lambda l: l == "waited 8000")
        sp = [l for l in sent if l.startswith("patch ")]
        c.ok(sp[:1] == ['patch delete [] 1'],
             f"send: the recording overlay was not removed first, got {sp[:1]!r}")
        # index 0 and highlighted: messages come back newest first, so a sent
        # message lands at the TOP, under a cursor that never moved. The black
        # ink (0) on the green highlight rect is what "selected" looks like.
        # the plan-0049 grid: marks, then the age ("now"), the sender — "me"
        # on an own row — and the right-aligned duration.
        c.line(sp, lambda l: re.fullmatch(
            r'patch insert \[0\.1\] 0 \$\S+:group\[hl:rect\(0,17,160,8,2016\) '
            r'mark:glyph\(0,17,128,0\) age:text\(2,2,"now",0\) '
            r'dur:text\(21,2,"0:01",0\) sender:text\(6,2,"me",0\)\]', l),
            f"send: no own message row for the ~1.2s recording, got {sp!r}")
        c.line(sp, lambda l: l == 'patch insert [] 1 flash:group[msg:text(8,9,"SENT",2016)]',
               f"send: no SENT flash (EvSendComplete never reached the pump), got {sp!r}")

        # and it is really on the server: a fresh bob session sees alice's
        # voice message in the family room, with the duration she recorded.
        benv = dict(env, WATA_TUI_HS=BASE, WATA_TUI_USER="bob", WATA_TUI_PASS=PASSWORD)
        bob2 = subprocess.run([tui_bin], input="snap\nmsgs 1\nquit\n",
                              capture_output=True, text=True, env=benv, timeout=120)
        b2 = (bob2.stdout + bob2.stderr).splitlines()
        c.line(b2, lambda l: l.startswith("conv 1 family ") and " msgs=2 " in l,
               f"bob2: the family room does not hold two messages, got {b2!r}")
        c.line(b2, lambda l: re.match(r'msg \d+ @alice:localhost dur=1\d\d\d ', l),
               f"bob2: no ~1s voice message from alice, got {b2!r}")

        # ---- arrival while the CONVERSATION IS OPEN ---------------------------
        # Everything above receives a message with the contact list showing and
        # opens the conversation afterwards. Nobody was watching an open
        # conversation when a message landed in it — which is exactly the moment
        # a user calls "live", and the only shape a report of "it does not
        # update live" can mean. Alice is still inside the family conversation
        # here, so bob's next message must appear as a row without a keystroke.
        bob3 = subprocess.run([tui_bin], input=BOB_SCRIPT, capture_output=True,
                              text=True, env=benv, timeout=120)
        c.line((bob3.stdout + bob3.stderr).splitlines(), lambda l: l.startswith("sent "),
               "bob3: no `sent` line")
        live = sess.cmd("wait 8000", lambda l: l == "waited 8000")
        lp = [l for l in live if l.startswith("patch ")]
        c.line(lp, lambda l: re.fullmatch(
            r'patch insert \[0\.1\] 0 \$\S+:group\[hl:rect\(\S+\) '
            r'mark:glyph\(\S+\) age:text\(\d+,\d+,"now",\d+\) '
            r'dur:text\(\d+,\d+,"0:0\d",\d+\) sender:text\(\d+,\d+,"Bob",\d+\)\]', l),
            f"open-conversation arrival: no new message row, got {lp!r}")
        t6 = tree_of(sess.cmd("tree", lambda l: l == "tree end"))
        c.ok(sum(1 for l in t6 if l.strip().endswith('"Bob"')) == 2,
             f"tree 6: want two Bob rows in the open conversation, got {t6!r}")
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
