#!/usr/bin/env python3
"""The on-device iroh LAN smoke (plan 0013 milestone 2): the BQ268 brings up
an embedded iroh endpoint and completes a client session against wata-server
on this machine, over the home LAN — relay "none", direct addresses only.

Steps:
  1. stage both staticlibs (mklib.py host + arm — cargo, rustup, zig);
  2. build the Mac side: sgo-build wata-server, then `go build -tags iroh`
     (plus the keygen helper);
  3. cross-build the device side: sgo cross build of wata-fb (armv7-musl,
     zig cc — also stages the audio C libs), then `go build -tags iroh`
     with external static linking in the emit dir;
  4. provision two fresh node keys, allowlist the device's id, boot
     wata-server with WATA_IROH_CONFIG, read its announce file;
  5. scp the device binary + client config to $BQ268_HOST:/dev/shm (the
     tmpfs — nothing installed, fb-deploy's conventions), remount exec,
     run `wata-fb-iroh integ login-syncing` over iroh, remove both files.

The scenario is login-syncing only: pure HTTP, no framebuffer, no audio —
safe to run next to the wata-fb the device is actively displaying (integ
mode has no audio thread and never opens /dev/fb0). Prints
IROH-LAN-SMOKE PASS / FAIL. Needs cargo + rustup + zig and the device
reachable as ssh host `bq268` (root); NOT part of `just ci` — it needs
hardware.
"""

import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare, sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
IROHNET = WATA / "go-pkgs" / "irohnet"
HOST = os.environ.get("BQ268_HOST", "bq268")
CC = os.environ.get("FB_CC", "zig cc -target arm-linux-musleabihf")
REMOTE_BIN = "/dev/shm/wata-fb-iroh"
REMOTE_CFG = "/dev/shm/wata-fb-iroh.json"
SCENARIO = "login-syncing"


def run(cmd, env, cwd=None, **kw):
    return subprocess.run(cmd, env=env, cwd=cwd or WATA, **kw)


def fail(msg):
    print(f"iroh-lan-smoke: {msg}")
    print("IROH-LAN-SMOKE FAIL")
    sys.exit(1)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else ""


