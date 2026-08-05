#!/usr/bin/env python3
"""The Gio blit shell (plan 0023 M2): the REAL wata-fb frame loop in a window.

    just phone-blit                     # scratch server + a window, interactive
    just phone-blit --frames 60         # unattended: quit after 60 frames
    just phone-blit --base http://127.0.0.1:8008 --user alice --pass secret
    just phone-blit --scale 3           # force the magnification

"The phone is a bigger BQ268": the window blits the same 160x128 RGB565 frame
the panel gets, integer-scaled with nearest-neighbour sampling, and its five
buttons are the handset's five keys. Nothing about the UI is reimplemented —
`GioDevice` (wata-fb/src/main/scala/gio.scala) is one more `UiDevice` backend.

This script owns the two things the recipe would otherwise leave to a README:

  1. THE TAGGED BUILD. Gio is opt-in — `sgo build` emits Go against
     go-pkgs/gioshell's window-free stub, so the armv7 device cross-build and
     the linux/amd64 smoke never grow a window toolkit. Here we `sgo build`
     and then re-build the emitted Go with `-tags gioshell`.
  2. A SERVER TO TALK TO. Without --base it boots a scratch wata-server on a
     random port and stops it on the way out, so the recipe is one command.

--frames N is the unattended sanity leg: the shell quits after N presented
frames, and this script asserts the client reported them. It cannot click a
button for you; the interactive run is the owner's.
"""

import argparse
import os
import random
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "testpass123"


def fail(msg):
    print(f"phone-blit: {msg}", file=sys.stderr)
    sys.exit(1)


# tools/sgo-env.sh + tools/emitdir.sh are the single source of truth for the
# toolchain and the emit layout; ask them rather than re-deriving either here.
def probe():
    script = (
        f'set -e; cd "{WATA}"; WATA="{WATA}"; . tools/sgo-env.sh; . tools/emitdir.sh; '
        'printf "%s\\n%s\\n%s\\n%s\\n%s\\n" '
        '"$SGO" "$(emitdir wata-fb)" "$(binname wata-fb)" '
        '"$(emitdir wata-server)/$(binname wata-server)" "${GOTOOLCHAIN:-}"'
    )
    out = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    if out.returncode != 0:
        fail("sgo environment probe failed:\n" + out.stderr)
    sgo, fb_emit, fb_bin, server, gotoolchain = out.stdout.strip().split("\n")
    env = dict(os.environ)
    env["GOWORK"] = "off"
    # sgo's go.mod stage writes no go.sum and no transitive requires for a
    # godep, so the go build stage has to be allowed to add them
    # (sgola ticket GOMOD-TRANSITIVE-SUM).
    env["GOFLAGS"] = "-mod=mod"
    if gotoolchain:
        env["GOTOOLCHAIN"] = gotoolchain
    return sgo, fb_emit, fb_bin, server, env


