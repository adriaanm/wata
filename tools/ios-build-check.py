#!/usr/bin/env python3
"""`just ios-build-check` — the generated UIKit bindings build for the simulator.

`go vet` + `go build` of go-pkgs/appleptt/uikit for GOOS=ios GOARCH=arm64
CGO_ENABLED=1 against the iphonesimulator sysroot (the ios spike's build env,
via tools/iosenv.py). Complements bindgen's own verify leg, which builds the
whole module against the iphoneos SDK: this is the slice and the sysroot the
simulator client (plan 0044) actually links.

Needs Xcode; not in ci.
"""

import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import iosenv  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent

# (module dir, package pattern) — the bindings plus every product package the
# simulator client links (plan 0044 stage 2), and the hello that composes them.
TARGETS = [
    (REPO / "go-pkgs" / "appleptt", "./uikit/..."),
    (REPO / "go-pkgs" / "iosui", "./..."),
    (REPO / "go-pkgs" / "iosshell", "./..."),
    # a main package: -o /dev/null so the check never litters a binary
    (REPO / "tools" / "ios-hello" / "app", "-o /dev/null ."),
]

# wata-ios's EMITTED module (plan 0044 stage 3) — a build product, present
# only after an `sgo build`, so it is covered when it exists and named as
# skipped when it does not (a fresh checkout has no .sgo tree; the full
# emit-and-run gate is `just ios-interptest`).
WATA_IOS_EMIT = (REPO / "wata-ios" / ".sgo" / "wata-ios", "-o /dev/null .")


def main():
    env = iosenv.go_env("iphonesimulator")
    targets = list(TARGETS)
    if WATA_IOS_EMIT[0].is_dir():
        targets.append(WATA_IOS_EMIT)
    else:
        print("ios-build-check: skipping wata-ios (no emitted module — "
              "run `just ios-interptest` or `sgo build` in wata-ios first)")
    for mod, pat in targets:
        for verb in ("vet", "build"):
            args = pat.split() if verb == "build" else [pat.split()[-1]]
            cmd = ["go", verb] + args
            print(f"+ (cd {mod.relative_to(REPO)}) " + " ".join(cmd), flush=True)
            r = subprocess.run(cmd, cwd=mod, env=env)
            if r.returncode != 0:
                sys.exit(r.returncode)
    covered = "uikit, iosui, iosshell, the ios hello"
    if WATA_IOS_EMIT[0].is_dir():
        covered += " and the emitted wata-ios"

    # irohnet (plan 0062 stage 1): its real implementation is `-tags iroh`
    # cgo over the staged Rust archive, and a package alone never links —
    # the keygen main is the link proof. Needs the simulator archive
    # (mklib.py ios-sim), skipped with a pointer when absent.
    irohnet = REPO / "go-pkgs" / "irohnet"
    if (irohnet / "clib" / "ios_sim" / "libirohnet_ffi.a").exists():
        for cmd in (
            [sys.executable, str(irohnet / "mklib.py"), "activate", "ios-sim"],
            ["go", "vet", "-tags", "iroh", "."],
            ["go", "build", "-tags", "iroh", "-o", "/dev/null",
             "./cmd/irohnet-keygen"],
        ):
            print(f"+ (cd {irohnet.relative_to(REPO)}) " + " ".join(cmd),
                  flush=True)
            r = subprocess.run(cmd, cwd=irohnet, env=env)
            if r.returncode != 0:
                sys.exit(r.returncode)
        covered += " and irohnet (iroh tag, simulator archive)"
    else:
        print("ios-build-check: skipping irohnet (no simulator archive — "
              "run go-pkgs/irohnet/mklib.py ios-sim first)")

    print(f"ios-build-check: {covered} are vet-clean and build for "
          "ios/arm64 against the iphonesimulator SDK")


if __name__ == "__main__":
    main()
