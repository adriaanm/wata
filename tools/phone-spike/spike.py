#!/usr/bin/env python3
"""The phone spike (plan 0023 M1), end to end.

Answers one question: does sgola-emitted Go survive `gomobile bind`'s
toolchain and type constraints at the bind surface? It does — this script is
the reproduction.

Stages (each runnable alone with --only):

  emit    `sgo build` the watabind Sgola module (core + json + wataclient
          whole-program linked), then aslib.py to make the emission importable.
  bind    `gomobile bind -target=ios,iossimulator` -> out/Watamobile.xcframework
          and `-target=macos` -> out/Watamobile-macos.xcframework.
  shell   swiftc the Swift shell against the macOS framework -> out/watashell.
  smoke   boot a wata-server on a free port, seed a family room (alice invites
          bob), run the Swift shell against it, and check the report it prints
          against the expected lines.

Two more, opt-in, for the iOS-simulator leg:

  --with-ios-runtime  download + install the iOS simulator runtime. Several GB;
          the download artifact goes to --runtime-dir (an external volume)
          because the internal disk is tight. The INSTALL still lands on the
          internal disk.
  --only sim          build the shell for the simulator and run it there
          against a live server. See REPORT.md for what blocks it here.

Requires: the pinned sgola toolchain (`just sync`), Xcode, and gomobile +
gobind on PATH or under $GOBIN/~/go/bin:

    go install golang.org/x/mobile/cmd/gomobile@latest \\
               golang.org/x/mobile/cmd/gobind@latest
"""

import argparse
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
WATA = HERE.parent.parent
OUT = HERE / "out"

# The seeded fixture the smoke asserts on: alice logs in, and the family room
# she created with bob invited is visible in her first snapshot. Contacts come
# from family membership, so bob shows up there too.
EXPECTED = [
    "hello watabind sgola-emitted go, wataclient linked",
    "self @alice:localhost Alice",
    "contacts 1",
    "contact @bob:localhost",
    "conversations 2",
    "conv family with=- messages=0 unplayed=0",
    "conv dm with=@bob:localhost messages=0 unplayed=0",
    "family Family",
]

STAGES = ["emit", "bind", "shell", "smoke"]
# `sim` is opt-in only (--only sim): it needs the iOS simulator runtime.
OPTIONAL_STAGES = ["sim"]


def run(cmd, **kw):
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True, **kw)


def gomobile_env():
    """gomobile runs OUTSIDE the sgo driver, so it needs the same two knobs the
    sgo scripts set: no go.work capture, and -mod=mod so the go build stage may
    complete the generated module's requirements (sgola ticket
    GOMOD-TRANSITIVE-SUM)."""
    env = dict(os.environ)
    env["GOWORK"] = "off"
    env["GOFLAGS"] = "-mod=mod"
    gobin = env.get("GOBIN") or str(pathlib.Path.home() / "go" / "bin")
    env["PATH"] = gobin + os.pathsep + env["PATH"]
    return env


def tool(name, env):
    p = shutil.which(name, path=env["PATH"])
    if not p:
        sys.exit(f"spike: {name} not found on PATH — "
                 f"go install golang.org/x/mobile/cmd/{name}@latest")
    return p


# ---- stages -------------------------------------------------------------------

def stage_emit():
    run([str(WATA / "tools" / "sgo"), "build"], cwd=HERE / "watabind")
    run([sys.executable, str(HERE / "aslib.py"), str(HERE / "watabind" / ".sgo" / "watacore")])


def stage_bind():
    env = gomobile_env()
    gomobile = tool("gomobile", env)
    tool("gobind", env)
    OUT.mkdir(exist_ok=True)
    for target, name in (("ios,iossimulator", "Watamobile.xcframework"),
                         ("macos", "Watamobile-macos.xcframework")):
        dest = OUT / name
        shutil.rmtree(dest, ignore_errors=True)
        run([gomobile, "bind", "-target=" + target, "-o", str(dest), "."],
            cwd=HERE / "watamobile", env=env)


