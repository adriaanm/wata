#!/usr/bin/env python3
"""The PushToTalk gate (plan 0065 tier 3): the ephemeral channel token.

    tools/wata-ptt-smoke.py            # run it

Tier 3's client half cannot be gated anywhere but on a phone — the
PushToTalk framework does not exist in the simulator — so the server half
carries the whole gateable weight: whether a channel token's LIFETIME is
respected, and whether the push it produces is the one Apple would accept.
Both are properties of the running server, and `tools/pushkit.py`'s fake
Apple sees them exactly as APNs would (bar the transport — see its header).

What the run asserts:

  * `POST /_wata/v1/push/channel/join` needs a session (401) and a token
    (400), and registers the CALLING session's device;
  * a message pushes to that token with `apns-push-type: pushtotalk`, at the
    `<bundle-id>.voip-ptt` topic — not the app's own — with the payload
    naming the active speaker and carrying the room and event ids;
  * `channel/leave` kills it: NOTHING is pushed afterwards;
  * a re-join REPLACES the token rather than accumulating — one message, one
    push, at the new token;
  * a device holding BOTH tokens gets the PushToTalk push and NOT the alert:
    one message is one notification, and the channel push is the better one
    (`Push.ChannelSuppressesAlert`, which carries what that costs);
  * unless APNs rejects the channel token, in which case that same message
    falls back to the device's alert — and the dead channel row is gone, so
    the next message goes straight to the alert with no wasted attempt;
  * a device holding only the stable token still gets its alert, i.e. tier 2
    is untouched by any of this;
  * `push/unregister` drops both kinds;
  * the channel registration survives a restart (re-joining a channel needs
    the user to foreground the app, so dropping it would silence the phone);
  * with NO APNs configuration nothing is pushed, nothing is logged, and the
    join is still accepted.
"""

import os
import pathlib
import random
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pushkit  # noqa: E402

WHO = "ptt-smoke"
PORT = int(os.environ.get("PTT_SMOKE_PORT") or random.randint(20000, 39999))
BUNDLE = "com.example.wata"
PTT_TOPIC = BUNDLE + ".voip-ptt"

checks = pushkit.Checks(WHO)


def join_channel(srv, token, tok, env="sandbox"):
    return srv.req("POST", "/_wata/v1/push/channel/join", {"token": tok, "env": env}, token=token)


