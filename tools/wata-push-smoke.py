#!/usr/bin/env python3
"""The APNs alert gate (plan 0065 tier 2): push.scala over HTTP, no Apple.

    tools/wata-push-smoke.py            # run it

The whole point of the tier's design is that it gates with NO Apple
credentials: the APNs host is configuration (`WATA_APNS_HOST`), so a local
fake standing in for Apple sees every push the server would have sent, and a
throwaway ES256 key signs the provider JWT. Nothing here needs a developer
account, a phone, or the portal. The fake, the key, the build and the server
are `tools/pushkit.py`, shared with the tier-3 gate (`wata-ptt-smoke.py`).

What the run asserts, all of it a property of the running server:

  * registration — `POST /_wata/v1/push/register` needs a token (401 without,
    400 with no `token`), and stores the row against the CALLING session;
  * the fan-out — a message landing in a room pushes to every joined member's
    registered devices, with the alert title/body, `interruption-level:
    time-sensitive`, a badge count, and the room and event ids as custom keys;
  * the sender's own device does NOT get a push (its OTHER sessions do);
  * re-registration overwrites, so a device that got a new token is pushed to
    once, at the new token;
  * 410 Gone DELETES the registration — the next message pushes to nobody;
  * unregister drops it too;
  * the registration survives a restart (it is journaled);
  * with NO APNs configuration the server behaves exactly as it did before:
    registrations are still accepted, messages still send, and nothing is
    pushed anywhere.
"""

import os
import pathlib
import random
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pushkit  # noqa: E402

WHO = "push-smoke"
PORT = int(os.environ.get("PUSH_SMOKE_PORT") or random.randint(20000, 39999))

checks = pushkit.Checks(WHO)


