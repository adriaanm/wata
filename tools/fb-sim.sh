#!/usr/bin/env bash
# The HOST SIMULATOR: the real wata-fb frame loop, drawn into this terminal.
#
#   just fb-sim [BASE [USER [PASS]]]        (default http://127.0.0.1:8008 alice)
#
# All this wrapper owns is the terminal mode — raw, no echo, non-blocking reads
# (`min 0 time 0`, which is what makes the sim's per-frame stdin poll return
# immediately) — and restoring it on every exit path. Everything else is
# `wata-fb sim` (sim.scala). Without a tty (a script, ci) it renders ONE frame
# and returns, so the recipe stays exercisable unattended.
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
BASE="${1:-${WATA_SIM_BASE:-http://127.0.0.1:8008}}"
SIMUSER="${2:-alice}"
SIMPASS="${3:-testpass123}"

( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "fb-sim: wata-fb build failed"; exit 1; }
FB="$(emitdir wata-fb)/$(binname wata-fb)"

if [ ! -t 0 ] || [ ! -t 1 ]; then
  echo "fb-sim: not attached to a terminal — rendering ONE frame, then exiting."
  echo "fb-sim: run it from an interactive shell for the live client."
  exec "$FB" sim "$BASE" "$SIMUSER" "$SIMPASS" --once
fi

SAVED="$(stty -g)"
restore() { stty "$SAVED"; printf '\033[?25h\033[0m\n'; }
trap restore EXIT INT TERM
stty raw -echo min 0 time 0
"$FB" sim "$BASE" "$SIMUSER" "$SIMPASS"
