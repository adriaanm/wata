#!/usr/bin/env python3
"""Does wata-mac remember who you are? (plan 0036)

    tools/mac-creds-smoke.py

Three headless runs against one fresh server, in order:

  1. WITH a password in the environment — the first run, as today. It must
     log in, and leave a token in the Keychain and an identity in the
     config file.
  2. With NOTHING in the environment — no user, no password, no
     homeserver. It must still reach `ready @alice:localhost`, off the
     stored identity and the stored token alone. This is the whole point.
  3. With the stored token no longer valid (the server is restarted under
     it, which is what an expiry or a server-side invalidation looks like
     from here). It must still get there, through the stored password —
     the unattended recovery path the windowed app needs, since there is
     nowhere in that window to type a password.

Then the config file is checked for the thing that must NOT be in it: the
access token. A store that keeps the secret in the plain file would pass
every run above and still be wrong.

Then the same question from the other side (plan 0037): the `Wata.app`
bundle builds, has the Info.plist keys macOS requires, verifies its
signature, and its executable starts from a bare environment.

Touches the developer's login keychain, under service `wata` with a
scratch homeserver in the account name, and cleans up after itself.
macOS only, not in ci.
"""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("ms", os.path.join(WATA, "tools", "mac-smoke.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)


def run_once(mac_bin, env, tmp, creds, label):
    """one headless session; returns (ok, lines). `creds` False = a bare
    environment, which is the case that matters."""
    senv = dict(env, WATA_MAC_HEADLESS="1", WATA_MAC_SCALE="1",
                WATA_MAC_AUDIO="fake",
                WATA_MAC_CONFIG=os.path.join(tmp, "config.json"),
                WATA_MAC_OUTBOX=os.path.join(tmp, "outbox"),
                WATA_MAC_CONNECT_MS="20000")
    for k in ("WATA_MAC_HS", "WATA_MAC_USER", "WATA_MAC_PASS"):
        senv.pop(k, None)
    if creds:
        senv.update(WATA_MAC_HS=ms.BASE, WATA_MAC_USER="alice",
                    WATA_MAC_PASS=ms.PASSWORD)
    p = subprocess.Popen([mac_bin], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, env=senv)
    try:
        out, _ = p.communicate("wait 500\nquit\n", timeout=90)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    lines = out.splitlines()
    ok = any(l == "ready @alice:localhost" for l in lines)
    print(f"  [{label}] ready={ok}")
    if not ok:
        print("    " + "\n    ".join(lines[:12]))
    return ok, lines


def main():
    sgo, mac_bin, tui_bin, server_bin, env = ms.build_env()
    ms.build(sgo, env)
    tmp = tempfile.mkdtemp(prefix="mac-creds-smoke.")
    proc, log = ms.start_server(server_bin, os.path.join(tmp, "server.log"), env)
    if proc is None:
        sys.exit("mac-creds-smoke: server never became ready")

    failed = []
    cfg_path = os.path.join(tmp, "config.json")
    try:
        ok, _ = run_once(mac_bin, env, tmp, True, "1: password in the environment")
        if not ok:
            failed.append("run 1 (password in env) never reached ready")

        if not os.path.exists(cfg_path):
            failed.append("run 1 wrote no config file")
        else:
            stored = json.load(open(cfg_path))
            if stored.get("username") != "alice":
                failed.append(f"config file has no username: {stored!r}")
            # THE assertion this store exists for.
            if "access_token" in stored:
                failed.append(f"the access token is in the plain config file: {stored!r}")

        ok, _ = run_once(mac_bin, env, tmp, False, "2: nothing in the environment")
        if not ok:
            failed.append("run 2 (bare environment) never reached ready — "
                          "the stored identity/token did not carry it")

        # 3. The stored token stops working — which is what a server restart,
        #    a token expiry or a server-side invalidation all look like from
        #    here. Restarting the (in-memory) server invalidates every token
        #    while leaving the Keychain and the config file untouched, so this
        #    is the real shape of the case and needs no keychain surgery.
        #    (Rewriting the item with `security` would take it out of
        #    wata-mac's ACL and make the read prompt — an unattended smoke
        #    cannot answer a dialog.)
        ms.stop_server(proc, log)
        proc, log = ms.start_server(server_bin, os.path.join(tmp, "server2.log"), env)
        if proc is None:
            failed.append("the server did not come back for run 3")
        else:
            ok, lines = run_once(mac_bin, env, tmp, False, "3: stored token rejected")
            if not ok:
                failed.append("run 3 (rejected token) never reached ready — the "
                              "stored password did not recover it")
    finally:
        ms.stop_server(proc, log)
        for acct in ("alice@" + ms.BASE, "password:alice@" + ms.BASE):
            subprocess.run(["security", "delete-generic-password", "-s", "wata",
                            "-a", acct], capture_output=True)
        if os.environ.get("MAC_CREDS_KEEP"):
            print("scratch kept at " + tmp)
        else:
            shutil.rmtree(tmp, ignore_errors=True)

    # ---- the bundle (plan 0037, slice 1) ------------------------------------
    # Not a credential case, but the same question from the other side: can
    # this thing be launched by someone who has no shell? Assert the shape
    # macOS requires, that the signature verifies, and that the executable
    # inside actually runs — a bundle that is merely well-formed is not one.
    app = os.path.join(WATA, "wata-mac", ".sgo", "Wata.app")
    r = subprocess.run([sys.executable, os.path.join(WATA, "tools", "mac-app.py")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        failed.append(f"mac-app.py failed: {r.stdout}{r.stderr}")
    else:
        plist = os.path.join(app, "Contents", "Info.plist")
        exe = os.path.join(app, "Contents", "MacOS", "Wata")
        if not os.path.exists(exe):
            failed.append("the bundle has no Contents/MacOS/Wata")
        import plistlib
        info = plistlib.load(open(plist, "rb")) if os.path.exists(plist) else {}
        for key in ("CFBundleIdentifier", "CFBundleExecutable",
                    "NSMicrophoneUsageDescription"):
            if not info.get(key):
                failed.append(f"Info.plist is missing {key}")
        if subprocess.run(["codesign", "--verify", "--deep", "--strict", app],
                          capture_output=True).returncode != 0:
            failed.append("the bundle's signature does not verify")
        # launched with NOTHING in the environment but HOME and PATH, it must
        # start and say what it needs — not crash, and not hang.
        bare = {"HOME": os.environ["HOME"], "PATH": "/usr/bin:/bin"}
        try:
            out = subprocess.run([exe], env=bare, stdin=subprocess.DEVNULL,
                                 capture_output=True, text=True, timeout=60).stdout
        except subprocess.TimeoutExpired:
            out = ""
            failed.append("the bundled app hung on a bare environment")
        if out and "WATA_MAC_USER" not in out and "ready" not in out:
            failed.append(f"the bundled app said something unexpected: {out[:200]!r}")
        print(f"  [4: the bundle] signed, {len(info)} Info.plist keys, runs bare")

    if failed:
        for f in failed:
            print("FAIL: " + f)
        sys.exit(1)
    print("PASS mac-creds-smoke")


main()