def run(tmp):
    sgo, server_bin, base_env = pushkit.build_env(WHO)
    pushkit.build(sgo, base_env, WHO)
    fake = pushkit.FakeAPNs()
    key = pushkit.make_key(tmp, WHO)
    journal = os.path.join(tmp, "wata.jsonl")

    env = dict(base_env)
    env.update({
        "WATA_APNS_KEY": key,
        "WATA_APNS_KEY_ID": "KEYID123",
        "WATA_APNS_TEAM_ID": "TEAMID456",
        "WATA_APNS_BUNDLE_ID": "com.example.wata",
        "WATA_APNS_HOST": fake.url,
        "WATA_LOG": journal,
    })

    srv = pushkit.Server(server_bin, PORT, WHO)
    srv.start(os.path.join(tmp, "server.log"), env)
    room = None
    try:
        alice = srv.login("alice")
        bob = srv.login("bob")

        # -- registration -------------------------------------------------------
        st, _ = srv.req("POST", "/_wata/v1/push/register", {"platform": "ios", "token": "T", "env": "sandbox"})
        checks(st == 401, "register without a token is 401")
        st, _ = srv.req("POST", "/_wata/v1/push/register", {"platform": "ios"}, token=bob)
        checks(st == 400, "register without a device token is 400")
        st, j = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "bobphone", "env": "sandbox"}, token=bob)
        checks(st == 200 and j.get("registered") is True, f"bob's phone registers (got {j})")
        st, _ = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "alicephone", "env": "sandbox"}, token=alice)
        checks(st == 200, "alice's own session registers too")

        st, j = srv.req("POST", "/_wata/v1/dm/bob", token=alice)
        room = j["room_id"]

        # -- the fan-out --------------------------------------------------------
        ev = srv.send(alice, room, "hello bob")
        got = fake.take(1)
        checks(len(got) == 1, f"one message, one push (got {len(got)})")
        if got:
            p = got[0]
            checks(p.token == "bobphone", f"the push went to bob's phone, not the sender's (got {p.token})")
            checks(p.push_type == "alert", f"it is an alert push (got {p.push_type})")
            checks(p.topic == "com.example.wata", f"at the app's own topic (got {p.topic})")
            checks(p.aps.get("alert", {}).get("title") == "Alice",
                   f"the alert names the sender (got {p.aps.get('alert')})")
            checks(p.aps.get("alert", {}).get("body") == "hello bob", "the alert carries the message")
            checks(p.aps.get("interruption-level") == "time-sensitive", "the push is time-sensitive")
            checks(p.aps.get("badge") == 1, f"the badge counts bob's unplayed messages (got {p.aps.get('badge')})")
            checks(p.key("room_id") == room and p.key("event_id") == ev,
                   "the room and event ids ride the payload")
            checks(p.priority == "10", f"apns-priority is 10 (got {p.priority})")
            checks(p.expiration == "0", f"apns-expiration is 0 — deliver once, do not store (got {p.expiration})")
            p.jwt_ok(checks, "TEAMID456", "KEYID123")

        # -- the sender's own device is excluded, its other sessions are not -----
        alice2 = srv.login("alice")
        st, _ = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "alicepad", "env": "sandbox"}, token=alice2)
        checks(st == 200, "alice registers a second session")
        srv.send(alice, room, "again")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicepad", "bobphone"],
               f"the sender's OTHER session is pushed, its own is not (got {fake.tokens(got)})")

        # -- re-registration overwrites ------------------------------------------
        st, _ = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "bobphone2", "env": "sandbox"}, token=bob)
        checks(st == 200, "bob re-registers with a new token")
        srv.send(alice, room, "new token")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicepad", "bobphone2"],
               f"the old token is gone, not doubled (got {fake.tokens(got)})")

        # -- 410 Gone deletes the registration ------------------------------------
        with fake.lock:
            fake.gone.add("bobphone2")
        srv.send(alice, room, "to a dead token")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicepad", "bobphone2"], "the dead token is pushed to once")
        srv.send(alice, room, "after the 410")
        got = fake.take(1)
        checks(fake.tokens(got) == ["alicepad"],
               f"the 410 deleted bob's registration (got {fake.tokens(got)})")

        # -- unregister ------------------------------------------------------------
        st, j = srv.req("POST", "/_wata/v1/push/unregister", {}, token=alice2)
        checks(st == 200 and j.get("registered") is False, "alice's second session unregisters")
        srv.send(alice, room, "after the unregister")
        got = fake.take(0, timeout=1.0)
        checks(got == [], f"nothing is pushed to an unregistered device (got {fake.tokens(got)})")

        # -- registrations survive a restart ---------------------------------------
        st, _ = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "bobphone3", "env": "sandbox"}, token=bob)
        checks(st == 200, "bob registers again before the restart")
    finally:
        srv.stop()

    srv.start(os.path.join(tmp, "server2.log"), env)
    try:
        # a fresh login mints a fresh device, so alice's FIRST session's
        # registration is no longer "the sender's own device" and is pushed to
        # as well — which is itself the journal replay being asserted.
        alice = srv.login("alice")
        srv.send(alice, room, "after the restart")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicephone", "bobphone3"],
               f"the registrations survived the restart (got {fake.tokens(got)})")
    finally:
        srv.stop()

    # -- no APNs configuration: exactly the behavior of a server without pushes --
    plain = dict(base_env)
    plain["WATA_LOG"] = journal
    srv.start(os.path.join(tmp, "server3.log"), plain)
    try:
        alice = srv.login("alice")
        bob = srv.login("bob")
        st, _ = srv.req("POST", "/_wata/v1/push/register",
                        {"platform": "ios", "token": "unused", "env": "sandbox"}, token=bob)
        checks(st == 200, "registration is still accepted with no APNs configured")
        srv.send(alice, room, "no pusher here")
        got = fake.take(0, timeout=1.5)
        checks(got == [], f"nothing is pushed with no APNs configured (got {fake.tokens(got)})")
        st, j = srv.req("GET", "/_matrix/client/v3/sync?timeout=0", token=bob)
        checks(st == 200, "the server is otherwise unchanged")
        errs = [ln for ln in open(os.path.join(tmp, "server3.log")) if "apns" in ln.lower()]
        checks(errs == [], f"no APNs noise in the log (got {errs})")
    finally:
        srv.stop()
        fake.stop()


def main():
    tmp = tempfile.mkdtemp(prefix="push-smoke.")
    try:
        run(tmp)
    except Exception:
        print(f"{WHO}: scratch kept at {tmp}")
        raise
    checks.verdict(tmp)


if __name__ == "__main__":
    main()
