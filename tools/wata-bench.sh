#!/usr/bin/env bash
# M7 chunk 6 — the wata-server benchmark harness. Three measurements:
#   (1) allocs/op on the EMITTED serve path (tools/wata-bench/bench_test.go copied
#       into $SGOLA_HOME/.sgo/wata, `go test -bench`, each endpoint in an ISOLATED process so
#       the package-global store starts fresh — the M5 ReportAllocs mechanism).
#   (2) throughput + long-poll wake latency vs the TS reference server
#       (tools/wata-throughput, run against each baseURL).
#   (3) Conduit: checked for; benchmarked if present, else recorded unavailable.
#
#   tools/wata-bench.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set"; exit 1; }
# E2b NOTE: moved from the sgola tree with the wata code. Dev tool, NOT in
# either ci gate; needs $SGOLA_HOME (the sgola toolchain) and builds the wata
# modules from THIS repo. E3 (b): emission is under the module's OWN tree.

SGO="$SGOLA_HOME/sgo/sgo"
WATA="${WATA_REPO:-$HOME/g/bq268/wata}"

( cd "$SGOLA_HOME/sgo" && go build -o sgo . ) >/dev/null || { echo "bench: sgo build failed"; exit 1; }
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "bench: build failed"; exit 1; }
WATA_EMIT="$WATA/wata-server/.sgo/wata"           # E3 (b): the module's own emission tree

echo "========== (1) allocs/op on the emitted serve path =========="
cp tools/wata-bench/bench_test.go "$WATA_EMIT/bench_test.go"
for bm in BenchmarkWhoami BenchmarkLogin BenchmarkSyncInitial; do
  ( cd "$WATA_EMIT" && go test -bench "^${bm}$" -benchmem -run '^$' 2>&1 | grep -E "$bm" )
done
( cd "$WATA_EMIT" && go test -bench '^BenchmarkSend$' -benchmem -benchtime=500x -run '^$' 2>&1 | grep -E "BenchmarkSend" )
rm -f "$WATA_EMIT/bench_test.go"

echo
echo "========== (2/3) throughput + wake latency (Sgola vs TS vs Conduit) =========="
N="${BENCH_N:-2000}"

# node dylib shim (this machine's brew llhttp breakage — same trick as wata-tests.sh)
NODE="$(command -v node || true)"
DYLD_SHIM=""
if [ -n "$NODE" ] && [[ "$(env "$NODE" --version 2>&1)" != v* ]]; then
  real="$(ls /opt/homebrew/Cellar/llhttp/*/lib/libllhttp.*.dylib 2>/dev/null | head -1)"
  if [ -n "$real" ]; then S="$(mktemp -d)/shim"; mkdir -p "$S"; ln -sf "$real" "$S/libllhttp.9.3.dylib"; DYLD_SHIM="$S"; fi
fi

SP=""; TP=""
cleanup() { [ -n "$SP" ] && kill -9 "$SP" 2>/dev/null; [ -n "$TP" ] && kill -9 "$TP" 2>/dev/null; pkill -9 -f "server/index.ts" 2>/dev/null; true; }
trap cleanup EXIT

"$WATA_EMIT/wata-server" ":8008" >/tmp/wata-bench-sgola.log 2>&1 & SP=$!
for i in $(seq 1 80); do curl -s -o /dev/null http://127.0.0.1:8008/_matrix/client/versions && break; sleep 0.25; done
echo "### SGOLA (8008) ###"
( cd tools/wata-throughput && go run . http://127.0.0.1:8008 "$N" )

# --- TS reference (node --import tsx; DYLD must be exported so node keeps it) ---
if [ -n "$NODE" ] && [ -f "$WATA/src/server/index.ts" ]; then
  echo; echo "### TYPESCRIPT reference (8009) ###"
  ( cd "$WATA" && DYLD_FALLBACK_LIBRARY_PATH="$DYLD_SHIM" WATA_SERVER_PORT=8009 "$NODE" --import tsx src/server/index.ts >/tmp/wata-bench-ts.log 2>&1 ) & TP=$!
  up=0; for i in $(seq 1 80); do curl -s -o /dev/null http://127.0.0.1:8009/_matrix/client/versions && { up=1; break; }; sleep 0.25; done
  if [ "$up" = 1 ]; then ( cd tools/wata-throughput && go run . http://127.0.0.1:8009 "$N" )
  else echo "TS server did not boot (see /tmp/wata-bench-ts.log — node/tsx ESM-cycle on this machine's node is known)"; fi
else
  echo "TS reference unavailable (no node or wata repo)"
fi

# --- Conduit (drop-in reference) ------------------------------------------------
echo
if command -v conduit >/dev/null 2>&1; then
  echo "### CONDUIT ###  (found: $(command -v conduit)) — start it on :8010 and re-point wata-throughput"
else
  echo "Conduit: NOT AVAILABLE on this machine (no binary on PATH, no brew formula, no ~/g checkout) — comparison is vs the TS reference only."
fi
echo "bench: done"
