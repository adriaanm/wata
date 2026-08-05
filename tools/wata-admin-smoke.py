#!/usr/bin/env python3
"""The admin-surface gate (plan 0021): accounts, the admin API, and the page.

    tools/wata-admin-smoke.py          # run it
    ADMIN_SMOKE_KEEP=1 …               # keep the scratch dir on success

Everything here is asserted against a REAL server process over HTTP, because
every property this plan added is a property of the running system rather than
of a pure function:

  * passwords at rest — a hand-written plaintext `users.json` still logs in,
    and after ONE boot the file holds only `pbkdf2-sha256$…` hashes with no
    plaintext substring surviving anywhere in it;
  * the admin gate — every `/_wata/v1/admin/…` route is 401 without a token
    and 403 with a non-admin one;
  * the server owns the file — create/rename/reset/remove apply IN MEMORY (a
    created account logs in with no restart, a removed account's token dies
    mid-session) AND land in `users.json`, which a reboot reads back;
  * `/admin` answers 200 text/html;
  * first-run setup (milestone C) — a `WATA_USERS` path with no file behind it
    boots into setup mode: the unauthenticated `mode` route says so, every
    other admin route is 503, the built-in alice/bob pair is NOT in force, and
    the one unauthenticated `setup` POST creates the first admin, writes the
    file hashed, and closes the window (a second one is 409);
  * device enrolment (milestone B) — an UNauthenticated announce parks a node
    id in memory and nothing else, the pending set is bounded by both an
    expiry and a cap, approve appends the id to the allowlist file atomically
    (and reports that the live apply is unavailable in this plain-TCP,
    stub-transport process), deny drops the row without writing anything;
  * the page-side announce and the typed code (plan 0014) — the endpoint
    sequence the admin page performs when a QR fragment names a node nobody
    announced, and the negative that makes the typed code safe: a node-id
    PREFIX is refused everywhere on the enrol surface, so a code a parent types
    can only ever select a row a device created.

The PBKDF2 derivation itself is oracled elsewhere: `wata-server selfcheck`
prints the published test vectors and `tools/wata-smoke.sh` byte-compares them
against tools/wata-selfcheck.expected.txt.
"""

import json
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
# A RANDOM port per run (ADMIN_SMOKE_PORT overrides): a fixed port collides
# with a sibling checkout running this same harness.
PORT = int(os.environ.get("ADMIN_SMOKE_PORT") or random.randint(20000, 39999))
BASE = f"http://127.0.0.1:{PORT}"

