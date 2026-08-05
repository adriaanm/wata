#!/usr/bin/env python3
"""Bootstrap and manage wata's pinned sgola toolchain.

Wata is written in Sgola (restricted Scala 3 compiled to Go source), so
building it needs the sgola compiler: the `sgo` driver, the scalac plugin jar,
the frontend daemon jar, and the prelude. Those come from a clone of
github.com/adriaanm/sgola pinned to the commit in tools/toolchain-pin.txt and
checked out under .toolchain/sgola (gitignored).

Subcommands:
  sync    clone/fetch and check out the pinned commit, then build the toolchain
  build   (re)build the toolchain artifacts in the existing clone
  status  report what is present and whether it matches the pin
  env     print shell `export` lines for a manual build environment
  pin     rewrite tools/toolchain-pin.txt to a new commit

Everyday use is `tools/sgo <args>`, which applies this environment and execs
the pinned driver.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

WATA = Path(__file__).resolve().parent.parent
PIN_FILE = WATA / "tools" / "toolchain-pin.txt"
PINNED_HOME = WATA / ".toolchain" / "sgola"


def resolve_home():
    """The sgola toolchain home to build against.

    A preset $SGOLA_HOME wins. That is what lets sgola drive wata as a proving
    consumer: sgola points SGOLA_HOME at its own checkout and runs wata's
    scripts, exercising an in-development compiler against real code. With no
    override we use the pinned clone, which is what wata's own development
    wants — a compiler that only moves when tools/toolchain-pin.txt moves.
    """
    env_home = os.environ.get("SGOLA_HOME")
    return Path(env_home).resolve() if env_home else PINNED_HOME


# On dep resolution: wata's Sgola deps need no module proxy and no populated
# module cache. `sgo` resolves an in-link library (the `sgo.deps` names) by
# searching the declaring module's parent dir first, then the toolchain home —
# so `wataclient` is found as a sibling here in this repo, and `json` as the
# author module in the sgola tree. Both compile from those sources directly.
#
# The go.mod `require` lines for them exist to declare the dependency and to
# drive sgola's own source-in-link check, which recompiles a dep from a
# published artifact in pkg/mod to prove the emitted Go is byte-identical
# either way. That check is sgola's; it sets up its own hermetic proxy. Nothing
# in wata's own build needs it.


def die(msg):
    print(f"toolchain: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd, cwd=None, env=None, check=True, quiet=False):
    r = subprocess.run(
        cmd, cwd=cwd, env=env,
        stdout=subprocess.PIPE if quiet else None,
        stderr=subprocess.STDOUT if quiet else None,
        text=True,
    )
    if check and r.returncode != 0:
        if quiet and r.stdout:
            print(r.stdout, file=sys.stderr)
        die(f"command failed ({r.returncode}): {' '.join(str(c) for c in cmd)}")
    return r


def read_pin():
    """Parse tools/toolchain-pin.txt -> (repo_url, commit_sha)."""
    if not PIN_FILE.exists():
        die(f"missing {PIN_FILE}")
    fields = {}
    for line in PIN_FILE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, val = line.partition(" ")
        fields[key.strip()] = val.strip()
    for key in ("repo", "commit"):
        if key not in fields:
            die(f"{PIN_FILE} has no `{key}` line")
    return fields["repo"], fields["commit"]


def go_pin(home):
    """The Go toolchain version sgola pins itself to.

    Emitted Go and the committed baselines are pinned-toolchain products
    (gofmt output is not stable across Go releases), so wata builds under the
    same version. Read it out of the toolchain rather than duplicating it here.
    """
    ci = home / "tools" / "ci.sh"
    if ci.exists():
        m = re.search(r'^SGOLA_GO_PIN="?([^"\n]+)"?', ci.read_text(), re.M)
        if m:
            return m.group(1)
    return None


def build_env(home=None):
    """The environment a wata `sgo` invocation needs."""
    home = home or resolve_home()
    env = dict(os.environ)
    env["SGOLA_HOME"] = str(home)
    # The emitted-Go pin. Without it a newer local `go` reformats emitted
    # sources and every byte-comparison against a committed tree drifts.
    pin = go_pin(home)
    if pin:
        env["GOTOOLCHAIN"] = pin
    # sgola's own go.work must not capture wata's modules.
    env["GOWORK"] = "off"
    # EXTERNAL GO DEPS (plan 0014, rsc.io/qr). `sgo`'s go.mod stage writes a
    # require+replace per `godep` and nothing else: the emitted app module gets
    # no go.sum and no line for a godep's own upstream requirements, so a
    # fetched transitive dep fails the build with "missing go.sum entry".
    # `-mod=mod` lets the go build stage add both to the GENERATED module,
    # which is the right place for them — the authoritative go.sum is the one
    # committed in the go-pkgs module that declares the require.
    # Workaround for sgola ticket GOMOD-TRANSITIVE-SUM; drop it when the go.mod
    # stage propagates its godeps' requirements.
    env["GOFLAGS"] = "-mod=mod"
    return env


def sgo_bin(home=None):
    return (home or resolve_home()) / "sgo" / "sgo"


def prepare(home=None):
    """Make `home` usable for a wata build. Idempotent and cheap once warm."""
    home = home or resolve_home()
    if not home.exists():
        die(f"no sgola toolchain at {home}"
            + ("" if os.environ.get("SGOLA_HOME") else " — run `tools/toolchain.py sync`"))
    if not sgo_bin(home).exists():
        run(["go", "build", "-o", "sgo", "."], cwd=home / "sgo", env=build_env(home), quiet=True)
    return home


def check_prereqs():
    missing = []
    for tool, why in (
        ("go", "builds the sgo driver and the emitted Go"),
        ("java", "runs the compiler frontend daemon"),
        ("sbt", "builds the sgola scalac plugin jar"),
        ("git", "manages the pinned clone"),
    ):
        if shutil.which(tool) is None:
            missing.append(f"  {tool} — {why}")
    if missing:
        die("missing prerequisites:\n" + "\n".join(missing))
    # The frontend resolves the pinned Scala 3 compiler jars straight out of
    # the Coursier cache. `sbt compile` populates it if it is cold.
    cache = os.environ.get("COURSIER_CACHE")
    if not cache:
        cache = (
            Path.home() / "Library/Caches/Coursier/v1"
            if sys.platform == "darwin"
            else Path.home() / ".cache/coursier/v1"
        )
    if not Path(cache).exists():
        print(f"toolchain: note — Coursier cache not found at {cache}; "
              "the first build will populate it via sbt (slow but fine).")


def cmd_sync(_args):
    """Clone/checkout the pin. Always the pinned clone — never a foreign home."""
    if os.environ.get("SGOLA_HOME"):
        die("SGOLA_HOME is set; `sync` only ever manages the pinned clone at "
            f"{PINNED_HOME}. Unset SGOLA_HOME, or use `prepare` to make the "
            "toolchain you pointed at usable.")
    check_prereqs()
    repo, commit = read_pin()
    if not (PINNED_HOME / ".git").exists():
        PINNED_HOME.parent.mkdir(parents=True, exist_ok=True)
        print(f"toolchain: cloning {repo} -> {PINNED_HOME}")
        run(["git", "clone", "--no-checkout", repo, str(PINNED_HOME)])
    have = run(["git", "rev-parse", "HEAD"], cwd=PINNED_HOME, check=False, quiet=True).stdout.strip()
    if not have.startswith(commit):
        print(f"toolchain: checking out pinned commit {commit[:12]}")
        if run(["git", "cat-file", "-e", commit + "^{commit}"], cwd=PINNED_HOME,
               check=False, quiet=True).returncode != 0:
            run(["git", "fetch", "origin"], cwd=PINNED_HOME)
        run(["git", "checkout", "-q", "--detach", commit], cwd=PINNED_HOME)
        # The clone's build products (plugin jar, frontend jar, minlib early
        # jars) are untracked, so they survive the checkout and would be
        # silently served for the WRONG commit — cmd_build's exists-checks
        # can't tell. Drop them so the build below is from the pinned sources.
        for jar in (PINNED_HOME / "plugin" / "target").glob("scala-*/sgola-plugin_*.jar"):
            jar.unlink()
        fe = PINNED_HOME / "tools" / "frontend" / "frontend.jar"
        if fe.exists():
            fe.unlink()
        for d in (PINNED_HOME / "minlib" / "target").glob("scala-*/early"):
            shutil.rmtree(d, ignore_errors=True)
    cmd_build(_args)


def cmd_build(_args):
    home = resolve_home()
    if not home.exists():
        die(f"no toolchain at {home} — run `tools/toolchain.py sync` first")
    env = build_env(home)

    print(f"toolchain: building the sgo driver ({home})")
    run(["go", "build", "-o", "sgo", "."], cwd=home / "sgo", env=env)

    if not sorted((home / "plugin" / "target").glob("scala-*/sgola-plugin_*.jar")):
        print("toolchain: building the scalac plugin jar (sbt; several minutes cold)")
        run(["sbt", "plugin/Compile/packageBin"], cwd=home, env=env)

    if not (home / "tools" / "frontend" / "frontend.jar").exists():
        print("toolchain: building the frontend daemon jar")
        run(["bash", "tools/frontend/build.sh"], cwd=home, env=env)

    # A resident frontend daemon from an earlier build keeps serving its old
    # classes (empty minlib early jars, phantom diagnostics from the wrong
    # commit's checks). Restart it so the daemon always matches the jars.
    run([str(home / "sgo" / "sgo"), "frontend", "restart"], cwd=home, env=env, check=False)

    print(f"toolchain: ready ({home})")


def cmd_prepare(_args):
    print(f"toolchain: {prepare()} ready")


def cmd_status(_args):
    home = resolve_home()
    repo, commit = read_pin()
    print(f"pin        {commit[:12]}  ({repo})")
    if home != PINNED_HOME:
        print(f"home       {home}  (SGOLA_HOME override — pin not enforced)")
    if not home.exists():
        print(f"clone      ABSENT at {home}"
              + ("" if home != PINNED_HOME else " — run `tools/toolchain.py sync`"))
        return 1
    have = run(["git", "rev-parse", "HEAD"], cwd=home, check=False, quiet=True).stdout.strip()
    if home == PINNED_HOME:
        # the pin may be an abbreviated sha; HEAD is always full
        print(f"clone      {have[:12]}  {'MATCHES pin' if have.startswith(commit) else 'DRIFTED from pin'}")
    else:
        print(f"checkout   {have[:12]}")
    plugin = sorted((home / 'plugin' / 'target').glob('scala-*/sgola-plugin_*.jar'))
    for label, path in (
        ("sgo driver", sgo_bin(home)),
        ("plugin jar", plugin[0] if plugin else home / 'plugin/target/MISSING'),
        ("frontend  ", home / "tools" / "frontend" / "frontend.jar"),
    ):
        print(f"{label} {'present' if Path(path).exists() else 'ABSENT'}")
    print(f"go pin     {go_pin(home) or 'unknown'}")
    return 0 if (home != PINNED_HOME or have.startswith(commit)) else 1


def cmd_env(_args):
    base = dict(os.environ)
    for k, v in build_env().items():
        if base.get(k) != v:
            print(f"export {k}={v!r}" if " " in v else f"export {k}={v}")


def cmd_pin(args):
    repo, old = read_pin()
    text = PIN_FILE.read_text().replace(f"commit {old}", f"commit {args.commit}")
    PIN_FILE.write_text(text)
    print(f"toolchain: pin {old[:12]} -> {args.commit[:12]} (now run `tools/toolchain.py sync`)")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sync", help="clone/checkout the pinned commit, then build")
    sub.add_parser("build", help="(re)build toolchain artifacts in the resolved home")
    sub.add_parser("prepare", help="make the resolved home usable (builds the driver)")
    sub.add_parser("status", help="report clone/artifact state against the pin")
    sub.add_parser("env", help="print export lines for a manual build environment")
    sp = sub.add_parser("pin", help="rewrite the pin to a new commit")
    sp.add_argument("commit")
    args = p.parse_args()
    fn = {"sync": cmd_sync, "build": cmd_build, "prepare": cmd_prepare,
          "status": cmd_status, "env": cmd_env, "pin": cmd_pin}[args.cmd]
    sys.exit(fn(args) or 0)


if __name__ == "__main__":
    main()
