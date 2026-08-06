#!/usr/bin/env python3
"""The machinery every iroh smoke needs: staging the Rust staticlib, the
two-step `-tags iroh` build, node-key provisioning, config files, and booting
a server that announces itself.

An iroh harness is the same six steps every time — cargo-stage the lib,
`sgo build` each app, `go build -tags iroh` over its emitted tree, mint keys,
write the two JSON configs, boot the server and read its announce file — and
they were written once inside `tunnel-smoke.py`. `tools/mac-iroh-smoke.py`
needs all six, so they live here and both import them.

Errors raise `IrohError`; each smoke catches it and prints its own verdict
line, since "TUNNEL-SMOKE FAIL" is the caller's word, not this module's.

NB (mklib.py's header says it too, and it costs an hour every time it is
rediscovered): Go's build cache does not see .a CONTENT changes, so after a
restaged staticlib an ordinary `go build` happily links the PREVIOUS one.
`stage_staticlib` closes that hole where it opens: it hashes the archive
around the cargo run and cleans the Go build cache only if the bytes moved —
paying the full rebuild exactly on the runs that would otherwise lie.
"""

import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
IROHNET = WATA / "go-pkgs" / "irohnet"


class IrohError(Exception):
    """anything that stops a harness before it can assert anything."""


def emit_dir(module: str) -> Path:
    """the module's emitted Go tree — where the `-tags iroh` build runs."""
    return WATA / module / ".sgo" / module


# ---- builds ------------------------------------------------------------------
# mklib.py's target name -> the clib/ subdir it stages into.
CLIB_DIRS = {"": "darwin", "arm": "linux_arm", "ios": "ios", "ios-sim": "ios_sim"}


def _lib_hash(target: str):
    lib = IROHNET / "clib" / CLIB_DIRS[target] / "libirohnet_ffi.a"
    if not lib.exists():
        return None
    return hashlib.sha256(lib.read_bytes()).hexdigest()


def stage_staticlib(env, *targets) -> bool:
    """cargo-build the irohnet Rust staticlib for each target and stage it
    where the cgo LDFLAGS expect it (no target = the host, darwin). Cleans
    Go's build cache if any archive's BYTES changed — see the module header.
    -> whether anything changed."""
    changed = False
    for target in targets or ("",):
        before = _lib_hash(target)
        args = [target] if target else []
        r = subprocess.run([sys.executable, "mklib.py", *args], env=env, cwd=IROHNET)
        if r.returncode != 0:
            raise IrohError(f"mklib.py {' '.join(args)} failed")
        if _lib_hash(target) != before:
            changed = True
    if changed:
        print("irohkit: the staticlib changed — cleaning Go's build cache "
              "(it does not see .a content)")
        subprocess.run(["go", "clean", "-cache"], env=env, cwd=WATA, check=False)
    return changed


def sgo_build(env, module: str) -> None:
    r = subprocess.run([str(sgo_bin()), "build"], env=env, cwd=WATA / module,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise IrohError(f"sgo build ({module}) failed:\n{r.stdout}{r.stderr}")


def go_build(env, out: Path, cwd: Path, pkg: str = ".", tags: str = "iroh") -> Path:
    """`go build -tags iroh` — the SECOND build, over an emitted tree (or over
    go-pkgs/irohnet itself, for its cmd/ tools). Staleness against the
    staticlib is `stage_staticlib`'s job, not a blanket `-a` here."""
    r = subprocess.run(["go", "build", "-tags", tags, "-o", str(out), pkg],
                       env=env, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise IrohError(f"go build -tags {tags} failed in {cwd}:\n{r.stdout}{r.stderr}")
    return out


def build_iroh_app(env, module: str, out_name: str = None) -> Path:
    """sgo-build an app, then go-build it with the iroh tag; -> the binary."""
    sgo_build(env, module)
    emit = emit_dir(module)
    return go_build(env, emit / (out_name or f"{module}-iroh"), emit)


def build_keygen(env, out: Path) -> Path:
    return go_build(env, out, IROHNET, "./cmd/irohnet-keygen")


# ---- identities and configs ---------------------------------------------------
def keygen(env, keygen_bin) -> dict:
    """a fresh node identity: {"secretKey": hex, "id": nodeid}."""
    out = subprocess.run([str(keygen_bin)], env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise IrohError(f"keygen failed: {out.stderr}")
    return json.loads(out.stdout)


def mint_into(env, keygen_bin, cfg_path) -> str:
    """the DEVICE-MINTED identity (plan 0014 milestone 1): irohnet.EnsureKey
    over a config that carries no secret — the same call a handset makes on
    first boot. Returns the node id; the secret never leaves the config."""
    out = subprocess.run([str(keygen_bin), "-config", str(cfg_path)], env=env,
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise IrohError(f"EnsureKey failed: {out.stderr}")
    return json.loads(out.stdout)["id"]


def server_config(path: Path, secret: str, allowlist, announce: Path,
                  relay: str = "none") -> Path:
    path.write_text(json.dumps({
        "secretKey": secret,
        "relay": relay,
        "allowlist": list(allowlist),
        "announceFile": str(announce),
    }))
    return path


def client_config(path: Path, secret: str, announce: dict, relay: str = "none",
                  **extra) -> Path:
    """a client config aimed at the peer the server announced. Only its IPv4
    direct addrs are carried: a relay-"none" harness dials loopback UDP."""
    v4 = [a for a in announce["addrs"] if not a.startswith("[")]
    path.write_text(json.dumps({
        "secretKey": secret,
        "relay": relay,
        "peer": announce["id"],
        "peerAddrs": v4,
        **extra,
    }))
    return path


# ---- the server ----------------------------------------------------------------
def start_server(server_bin, env, cfg_path: Path, log_path: Path, listen=None):
    """boot a wata-server over iroh. `listen` adds the plain-TCP admin listener
    (plan 0021's dual listener); without it the process has NO TCP port at
    all, which is the shape a transport smoke wants. -> (proc, log file)."""
    log = open(log_path, "w")
    extra = {"WATA_LISTEN": listen} if listen else {}
    proc = subprocess.Popen(
        [str(server_bin)],
        env={**env, "WATA_IROH_CONFIG": str(cfg_path), **extra},
        stdout=log, stderr=subprocess.STDOUT, cwd=WATA,
    )
    return proc, log


def await_announce(announce: Path, proc=None, timeout_s: float = 10.0) -> dict:
    """the announce file the server writes once its iroh listener is up: its
    node id and direct addrs, which is everything a client needs to dial it."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if announce.exists():
            return json.loads(announce.read_text())
        if proc is not None and proc.poll() is not None:
            raise IrohError("the server exited before announcing")
        time.sleep(0.1)
    raise IrohError("the server never announced")


def stop_server(proc, log, timeout_s: float = 5.0) -> None:
    if proc is not None and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            proc.kill()
    if log is not None:
        log.close()
