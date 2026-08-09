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


def main():
    env = iosenv.go_env("iphonesimulator")
    for mod, pat in TARGETS:
        for verb in ("vet", "build"):
            args = pat.split() if verb == "build" else [pat.split()[-1]]
            cmd = ["go", verb] + args
            print(f"+ (cd {mod.relative_to(REPO)}) " + " ".join(cmd), flush=True)
            r = subprocess.run(cmd, cwd=mod, env=env)
            if r.returncode != 0:
                sys.exit(r.returncode)
    print("ios-build-check: uikit, iosui, iosshell and the ios hello are "
          "vet-clean and build for ios/arm64 against the iphonesimulator SDK")


if __name__ == "__main__":
    main()