def macos_framework_dir():
    xc = OUT / "Watamobile-macos.xcframework"
    hits = sorted(d for d in xc.glob("macos-*") if d.is_dir())
    if not hits:
        sys.exit(f"spike: no macos slice in {xc} — run the bind stage")
    return hits[0]


def stage_shell():
    fw = macos_framework_dir()
    OUT.mkdir(exist_ok=True)
    run(["xcrun", "swiftc",
         "-import-objc-header", str(HERE / "swift" / "bridge.h"),
         "-F", str(fw),
         "-Xlinker", "-rpath", "-Xlinker", str(fw),
         "-framework", "Watamobile-Macos",
         "-o", str(OUT / "watashell"),
         str(HERE / "swift" / "main.swift")])


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def post(base, path, body, token=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 method="POST",
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


def login(base, user):
    return post(base, "/_matrix/client/v3/login",
                {"type": "m.login.password",
                 "identifier": {"type": "m.id.user", "user": user},
                 "password": "testpass123"})["access_token"]


def seed(base):
    """alice creates the family room inviting bob, and bob joins — the shape the
    `family-room` integ scenario sets up. Bob has to actually JOIN: contacts are
    built from family MEMBERSHIP, so an invite alone leaves the list empty and
    the smoke would assert on nothing."""
    alice = login(base, "alice")
    room = post(base, "/_matrix/client/v3/createRoom",
                {"room_alias_name": "family", "preset": "public_chat",
                 "visibility": "public", "invite": ["@bob:localhost"]},
                token=alice)["room_id"]
    post(base, f"/_matrix/client/v3/rooms/{room}/join", {}, token=login(base, "bob"))


def stage_smoke():
    shell = OUT / "watashell"
    if not shell.exists():
        sys.exit("spike: out/watashell missing — run the shell stage")
    run([str(WATA / "tools" / "sgo"), "build"], cwd=WATA / "wata-server")
    server = WATA / "wata-server" / ".sgo" / "wata-server" / "wata-server"
    port = free_port()
    base = f"http://127.0.0.1:{port}"
    log = open(OUT / "server.log", "w")
    proc = subprocess.Popen([str(server), f":{port}"], stdout=log, stderr=log)
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(base + "/_matrix/client/versions", timeout=1).read()
                break
            except (urllib.error.URLError, OSError):
                if proc.poll() is not None:
                    sys.exit(f"spike: wata-server exited early — see {OUT/'server.log'}")
                time.sleep(0.1)
        else:
            sys.exit("spike: wata-server never became ready")
        seed(base)
        r = subprocess.run([str(shell), base, "alice", "testpass123", "30000"],
                           capture_output=True, text=True)
    finally:
        proc.terminate()
        proc.wait(timeout=10)
        log.close()

    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    got = r.stdout.strip().splitlines()
    if r.returncode != 0 or got != EXPECTED:
        print("spike: FAIL — the shell's report is not the expected one", file=sys.stderr)
        print("expected:\n  " + "\n  ".join(EXPECTED), file=sys.stderr)
        print("got:\n  " + "\n  ".join(got), file=sys.stderr)
        sys.exit(1)
    print("spike: PASS — sgola-emitted wataclient logged in and synced "
          "through a gomobile-bound framework")


def stage_sim():
    """OPTIONAL (not in the default run): build the shell for the iOS simulator
    and run it there against a live server. Needs the iOS simulator runtime
    (--with-ios-runtime) AND a device store CoreSimulatorService can write —
    on a machine whose ~/Library/Developer lives on an external volume, that
    is a TCC grant no script can make. See REPORT.md."""
    xc = OUT / "Watamobile.xcframework"
    fw = xc / "ios-arm64_x86_64-simulator"
    if not fw.is_dir():
        sys.exit(f"spike: no simulator slice in {xc} — run the bind stage")
    sdk = subprocess.run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"],
                         capture_output=True, text=True, check=True).stdout.strip()
    run(["xcrun", "--sdk", "iphonesimulator", "swiftc",
         "-target", "arm64-apple-ios26.0-simulator", "-sdk", sdk,
         "-import-objc-header", str(HERE / "swift" / "bridge-ios.h"),
         "-F", str(fw),
         "-Xlinker", "-rpath", "-Xlinker", str(fw),
         "-framework", "Watamobile",
         "-o", str(OUT / "watashell-sim"),
         str(HERE / "swift" / "main.swift")])

    # The device set lives OUTSIDE ~/Library/Developer: with that path
    # symlinked to an external volume, CoreSimulatorService takes a TCC
    # "removable volume" EPERM it can never prompt its way out of. A custom
    # set on the internal disk sidesteps the gate entirely, and a smoke
    # device costs ~0.1 GiB.
    devset = os.environ.get("WATA_SIM_DEVSET",
                            os.path.expanduser("~/.wata-simdevices"))
    pathlib.Path(devset).mkdir(parents=True, exist_ok=True)
    simctl = ["xcrun", "simctl", "--set", devset]
    runtimes = subprocess.run(simctl + ["list", "runtimes", "-j"],
                              capture_output=True, text=True, check=True).stdout
    ios = [r for r in json.loads(runtimes)["runtimes"]
           if r.get("isAvailable") and r["identifier"].startswith(
               "com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not ios:
        sys.exit("spike: no iOS simulator runtime — rerun with --with-ios-runtime")
    dev = subprocess.run(
        simctl + ["create", "wata-spike",
                  "com.apple.CoreSimulator.SimDeviceType.iPhone-17",
                  ios[-1]["identifier"]],
        capture_output=True, text=True)
    if dev.returncode != 0:
        sys.exit("spike: simctl create failed — see ~/Library/Logs/CoreSimulator/"
                 "CoreSimulator.log\n" + dev.stderr)
    udid = dev.stdout.strip()
    try:
        run(simctl + ["bootstatus", udid, "-b"])
        run([str(WATA / "tools" / "sgo"), "build"], cwd=WATA / "wata-server")
        server = WATA / "wata-server" / ".sgo" / "wata-server" / "wata-server"
        port = free_port()
        base = f"http://127.0.0.1:{port}"
        proc = subprocess.Popen([str(server), f":{port}"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            time.sleep(2)
            seed(base)
            run(simctl + ["spawn", udid, str(OUT / "watashell-sim"),
                          base, "alice", "testpass123", "30000"])
        finally:
            proc.terminate()
            proc.wait(timeout=10)
    finally:
        subprocess.run(simctl + ["delete", udid])


def stage_ios_runtime(runtime_dir):
    """OPTIONAL. Downloads the iOS simulator runtime (multi-GB). The download
    artifact lands in runtime_dir (pass an external volume); the INSTALLED
    image still goes to the internal disk."""
    d = pathlib.Path(runtime_dir)
    d.mkdir(parents=True, exist_ok=True)
    run(["xcodebuild", "-downloadPlatform", "iOS", "-exportPath", str(d)])
    dmgs = sorted(d.glob("*.dmg"))
    if not dmgs:
        sys.exit(f"spike: no runtime dmg exported to {d}")
    run(["xcrun", "simctl", "runtime", "add", str(dmgs[-1])])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=STAGES + OPTIONAL_STAGES, action="append",
                    help="run just these stages (default: all four, in order; `sim` is opt-in)")
    ap.add_argument("--with-ios-runtime", action="store_true",
                    help="also download+install the iOS simulator runtime (GBs)")
    ap.add_argument("--runtime-dir", default="/Volumes/MoorsExt/xcode-runtimes",
                    help="where the runtime download artifact goes")
    args = ap.parse_args()

    for s in (args.only or STAGES):
        globals()["stage_" + s.replace("-", "_")]()
    if args.with_ios_runtime:
        stage_ios_runtime(args.runtime_dir)


if __name__ == "__main__":
    main()
