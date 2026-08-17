#!/usr/bin/env python3
"""Package and run wata-server as a macOS launchd LaunchDaemon.

Plan: docs/plans/0015-server-service-mac.md (`[SRV-PACKAGE]`). Layout:

  <root>/usr/local/wata/
    releases/<version>/wata-server     one dir per packaged build
    current -> releases/<version>      the running release
    bin/wata-server-run                wrapper the daemon execs
    etc/wata.env                       config as env lines (sourced by the wrapper)
    etc/users.json                     accounts (WATA_USERS) — NOT installed;
                                       the server writes it when the first
                                       admin is created at /admin
    data/                              WATA_DATA: journal.jsonl, media/, FORMAT
    log/wata-server.log                daemon stdout+stderr
  <root>/Library/LaunchDaemons/net.wata.server.plist
  <root>/etc/newsyslog.d/wata-server.conf

`--root <dir>` re-roots every absolute path above under `<dir>` instead of
`/`, so `install`/`uninstall`/`status`/`restart` can be exercised with no
sudo and no touch to the real system (that is what `selftest` does). Without
`--root`, `install`/`uninstall` require the real effective root (run them via
`sudo`) and additionally drive `launchctl`/`newsyslog`; this script never
calls `sudo` itself.

Subcommands: package [--iroh], install, uninstall [--purge], status,
restart, selftest, prune. Each accepts --root; see docs/design/wata-server.md
"Running as a service (macOS)" for the operational rundown.
"""

import argparse
import getpass
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare, sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
STAGE_DIR = WATA / ".service-stage"
LABEL = "net.wata.server"
DEFAULT_LISTEN = ":8008"
# The Bonjour name an install publishes: handsets' enrolment QRs default to
# http://wata.local:8008 (wata-fb's DEFAULT_ADMIN_URL), so the machine serving
# the admin page must answer that name. See ensure_mdns.
DEFAULT_MDNS = "wata"

# NOTE: no accounts file is written by an install. `WATA_USERS` names a path
# that does not exist yet, which puts the server in SETUP MODE: browse
# http://<server>:8008/admin and create the first (admin) account there, and
# the server writes the file itself, hashed. A shipped placeholder account
# would be a shipped credential — see docs/design/wata-server.md#accounts.


# ---- path re-rooting --------------------------------------------------------

def rootify(root: Path | None, abs_path: str) -> Path:
    """`abs_path` (e.g. "/usr/local/wata"), under `root` if one was given."""
    if root is None:
        return Path(abs_path)
    return root / Path(abs_path).relative_to("/")


class Layout:
    """Every path this tool writes, computed once against a (possibly test) root."""

    def __init__(self, root: Path | None):
        self.root = root
        self.real = root is None
        self.prefix = rootify(root, "/usr/local/wata")
        self.releases = self.prefix / "releases"
        self.current = self.prefix / "current"
        self.bin = self.prefix / "bin"
        self.etc = self.prefix / "etc"
        self.data = self.prefix / "data"
        self.log = self.prefix / "log"
        self.plist = rootify(root, "/Library/LaunchDaemons") / f"{LABEL}.plist"
        self.newsyslog = rootify(root, "/etc/newsyslog.d") / "wata-server.conf"


# ---- version ----------------------------------------------------------------

def version_string() -> str:
    day = date.today().strftime("%Y%m%d")
    sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=WATA,
                          capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=WATA,
                            capture_output=True, text=True, check=True).stdout.strip()
    return f"{day}-{sha}" + ("-dirty" if dirty else "")


# ---- package: build the server, stage a release dir (no sudo, no system paths) --

