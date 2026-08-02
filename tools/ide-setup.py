#!/usr/bin/env python3
"""Set up Metals for the Sgola modules: BSP launch files + dep TASTy paths.

Two pieces:

1. `sgo bsp install` writes `.bsp/sgo.json` into each module dir, so Metals
   discovers and launches `sgo bsp` (one build target per module — open a
   module dir, not the repo root, as the editor workspace).

2. The BSP shim's classpath names each in-link dep's early.jar at
   `$SGOLA_HOME/<dep>/target/scala-<v>/early/early.jar`, but nothing builds
   jars at those paths for wata's deps: the real build resolves `json` to the
   author module at `$SGOLA_HOME/scenarios/json` and `wataclient` to its
   sibling dir in this repo, and the whole-program compile writes the
   early.jars THERE. Bridge the gap with symlinks inside the (gitignored)
   toolchain clone, so Metals sees TASTy that every build keeps fresh.

Idempotent; rerun after `just sync` (a re-clone drops the symlinks).
"""

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from toolchain import build_env, prepare, sgo_bin  # noqa: E402

WATA = Path(__file__).resolve().parent.parent
MODULES = ["wataclient", "wata-server", "wata-fb"]

# in-link dep -> the dir whose target/ the build actually writes
DEP_SOURCES = {
    "json": lambda home: home / "scenarios" / "json",
    "wataclient": lambda home: WATA / "wataclient",
}


def scala_version(home):
    """The early.jar path embeds the Scala version; read it from sgo's source."""
    for line in (home / "sgo" / "frontend.go").read_text().splitlines():
        if "scalaVersion" in line and "=" in line and '"' in line:
            return line.split('"')[1]
    sys.exit("ide-setup: cannot read scalaVersion from sgo/frontend.go")


def link_dep_early(home, dep, src_dir, sv):
    """Symlink home/<dep>/target/scala-<v>/early at the dir the build writes."""
    early = Path("target") / f"scala-{sv}" / "early"
    target = src_dir / early
    link = home / dep / early
    if link.parent == target.parent:  # already the same place
        return
    link.parent.mkdir(parents=True, exist_ok=True)
    if link.is_symlink() or link.exists():
        if link.is_symlink() and link.readlink() == target:
            return
        sys.exit(f"ide-setup: {link} exists and is not the expected symlink")
    link.symlink_to(target)
    print(f"ide-setup: {link} -> {target}")


def main():
    home = prepare()
    env = build_env(home)
    sgo = str(sgo_bin(home))
    for mod in MODULES:
        subprocess.run([sgo, "bsp", "install", "--dir", str(WATA / mod)],
                       env=env, check=True)
    sv = scala_version(home)
    for dep, src in DEP_SOURCES.items():
        link_dep_early(home, dep, src(home), sv)
    print("ide-setup: done — open a module dir (not the repo root) in the editor")


if __name__ == "__main__":
    main()
