#!/usr/bin/env python3
"""Build wata-mac with the real iroh transport compiled in (plan 0034).

    tools/mac-iroh-build.py     # -> wata-mac/.sgo/wata-mac/wata-mac-iroh

Two builds, because the transport is opt-in: `sgo build` emits the Go tree as
always, then a SECOND `go build -tags iroh` over that tree links the cgo
implementation and the Rust staticlib (staged here by mklib.py). Without the
tag the app links irohnet's pure-Go stub, which is what keeps the ordinary
`just mac-build` free of cargo — and, with WATA_IROH_CONFIG set, is exactly
the "configured but unavailable" state the boot screen names.

Run it against a real deployment with the client's node id allowlisted:

    WATA_IROH_CONFIG=~/.wata/iroh.json WATA_MAC_USER=… \\
      wata-mac/.sgo/wata-mac/wata-mac-iroh http://wata.iroh
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import irohkit  # noqa: E402
from irohkit import IrohError  # noqa: E402
from toolchain import build_env, prepare  # noqa: E402


def main() -> int:
    if sys.platform != "darwin":
        sys.exit("mac-iroh-build: macOS only")
    prepare()
    env = build_env()
    try:
        print("mac-iroh-build: staging the irohnet staticlib (cargo)…")
        irohkit.stage_staticlib(env)
        print("mac-iroh-build: sgo build, then go build -tags iroh…")
        out = irohkit.build_iroh_app(env, "wata-mac")
    except IrohError as e:
        sys.exit(f"mac-iroh-build: {e}")
    print(f"mac-iroh-build: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