def register(srv, token, tok, env="sandbox"):
    return srv.req("POST", "/_wata/v1/push/register",
                   {"platform": "ios", "token": tok, "env": env}, token=token)


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
        "WATA_APNS_BUNDLE_ID": BUNDLE,
        "WATA_APNS_HOST": fake.url,
        "WATA_LOG": journal,
    })

    srv = pushkit.Server(server_bin, PORT, WHO)
    srv.start(os.path.join(tmp, "server.log"), env)
    room = None
    try:
        alice = srv.login("alice")
        bob = srv.login("bob")

        # -- the join ------------------------------------------------------------
        st, _ = srv.req("POST", "/_wata/v1/push/channel/join", {"token": "T"})
        checks(st == 401, "a channel join without a session is 401")
        st, _ = srv.req("POST", "/_wata/v1/push/channel/join", {}, token=bob)
        checks(st == 400, "a channel join without a token is 400")
        st, j = join_channel(srv, bob, "bobchan1")
        checks(st == 200 and j.get("channel") is True, f"bob's app joins the channel (got {j})")

        st, j = srv.req("POST", "/_wata/v1/dm/bob", token=alice)
        room = j["room_id"]

        # -- the pushtotalk push --------------------------------------------------
        ev = srv.send(alice, room, "hello bob")
        got = fake.take(1)
        checks(len(got) == 1, f"one message, one push (got {len(got)})")
        if got:
            p = got[0]
            checks(p.token == "bobchan1", f"it went to the channel token (got {p.token})")
            checks(p.push_type == "pushtotalk", f"apns-push-type is pushtotalk (got {p.push_type})")
            checks(p.topic == PTT_TOPIC, f"the topic is the .voip-ptt one, not the app's (got {p.topic})")
            checks(p.key("activeSpeaker") == "Alice",
                   f"the payload names the active speaker (got {p.key('activeSpeaker')})")
            checks(p.aps == {}, f"a pushtotalk push carries no aps dictionary (got {p.payload})")
            checks(p.key("room_id") == room and p.key("event_id") == ev,
                   "the room and event ids ride the payload")
            checks(p.auth.startswith("bearer "), "the request carries a provider JWT")

        # -- leave kills the token -------------------------------------------------
        st, j = srv.req("POST", "/_wata/v1/push/channel/leave", {}, token=bob)
        checks(st == 200 and j.get("channel") is False, "bob's app leaves the channel")
        srv.send(alice, room, "after the leave")
        got = fake.take(0, timeout=1.5)
        checks(got == [], f"a token dead by leave is pushed to NEVER (got {fake.tokens(got)})")

        # -- a re-join replaces, it does not accumulate -----------------------------
        join_channel(srv, bob, "bobchan2")
        join_channel(srv, bob, "bobchan3")
        srv.send(alice, room, "after two joins")
        got = fake.take(1)
        checks(fake.tokens(got) == ["bobchan3"],
               f"a re-join replaces the token wholesale (got {fake.tokens(got)})")

        # -- both tokens: the channel push WINS, the alert is suppressed ------------
        # One message is one notification, and the channel push is the better one:
        # it plays the message rather than asking for a tap. `ChannelSuppressesAlert`
        # carries the reasoning and what it costs; these two assertions are what
        # fail the day it moves, whichever way it moves.
        st, _ = register(srv, bob, "bobalert")
        checks(st == 200, "bob also holds a stable alert token")
        srv.send(alice, room, "with both tokens")
        got = fake.take(1)
        checks(fake.tokens(got) == ["bobchan3"],
               f"a device with both gets the channel push and NOT the alert "
               f"(got {fake.tokens(got)})")

        # -- alice, alert-only, is unaffected ---------------------------------------
        alice2 = srv.login("alice")
        register(srv, alice2, "alicealert")
        srv.send(alice, room, "alice's other session")
        got = fake.take(2)
        checks(sorted(fake.tokens(got)) == ["alicealert", "bobchan3"],
               f"an alert-only device still gets its alert (got {fake.tokens(got)})")
        alert = [p for p in got if p.token == "alicealert"]
        checks(bool(alert) and alert[0].push_type == "alert" and alert[0].topic == BUNDLE,
               "the alert leg is untouched: alert type, the app's own topic")

        # -- a rejected channel token FALLS BACK to the alert, and is dropped --------
        # Bob's alert was suppressed for this message, so the rejection is the only
        # thing standing between him and silence: the fallback is what makes the
        # suppression safe.
        with fake.lock:
            fake.bad.add("bobchan3")
        srv.send(alice, room, "to a dead channel")
        got = fake.take(3)
        checks(sorted(fake.tokens(got)) == ["alicealert", "bobalert", "bobchan3"],
               f"a rejected channel push falls back to bob's alert rather than "
               f"losing the message (got {fake.tokens(got)})")
        srv.send(alice, room, "after the rejection")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicealert", "bobalert"],
               f"the dead channel row is gone: straight to the alert (got {fake.tokens(got)})")

        # -- unregister drops both kinds ---------------------------------------------
        join_channel(srv, bob, "bobchan4")
        st, _ = srv.req("POST", "/_wata/v1/push/unregister", {}, token=bob)
        checks(st == 200, "bob's device unregisters altogether")
        srv.send(alice, room, "after the unregister")
        got = fake.take(1)
        checks(fake.tokens(got) == ["alicealert"],
               f"unregister drops the channel token as well as the stable one (got {fake.tokens(got)})")

        # -- and survives a restart ---------------------------------------------------
        join_channel(srv, bob, "bobchan5")
    finally:
        srv.stop()

    srv.start(os.path.join(tmp, "server2.log"), env)
    try:
        alice = srv.login("alice")
        srv.send(alice, room, "after the restart")
        got = fake.take(2)
        checks(fake.tokens(got) == ["alicealert", "bobchan5"],
               f"the channel registration survived the restart (got {fake.tokens(got)})")
    finally:
        srv.stop()

    # -- no APNs configuration ------------------------------------------------------
    plain = dict(base_env)
    plain["WATA_LOG"] = journal
    srv.start(os.path.join(tmp, "server3.log"), plain)
    try:
        alice = srv.login("alice")
        bob = srv.login("bob")
        st, _ = join_channel(srv, bob, "unused")
        checks(st == 200, "a channel join is still accepted with no APNs configured")
        srv.send(alice, room, "no pusher here")
        got = fake.take(0, timeout=1.5)
        checks(got == [], f"nothing is pushed with no APNs configured (got {fake.tokens(got)})")
        noise = [ln for ln in open(os.path.join(tmp, "server3.log")) if "apns" in ln.lower()]
        checks(noise == [], f"no APNs noise in the log (got {noise})")
    finally:
        srv.stop()
        fake.stop()


def main():
    tmp = tempfile.mkdtemp(prefix="ptt-smoke.")
    try:
        run(tmp)
    except Exception:
        print(f"{WHO}: scratch kept at {tmp}")
        raise
    checks.verdict(tmp)


if __name__ == "__main__":
    main()
