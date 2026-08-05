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
  * `/admin` answers 200 text/html.

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

ADMIN_ROUTES = [
    ("GET", "/_wata/v1/admin/status", None),
    ("GET", "/_wata/v1/admin/users", None),
    ("POST", "/_wata/v1/admin/users", {"user": "x", "password": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/password", {"password": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/displayname", {"displayname": "y"}),
    ("POST", "/_wata/v1/admin/users/bob/admin", {"admin": True}),
    ("DELETE", "/_wata/v1/admin/users/bob", None),
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
def req(method, path, body=None, token=None):
    """-> (status, parsed-json-or-text, content-type)."""
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
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


def run(server, env, tmp):
    users = os.path.join(tmp, "users.json")
    # Provisioned BY HAND, the way a human writes one: plaintext passwords.
    with open(users, "w") as f:
        json.dump([{"user": "alice", "password": "alicepw1", "displayname": "Alice", "admin": True},
                   {"user": "bob", "password": "bobpw123", "displayname": "Bob"}], f)
    senv = dict(env, WATA_USERS=users, WATA_LOG=os.path.join(tmp, "journal.jsonl"))
    proc, log = start_server(server, os.path.join(tmp, "server1.log"), senv)
    try:
        first_boot(users)
        atok, btok = sessions()
        gate(atok, btok)
        page()
        status_panel(atok)
        crud(atok, users)
        revoke(atok, users)
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


def after_reboot(users):
    print("reboot")
    check(login("alice", "alicepw1") is not None, "the hashed file logs in after a reboot")
    check(login("kid", "kidpw456") is None, "a removed account stays removed")
    atok = login("alice", "alicepw1")
    _, s, _ = req("GET", "/_wata/v1/admin/status", None, atok)
    check(s is not None and {u["user"] for u in s["users"]} == {"alice", "bob"},
          "the account set round-trips the reboot")
    check(all(hashed(e) for e in entries(users).values()),
          "the file is still fully hashed after the reboot")


def builtin_defaults():
    print("built-in accounts (no users.json)")
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
