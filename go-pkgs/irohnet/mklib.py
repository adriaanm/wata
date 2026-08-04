#!/usr/bin/env python3
"""Build the irohnet-ffi Rust staticlib for the HOST (darwin) and stage it
where the cgo directives expect it: clib/darwin/libirohnet_ffi.a.

    ./mklib.py            # cargo build --release, copy the .a

clib/ is a build artifact (gitignored); re-run to regenerate. The iroh
version is pinned by rust/Cargo.lock (committed) — `cargo build` respects it.
Cross targets (the armv7 device) are milestone 2 and get their own archdir.

NB (same trap as go-pkgs/audio): Go's build cache does not see .a changes —
after rebuilding the lib, build the consuming binary with `go build -a` or
clean the cache.
"""

import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUST = HERE / "rust"
OUT = HERE / "clib" / "darwin"


def main() -> int:
    subprocess.run(["cargo", "build", "--release", "--locked"], cwd=RUST, check=True)
    lib = RUST / "target" / "release" / "libirohnet_ffi.a"
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copy2(lib, OUT / lib.name)
    size_mb = lib.stat().st_size / (1024 * 1024)
    print(f"[mklib] {OUT / lib.name} ({size_mb:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
