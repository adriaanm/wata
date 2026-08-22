#!/usr/bin/env python3
"""`just watch-rolodex` — photograph plan 0070's rolodex on the watch simulator.

`watch-e2e` proves the client works and ends with ONE screenshot of whatever
was on screen when the run finished. The rolodex is a MOTION design, and its
three interesting frames are a card at rest, the stack open mid-scroll, and the
card it settled onto — none of which a verdict-shaped harness can catch,
because two of them exist for a few hundred milliseconds in the middle of a run.

So this drives the same app against the same live server, scripts a couple of
`down` intents through watchshell's `ScriptIntents` seam, and takes timed
screenshots around them. It is a LOOKING tool, not a gate: it asserts nothing
and is not in `ci`. The oracle for the rolodex is `watch-interptest`'s
`rolodexAtRestIsOneCard` / `rolodexMidScrollIsAStack`, which read pixels.

    just watch-rolodex                 # three shots into wata-watch/.sgo/…
    just watch-rolodex --keep-server   # leave the server up afterwards

The timing is open-loop — the shots are scheduled at fixed offsets from the
app's first printed line, and a simulator screenshot takes tens of
milliseconds. The mid-scroll shot has a ~450 ms window (the settle) and lands
in it reliably; if a machine is loaded enough to miss it, the shot shows a
closed card and says so rather than pretending.

Needs Xcode + a watchOS simulator runtime; not in ci.
"""

import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import simrun  # noqa: E402
import watchrun  # noqa: E402

# watch-e2e owns the server bring-up, the app build and the bundle; its file
# name is hyphenated, so it is loaded by path rather than imported.
_spec = importlib.util.spec_from_file_location("watch_e2e", HERE / "watch-e2e.py")
e2e = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(e2e)

REPO = HERE.parent
OUT = REPO / "wata-watch" / ".sgo" / "wata-watch"

# app-relative milliseconds. The contact list paints a couple of seconds in, so
# everything here sits comfortably after it.
NUDGE_MS = 7000        # one crown detent
NUDGE2_MS = 7250       # and a second, which is twice the shove
SHOTS = [
    (6.5, "rolodex-rest.png", "at rest — one contact, full bleed"),
    (7.45, "rolodex-scroll.png", "mid-scroll — the stack open under the centre band"),
    (9.0, "rolodex-settled.png", "settled — the card it landed on, full bleed again"),
]


def post(base, path, body, token=None):
    data = json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"errcode": e.code}
    except OSError:
        return {}


def seed_family(base, names):
    """Give the roster more than two cards to show.

    A wata family is `Config.allUsers()` — the server's accounts — not whoever
    has logged in, so an extra contact has to be CREATED through the admin API
    rather than conjured by logging a new name in. alice is the default
    server's admin, which is what makes this two calls. Best effort: a stack of
    two cards still photographs, it just says less."""
    tok = post(base, "/_matrix/client/v3/login", {
        "type": "m.login.password", "password": e2e.PASSWORD,
        "identifier": {"type": "m.id.user", "user": "alice"}}).get("access_token")
    if not tok:
        print("watch-rolodex: could not log alice in to seed contacts", file=sys.stderr)
        return
    for name in names:
        post(base, "/_wata/v1/admin/users",
             {"user": name, "password": e2e.PASSWORD,
              "displayname": name.capitalize()}, tok)


def take_shots(sc, udid, t0):
    for at, name, what in SHOTS:
        wait = t0 + at - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        path = OUT / name
        subprocess.run(sc + ["io", udid, "screenshot", str(path)],
                       capture_output=True, text=True)
        print(f"watch-rolodex: {name} — {what}")


def main():
    if sys.platform != "darwin":
        sys.exit("watch-rolodex: macOS only")
    sgo, server_bin, tui_bin, env = e2e.build_env()
    for module in ("wata-server", "wata-tui"):
        r = subprocess.run([sgo, "build"], cwd=REPO / module, env=env)
        if r.returncode != 0:
            sys.exit(f"watch-rolodex: {module} build failed")
    e2e.build_app(env)

    proc, log = e2e.start_server(server_bin, str(OUT / "rolodex-server.log"), env)
    if proc is None:
        sys.exit("watch-rolodex: server never became ready")

    seed_family(e2e.BASE, ("carol", "dave", "erin"))

    os.environ["SIMCTL_CHILD_WATA_IOS_HS"] = e2e.BASE
    os.environ["SIMCTL_CHILD_WATA_IOS_USER"] = "alice"
    os.environ["SIMCTL_CHILD_WATA_IOS_PASS"] = e2e.PASSWORD
    os.environ["SIMCTL_CHILD_WATA_MAC_AUDIO"] = "fake"
    os.environ["SIMCTL_CHILD_WATA_WATCH_SCRIPT_INTENTS"] = (
        f"down@{NUDGE_MS},down@{NUDGE2_MS}")

    sc = watchrun.simctl()
    udid = watchrun.ensure_device(sc)
    started = {"t0": None}

    def anchor():
        # the app's first printed line is the clock the scripted intents are
        # timed from, so the shots are scheduled off the same instant.
        started["t0"] = time.monotonic()
        threading.Thread(target=take_shots, args=(sc, udid, started["t0"]),
                         daemon=True).start()

    def send_inbound():
        # one voice message into the family thread, so a card has something
        # unheard to show in its band.
        simrun.tui_send(tui_bin, env, e2e.BASE, tag="watch-rolodex")

    try:
        lines, elapsed, missing = watchrun.launch_expect_verdict(
            sc, udid, e2e.APP, e2e.BUNDLE_ID, [], done_res=(r"__never__",),
            timeout=12, attempts=1,
            on_match=[(r"metrics: ", anchor),
                      (r"paint contacts lit=", send_inbound)])
        for _, name, _ in SHOTS:
            p = OUT / name
            print(f"watch-rolodex: {p}" + ("" if p.exists() else "  MISSING"))
    finally:
        watchrun.shutdown(sc, udid)
        e2e.stop_server(proc, log)


if __name__ == "__main__":
    main()