def main():
    prepare()
    env = build_env()
    tmp = Path(tempfile.mkdtemp(prefix="iroh-lan-smoke."))

    # ---- 1. staticlibs (host + device) -------------------------------------
    arm_lib = IROHNET / "clib" / "linux_arm" / "libirohnet_ffi.a"
    arm_lib_before = sha(arm_lib)
    print("iroh-lan-smoke: staging the irohnet staticlibs (cargo: host + armv7-musl)…")
    for arch in ([], ["arm"]):
        if run([sys.executable, "mklib.py", *arch], env, cwd=IROHNET).returncode != 0:
            fail(f"mklib.py {' '.join(arch)} failed")

    # ---- 2. the Mac side ---------------------------------------------------
    print("iroh-lan-smoke: building wata-server (sgo, then -tags iroh) + keygen…")
    r = run([str(sgo_bin()), "build"], env, cwd=WATA / "wata-server", capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail("sgo build (wata-server) failed")
    srv_emit = WATA / "wata-server" / ".sgo" / "wata-server"
    server_bin = srv_emit / "wata-server-iroh"
    keygen_bin = tmp / "irohnet-keygen"
    for out, cwd, pkg in [(server_bin, srv_emit, "."), (keygen_bin, IROHNET, "./cmd/irohnet-keygen")]:
        r = run(["go", "build", "-tags", "iroh", "-o", str(out), pkg], env, cwd=cwd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout + r.stderr)
            fail(f"go build -tags iroh failed in {cwd}")

    # ---- 3. the device side (armv7-musl cross, static) ---------------------
    print("iroh-lan-smoke: cross-building wata-fb (sgo, then -tags iroh, static)…")
    r = run([str(sgo_bin()), "build", "--goos", "linux", "--goarch", "arm", "--goarm", "7",
             "--cgo", "--cc", CC], env, cwd=WATA / "wata-fb", capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail("sgo cross build (wata-fb) failed")
    fb_emit = WATA / "wata-fb" / ".sgo" / "wata-fb"
    client_bin = fb_emit / "wata-fb-iroh-linux-arm"
    cross_env = {**env, "GOOS": "linux", "GOARCH": "arm", "GOARM": "7", "CGO_ENABLED": "1", "CC": CC}
    cmd = ["go", "build", "-tags", "iroh",
           "-ldflags", "-linkmode=external -extldflags=-static",
           "-o", str(client_bin), "."]
    if sha(arm_lib) != arm_lib_before:
        # Go's build cache is blind to .a content changes (the audio-module
        # trap): a freshly-built staticlib needs -a or it links the stale one.
        cmd.insert(1, "-a")
    r = run(cmd, cross_env, cwd=fb_emit, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail("cross go build -tags iroh (wata-fb) failed")

    # ---- 4. provisioning + the Mac server over iroh ------------------------
    keys = []
    for _ in range(2):
        out = run([str(keygen_bin)], env, capture_output=True, text=True)
        if out.returncode != 0:
            fail(f"keygen failed: {out.stderr}")
        keys.append(json.loads(out.stdout))
    srv_key, dev_key = keys
    announce = tmp / "announce.json"
    srv_cfg = tmp / "server.json"
    srv_cfg.write_text(json.dumps({
        "secretKey": srv_key["secretKey"],
        "relay": "none",
        "allowlist": [dev_key["id"]],
        "announceFile": str(announce),
    }))
    log_path = tmp / "server.log"
    log = open(log_path, "w")
    proc = subprocess.Popen([str(server_bin)], env={**env, "WATA_IROH_CONFIG": str(srv_cfg)},
                            stdout=log, stderr=subprocess.STDOUT, cwd=WATA)
    ok = False
    try:
        for _ in range(100):
            if announce.exists():
                break
            if proc.poll() is not None:
                print(log_path.read_text())
                fail("server exited before announcing")
            time.sleep(0.1)
        else:
            fail("server never announced")
        ann = json.loads(announce.read_text())
        # LAN-dialable direct addrs only: IPv4, not loopback.
        lan = [a for a in ann["addrs"] if not a.startswith("[") and not a.startswith("127.")]
        if not lan:
            fail(f"no LAN IPv4 addr announced (got {ann['addrs']})")
        dev_cfg = tmp / "device.json"
        dev_cfg.write_text(json.dumps({
            "secretKey": dev_key["secretKey"],
            "relay": "none",
            "peer": ann["id"],
            "peerAddrs": lan,
        }))

        # ---- 5. deploy to /dev/shm and run (fb-deploy's conventions) -------
        print(f"iroh-lan-smoke: scp -> root@{HOST}:/dev/shm …")
        if run(["scp", "-q", str(client_bin), f"root@{HOST}:{REMOTE_BIN}"], env).returncode != 0:
            fail(f"cannot scp to {HOST} (wifi/DHCP? update ~/.ssh/config HostName)")
        if run(["scp", "-q", str(dev_cfg), f"root@{HOST}:{REMOTE_CFG}"], env).returncode != 0:
            fail(f"cannot scp config to {HOST}")
        # /dev/shm is noexec by default; remount (non-persistent), run, and
        # ALWAYS remove both files — nothing is installed on the device.
        remote = (f"mount -o remount,exec /dev/shm && chmod +x {REMOTE_BIN} && "
                  f"WATA_IROH_CONFIG={REMOTE_CFG} {REMOTE_BIN} integ {SCENARIO} http://wata.iroh; "
                  f"rc=$?; rm -f {REMOTE_BIN} {REMOTE_CFG}; exit $rc")
        print(f"iroh-lan-smoke: device dials {ann['id'][:16]}… at {lan} over iroh…")
        r = run(["ssh", f"root@{HOST}", remote], env, capture_output=True, text=True, timeout=180)
        print(r.stdout, end="")
        ok = f"INTEG PASS {SCENARIO}" in r.stdout
        if not ok:
            print("---- device stderr ----")
            print(r.stderr)
            print("---- server log ----")
            print(log_path.read_text())
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()
        run(["ssh", f"root@{HOST}", f"rm -f {REMOTE_BIN} {REMOTE_CFG}"], env,
            capture_output=True, text=True)

    if ok:
        print("IROH-LAN-SMOKE PASS (device -> Mac over embedded iroh, LAN direct)")
        return 0
    print(f"iroh-lan-smoke: artifacts kept at {tmp}")
    print("IROH-LAN-SMOKE FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