NODE_A = "a" * 64          # a well-formed node id (64 lowercase hex)
ADMIN_ROUTES = [
    ("GET", "/_wata/v1/admin/status", None),
    ("GET", "/_wata/v1/admin/users", None),
    ("POST", "/_wata/v1/admin/users", {"user": "x", "password": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/password", {"password": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/displayname", {"displayname": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/admin", {"admin": True}),
    ("DELETE", "/_wata/v1/admin/users/bob", None),
    ("GET", "/_wata/v1/admin/enroll", None),
    ("POST", f"/_wata/v1/admin/enroll/{NODE_A}/approve", None),
    ("POST", f"/_wata/v1/admin/enroll/{NODE_A}/deny", None),
]

failures = []


def check(ok, what):
    print(("  ok   " if ok else "  FAIL ") + what)
    if not ok:
        failures.append(what)


# ---- the sgo build environment -------------------------------------------------
# tools/sgo-env.sh + tools/emitdir.sh are the single source of truth for the
# toolchain and the emit layout; ask them rather than re-deriving either here.
def build_env():
    probe = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n" "$SGO" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("admin-smoke: sgo environment probe failed:\n" + out.stderr)
    sgo, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, server, env


def build(sgo, env):
    r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, "wata-server"),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit(f"admin-smoke: wata-server build failed:\n{r.stdout}{r.stderr}")


# ---- the server --------------------------------------------------------------
def our_listener(pid):
    """True when `pid` is the process listening on PORT. A wata-server that
    lost a bind race exits ZERO (the subset has no os.Exit facade), so the
    listener's identity is the only trustworthy readiness signal."""
    r = subprocess.run(["lsof", "-ti", f"tcp:{PORT}", "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return str(pid) in r.stdout.split()


def start_server(binary, log_path, env):
    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, f":{PORT}"], stdout=log, stderr=log, env=env)
    for _ in range(200):
        if proc.poll() is not None:
            break
        if our_listener(proc.pid):
            try:
                urllib.request.urlopen(f"{BASE}/_matrix/client/versions", timeout=0.5).read()
                return proc, log
            except (urllib.error.URLError, OSError):
                pass
        time.sleep(0.1)
    proc.kill()
    log.close()
    sys.exit("admin-smoke: server never became ready\n" + open(log_path).read())


def stop_server(proc, log):
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()


# ---- HTTP --------------------------------------------------------------------
def req(method, path, body=None, token=None, headers=None):
    """-> (status, parsed-json-or-text, content-type)."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            raw = resp.read().decode()
            return resp.status, parse(raw), resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, parse(raw), e.headers.get("Content-Type", "")


def parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def login(user, password):
    """-> access token, or None when the credentials are refused."""
    status, body, _ = req("POST", "/_matrix/client/v3/login", {
        "identifier": {"type": "m.id.user", "user": user}, "password": password})
    return body.get("access_token") if status == 200 else None


# ---- the checks ----------------------------------------------------------------
def entries(path):
    """users.json as {localpart: entry}."""
    with open(path) as f:
        return {e["user"]: e for e in json.load(f)}


def hashed(entry):
    return entry.get("hash", "").startswith("pbkdf2-sha256$") and "password" not in entry


def node_id(seed):
    """a well-formed iroh node id (64 lowercase hex) derived from `seed`, so
    every announce in this harness is distinguishable and reproducible."""
    return f"{seed:064x}"


def allowlist(path):
    with open(path) as f:
        return json.load(f)


def run(server, env, tmp):
    setup_mode(server, env, tmp)
    users = os.path.join(tmp, "users.json")
    # Provisioned BY HAND, the way a human writes one: plaintext passwords.
    with open(users, "w") as f:
        json.dump([{"user": "alice", "password": "alicepw1", "displayname": "Alice", "admin": True},
                   {"user": "bob", "password": "bobpw123", "displayname": "Bob"}], f)
    # The durable allowlist enrolment approval appends to. In a real
    # deployment this IS the iroh config (WATA_IROH_CONFIG); the override
    # exists so the admin surface can be exercised without the process also
    # having to serve over iroh (which it cannot in a stub-transport build).
    iroh = os.path.join(tmp, "iroh.json")
    with open(iroh, "w") as f:
        json.dump({"secretKey": "0" * 64, "relay": "none", "allowlist": []}, f)
    senv = dict(env, WATA_USERS=users, WATA_LOG=os.path.join(tmp, "journal.jsonl"),
                WATA_ENROLL_ALLOWLIST=iroh)
    proc, log = start_server(server, os.path.join(tmp, "server1.log"), senv)
    try:
        first_boot(users)
        atok, btok = sessions()
        gate(atok, btok)
        page()
        status_panel(atok)
        crud(atok, users)
        revoke(atok, users)
        enrolment(atok, btok, iroh)
        provisioning(atok, users)
    finally:
        stop_server(proc, log)

    # ---- bounded pending set: a short expiry and a small cap ------------------
    proc, log = start_server(server, os.path.join(tmp, "server4.log"),
                             dict(senv, WATA_ENROLL_MAX="3", WATA_ENROLL_EXPIRY_MS="1500"))
    try:
        bounded()
    finally:
        stop_server(proc, log)

    # ---- the reboot: the file IS the source of truth -------------------------
    proc, log = start_server(server, os.path.join(tmp, "server2.log"), senv)
    try:
        after_reboot(users)
    finally:
        stop_server(proc, log)

    # ---- no file at all: the built-in pair, alice the admin ------------------
    proc, log = start_server(server, os.path.join(tmp, "server3.log"),
                             dict(env, WATA_USERS=""))
    try:
        builtin_defaults()
    finally:
        stop_server(proc, log)


def setup_mode(server, env, tmp):
    """Milestone C: an install that has never had an account. `WATA_USERS`
    names a file that does not exist, so the server has no accounts at all —
    not even the harness fallback pair — until someone claims it through the
    one unauthenticated endpoint."""
    fresh = os.path.join(tmp, "fresh")
    os.mkdir(fresh)
    users = os.path.join(fresh, "users.json")
    senv = dict(env, WATA_USERS=users, WATA_LOG=os.path.join(fresh, "journal.jsonl"))
    proc, log = start_server(server, os.path.join(fresh, "setup.log"), senv)
    try:
        print("setup mode")
        status, body, _ = req("GET", "/_wata/v1/admin/mode")
        check(status == 200 and body.get("setup") is True,
              f"an empty install reports setup mode, unauthenticated (saw {status} {body})")
        others = {req(m, p, b)[0] for m, p, b in ADMIN_ROUTES}
        check(others == {503}, f"every other admin route is 503 during setup (saw {sorted(others)})")
        check(login("alice", "testpass123") is None,
              "the built-in fallback pair is NOT in force when WATA_USERS is set")
        check(req("GET", "/admin")[0] == 200, "the page is still served during setup")
        check(not os.path.exists(users), "nothing is written before the first claim")

        print("setup: the claim")
        check(req("POST", "/_wata/v1/admin/setup",
                  {"user": "Bad/Name", "password": "x"})[0] == 400,
              "an invalid localpart is refused")
        check(req("POST", "/_wata/v1/admin/setup", {"user": "parent"})[0] == 400,
              "a passwordless setup is refused")
        check(req("GET", "/_wata/v1/admin/mode")[1].get("setup") is True,
              "a refused setup leaves the window open")
        status, body, _ = req("POST", "/_wata/v1/admin/setup",
                              {"user": "parent", "password": "parentpw1", "displayname": "Parent"})
        check(status == 200 and body.get("user_id") == "@parent:localhost",
              f"setup creates the first account (saw {status} {body})")
        check(req("GET", "/_wata/v1/admin/mode")[1].get("setup") is False,
              "the window closes with that first write")
        check(req("POST", "/_wata/v1/admin/setup",
                  {"user": "intruder", "password": "x", "displayname": "X"})[0] == 409,
              "a second setup is 409 — first comer claims the install")
        check(login("intruder", "x") is None, "the loser's account was never created")

        print("setup: what it left behind")
        es = entries(users)
        check(list(es) == ["parent"], f"the file holds exactly the claimed account ({list(es)})")
        check(hashed(es["parent"]), "the first account is stored hashed")
        check("parentpw1" not in open(users).read(), "no plaintext password in the file")
        check(es["parent"]["admin"] is True, "the first account is the admin")
        check(es["parent"]["displayname"] == "Parent", "the display name is stored")
        check(oct(os.stat(users).st_mode & 0o777) == "0o600",
              f"the file is 0600 (saw {oct(os.stat(users).st_mode & 0o777)})")

        ptok = login("parent", "parentpw1")
        check(ptok is not None, "the claimed account logs in with NO restart")
        check(req("GET", "/_wata/v1/admin/status", None, ptok)[0] == 200,
              "it reaches the admin surface")
        check(req("GET", "/_wata/v1/admin/status")[0] == 401,
              "the admin gate is back to 401 once setup is done")
    finally:
        stop_server(proc, log)

    proc, log = start_server(server, os.path.join(fresh, "setup2.log"), senv)
    try:
        print("setup: the reboot")
        check(req("GET", "/_wata/v1/admin/mode")[1].get("setup") is False,
              "a server that reads the written file is not in setup mode")
        check(login("parent", "parentpw1") is not None, "the claimed account survives a reboot")
        check(req("POST", "/_wata/v1/admin/setup",
                  {"user": "intruder", "password": "x"})[0] == 409,
              "setup stays closed across a reboot")
    finally:
        stop_server(proc, log)


def first_boot(users):
    print("plaintext rewrite")
    raw = open(users).read()
    es = entries(users)
    check(all(hashed(e) for e in es.values()), "every entry is hashed after one boot")
    check("alicepw1" not in raw and "bobpw123" not in raw,
          "no plaintext password substring survives in the file")
    check(es["alice"]["admin"] is True and es["bob"]["admin"] is False,
          "admin flags are preserved by the rewrite")
    check(es["alice"]["displayname"] == "Alice", "display names are preserved")


def sessions():
    print("login")
    atok = login("alice", "alicepw1")
    btok = login("bob", "bobpw123")
    check(atok is not None, "a hand-written plaintext password still logs in")
    check(btok is not None, "the second account logs in")
    check(login("alice", "alicepw2") is None, "a wrong password is refused")
    return atok, btok


def gate(atok, btok):
    print("the admin gate")
    anon = {req(m, p, b)[0] for m, p, b in ADMIN_ROUTES}
    check(anon == {401}, f"every admin route is 401 unauthenticated (saw {sorted(anon)})")
    nonadmin = {req(m, p, b, btok)[0] for m, p, b in ADMIN_ROUTES}
    check(nonadmin == {403}, f"every admin route is 403 for a non-admin (saw {sorted(nonadmin)})")
    check(req("GET", "/_wata/v1/admin/status", None, "syt_nope")[0] == 401,
          "an unknown token is 401")
    check(req("GET", "/_wata/v1/admin/status", None, atok)[0] == 200,
          "an admin token is 200")
    # the two ungated routes, on a server that HAS accounts.
    status, body, _ = req("GET", "/_wata/v1/admin/mode")
    check(status == 200 and body.get("setup") is False,
          f"the mode route is unauthenticated and reports no setup (saw {status} {body})")
    check(req("POST", "/_wata/v1/admin/setup", {"user": "x", "password": "y"})[0] == 409,
          "the setup route is closed once accounts exist")


def page():
    print("the page")
    status, body, ctype = req("GET", "/admin")
    check(status == 200, "GET /admin is 200")
    check(ctype.startswith("text/html"), f"GET /admin is text/html (saw {ctype!r})")
    check(isinstance(body, str) and "wata admin" in body, "the page is the admin page")


def status_panel(atok):
    print("status")
    _, s, _ = req("GET", "/_wata/v1/admin/status", None, atok)
    for field in ("version", "uptime_ms", "transport", "retention_days",
                  "journal_bytes", "rooms", "media_count", "media_bytes", "users"):
        check(field in s, f"status reports {field}")
    check(s["transport"] == "tcp", "status reports the tcp transport")
    check(s["journal_bytes"] > 0, "status reports a non-empty journal")
    rows = {u["user"]: u for u in s["users"]}
    check(rows["alice"]["admin"] is True and rows["bob"]["admin"] is False,
          "status reports the admin flags")
    check(rows["alice"]["devices"] >= 1, "status counts alice's live devices")
    check("hash" not in json.dumps(s), "status never hands out a password hash")


def crud(atok, users):
    print("accounts CRUD")
    status, body, _ = req("POST", "/_wata/v1/admin/users",
                          {"user": "kid", "password": "kidpw123", "displayname": "Kid"}, atok)
    check(status == 200, f"create answers 200 (saw {status} {body})")
    check(login("kid", "kidpw123") is not None, "the new account logs in with NO restart")
    check(entries(users)["kid"]["displayname"] == "Kid", "the new account is in users.json")
    check(hashed(entries(users)["kid"]), "the new account is stored hashed")
    _, prof, _ = req("GET", "/_matrix/client/v3/profile/@kid:localhost")
    check(prof.get("displayname") == "Kid", "the new account has a profile")

    # plan 0018: provisioning an account IS joining the family — the new
    # account's very first sync serves the stamped family room, joined, with
    # no client-side join and no restart.
    ktok = login("kid", "kidpw123")
    _, sync, _ = req("GET", "/_matrix/client/v3/sync?timeout=0", None, ktok)
    fam = [rid for rid, room in sync.get("rooms", {}).get("join", {}).items()
           if any(e["type"] == "net.wata.family"
                  for e in room.get("state", {}).get("events", []))]
    check(len(fam) == 1, "the new account is joined to THE stamped family room")

    status, body, _ = req("POST", "/_wata/v1/admin/users",
                          {"user": "kid", "password": "other"}, atok)
    check(status == 400 and body.get("errcode") == "M_USER_IN_USE",
          f"a duplicate create is M_USER_IN_USE (saw {status} {body})")
    check(req("POST", "/_wata/v1/admin/users",
              {"user": "Bad/Name", "password": "x"}, atok)[0] == 400,
          "an invalid localpart is refused")
    check(req("POST", "/_wata/v1/admin/users", {"user": "nopw"}, atok)[0] == 400,
          "a passwordless create is refused")

    print("password reset")
    check(req("POST", "/_wata/v1/admin/users/kid/password",
              {"password": "kidpw456"}, atok)[0] == 200, "reset answers 200")
    check(login("kid", "kidpw456") is not None, "the new password logs in")
    check(login("kid", "kidpw123") is None, "the old password no longer logs in")
    check(req("POST", "/_wata/v1/admin/users/ghost/password",
              {"password": "x"}, atok)[0] == 404, "resetting an unknown user is 404")

    print("rename")
    check(req("POST", "/_wata/v1/admin/users/kid/displayname",
              {"displayname": "Kiddo"}, atok)[0] == 200, "rename answers 200")
    check(entries(users)["kid"]["displayname"] == "Kiddo", "the rename is in users.json")
    _, prof, _ = req("GET", "/_matrix/client/v3/profile/@kid:localhost")
    check(prof.get("displayname") == "Kiddo", "the rename reached the profile")


def revoke(atok, users):
    print("removal revokes the session")
    ktok = login("kid", "kidpw456")
    check(req("GET", "/_matrix/client/v3/account/whoami", None, ktok)[0] == 200,
          "the account's token works before removal")
    check(req("DELETE", "/_wata/v1/admin/users/alice", None, atok)[0] == 403,
          "an admin cannot remove their own account")
    check(req("DELETE", "/_wata/v1/admin/users/kid", None, atok)[0] == 200,
          "remove answers 200")
    check(req("GET", "/_matrix/client/v3/account/whoami", None, ktok)[0] == 401,
          "the removed account's token is dead MID-SESSION")
    check(login("kid", "kidpw456") is None, "the removed account cannot log in")
    check("kid" not in entries(users), "the removal is in users.json")
    check(req("DELETE", "/_wata/v1/admin/users/kid", None, atok)[0] == 404,
          "removing an unknown user is 404")

    print("admin flag")
    check(req("POST", "/_wata/v1/admin/users/bob/admin", {"admin": True}, atok)[0] == 200,
          "granting admin answers 200")
    btok = login("bob", "bobpw123")
    check(req("GET", "/_wata/v1/admin/status", None, btok)[0] == 200,
          "the promoted account reaches the admin surface")
    check(entries(users)["bob"]["admin"] is True, "the grant is in users.json")
    check(req("POST", "/_wata/v1/admin/users/bob/admin", {"admin": False}, atok)[0] == 200,
          "revoking admin answers 200")
    check(req("GET", "/_wata/v1/admin/status", None, btok)[0] == 403,
          "the demoted account is 403 again, on the SAME token")


def pending(atok):
    _, body, _ = req("GET", "/_wata/v1/admin/enroll", None, atok)
    return {p["node_id"]: p for p in body["pending"]}


def enrolment(atok, btok, iroh):
    print("enrolment: the announce")
    one, two, three = node_id(1), node_id(2), node_id(3)
    status, body, _ = req("POST", "/_wata/v1/enroll", {"nodeId": one, "nonce": "AB12"})
    check(status == 200, f"an announce needs NO token (saw {status} {body})")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": "not-a-node-id", "nonce": "AB12"})[0] == 400,
          "a malformed node id is refused")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": one, "nonce": "bad nonce"})[0] == 400,
          "a malformed nonce is refused")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": node_id(0xABCDEF).upper(), "nonce": "AB12"})[0] == 400,
          "an uppercase-hex node id is refused (the allowlist form is lowercase)")
    req("POST", "/_wata/v1/enroll", {"nodeId": one, "nonce": "CD34"})
    req("POST", "/_wata/v1/enroll", {"nodeId": two, "nonce": "EF56"})

    rows = pending(atok)
    check(set(rows) == {one, two}, f"a re-announce refreshes its row rather than adding one ({len(rows)} rows)")
    check(rows[one]["nonce"] == "CD34", "the row carries the latest nonce")
    check(rows[one]["age_ms"] >= 0 and rows[one]["expires_in_ms"] > 0, "the row carries its age and its expiry")
    check(req("GET", "/_wata/v1/admin/enroll", None, btok)[0] == 403,
          "the pending list is admin-only")

    print("enrolment: approve")
    status, body, _ = req("POST", f"/_wata/v1/admin/enroll/{one}/approve", None, atok)
    check(status == 200, f"approve answers 200 (saw {status} {body})")
    check(body.get("node_id") == one, "approve names the node it approved")
    check(body.get("live") is False and "stub build" in body.get("note", ""),
          f"approve reports that the LIVE apply is unavailable here (saw {body.get('note')!r})")
    cfg = allowlist(iroh)
    check(cfg["allowlist"] == [one], f"the id is in the allowlist file (saw {cfg['allowlist']})")
    check(cfg["secretKey"] == "0" * 64 and cfg["relay"] == "none",
          "the rewrite preserves the rest of the config")
    check(not os.path.exists(iroh + ".tmp"), "the atomic rewrite leaves no temp file behind")
    check(one not in pending(atok), "the approved row leaves the pending list")
    check(req("POST", f"/_wata/v1/admin/enroll/{one}/approve", None, atok)[0] == 404,
          "approving the same node twice is 404 (it is no longer pending)")
    check(req("POST", f"/_wata/v1/admin/enroll/{three}/approve", None, atok)[0] == 404,
          "approving a node that never announced is 404")

    print("enrolment: deny")
    check(req("POST", f"/_wata/v1/admin/enroll/{two}/deny", None, atok)[0] == 200,
          "deny answers 200")
    check(two not in pending(atok), "the denied row leaves the pending list")
    check(allowlist(iroh)["allowlist"] == [one], "deny writes nothing to the allowlist file")
    check(req("POST", f"/_wata/v1/admin/enroll/{two}/deny", None, atok)[0] == 404,
          "denying an unknown node is 404")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": two, "nonce": "GH78"})[0] == 200,
          "a denied device may announce again")
    req("POST", f"/_wata/v1/admin/enroll/{two}/deny", None, atok)

    fragment_announce(atok, iroh)
    typed_code(atok)
    already_enrolled(atok, one)


def fragment_announce(atok, iroh):
    """The PAGE-SIDE ANNOUNCE (plan 0014's ruling, adminui/index.html): a parent
    scans a handset's QR, lands on /admin#enroll/<nodeId>/<nonce>, and the page
    — finding no pending row — posts the announce ITSELF before highlighting it
    for approval. That is what lets a handset with no route to this server at
    all (cellular-only, or refused by the very allowlist it is trying to join)
    be enrolled.

    The page's JavaScript cannot run in this harness, so what is asserted here
    is the ENDPOINT SEQUENCE it performs, in order, with the same authority it
    has: an unauthenticated announce, then an authenticated read, then an
    authenticated approve."""
    print("enrolment: the page-side announce (the fragment sequence)")
    node = node_id(0x9A)
    check(node not in pending(atok), "the scanned node has no row yet — nobody announced it")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": node, "nonce": "QR01"})[0] == 200,
          "the PAGE announces it, unauthenticated, on the parent's behalf")
    rows = pending(atok)
    check(node in rows and rows[node]["nonce"] == "QR01",
          "the row the page just created is the one it can highlight")
    check(req("POST", f"/_wata/v1/admin/enroll/{node}/approve", None, atok)[0] == 200,
          "and approving it from that row works like any other")
    check(node in allowlist(iroh)["allowlist"], "the page-announced node reaches the allowlist file")


def typed_code(atok):
    """The TYPED-CODE FALLBACK (milestone 4). The handset prints
    `<nonce>-<first 8 of its node id>` under the QR, and the page matches that
    against the pending rows it already has — a SELECTION, never a creation.

    The security property is asserted from the other side: a prefix must not be
    able to reach the server AS an identity. Eight hex characters are not a node
    id, the announce validator says so, and nothing else on the enrol surface
    takes anything shorter — so no typed code can put an id into the allowlist
    that a device never announced."""
    print("enrolment: the typed code selects, and a prefix cannot inject")
    node = node_id(0xBEEF)
    req("POST", "/_wata/v1/enroll", {"nodeId": node, "nonce": "QR02"})
    rows = pending(atok)
    check(node in rows, "the device announced itself (the typed path needs a reachable device)")
    prefix = node[:8]
    hits = [n for n, p in rows.items() if p["nonce"] == "QR02" and n.startswith(prefix)]
    check(hits == [node], f"nonce+prefix picks exactly one pending row (saw {hits})")

    check(req("POST", "/_wata/v1/enroll", {"nodeId": prefix, "nonce": "QR02"})[0] == 400,
          "a PREFIX announce is refused — 8 hex characters are not a node id")
    check(req("POST", "/_wata/v1/enroll", {"nodeId": node[:63], "nonce": "QR02"})[0] == 400,
          "so is a 63-character near-miss")
    check(req("POST", f"/_wata/v1/admin/enroll/{prefix}/approve", None, atok)[0] == 404,
          "approving a prefix approves nothing — the id has to be one that announced")
    check(set(pending(atok)) == {node},
          "none of the rejected forms left a row behind")
    req("POST", f"/_wata/v1/admin/enroll/{node}/deny", None, atok)


def already_enrolled(atok, enrolled_id):
    """ALREADY ENROLLED IS AN ANSWER ([ADMIN-ENROLL-ALREADY-DONE]). An enrolled
    handset has no pending row, and neither does one whose announce expired —
    the page could not tell the two apart and reported the expiry over both,
    including immediately after a SUCCESSFUL approve (the fragment re-run finds
    the row cleared, re-announces, gets no row back). So the server marks it:
    the announce answers `allowlisted`, and the admin listing carries the ids
    the allowlist holds beside the pending rows.

    What is asserted here is the endpoint sequence the page performs — a
    reload of the fragment, and the refresh that follows an approve — plus the
    typed-code half, which has only a prefix to match and therefore needs those
    ids listed."""
    print("enrolment: an already-enrolled device reads as done, not as an expiry")
    _, listing, _ = req("GET", "/_wata/v1/admin/enroll", None, atok)
    check(enrolled_id in (listing.get("allowlisted") or []),
          "the admin listing reports the allowlist's ids beside the pending rows")
    check(enrolled_id not in {p["node_id"] for p in listing["pending"]},
          "the enrolled device has no pending row — which is why the marker is needed")

    status, body, _ = req("POST", "/_wata/v1/enroll", {"nodeId": enrolled_id, "nonce": "QR09"})
    check(status == 200 and body.get("allowlisted") is True and body.get("pending") is False,
          f"the page's re-announce answers 'already enrolled' (saw {status} {body})")
    check(enrolled_id not in pending(atok),
          "and records nothing — a pending row asks for a decision that is already made")

    fresh = node_id(0x5150)
    status, body, _ = req("POST", "/_wata/v1/enroll", {"nodeId": fresh, "nonce": "QR10"})
    check(body.get("allowlisted") is False and body.get("pending") is True,
          f"an id nobody approved still answers pending, not enrolled (saw {body})")
    req("POST", f"/_wata/v1/admin/enroll/{fresh}/deny", None, atok)

    _, listing, _ = req("GET", "/_wata/v1/admin/enroll", None, atok)
    allowed = listing.get("allowlisted") or []
    prefix = enrolled_id[:8]
    check(any(i.startswith(prefix) for i in allowed),
          "a TYPED code's prefix finds the enrolled device in that list — 'already enrolled'")
    # (the harness's node ids are seeds padded with zeros, so they share every
    # prefix — the negative is asserted on membership, which is what the page's
    # fragment path matches on.)
    check(fresh not in allowed, "a device nobody approved is not in that list")


def provisioning(atok, users):
    """Account provisioning via enrolment (plan 0027). This process serves
    plain TCP only, which is exactly what makes it the right place to prove
    the seam's TCP half: device-login must be 403 here UNCONDITIONALLY — with
    no header, and with a FORGED X-Wata-Node-Id naming a genuinely bound node
    (the strip in server.serveTcp is what makes the forgery inert). The
    approve-side binding, the roster the picker reads, and the inline
    create-and-bind are all plain admin-API properties and are asserted here
    too; the iroh-path positive (a real device-login answering a session)
    needs a provable peer and lives in tunnel-smoke."""
    print("provisioning: device-login is 403 on the TCP path, always")
    node_p, node_q = node_id(0x70), node_id(0x71)
    status, body, _ = req("POST", "/_wata/v1/device-login", {})
    check(status == 403, f"no trusted node id -> 403 (saw {status} {body})")

    print("provisioning: approve binds an existing account")
    req("POST", "/_wata/v1/enroll", {"nodeId": node_p, "nonce": "PV01"})
    status, body, _ = req("POST", f"/_wata/v1/admin/enroll/{node_p}/approve",
                          {"user": "bob"}, atok)
    check(status == 200 and body.get("user") == "bob"
          and body.get("user_id") == "@bob:localhost",
          f"approve with a roster account answers the binding (saw {status} {body})")
    _, listing, _ = req("GET", "/_wata/v1/admin/enroll", None, atok)
    check({b["node_id"]: b["user"] for b in listing.get("bindings", [])}.get(node_p) == "bob",
          "the listing reports the binding beside the enrolled id")
    check({u["user"] for u in listing.get("users", [])} >= {"alice", "bob"},
          "the listing carries the roster the picker renders")

    # the forgery: a REAL bound node id in the header, over TCP. If the strip
    # ever regressed, this would mint a session for bob with no credential.
    status, body, _ = req("POST", "/_wata/v1/device-login", {},
                          headers={"X-Wata-Node-Id": node_p})
    check(status == 403, f"a forged trusted header over TCP is stripped -> 403 (saw {status} {body})")

    print("provisioning: inline create-and-bind")
    req("POST", "/_wata/v1/enroll", {"nodeId": node_q, "nonce": "PV02"})
    check(req("POST", f"/_wata/v1/admin/enroll/{node_q}/approve",
              {"user": "Bad/Name"}, atok)[0] == 400,
          "an invalid inline name is refused")
    check(node_q in pending(atok), "a refused binding leaves the row pending")
    status, body, _ = req("POST", f"/_wata/v1/admin/enroll/{node_q}/approve",
                          {"user": "handset1"}, atok)
    check(status == 200 and body.get("user_id") == "@handset1:localhost",
          f"a NEW name creates the account and binds it, one step (saw {status} {body})")
    es = entries(users)
    check("handset1" in es, "the created account is in users.json")
    check(es["handset1"].get("hash", "x") == "", "the device account stores NO password hash")
    check(login("handset1", "") is None and login("handset1", "guess") is None,
          "no password logs in as it — the node key is its only credential")
    _, prof, _ = req("GET", "/_matrix/client/v3/profile/@handset1:localhost")
    check(prof.get("displayname") == "handset1", "the created account has a profile")
    _, sroom, _ = req("GET", "/_wata/v1/admin/status", None, atok)
    check("handset1" in {u["user"] for u in sroom["users"]},
          "the created account is on the users surface like any other")


def bounded():
    print("enrolment: the pending set is bounded (max 3, expiry 1.5s)")
    atok = login("alice", "alicepw1")
    for i in range(40):
        req("POST", "/_wata/v1/enroll", {"nodeId": node_id(100 + i), "nonce": f"N{i:03d}"})
    rows = pending(atok)
    check(len(rows) == 3, f"a 40-announce flood does not grow the set past the cap (saw {len(rows)})")
    check(set(rows) == {node_id(137), node_id(138), node_id(139)},
          "the cap evicts the OLDEST announces, keeping the newest")
    time.sleep(1.8)
    check(pending(atok) == {}, "every row is gone once its expiry passes")
    check(req("POST", f"/_wata/v1/admin/enroll/{node_id(139)}/approve", None, atok)[0] == 404,
          "an expired announce cannot be approved")


def after_reboot(users):
    print("reboot")
    check(login("alice", "alicepw1") is not None, "the hashed file logs in after a reboot")
    check(login("kid", "kidpw456") is None, "a removed account stays removed")
    atok = login("alice", "alicepw1")
    _, s, _ = req("GET", "/_wata/v1/admin/status", None, atok)
    check(s is not None and {u["user"] for u in s["users"]} == {"alice", "bob", "handset1"},
          "the account set round-trips the reboot (incl. the bound device account)")
    check(all(hashed(e) for e in entries(users).values() if e["user"] != "handset1"),
          "the file is still fully hashed after the reboot")
    check(entries(users)["handset1"].get("hash", "x") == "",
          "the device account is still passwordless after the reboot")

    print("reboot: bindings replay from the journal")
    _, listing, _ = req("GET", "/_wata/v1/admin/enroll", None, atok)
    binds = {b["node_id"]: b["user"] for b in listing.get("bindings", [])}
    check(binds.get(node_id(0x70)) == "bob" and binds.get(node_id(0x71)) == "handset1",
          f"both bindings round-trip the reboot (saw {binds})")


def builtin_defaults():
    print("built-in accounts (WATA_USERS unset)")
    check(req("GET", "/_wata/v1/admin/mode")[1].get("setup") is False,
          "an unset WATA_USERS is harness mode, never setup mode")
    atok = login("alice", "testpass123")
    btok = login("bob", "testpass123")
    check(atok is not None and btok is not None, "the built-in pair logs in")
    check(req("GET", "/_wata/v1/admin/status", None, atok)[0] == 200,
          "the built-in alice is an admin")
    check(req("GET", "/_wata/v1/admin/status", None, btok)[0] == 403,
          "the built-in bob is not")


def main():
    sgo, server, env = build_env()
    build(sgo, env)
    tmp = tempfile.mkdtemp(prefix="wata-admin-smoke.")
    try:
        run(server, env, tmp)
    finally:
        if failures or os.environ.get("ADMIN_SMOKE_KEEP"):
            print(f"admin-smoke: scratch kept at {tmp}")
        else:
            shutil.rmtree(tmp, ignore_errors=True)
    if failures:
        print(f"ADMIN-SMOKE FAIL ({len(failures)} checks)")
        return 1
    print("ADMIN-SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
