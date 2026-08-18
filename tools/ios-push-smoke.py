#!/usr/bin/env python3
"""`just ios-push-smoke` — the client half of plan 0065 tier 2, gated with no
Apple credentials at all.

`xcrun simctl push` hands a payload straight to a simulator app's notification
pipeline: no APNs connection, no developer account, no device token. So the
two client legs are gated SEPARATELY, and it is worth being precise about
which one this run proves:

  * the PRESENTATION leg (what this run judges): a realistic wata payload —
    the exact shape `go-pkgs/apns`'s AlertPayload builds, alert +
    time-sensitive + badge + the room and event ids — is pushed to the running
    app, and the app must print `push: present room=… event=…`. That is the
    shell's UNUserNotificationCenter delegate seeing it, reading wata's own
    payload keys, and telling iOS to present it (a foreground notification the
    delegate does not answer for is dropped silently).

  * the REGISTRATION leg (attempted, not completed here): the app asks for
    permission and calls registerForRemoteNotifications at startup. A
    hand-bundled simulator app carries no `aps-environment` entitlement, so
    iOS answers didFailToRegisterForRemoteNotifications — which is exactly
    what this run asserts: that the failure is REPORTED and survivable, and
    the app goes on being a working client. A real device token, the POST to
    `/_wata/v1/push/register`, and Apple actually delivering a push need a
    signed build on a physical phone; that is the owner's leg.

The server is real (the app logs in and syncs against it) so the run also
proves the push path does not disturb the session.

Needs Xcode + an iOS simulator runtime; not in ci (mac-smoke's posture).
"""

import json
import os
import pathlib
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import simrun  # noqa: E402

REPO = HERE.parent
APP = REPO / "wata-ios" / ".sgo" / "wata-ios" / "WataIos.app"
BUNDLE_ID = "net.wa-ta.ios"
PASSWORD = "testpass123"
PORT = int(os.environ.get("IOS_PUSH_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

ROOM = "!family:localhost"
EVENT = "$pushsmoke1"

# what wata-server sends: apns.AlertPayload's aps dictionary plus wata's two
# top-level keys. Keep this in step with go-pkgs/apns/payload.go — the point
# of the gate is that the CLIENT understands what the SERVER emits.
PAYLOAD = {
    "aps": {
        "alert": {"title": "bob", "body": "voice message"},
        "sound": "default",
        "badge": 1,
        "interruption-level": "time-sensitive",
    },
    "room_id": ROOM,
    "event_id": EVENT,
}

PRESENT_RE = r"push: present room=" + r"\!family:localhost" + r" event=\$pushsmoke1"
# the registration attempt has to leave a trace either way: a token on a
# properly entitled build, the honest failure on this one.
REGISTER_RE = r"push: (device token|remote registration failed)"

EXPECT = [r"ready @alice:localhost", r"paint contacts lit=[1-9]", REGISTER_RE, PRESENT_RE]
DONE_RES = (PRESENT_RE, r"login failed", r"rejected", r"wata-ios: ")


def build_env():
    """the sgo environment + the server binary path (ios-smoke's probe)."""
    probe = (
        f'set -e; cd "{REPO}"; WATA="{REPO}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("ios-push-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, env


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
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions",
                                       timeout=0.5).read()
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


def main():
    if sys.platform != "darwin":
        sys.exit("ios-push-smoke: macOS only")
    sgo, server_bin, env = build_env()

    r = subprocess.run([sgo, "build"], cwd=REPO / "wata-server", env=env)
    if r.returncode != 0:
        sys.exit("ios-push-smoke: wata-server build failed")
    r = subprocess.run([str(HERE / "ios-interptest.py"),
                        "--only", "build", "--only", "bundle"])
    if r.returncode != 0:
        sys.exit("ios-push-smoke: wata-ios build/bundle failed")

    out = REPO / "wata-ios" / ".sgo" / "wata-ios"
    proc, log = start_server(server_bin, str(out / "push-smoke-server.log"), env)
    if proc is None:
        sys.exit("ios-push-smoke: server never became ready")

    payload_file = out / "push-smoke-payload.json"
    payload_file.write_text(json.dumps(PAYLOAD))

    os.environ["SIMCTL_CHILD_WATA_IOS_HS"] = BASE
    os.environ["SIMCTL_CHILD_WATA_IOS_USER"] = "alice"
    os.environ["SIMCTL_CHILD_WATA_IOS_PASS"] = PASSWORD
    # the real audio thread on macaudio's fake hardware ends: no mic prompt
    # can block the harness (ios-smoke's rule).
    os.environ["SIMCTL_CHILD_WATA_MAC_AUDIO"] = "fake"

    sc = simrun.simctl()
    udid = simrun.ensure_device(sc)
    pushed = {"ok": False}

    def push_it():
        """fired once the contact list has painted, so the push lands on a
        live, foregrounded, logged-in app — the state a real one is in when a
        message arrives while the user is looking at it."""
        time.sleep(1.0)
        r = subprocess.run(sc + ["push", udid, BUNDLE_ID, str(payload_file)],
                           capture_output=True, text=True)
        print("ios-push-smoke: simctl push rc=%d %s%s"
              % (r.returncode, r.stdout.strip(), r.stderr.strip()), flush=True)
        pushed["ok"] = r.returncode == 0

    try:
        lines, elapsed, missing = simrun.launch_expect_verdict(
            sc, udid, APP, BUNDLE_ID, EXPECT, done_res=DONE_RES, timeout=120,
            screenshot=out / "push-smoke-screen.png",
            on_match=[(r"paint contacts lit=", push_it)])
        print(f"ios-push-smoke: launch-to-verdict {elapsed:.2f}s")
        if not pushed["ok"]:
            print("ios-push-smoke: `simctl push` itself failed — a missing "
                  "arrival proves nothing", file=sys.stderr)
        if missing:
            for m in missing:
                print("ios-push-smoke: MISSING " + m, file=sys.stderr)
            sys.exit(1)
        if not pushed["ok"]:
            sys.exit(1)
        print("ios-push-smoke: PASS — wata-ios registered for remote "
              "notifications, and a wata APNs payload delivered by `simctl "
              "push` was presented and read (room and event ids intact)")
    finally:
        simrun.shutdown(sc, udid)
        stop_server(proc, log)


if __name__ == "__main__":
    main()
