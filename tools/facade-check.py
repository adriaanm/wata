#!/usr/bin/env python3
"""Declaration identity between facades that a SYMLINKED source file binds.

    tools/facade-check.py

`wata-fb/src/main/scala/audiothread.scala` is one file, symlinked into
`wata-mac/src/main/scala/` (plan 0033). It compiles in both modules only
because each module supplies a `go.audio` object with the SAME declarations —
one bound to `go-pkgs/audio` (cgo: opus + tinyalsa), one to `go-pkgs/macaudio`
(purego: AudioToolbox + AVFAudio). That identity is the whole price of the
symlink, and nothing in either compiler run enforces it: a member added on one
side alone breaks the other module's build only if the shared thread happens to
call it, and a member whose SIGNATURE drifts can keep compiling while meaning
something different.

So this compares the two files' declarations directly. Comments, blank lines
and the `@go.bind` line are ignored — the comments describe two different
backends on purpose, and the bind path IS the seam. Everything else must match
byte for byte after whitespace normalization.

Pure text: no toolchain, no macOS, so it runs in `just ci`.

Caveat worth knowing: comment stripping is lexical and assumes no `//` or `/*`
inside a string literal. These facade files are declarations and doc comments
only, so that holds; a facade that grows a string constant needs a real lexer.
"""

import difflib
import os
import re
import sys

WATA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The facade pairs to hold identical. Each entry: (reference, mirror).
PAIRS = [
    (
        "wata-fb/src/main/scala/audio.scala",
        "wata-mac/src/main/scala/audio.scala",
    ),
    # wata-ios binds macaudio too (plan 0063): audiothread.scala is the same
    # symlinked file, so the same identity must hold there.
    (
        "wata-fb/src/main/scala/audio.scala",
        "wata-ios/src/main/scala/audio.scala",
    ),
    # The iroh transport facade (plan 0034). Nothing symlinked binds this one:
    # wata-tui and wata-mac each declare their own `go.irohnet` over the SAME
    # Go package, and the pair is held identical so the client-side surface
    # stays one surface — a signature that drifts on one side is a second,
    # unreviewed contract against the same package. wata-fb's facade is
    # deliberately NOT the reference: it declares the enrolment calls
    # (EnsureKey, LastRefusal) that only a handset makes.
    (
        "wata-tui/src/main/scala/irohnet.scala",
        "wata-mac/src/main/scala/irohnet.scala",
    ),
]

BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE = re.compile(r"//.*")
BIND = re.compile(r"^\s*@go\.bind\(")


def declarations(path):
    """the file's declaration lines: comments, blank lines and @go.bind out,
    interior whitespace collapsed so re-wrapping a signature is not a diff."""
    text = BLOCK.sub("", open(path).read())
    out = []
    for raw in text.splitlines():
        line = LINE.sub("", raw).strip()
        if not line or BIND.match(raw):
            continue
        out.append(re.sub(r"\s+", " ", line))
    return out


def check(ref, mirror):
    a, b = os.path.join(WATA, ref), os.path.join(WATA, mirror)
    for p in (a, b):
        if not os.path.exists(p):
            print(f"FAIL: facade file missing: {p}")
            return False
    da, db = declarations(a), declarations(b)
    if da == db:
        return True
    print(f"FAIL: facade declarations differ")
    print(f"  reference: {ref}")
    print(f"  mirror:    {mirror}")
    diff = difflib.unified_diff(da, db, fromfile=ref, tofile=mirror, lineterm="")
    for line in diff:
        print("  " + line)
    return False


def main():
    ok = True
    for ref, mirror in PAIRS:
        if not check(ref, mirror):
            ok = False
    if not ok:
        print("facade-check: the symlinked source that binds these facades "
              "needs them declaration-identical (plan 0033).")
        sys.exit(1)
    print(f"PASS facade-check ({len(PAIRS)} pair(s))")


if __name__ == "__main__":
    main()
