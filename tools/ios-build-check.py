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
MOD = REPO / "go-pkgs" / "appleptt"


def main():
    env = iosenv.go_env("iphonesimulator")
    for cmd in (["go", "vet", "./uikit/..."], ["go", "build", "./uikit/..."]):
        print("+ " + " ".join(cmd), flush=True)
        r = subprocess.run(cmd, cwd=MOD, env=env)
        if r.returncode != 0:
            sys.exit(r.returncode)
    print("ios-build-check: uikit is vet-clean and builds for ios/arm64 "
          "against the iphonesimulator SDK")


if __name__ == "__main__":
    main()
