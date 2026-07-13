#!/usr/bin/env bash
# M7 chunk 5 — THE GATE. Run wata's integration jest suites (the oracle) against
# the Sgola-built wata-server binary.
#
#   tools/wata-tests.sh              # build, run all 10 suites, print scoreboard
#   tools/wata-tests.sh <suite>      # run one suite (basename, e.g. matrix)
#
# Lifecycle: the wata orchestrator defaults to http://localhost:8008 (zero wata
# edits). wata's in-memory server accumulates state across suites (its own
# documented workflow restarts between runs), and several suites assert exact
# per-user room/message sets, so THIS runner starts a FRESH wata-server on :8008
# for EACH suite and tears it down after — that isolation is our side, allowed by
# the brief. The wata repo is READ-ONLY: we only execute its jest suites (pnpm's
# node_modules must already be installed; we never write to the repo).
#
# Node note (this machine): homebrew node 25.x is linked against a llhttp dylib
# version that is no longer installed, so `node` fails to launch. Rather than
# mutate the user's homebrew, we detect that and build a leaf-name symlink shim in
# a temp dir + DYLD_FALLBACK_LIBRARY_PATH (llhttp 9.x is ABI-stable). We also
# invoke jest's real JS entry directly via node (not the pnpm /bin/sh shim), since
# macOS strips DYLD_* across a SIP-protected /bin/sh exec but preserves it
# node->node. If node still can't launch, we STOP with a clear message.
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set"; exit 1; }
# E2b NOTE: moved from the sgola tree with the wata code. Dev tool, NOT in
# either ci gate; needs $SGOLA_HOME (the sgola toolchain) and builds the wata
# modules from THIS repo. E3 (b): emission is under the module's OWN tree.

WATA_TS="${WATA_TS_REPO:-$HOME/g/bq268/wata}"  # the ORIGINAL TS wata repo (jest oracle source)
PORT="${WATA_PORT:-8008}"
BASE="http://127.0.0.1:$PORT"
ONLY="${1:-}"

SGO="$SGOLA_HOME/tools/sgo/sgo"
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
BIN="$(emitdir wata-server)/$(binname wata-server)"

# M7 chunk 6: WATA_PERSIST=1 re-runs the SAME oracle with the JSONL journal ON,
# a FRESH log file per suite (temp dir) — each suite still starts clean (empty
# log => behaves exactly stateless) while the append-only write path is fully
# exercised on every mutation. Green here == journaling corrupts no response.
# (Replay round-trip is covered separately by wata-persist-smoke.sh.)
PERSIST="${WATA_PERSIST:-}"
PERSIST_DIR=""
if [ -n "$PERSIST" ]; then PERSIST_DIR="$(mktemp -d)"; echo "wata-tests: PERSISTENCE ON — fresh journal per suite in $PERSIST_DIR"; fi
CUR_SUITE=""

ALL_SUITES=(auto-login contacts e2e-flow edge-cases family-room matrix message-ordering read-receipts stress-tests voice-message-flow)

# ---- prerequisites ----------------------------------------------------------
[ -d "$WATA_TS/test/integration" ] || { echo "wata-tests: TS wata repo not found at $WATA_TS (set WATA_TS_REPO)"; exit 1; }
JEST_JS="$WATA_TS/node_modules/jest/bin/jest.js"
[ -f "$JEST_JS" ] || { echo "wata-tests: $JEST_JS missing — run 'pnpm install' in $WATA_TS first (STOP: we will not install for you)"; exit 1; }

NODE="$(command -v node || true)"
[ -n "$NODE" ] || { echo "wata-tests: node not on PATH"; exit 1; }

# ---- node launch probe + optional dylib shim --------------------------------
# NODE_ENV_ASSIGNS are passed as a SINGLE `env VAR=VAL ... node` invocation:
# /usr/bin/env is SIP-protected, and dyld purges DYLD_* from a SIP binary's
# INHERITED environment — so chaining two `env`s would strip the shim on the
# second hop. One env that sets the var and execs the (unrestricted) node keeps it.
NODE_ENV_ASSIGNS=()
probe="$(env "$NODE" --version 2>&1)"
if [[ "$probe" != v* ]]; then
  # Parse the missing absolute dylib path from the dyld error, e.g.
  #   Library not loaded: /opt/homebrew/opt/llhttp/lib/libllhttp.9.3.dylib
  missing="$(printf '%s\n' "$probe" | sed -n 's/.*Library not loaded: \(.*\.dylib\).*/\1/p' | head -1)"
  if [ -n "$missing" ]; then
    leaf="$(basename "$missing")"                       # libllhttp.9.3.dylib
    stem="$(printf '%s' "$leaf" | sed -E 's/\.[0-9].*$//')"  # libllhttp
    # find an installed dylib of the same stem to alias
    real="$(ls /opt/homebrew/Cellar/"${stem#lib}"/*/lib/"$stem".*.dylib 2>/dev/null | head -1)"
    [ -z "$real" ] && real="$(ls /opt/homebrew/lib/"$stem".*.dylib 2>/dev/null | head -1)"
    if [ -n "$real" ]; then
      SHIM="$(mktemp -d)/dylib-shim"; mkdir -p "$SHIM"
      ln -sf "$real" "$SHIM/$leaf"
      NODE_ENV_ASSIGNS=("DYLD_FALLBACK_LIBRARY_PATH=$SHIM")
      probe="$(env "${NODE_ENV_ASSIGNS[@]}" "$NODE" --version 2>&1)"
      echo "wata-tests: node shim: aliased $leaf -> $(basename "$real")"
    fi
  fi
  if [[ "$probe" != v* ]]; then
    echo "wata-tests: STOP — node cannot launch on this machine:"; printf '%s\n' "$probe" | head -3
    echo "wata-tests: fix with 'brew reinstall node' (or install the missing dylib), then re-run."
    exit 1
  fi
fi

run_node() { env "${NODE_ENV_ASSIGNS[@]}" NODE_OPTIONS='--experimental-vm-modules' "$NODE" "$@"; }

# ---- build ------------------------------------------------------------------
( cd "$SPIKE/tools/sgo" && go build -o sgo . ) || { echo "wata-tests: sgo build failed"; exit 1; }
echo "wata-tests: building wata-server…"
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "wata-tests: sgo build --app wata-server failed"; exit 1; }
[ -x "$BIN" ] || { echo "wata-tests: $BIN missing after build"; exit 1; }

