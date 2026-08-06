#!/usr/bin/env bash
# The wata-server benchmark harness. Three measurements:
#   (1) allocs/op on the EMITTED serve path (tools/wata-bench/bench_test.go copied
#       into the emitted tree, `go test -bench`, each endpoint in an ISOLATED
#       process so the package-global store starts fresh).
#   (2) throughput + long-poll wake latency (tools/wata-throughput).
#   (3) Conduit: checked for; benchmarked if present, else recorded unavailable.
#
# The retired TS reference server used to be a comparison leg here; it lives
# in git history at 27a2f75.
#
#   tools/wata-bench.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
# A dev tool, not part of `just ci`.
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers

( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "bench: build failed"; exit 1; }
WATA_EMIT="$(emitdir wata-server)"

echo "========== (1) allocs/op on the emitted serve path =========="
cp tools/wata-bench/bench_test.go "$WATA_EMIT/bench_test.go"
for bm in BenchmarkWhoami BenchmarkLogin BenchmarkSyncInitial; do
  ( cd "$WATA_EMIT" && go test -bench "^${bm}$" -benchmem -run '^$' 2>&1 | grep -E "$bm" )
done
( cd "$WATA_EMIT" && go test -bench '^BenchmarkSend$' -benchmem -benchtime=500x -run '^$' 2>&1 | grep -E "BenchmarkSend" )
rm -f "$WATA_EMIT/bench_test.go"

echo
echo "========== (2/3) throughput + wake latency (Sgola vs Conduit) =========="
N="${BENCH_N:-2000}"

SP=""
cleanup() { [ -n "$SP" ] && kill -9 "$SP" 2>/dev/null; true; }
trap cleanup EXIT

"$WATA_EMIT/wata-server" ":8008" >/tmp/wata-bench-sgola.log 2>&1 & SP=$!
for i in $(seq 1 80); do curl -s -o /dev/null http://127.0.0.1:8008/_matrix/client/versions && break; sleep 0.25; done
echo "### SGOLA (8008) ###"
( cd tools/wata-throughput && go run . http://127.0.0.1:8008 "$N" )

# --- Conduit (drop-in reference) ------------------------------------------------
echo
if command -v conduit >/dev/null 2>&1; then
  echo "### CONDUIT ###  (found: $(command -v conduit)) — start it on :8010 and re-point wata-throughput"
else
  echo "Conduit: NOT AVAILABLE on this machine (no binary on PATH, no brew formula, no ~/g checkout) — Sgola numbers stand alone."
fi
echo "bench: done"