def sgo_build(sgo, env, module):
    r = subprocess.run([sgo, "build"], cwd=os.path.join(WATA, module),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        fail(f"{module} build failed:\n{r.stdout}{r.stderr}")


def go_build_tagged(emit, env, out):
    """Rebuild the emitted Go with Gio compiled in."""
    r = subprocess.run(["go", "build", "-tags", "gioshell", "-o", out, "."],
                       cwd=emit, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        fail("go build -tags gioshell failed:\n" + r.stdout + r.stderr)


# ---- the scratch server ------------------------------------------------------
def our_listener(pid, port):
    """A wata-server that lost a bind race exits ZERO, and a foreign squatter
    would answer a bare readiness probe — the listener's identity is the only
    trustworthy readiness signal (the same check fb-ui-tests makes)."""
    r = subprocess.run(["lsof", "-ti", f"tcp:{port}", "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return str(pid) in r.stdout.split()


def start_server(binary, port, log_path, env):
    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, f":{port}"], stdout=log, stderr=log, env=env)
    base = f"http://127.0.0.1:{port}"
    for _ in range(200):
        if proc.poll() is not None:
            return None, log
        if our_listener(proc.pid, port):
            try:
                urllib.request.urlopen(f"{base}/_matrix/client/versions", timeout=0.5).read()
                return proc, log
            except (urllib.error.URLError, OSError):
                pass
        time.sleep(0.1)
    proc.kill()
    return None, log


def stop_server(proc, log):
    if proc is not None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
    if log is not None:
        log.close()


def main():
    ap = argparse.ArgumentParser(description="the Gio blit shell")
    ap.add_argument("--base", help="an existing homeserver (default: boot a scratch one)")
    ap.add_argument("--user", default="alice")
    ap.add_argument("--password", "--pass", dest="password", default=PASSWORD)
    ap.add_argument("--scale", type=int, default=0, help="forced integer magnification (0 = fit)")
    ap.add_argument("--frames", type=int, default=0, help="quit after N presented frames")
    ap.add_argument("--config", help="session store path (default: a scratch one)")
    ap.add_argument("--timeout", type=float, default=120.0, help="unattended run timeout")
    args = ap.parse_args()

    sgo, fb_emit, fb_bin, server_bin, env = probe()
    # The draw-path oracle: the same view the window lays out, rendered through
    # Gio's headless GPU surface and read back pixel by pixel. It needs a GPU
    # context but no display, so it runs here even when a window cannot open —
    # and it is what actually proves the blit puts the frame on the surface
    # unchanged. (The window-free half of it is in `just fb-smoke`.)
    print("phone-blit: gioshell tests (go test -tags gioshell)…")
    r = subprocess.run(["go", "test", "-count=1", "-tags", "gioshell", "./..."],
                       cwd=os.path.join(WATA, "go-pkgs", "gioshell"),
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        fail("gioshell tests failed:\n" + r.stdout + r.stderr)

    print("phone-blit: building wata-fb (sgo, then -tags gioshell)…")
    sgo_build(sgo, env, "wata-fb")
    gio_bin = os.path.join(fb_emit, fb_bin + "-gio")
    go_build_tagged(fb_emit, env, gio_bin)

    tmp = tempfile.mkdtemp(prefix="phone-blit.")
    env = dict(env, WATA_FB_CONFIG=args.config or os.path.join(tmp, "config.json"))

    srv, log, base = None, None, args.base
    if base is None:
        sgo_build(sgo, env, "wata-server")
        port = random.randint(20000, 39999)
        print(f"phone-blit: booting a scratch wata-server on :{port}…")
        srv, log = start_server(server_bin, port, os.path.join(tmp, "server.log"), env)
        if srv is None:
            fail("the scratch server never became ready; see " + tmp)
        base = f"http://127.0.0.1:{port}"

    cmd = [gio_bin, "gio", base, args.user, args.password]
    if args.scale:
        cmd += ["--scale", str(args.scale)]
    if args.frames:
        cmd += ["--frames", str(args.frames)]
    print("phone-blit: " + " ".join(cmd))
    if not args.frames:
        print("phone-blit: close the window (or Back twice from contacts) to quit.")
    try:
        if args.frames:
            r = subprocess.run(cmd, env=env, capture_output=True, text=True,
                               timeout=args.timeout)
            out = r.stdout + r.stderr
            print(out.strip())
            got = [l for l in out.splitlines() if l.startswith("gio: frames ")]
            if not got:
                fail("the shell never reported a frame count — no window, no frames")
            # "gio: frames <presented> painted <painted>" — painted is the one
            # that says a WINDOW existed and the blit path ran, not merely that
            # the client kept looping.
            words = got[-1].split()
            presented, painted = int(words[2]), int(words[4])
            if presented < args.frames:
                fail(f"only {presented} frames presented, wanted {args.frames}")
            if painted < 1:
                fail("the client looped but no window ever drew a frame. Gio needs a GUI "
                     "session with an ACTIVE display — a sleeping or locked screen, or a "
                     "session with no window server, is enough to stop it. The blit path "
                     "itself is covered by the gioshell tests above, which passed.")
            print(f"phone-blit: PASS — {presented} frames presented, {painted} painted")
        else:
            subprocess.run(cmd, env=env)
    except subprocess.TimeoutExpired:
        fail(f"the shell did not finish within {args.timeout}s")
    except KeyboardInterrupt:
        pass
    finally:
        stop_server(srv, log)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        sys.exit(130)