# ---- server lifecycle -------------------------------------------------------
SPID=""
stop_server() {
  if [ -n "$SPID" ]; then kill -9 "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; fi
  SPID=""
  lsof -ti tcp:"$PORT" 2>/dev/null | xargs kill -9 2>/dev/null
}
start_server() {
  stop_server
  local logenv=()
  if [ -n "$PERSIST" ]; then
    local lf="$PERSIST_DIR/${CUR_SUITE:-suite}.jsonl"
    rm -f "$lf"                       # FRESH per suite: each suite starts clean
    logenv=(WATA_LOG="$lf")
  fi
  env "${logenv[@]}" "$BIN" ":$PORT" >/tmp/wata-tests-server.log 2>&1 &
  SPID=$!
  local i
  for i in $(seq 1 100); do
    curl -s -o /dev/null "$BASE/_matrix/client/versions" 2>/dev/null && return 0
    sleep 0.1
  done
  echo "wata-tests: server never became ready on :$PORT"; cat /tmp/wata-tests-server.log; return 1
}
trap stop_server EXIT

# ---- run one suite, emit scoreboard row -------------------------------------
declare -a ROWS=()
GTOTAL=0; GPASS=0; GFAIL=0
run_suite() {
  local suite="$1"
  CUR_SUITE="$suite"
  local out; out="$(mktemp)"
  start_server || exit 1
  run_node "$JEST_JS" -c "$WATA_TS/test/integration/jest.config.js" \
    "$WATA_TS/test/integration/$suite.test.ts" --runInBand --forceExit >"$out" 2>&1
  stop_server
  local line; line="$(grep -E '^Tests:' "$out" | tail -1)"
  local passed failed total
  passed="$(printf '%s' "$line" | sed -n 's/.* \([0-9]*\) passed.*/\1/p')"; passed="${passed:-0}"
  failed="$(printf '%s' "$line" | sed -n 's/.* \([0-9]*\) failed.*/\1/p')"; failed="${failed:-0}"
  total="$(printf '%s' "$line" | sed -n 's/.* \([0-9]*\) total.*/\1/p')"; total="${total:-0}"
  if [ "$total" = 0 ]; then
    echo "  !! $suite: no Tests: line — jest crashed:"; tail -15 "$out"
  fi
  GPASS=$((GPASS+passed)); GFAIL=$((GFAIL+failed)); GTOTAL=$((GTOTAL+total))
  local mark="ok "; [ "$failed" != 0 ] && mark="FAIL"
  ROWS+=("$(printf '  %-4s %-22s %2s/%-2s passed' "$mark" "$suite" "$passed" "$total")")
  if [ "$failed" != 0 ]; then
    while IFS= read -r t; do ROWS+=("        ✕ $t"); done < <(grep -E '^\s+✕ ' "$out" | sed -E 's/^\s+✕ //; s/ \([0-9]+ ms\)$//')
  fi
  rm -f "$out"
}

echo "wata-tests: fresh wata-server per suite on :$PORT (READ-ONLY TS wata repo at $WATA_TS)"
START=$(date +%s)
if [ -n "$ONLY" ]; then run_suite "$ONLY"; else for s in "${ALL_SUITES[@]}"; do run_suite "$s"; done; fi
END=$(date +%s)

echo
echo "=== wata integration scoreboard (Sgola wata-server binary) ==="
for r in "${ROWS[@]}"; do echo "$r"; done
echo "  ----------------------------------------------"
printf '  TOTAL %2s/%-2s passed  (%s failed)   in %ss\n' "$GPASS" "$GTOTAL" "$GFAIL" "$((END-START))"
[ "$GFAIL" = 0 ] && { echo "wata-tests: GREEN"; exit 0; } || { echo "wata-tests: RED"; exit 1; }
