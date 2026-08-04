#!/usr/bin/env python3
"""The on-device iroh ROAM smoke (plan 0013 milestone 3): the BQ268, with
wifi DOWN and only its cellular PPP link, dials wata-server on this machine
through the n0 relay — no LAN path, no direct addresses, discovery by node
id only. This is the walkie-talkie-away-from-home case.

Steps 1-4 mirror iroh-lan-smoke.py (builds + keys + server boot), except the
configs say relay "n0" and the device config carries NO peerAddrs. The device
run is driven over the USB SERIAL CONSOLE (ssh dies with wifi):

  5. stage binary + config to /dev/shm over ssh while wifi is still up;
  6. over serial: freeze the net-watchdog (/run/cell-data.force), take
     wlan0 down, point resolv.conf at public DNS (the PPP ip-up hook does
     not install peer DNS — see the alpine repo), verify cellular passes
     traffic, run `integ login-syncing` over iroh, capture the output;
  7. restore: resolv.conf back, wifi service restarted, force-file and
     staged files removed; wait until ssh answers again.

Prints IROH-ROAM-SMOKE PASS / FAIL. Needs the device on ssh host `bq268`
AND its serial console (BQ268_SERIAL, default /dev/cu.usbmodem000000001),
cargo + rustup + zig. NOT part of `just ci` — hardware + live cellular.
"""

import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import termios
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare, sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
IROHNET = WATA / "go-pkgs" / "irohnet"
HOST = os.environ.get("BQ268_HOST", "bq268")
SERIAL = os.environ.get("BQ268_SERIAL", "/dev/cu.usbmodem000000001")
CC = os.environ.get("FB_CC", "zig cc -target arm-linux-musleabihf")
REMOTE_BIN = "/dev/shm/wata-fb-iroh"
REMOTE_CFG = "/dev/shm/wata-fb-iroh.json"
SCENARIOS = ["login-syncing", "voice-to-bob"]   # tunnel-smoke's pair: sync + voice round trip


def run(cmd, env, cwd=None, **kw):
    return subprocess.run(cmd, env=env, cwd=cwd or WATA, **kw)


def fail(msg):
    print(f"iroh-roam-smoke: {msg}")
    print("IROH-ROAM-SMOKE FAIL")
    sys.exit(1)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else ""


class Serial:
    """Line-oriented driver for the device's busybox console: write a
    command, then poll-read until a unique end marker (with the exit code)
    comes back. The console echoes input; the marker protocol is what
    separates output from echo."""

    def __init__(self, path):
        self.fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        attrs = termios.tcgetattr(self.fd)
        attrs[0] = attrs[1] = attrs[3] = 0            # iflag, oflag, lflag: raw
        attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        attrs[4] = attrs[5] = termios.B115200
        termios.tcsetattr(self.fd, termios.TCSANOW, attrs)
        self.n = 0

    def drain(self, secs=0.5):
        end = time.time() + secs
        buf = b""
        while time.time() < end:
            try:
                buf += os.read(self.fd, 4096)
            except BlockingIOError:
                time.sleep(0.05)
        return buf.decode(errors="replace")

    def write(self, s):
        for ch in s.encode():
            os.write(self.fd, bytes([ch]))
            time.sleep(0.002)                          # busybox console pacing

    def cmd(self, command, timeout=15):
        """Run `command`; return (exit_code, output-between-markers)."""
        self.n += 1
        s, e = f"@@S{self.n}@@", f"@@E{self.n}"
        self.drain(0.3)
        self.write(f"echo {s}; {command}; echo {e}-$?@@\n")
        buf, end = "", time.time() + timeout
        while time.time() < end:
            buf += self.drain(0.3)
            if f"{e}-" in buf.split(f"echo {e}")[-1]:
                mid = buf.rsplit(f"{e}-", 1)[1]
                rc = mid.split("@@", 1)[0].strip()
                body = buf.split(s)[-1] if s in buf else buf
                body = body.rsplit(f"{e}-", 1)[0]
                try:
                    return int(rc), body.strip()
                except ValueError:
                    return -1, body.strip()
        return -1, buf.strip() + " [serial timeout]"