def stage_release(iroh: bool) -> tuple[str, Path]:
    prepare()
    env = build_env()
    version = version_string()
    stage = STAGE_DIR / version
    stage.mkdir(parents=True, exist_ok=True)

    print(f"server-service: sgo build (wata-server, version {version})…")
    r = subprocess.run([str(sgo_bin()), "build"], cwd=WATA / "wata-server", env=env,
                        capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        sys.exit("server-service: sgo build failed")

    emit = WATA / "wata-server" / ".sgo" / "wata-server"
    out = stage / "wata-server"
    cmd = ["go", "build", "-o", str(out), "."]
    if iroh:
        cmd[2:2] = ["-tags", "iroh"]
    print(f"server-service: go build{' -tags iroh' if iroh else ''}…")
    r = subprocess.run(cmd, cwd=emit, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        sys.exit("server-service: go build failed")
    return version, stage


def cmd_package(args):
    # Refuse to build as root: it would root-own the .sgo emit tree, the Go
    # build cache, and the toolchain clone. `just server-install` runs this
    # unprivileged and applies sudo to the install step alone.
    if os.geteuid() == 0:
        sys.exit("server-service: refusing to build as root — run `just server-install` "
                 "(or `just server-package`) without sudo; only the install step needs it")
    version, stage = stage_release(args.iroh)
    print(f"server-service: staged {stage / 'wata-server'} (version {version})")


# ---- generated file contents -------------------------------------------------

def plist_bytes(layout: Layout, user: str) -> bytes:
    import plistlib
    doc = {
        "Label": LABEL,
        "UserName": user,
        "KeepAlive": {"SuccessfulExit": False},
        "RunAtLoad": True,
        "ThrottleInterval": 10,
        "StandardOutPath": str(layout.log / "wata-server.log"),
        "StandardErrorPath": str(layout.log / "wata-server.log"),
        "ProgramArguments": [str(layout.bin / "wata-server-run")],
    }
    return plistlib.dumps(doc)


def wrapper_script(layout: Layout) -> str:
    # Kept under 10 lines (repo convention: bash is fine here, Python is for
    # anything longer). `WATA_LISTEN` is not part of the shipped wata.env —
    # unset, the binary's own default (":8008") applies; selftest exports it
    # to grab a free port without touching the config file.
    return f"""#!/bin/bash
set -a
source "{layout.etc / 'wata.env'}"
set +a
exec "{layout.current / 'wata-server'}" "${{WATA_LISTEN:-{DEFAULT_LISTEN}}}"
"""


def env_file(layout: Layout) -> str:
    return f"""# wata-server config, sourced by bin/wata-server-run. Edit, then
# `just server-restart` (or `sudo launchctl kickstart -k system/{LABEL}`).
WATA_USERS={layout.etc / 'users.json'}
WATA_LOG={layout.data / 'journal.jsonl'}
WATA_DATA={layout.data}
# WATA_IROH_CONFIG={layout.etc / 'iroh.json'}
# WATA_MEDIA_RETAIN_DAYS=7
"""


def newsyslog_conf(layout: Layout) -> str:
    # newsyslog.conf columns: logfile mode count size(KB) when flags.
    # 5 MB = 5120 KB; Z = gzip-compress rotated files.
    return (
        "# logfilename                                     mode  count  size(KB)  when  flags\n"
        f"{layout.log / 'wata-server.log'}                          644   5      5120      *     Z\n"
    )


# ---- install ------------------------------------------------------------------

def require_real_root(action: str):
    if os.geteuid() != 0:
        sys.exit(f"server-service: {action} needs root — run `sudo tools/server-service.py {action}` "
                  "(or pass --root <dir> to test against a fake root; this tool never calls sudo itself)")


def installing_user() -> str:
    return os.environ.get("SUDO_USER") or getpass.getuser()


def write_if_absent(path: Path, content_bytes: bytes) -> bool:
    """Write `path` unless it already exists. Returns True if written."""
    if path.exists():
        print(f"server-service: keeping existing {path}")
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content_bytes)
    return True


def latest_stage() -> tuple[str, Path]:
    """Newest staged release by mtime. Real install consumes a prior
    `package` run instead of building: building under sudo would root-own
    the .sgo emit tree, the Go build cache, and the toolchain clone."""
    stages = sorted(STAGE_DIR.glob("*/wata-server"), key=lambda p: p.stat().st_mtime)
    if not stages:
        sys.exit("server-service: no staged release — run `just server-package` (unprivileged) first")
    stage = stages[-1].parent
    return stage.name, stage


def local_hostname() -> str:
    r = subprocess.run(["scutil", "--get", "LocalHostName"], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def ensure_mdns(name: str):
    """Publish the server's Bonjour/mDNS name — `<name>.local` — by setting the
    machine's LocalHostName. mDNSResponder then answers the name with whatever
    address the machine currently holds, which is what makes the handsets'
    default enrolment URL (http://wata.local:8008) DHCP-proof where a baked IP
    goes stale. `--mdns-name` overrides the name (handsets then need
    WATA_ADMIN_URL / the iroh config's adminUrl pinned to match); `--no-mdns`
    leaves the machine's name alone entirely."""
    current = local_hostname()
    if current == name:
        print(f"server-service: Bonjour name already published — {name}.local")
        return
    r = subprocess.run(["scutil", "--set", "LocalHostName", name],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"server-service: scutil --set LocalHostName {name} failed: {r.stderr.strip()}")
    print(f"server-service: Bonjour name published — {name}.local "
          f"(LocalHostName was {current or 'unset'}); handsets' enrolment QRs "
          f"resolve http://{name}.local:{DEFAULT_LISTEN.lstrip(':')} here")


def do_install(layout: Layout, iroh: bool, mdns_name: str | None = None):
    if layout.real:
        require_real_root("install")
        if iroh:
            print("server-service: note — --iroh is a package-time flag; "
                  "installing whatever the newest staged release was built as")
        version, stage = latest_stage()
        print(f"server-service: installing staged release {version}")
    else:
        version, stage = stage_release(iroh)

    for d in (layout.releases, layout.bin, layout.etc, layout.data, layout.log):
        d.mkdir(parents=True, exist_ok=True)

    release_dir = layout.releases / version
    if release_dir.exists():
        shutil.rmtree(release_dir)
    release_dir.mkdir(parents=True)
    shutil.copy2(stage / "wata-server", release_dir / "wata-server")
    os.chmod(release_dir / "wata-server", 0o755)

    if layout.current.is_symlink() or layout.current.exists():
        layout.current.unlink()
    layout.current.symlink_to(release_dir)

    # Regenerated every install (they're infrastructure, not user config):
    wrapper = layout.bin / "wata-server-run"
    wrapper.write_text(wrapper_script(layout))
    os.chmod(wrapper, 0o755)
    layout.plist.parent.mkdir(parents=True, exist_ok=True)
    layout.plist.write_bytes(plist_bytes(layout, installing_user()))
    layout.newsyslog.parent.mkdir(parents=True, exist_ok=True)
    layout.newsyslog.write_text(newsyslog_conf(layout))

    # etc/ and data/ are never overwritten once present — they hold the
    # human's config and the journal.
    write_if_absent(layout.etc / "wata.env", env_file(layout).encode())
    write_if_absent(layout.data / "FORMAT", b"1\n")

    # The daemon runs as the INSTALLING USER (plist UserName), but a sudo
    # install creates everything root-owned — which leaves launchd unable to
    # even open the StandardOutPath log as that user, so the job never comes
    # up. Hand the whole layout to the user; root keeps the plist and the
    # newsyslog conf (system files, and newsyslog rotates as root anyway).
    if layout.real:
        chown_layout(layout.prefix, installing_user())

    print(f"server-service: installed {version} at {layout.prefix} (current -> {release_dir})")
    if not (layout.etc / "users.json").exists():
        print("server-service: no accounts yet — browse http://<this-host>:8008/admin "
              "and create the first (admin) account")

    if layout.real:
        subprocess.run(["launchctl", "bootout", f"system/{LABEL}"], capture_output=True)
        r = subprocess.run(["launchctl", "bootstrap", "system", str(layout.plist)],
                            capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"server-service: launchctl bootstrap failed: {r.stderr.strip()}")
        print(f"server-service: {LABEL} bootstrapped")
        if mdns_name:
            ensure_mdns(mdns_name)
        else:
            print("server-service: --no-mdns — Bonjour name left alone; handsets need "
                  "WATA_ADMIN_URL or the iroh config's adminUrl pinned")
    else:
        print("server-service: --root install — launchctl/newsyslog/Bonjour left untouched")


def chown_layout(prefix: Path, user: str):
    import pwd
    pw = pwd.getpwnam(user)
    os.chown(prefix, pw.pw_uid, pw.pw_gid, follow_symlinks=False)
    for root, dirs, files in os.walk(prefix):
        for name in dirs + files:
            os.chown(os.path.join(root, name), pw.pw_uid, pw.pw_gid,
                     follow_symlinks=False)


def cmd_install(args):
    do_install(Layout(args.root), args.iroh,
               None if args.no_mdns else args.mdns_name)


# ---- uninstall ------------------------------------------------------------------

def cmd_uninstall(args):
    layout = Layout(args.root)
    if layout.real:
        require_real_root("uninstall")
        subprocess.run(["launchctl", "bootout", f"system/{LABEL}"], capture_output=True)
    if layout.plist.exists():
        layout.plist.unlink()
    if layout.newsyslog.exists():
        layout.newsyslog.unlink()
    print(f"server-service: removed {layout.plist} and {layout.newsyslog}")

    if not args.purge:
        return
    if not args.yes:
        reply = input(f"purge {layout.prefix} (all releases AND data)? type 'yes' to confirm: ")
        if reply.strip() != "yes":
            print("server-service: purge cancelled")
            return
    shutil.rmtree(layout.prefix, ignore_errors=True)
    print(f"server-service: purged {layout.prefix}")


# ---- status / restart / prune ----------------------------------------------------

def cmd_status(args):
    layout = Layout(args.root)
    print(f"prefix     {layout.prefix}  {'present' if layout.prefix.exists() else 'ABSENT'}")
    if not layout.prefix.exists():
        return 1
    current = layout.current.resolve() if layout.current.is_symlink() else None
    print(f"current    {current or 'unset'}")
    releases = sorted(p.name for p in layout.releases.glob("*")) if layout.releases.exists() else []
    print(f"releases   {', '.join(releases) or '(none)'}")
    journal = layout.data / "journal.jsonl"
    print(f"journal    {journal} ({journal.stat().st_size} bytes)" if journal.exists()
          else "journal    (none yet)")
    print(f"plist      {layout.plist}  {'present' if layout.plist.exists() else 'ABSENT'}")
    if layout.real:
        r = subprocess.run(["launchctl", "print", f"system/{LABEL}"], capture_output=True, text=True)
        print(f"launchd    {'loaded' if r.returncode == 0 else 'not loaded'}")
        name = local_hostname()
        note = "" if name == DEFAULT_MDNS else \
            "  (handsets' default QR URL is http://wata.local:8008 — pin WATA_ADMIN_URL if this differs)"
        print(f"bonjour    {name + '.local' if name else 'unset'}{note}")
    else:
        print("launchd    n/a (--root)")
    return 0


def cmd_restart(args):
    layout = Layout(args.root)
    if layout.real:
        require_real_root("restart")
        r = subprocess.run(["launchctl", "kickstart", "-k", f"system/{LABEL}"], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"server-service: launchctl kickstart failed: {r.stderr.strip()}")
        print(f"server-service: {LABEL} restarted")
    else:
        if not (layout.bin / "wata-server-run").exists():
            sys.exit(f"server-service: nothing installed at {layout.prefix}")
        print(f"server-service: --root restart is a no-op (no launchd here); "
              f"{layout.bin / 'wata-server-run'} is in place")


def cmd_prune(args):
    layout = Layout(args.root)
    # Compare by directory NAME, not resolved path: on macOS a tempdir root
    # resolves through /private, so a resolved `current` vs. an unresolved
    # release dir never string-compares equal even when they are the same
    # release — that bug once made prune delete the current release too.
    current_name = os.readlink(layout.current) if layout.current.is_symlink() else None
    current_name = Path(current_name).name if current_name else None
    if not layout.releases.exists():
        print("server-service: nothing to prune")
        return
    removed = []
    for rel in sorted(layout.releases.glob("*")):
        if rel.name != current_name:
            shutil.rmtree(rel)
            removed.append(rel.name)
    print(f"server-service: pruned {', '.join(removed) or '(nothing to prune)'}; kept {current_name}")


# ---- selftest -----------------------------------------------------------------

def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def poll_until_up(url: str, deadline: float):
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                if r.status == 200:
                    return True
        except OSError:
            pass
        time.sleep(0.1)
    return False


def selftest_fail(msg: str) -> int:
    print(f"server-service: {msg}")
    print("SRV-PACKAGE SELFTEST FAIL")
    return 1


def http(base: str, method: str, path: str, body=None):
    """-> (status, parsed body). An error status is a value here, not a raise:
    the setup legs assert on 409 as much as on 200."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


SETUP_USER = "parent"
SETUP_PASS = "selftest-pw-1"


def run_server(layout: Layout, port: int):
    return subprocess.Popen([str(layout.bin / "wata-server-run")],
                            env={**os.environ, "WATA_LISTEN": f":{port}"})


def stop(proc):
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def selftest_setup(layout: Layout, base: str):
    """The first-run flow a fresh install boots into (plan 0021 C): no accounts
    file, so the server is in setup mode; the unauthenticated setup endpoint
    creates the first admin, closes the window, and writes the file hashed."""
    users = layout.etc / "users.json"
    if users.exists():
        return f"a fresh install wrote {users} — it must not seed accounts"

    status, mode = http(base, "GET", "/_wata/v1/admin/mode")
    if status != 200 or mode.get("setup") is not True:
        return f"a fresh install is not in setup mode (saw {status} {mode})"
    status, _ = http(base, "GET", "/_wata/v1/admin/status")
    if status != 503:
        return f"an admin route answered {status} during setup, expected 503"
    print("server-service: setup mode reported, other admin routes 503")

    status, body = http(base, "POST", "/_wata/v1/admin/setup", {
        "user": SETUP_USER, "password": SETUP_PASS, "displayname": "Parent"})
    if status != 200 or body.get("user_id") != f"@{SETUP_USER}:localhost":
        return f"setup returned {status} {body}"
    status, _ = http(base, "POST", "/_wata/v1/admin/setup", {
        "user": "second", "password": "x", "displayname": "Second"})
    if status != 409:
        return f"a second setup answered {status}, expected 409 (the window closed)"
    _, mode = http(base, "GET", "/_wata/v1/admin/mode")
    if mode.get("setup") is not False:
        return f"setup mode did not close (mode {mode})"
    print("server-service: first admin created, the setup window is closed")

    if not users.exists():
        return f"setup did not write {users}"
    entries = json.loads(users.read_text())
    if [e["user"] for e in entries] != [SETUP_USER]:
        return f"{users} holds unexpected accounts: {entries}"
    entry = entries[0]
    if not entry.get("hash", "").startswith("pbkdf2-sha256$") or "password" in entry:
        return f"the account is not stored hashed: {entry}"
    if SETUP_PASS in users.read_text():
        return f"the password appears in {users} in plaintext"
    if entry.get("admin") is not True or entry.get("displayname") != "Parent":
        return f"the first account is not the admin, or lost its display name: {entry}"
    if users.stat().st_mode & 0o777 != 0o600:
        return f"{users} is mode {users.stat().st_mode & 0o777:o}, expected 600"
    print(f"server-service: {users} holds one hashed admin account, mode 600")
    return None


def selftest_login(base: str) -> tuple[str | None, str | None]:
    """-> (token, failure message)."""
    status, body = http(base, "POST", "/_matrix/client/v3/login", {
        "identifier": {"type": "m.id.user", "user": SETUP_USER}, "password": SETUP_PASS})
    if status != 200 or not body.get("access_token") or \
            body.get("user_id") != f"@{SETUP_USER}:localhost":
        return None, f"login as {SETUP_USER} returned {status} {body}"
    return body["access_token"], None


def cmd_selftest(args):
    root = Path(tempfile.mkdtemp(prefix="wata-service-selftest."))
    layout = Layout(root)
    print(f"server-service: selftest root {root}")

    do_install(layout, iroh=False)

    r = subprocess.run(["plutil", "-lint", str(layout.plist)], capture_output=True, text=True)
    if r.returncode != 0:
        return selftest_fail(f"plutil -lint failed:\n{r.stdout}{r.stderr}")
    print(f"server-service: plutil -lint OK ({layout.plist})")

    port = free_port()
    base = f"http://127.0.0.1:{port}"
    proc = run_server(layout, port)
    try:
        if not poll_until_up(f"{base}/_matrix/client/versions", time.monotonic() + 15):
            proc.poll()
            return selftest_fail(f"server never answered /_matrix/client/versions "
                                  f"(exit code {proc.returncode})")
        print("server-service: GET /_matrix/client/versions -> 200")

        failure = selftest_setup(layout, base)
        if failure:
            return selftest_fail(failure)

        token, failure = selftest_login(base)
        if failure:
            return selftest_fail(failure)
        print(f"server-service: login as {SETUP_USER} OK (the account setup created)")
        status, _ = http(base, "GET", "/_wata/v1/admin/status")
        if status != 401:
            return selftest_fail(f"the admin surface answered {status} unauthenticated "
                                  "after setup, expected 401")

        journal = layout.data / "journal.jsonl"
        if not journal.exists() or journal.stat().st_size == 0:
            return selftest_fail(f"{journal} missing or empty after a login")
        print(f"server-service: {journal} present, {journal.stat().st_size} bytes")
    finally:
        stop(proc)
    print(f"server-service: process exited (code {proc.returncode}) on SIGTERM")

    # The restart: the account the setup flow created is the FILE's now, so a
    # fresh process reads it back and is no longer in setup mode.
    proc = run_server(layout, port)
    try:
        if not poll_until_up(f"{base}/_matrix/client/versions", time.monotonic() + 15):
            return selftest_fail("server never came back up after a restart")
        _, mode = http(base, "GET", "/_wata/v1/admin/mode")
        if mode.get("setup") is not False:
            return selftest_fail(f"the restarted server went back into setup mode ({mode})")
        token, failure = selftest_login(base)
        if failure:
            return selftest_fail(f"after a restart, {failure}")
        status, body = http(base, "GET", "/_wata/v1/admin/status")
        if status != 401:
            return selftest_fail(f"admin status answered {status} unauthenticated, expected 401")
        req = urllib.request.Request(f"{base}/_wata/v1/admin/status")
        req.add_header("Authorization", "Bearer " + token)
        with urllib.request.urlopen(req, timeout=5) as resp:
            status_body = json.loads(resp.read())
        if [u["user"] for u in status_body.get("users", [])] != [SETUP_USER]:
            return selftest_fail(f"the account did not survive the restart: {status_body.get('users')}")
        print("server-service: the account survives a restart and is still the admin")
    finally:
        stop(proc)

    print("SRV-PACKAGE SELFTEST PASS")
    return 0


# ---- CLI ------------------------------------------------------------------------

def add_root(sp):
    sp.add_argument("--root", type=Path, default=None,
                     help="re-root every absolute path under this dir (testing; no sudo, no launchctl)")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("package", help="build the server, stage a release (no sudo)")
    sp.add_argument("--iroh", action="store_true", help="build with -tags iroh")

    sp = sub.add_parser("install",
                        help="install the newest staged release + launchd daemon (run package first)")
    sp.add_argument("--iroh", action="store_true")
    sp.add_argument("--mdns-name", default=DEFAULT_MDNS,
                    help=f"Bonjour name to publish via scutil LocalHostName (default: {DEFAULT_MDNS}; "
                          "handsets' enrolment QRs default to http://wata.local:8008)")
    sp.add_argument("--no-mdns", action="store_true", help="leave the machine's Bonjour name alone")
    add_root(sp)

    sp = sub.add_parser("uninstall", help="bootout + remove the daemon files")
    sp.add_argument("--purge", action="store_true", help="also remove /usr/local/wata (data included)")
    sp.add_argument("--yes", action="store_true", help="skip the purge confirmation prompt")
    add_root(sp)

    sp = sub.add_parser("status", help="report layout/daemon/journal state")
    add_root(sp)

    sp = sub.add_parser("restart", help="launchctl kickstart the daemon")
    add_root(sp)

    sp = sub.add_parser("prune", help="delete every release except the current one")
    add_root(sp)

    sub.add_parser("selftest", help="package+install+boot+serve+journal, no sudo, in a temp root")

    args = p.parse_args()
    fn = {"package": cmd_package, "install": cmd_install, "uninstall": cmd_uninstall,
          "status": cmd_status, "restart": cmd_restart, "prune": cmd_prune,
          "selftest": cmd_selftest}[args.cmd]
    sys.exit(fn(args) or 0)


if __name__ == "__main__":
    main()
