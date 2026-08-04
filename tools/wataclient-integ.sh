#!/usr/bin/env bash
# The live client-server oracle: run the scenarios in
# wata-fb/src/main/scala/integ.scala against a FRESH wata-server per scenario.
# The server keeps its state in memory and accumulates it across a run, so a
# per-scenario server is what keeps the assertions independent.
#
#   tools/wataclient-integ.sh              # all scenarios, scoreboard
#   tools/wataclient-integ.sh <scenario>   # one scenario
#
# The client driver runs as a `-race` BINARY (built from wata-fb's emitted
# sources) — every scenario exercises the sync loop + action loop + driver
# concurrently, so the whole oracle doubles as a -race gate. Any "DATA RACE" in
# client or server output fails the run.
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                          # SGOLA_HOME (pinned clone by default), GOTOOLCHAIN, SGO
PORT="${INTEG_PORT:-18121}"
BASE="http://127.0.0.1:$PORT"
ONLY="${1:-}"

SCENARIOS=(login-syncing both-sync voice-to-bob receipt-accepted receipt-roundtrip
           multiturn-order redaction download-bytes family-room session-resume)

# ---- build -------------------------------------------------------------------
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "integ: wata-server build failed"; exit 1; }
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "integ: wata-fb build failed"; exit 1; }
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
FB_EMIT="$(emitdir wata-fb)"; WATA_EMIT="$(emitdir wata-server)"
echo "integ: building -race client binary…"
( cd "$FB_EMIT" && go build -race -o wata-fb_race . ) || { echo "integ: go build -race failed"; exit 1; }
CLIENT="$FB_EMIT/wata-fb_race"
SERVER="$WATA_EMIT/$(binname wata-server)"

TMP=$(mktemp -d)
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true; }
trap cleanup EXIT

start_server() { # $1 = scenario (log name)
  "$SERVER" ":$PORT" >"$TMP/server-$1.log" 2>&1 &
  SRV_PID=$!
  for _ in $(seq 1 100); do
    curl -s -o /dev/null "$BASE/_matrix/client/versions" 2>/dev/null && return 0
    sleep 0.1
  done
  echo "integ: server never became ready for $1"; cat "$TMP/server-$1.log"; return 1
}

stop_server() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true
  wait "$SRV_PID" 2>/dev/null || true
  SRV_PID=""
}

pass=0; fail=0; declare -a ROWS=()
for s in "${SCENARIOS[@]}"; do
  [ -n "$ONLY" ] && [ "$s" != "$ONLY" ] && continue
  start_server "$s" || { ROWS+=("FAIL  $s (server)"); fail=$((fail+1)); continue; }
  t0=$(date +%s)
  out="$("$CLIENT" integ "$s" "$BASE" 2>&1)"
  rc_line=$(printf '%s\n' "$out" | grep -c "INTEG PASS $s" || true)
  t1=$(date +%s)
  stop_server
  race=0
  printf '%s' "$out" | grep -q "DATA RACE" && race=1
  grep -q "DATA RACE" "$TMP/server-$s.log" && race=1
  if [ "$rc_line" -ge 1 ] && [ "$race" -eq 0 ]; then
    ROWS+=("ok    $s (${t0:+$((t1-t0))}s)"); pass=$((pass+1))
  else
    [ "$race" -eq 1 ] && ROWS+=("FAIL  $s (DATA RACE)") || ROWS+=("FAIL  $s")
    fail=$((fail+1))
    echo "---- $s output ----"; printf '%s\n' "$out" | tail -20
  fi
done

echo
echo "==== wataclient live oracle (fresh wata-server per scenario, -race client) ===="
for r in "${ROWS[@]}"; do echo "  $r"; done
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
echo "integ: ALL GREEN"
