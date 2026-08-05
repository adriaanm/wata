#!/usr/bin/env python3
"""Turn the emitted `watabind` app module into an IMPORTABLE Go package.

Why this exists: `sgo` has two emission shapes and neither is "a Go library
that carries the sgola runtime".

  * `mode app` emits a whole-program `package main` module — everything
    wataclient needs (the runtime, the linked template instantiations, core),
    but not importable: Go cannot import a `main` package.
  * `mode library` is the `@goexport` publish shape: a runtime-FREE facade
    package (no `sgruntime.go`), so it cannot carry an in-link library that
    depends on core — which wataclient does, thoroughly.

So the spike builds as an app and rewrites the emission in place, mechanically:

  1. `package main`  ->  `package <name>` in every `.go` file
  2. `func main()`   ->  `func RunCLI()`   (kept, not deleted, so the `os`
     import it needs stays used and the CLI stays runnable via a shim)

Both edits are line-exact and idempotent; rerunning after `sgo build` is the
normal flow (sgo rewrites the tree, this rewrites it back).

    tools/phone-spike/aslib.py <emit-dir> [--package watacore]

Tracked upstream as sgola ticket NO-LIB-EMIT-FOR-RUNTIME-LIBS.
"""

import argparse
import pathlib
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("emitdir", type=pathlib.Path)
    ap.add_argument("--package", default="watacore")
    args = ap.parse_args()

    if not (args.emitdir / "go.mod").exists():
        print(f"aslib: {args.emitdir} is not an emitted module (no go.mod)", file=sys.stderr)
        return 1

    changed = 0
    for f in sorted(args.emitdir.glob("*.go")):
        src = f.read_text()
        out = src
        if out.startswith("package main\n"):
            out = f"package {args.package}\n" + out[len("package main\n"):]
        out = out.replace("\nfunc main() {\n", "\nfunc RunCLI() {\n")
        if out != src:
            f.write_text(out)
            changed += 1

    print(f"aslib: rewrote {changed} file(s) in {args.emitdir} to package {args.package}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