def main():
    prepare()
    env = build_env()
    tmp = Path(tempfile.mkdtemp(prefix="iroh-roam-smoke."))

    # ---- 1-3. staticlibs + both binaries (identical to the LAN smoke) ------
    arm_lib = IROHNET / "clib" / "linux_arm" / "libirohnet_ffi.a"
    arm_lib_before = sha(arm_lib)
    print("iroh-roam-smoke: staging the irohnet staticlibs…")
    for arch in ([], ["arm"]):
        if run([sys.executable, "mklib.py", *arch], env, cwd=IROHNET).returncode != 0:
            fail(f"mklib.py {' '.join(arch)} failed")
    print("iroh-roam-smoke: building wata-server (-tags iroh) + keygen…")
    r = run([str(sgo_bin()), "build"], env, cwd=WATA / "wata-server", capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail("sgo build (wata-server) failed")
    srv_emit = WATA / "wata-server" / ".sgo" / "wata-server"
    server_bin = srv_emit / "wata-server-iroh"
    keygen_bin = tmp / "irohnet-keygen"
    for out, cwd, pkg in [(server_bin, srv_emit, "."), (keygen_bin, IROHNET, "./cmd/irohnet-keygen")]:
        r = run(["go", "build", "-tags", "iroh", "-o", str(out), pkg], env, cwd=cwd,
                capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout + r.stderr)
            fail(f"go build -tags iroh failed in {cwd}")
    print("iroh-roam-smoke: cross-building wata-fb (-tags iroh, armv7-musl static)…")
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
        cmd.insert(2, "-a")                            # Go's cache is blind to .a changes
    r = run(cmd, cross_env, cwd=fb_emit, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        fail("cross go build -tags iroh (wata-fb) failed")

    # ---- 4. keys + the Mac server over the n0 relay ------------------------
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
        "relay": "n0",
        "allowlist": [dev_key["id"]],
        "announceFile": str(announce),
    }))
    log_path = tmp / "server.log"
    log = open(log_path, "w")
    proc = subprocess.Popen([str(server_bin)], env={**env, "WATA_IROH_CONFIG": str(srv_cfg)},
                            stdout=log, stderr=subprocess.STDOUT, cwd=WATA)
    ok = False
    ser = None
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
        dev_cfg = tmp / "device.json"
        dev_cfg.write_text(json.dumps({
            "secretKey": dev_key["secretKey"],
            "relay": "n0",
            "peer": ann["id"],                          # id only: no peerAddrs
        }))
        time.sleep(3)                                   # let discovery publish

        # ---- 5. stage over ssh while wifi is up ----------------------------
        print(f"iroh-roam-smoke: scp -> root@{HOST}:/dev/shm …")
        if run(["scp", "-q", str(client_bin), f"root@{HOST}:{REMOTE_BIN}"], env).returncode != 0:
            fail(f"cannot scp to {HOST}")
        if run(["scp", "-q", str(dev_cfg), f"root@{HOST}:{REMOTE_CFG}"], env).returncode != 0:
            fail(f"cannot scp config to {HOST}")
        run(["ssh", f"root@{HOST}",
             f"mount -o remount,exec /dev/shm && chmod +x {REMOTE_BIN}"], env)

        # ---- 6. the serial-driven cellular-only run ------------------------
        print(f"iroh-roam-smoke: serial console {SERIAL}: wifi down, cellular only…")
        ser = Serial(SERIAL)
        ser.write("\x03\n")                            # clear any stuck foreground job
        ser.drain(1.0)
        ser.write("stty -echo\n")                       # console wraps at 80 cols; echoed
        ser.drain(1.0)                                  # long commands break marker parsing
        rc, out = ser.cmd("echo SER-OK")
        if "SER-OK" not in out and rc != 0:
            fail(f"serial console not answering: {out[:200]}")
        ser.cmd("cp /etc/resolv.conf /dev/shm/resolv.saved; touch /run/cell-data.force")
        # Cellular is on-demand (the watchdog tears it down while wifi is
        # healthy — the force file above stops that). Bring it up and wait
        # for an address before touching wifi.
        rc, out = ser.cmd("ip -4 addr show ppp0 2>/dev/null | grep -q inet && echo PPP-UP || "
                          "(cell-data up >/dev/null 2>&1 &); echo KICKED", timeout=10)
        if "PPP-UP" not in out:
            print("iroh-roam-smoke: bringing up cellular (cell-data up)…")
            for _ in range(30):
                rc, out = ser.cmd("ip -4 addr show ppp0 2>/dev/null | grep inet; true")
                if "inet " in out:
                    break
                time.sleep(3)
            else:
                fail("cellular (ppp0) never got an address")
        ser.cmd("ifconfig wlan0 down")
        # admin-down keeps the configured ADDRESS visible; what goes away is
        # the routes. Down state + no wlan0 routes = no usable wifi path.
        rc, out = ser.cmd("cat /sys/class/net/wlan0/operstate; true")
        if "down" not in out:
            fail(f"wlan0 not down after ifconfig down: {out[:120]}")
        rc, out = ser.cmd("ip route show dev wlan0; echo ROUTES-END")
        if out.replace("ROUTES-END", "").strip():
            fail(f"wlan0 still has routes: {out[:200]}")
        # wifi's DHCP owned the default route; with it gone the resolver and
        # the relay dial need one via cellular. Added only if missing; the
        # restore path deletes it before wifi comes back.
        ser.cmd("ip route | grep -q ^default || ip route add default dev ppp0")
        # a fresh PDP context can take a while to actually forward after the
        # address appears — probe with retries, and show each miss.
        ping_ok = False
        for i in range(8):
            rc, out = ser.cmd("ping -c1 -W5 -I ppp0 1.1.1.1 >/dev/null 2>&1; echo PING=$?",
                              timeout=25)
            if "PING=0" in out:
                ping_ok = True
                break
            print(f"iroh-roam-smoke: ppp0 probe {i + 1}/8: {out[:120]!r}")
            time.sleep(3)
        if not ping_ok:
            fail("cellular (ppp0) does not pass traffic after 8 probes")
        ser.cmd("echo 'nameserver 1.1.1.1' > /etc/resolv.conf")
        print(f"iroh-roam-smoke: dialing {ann['id'][:16]}… via relay n0 over cellular…")
        ok = True
        for scenario in SCENARIOS:
            t0 = time.time()
            rc, out = ser.cmd(
                f"WATA_IROH_CONFIG={REMOTE_CFG} {REMOTE_BIN} integ {scenario} http://wata.iroh "
                f"> /dev/shm/roam.out 2>&1; echo RUN=$?", timeout=240)
            dt = time.time() - t0
            rc2, body = ser.cmd("cat /dev/shm/roam.out", timeout=20)
            print(body)
            print(f"iroh-roam-smoke: {scenario} wall time {dt:.1f}s "
                  f"(includes endpoint bring-up + discovery + relay dial)")
            if f"INTEG PASS {scenario}" not in body:
                ok = False
                print("---- server log ----")
                print(log_path.read_text())
                break
    finally:
        if ser is not None:
            # ---- 7. restore, regardless of outcome -------------------------
            print("iroh-roam-smoke: restoring wifi + DNS over serial…")
            ser.cmd("ip route del default dev ppp0 2>/dev/null; true")
            ser.cmd("cp /dev/shm/resolv.saved /etc/resolv.conf; rm -f /run/cell-data.force")
            # The wifi service owns the whole bring-up (WCNSS wait, module,
            # wpa_supplicant, the wpa_cli DHCP action daemon) — interfering
            # with a bare `ifconfig up` first can wedge the CAF driver's
            # connect state machine, which only a reboot clears.
            ser.cmd("rc-service wifi restart >/dev/null 2>&1; true", timeout=45)
            for _ in range(10):
                rc, out = ser.cmd("wpa_cli -i wlan0 status 2>/dev/null | head -1; true")
                if "COMPLETED" in out:
                    break
                time.sleep(3)
            else:
                print("iroh-roam-smoke: WARNING — wpa not associated after restart; "
                      "if ssh stays dead, reboot the device (clears the CAF wedge)")
            ser.cmd(f"rm -f {REMOTE_BIN} {REMOTE_CFG} /dev/shm/roam.out /dev/shm/resolv.saved")
            ser.write("stty echo\n")
            for i in range(30):
                r = run(["ssh", "-o", "ConnectTimeout=4", f"root@{HOST}", "true"], env,
                        capture_output=True)
                if r.returncode == 0:
                    print("iroh-roam-smoke: wifi + ssh restored")
                    break
                time.sleep(4)
            else:
                print(f"iroh-roam-smoke: WARNING — ssh to {HOST} not back yet; "
                      "check the device (serial console still works)")
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()

    if ok:
        print("IROH-ROAM-SMOKE PASS (device -> Mac via n0 relay, cellular only, id-only dial)")
        return 0
    print(f"iroh-roam-smoke: artifacts kept at {tmp}")
    print("IROH-ROAM-SMOKE FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
