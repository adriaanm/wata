#!/usr/bin/env bash
# The wataui library gate — three checks over the declarative UI layer.
#
#   1. PORTABILITY TRIPWIRE: wataui sources must have ZERO `go.*` references.
#      The view algebra and the differ are what every backend links — the
#      framebuffer painter today, a native toolkit later — so the module knows
#      nothing about a framebuffer, a syscall or a clock.
#   2. DEPENDENCY TRIPWIRE: wataui declares no in-link dep beyond the implicit
#      prelude (`core`). It must not reach into wataclient or json: a view is
#      built FROM the domain by an app-side body, never the other way round.
#   3. DIFFER ORACLE: `wata-fb difftest` runs DiffOracle.report() — the
#      apply-o-diff round trip on every case (`ok=true`, the differ's whole
#      contract) plus the exact edit script for the cases the design makes
#      claims about (leaf granularity, keyed matching, positional fallback,
#      subtree replace). Byte-diffed against tools/wataui-diff.expected.txt.
#      wata-fb is only the DRIVER: its painter repaints whole trees and never
#      calls the differ.
#
#   tools/wataui-tests.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
SRC="$WATA/wataui/src/main/scala"

echo "== wataui-tests: 1/3 portability tripwire (zero go.* in $SRC) =="
# match a `go.` qualifier that is NOT part of `sgo.` (word-boundary before `go`).
HITS=$(grep -rnE '(^|[^A-Za-z0-9_])go\.' "$SRC" || true)
if [ -n "$HITS" ]; then
  echo "TRIPWIRE FAIL: wataui references the go.* facade (must be portable):"
  echo "$HITS"
  exit 1
fi
echo "   ok — no go.* references"

echo "== wataui-tests: 2/3 dependency tripwire (core only) =="
if [ -f "$WATA/wataui/sgo.deps" ]; then
  echo "wataui-tests FAIL: wataui/sgo.deps exists — wataui links core and nothing else"
  exit 1
fi
echo "   ok — no in-link deps beyond the prelude"

echo "== wataui-tests: 3/3 differ oracle (wata-fb difftest) =="
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "wataui-tests FAIL: wata-fb build"; exit 1; }
FB="$(emitdir wata-fb)/$(binname wata-fb)"
OUT="$("$FB" difftest)" || { echo "wataui-tests FAIL: difftest run"; exit 1; }
if printf '%s\n' "$OUT" | grep -q 'ok=false'; then
  echo "wataui-tests FAIL: a round trip did not reproduce the target tree:"
  printf '%s\n' "$OUT" | grep -n 'ok=false'
  exit 1
fi
if ! diff <(printf '%s\n' "$OUT") tools/wataui-diff.expected.txt; then
  echo "wataui-tests FAIL: differ oracle diverged from the pinned expected"
  exit 1
fi
echo "   ok — round trips hold and the edit scripts byte-match the pinned expected"

echo "wataui-tests: PASS (portability + dependency tripwires + differ oracle)"
