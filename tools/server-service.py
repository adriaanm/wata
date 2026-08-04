#!/usr/bin/env python3
"""Package and run wata-server as a macOS launchd LaunchDaemon.

Plan: docs/plans/0015-server-service-mac.md (`[SRV-PACKAGE]`). Layout:

  <root>/usr/local/wata/
    releases/<version>/wata-server     one dir per packaged build
    current -> releases/<version>      the running release
    bin/wata-server-run                wrapper the daemon execs
    etc/wata.env                       config as env lines (sourced by the wrapper)
    etc/users.json                     provisioned accounts (WATA_USERS)
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
import urllib.request
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare, sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
STAGE_DIR = WATA / ".service-stage"
LABEL = "net.wata.server"
DEFAULT_LISTEN = ":8008"

USERS_JSON = json.dumps([
    {"_comment": "REPLACE these before going live — see docs/design/wata-server.md#accounts"},
    {"user": "alice", "password": "testpass123", "displayname": "Alice"},
    {"user": "bob", "password": "testpass123", "displayname": "Bob"},
], indent=2) + "\n"


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


def do_install(layout: Layout, iroh: bool):
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
    write_if_absent(layout.etc / "users.json", USERS_JSON.encode())
    write_if_absent(layout.data / "FORMAT", b"1\n")

    print(f"server-service: installed {version} at {layout.prefix} (current -> {release_dir})")

    if layout.real:
        subprocess.run(["launchctl", "bootout", f"system/{LABEL}"], capture_output=True)
        r = subprocess.run(["launchctl", "bootstrap", "system", str(layout.plist)],
                            capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"server-service: launchctl bootstrap failed: {r.stderr.strip()}")
        print(f"server-service: {LABEL} bootstrapped")
    else:
        print("server-service: --root install — launchctl/newsyslog left untouched")


def cmd_install(args):
    do_install(Layout(args.root), args.iroh)


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
    proc = subprocess.Popen([str(layout.bin / "wata-server-run")],
                             env={**os.environ, "WATA_LISTEN": f":{port}"})
    try:
        if not poll_until_up(f"{base}/_matrix/client/versions", time.monotonic() + 15):
            proc.poll()
            return selftest_fail(f"server never answered /_matrix/client/versions "
                                  f"(exit code {proc.returncode})")
        print("server-service: GET /_matrix/client/versions -> 200")

        body = json.dumps({"identifier": {"type": "m.id.user", "user": "alice"},
                            "password": "testpass123"}).encode()
        req = urllib.request.Request(f"{base}/_matrix/client/v3/login", data=body, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            login = json.loads(resp.read())
        if not login.get("access_token") or login.get("user_id") != "@alice:localhost":
            return selftest_fail(f"login as alice (from etc/users.json) returned unexpected body: {login}")
        print(f"server-service: login as alice OK ({login['user_id']})")

        journal = layout.data / "journal.jsonl"
        if not journal.exists() or journal.stat().st_size == 0:
            return selftest_fail(f"{journal} missing or empty after a login")
        print(f"server-service: {journal} present, {journal.stat().st_size} bytes")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
    print(f"server-service: process exited (code {proc.returncode}) on SIGTERM")

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
