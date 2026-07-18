#!/usr/bin/env bash
# GRADUATION H2b (SGOLA STANDS ALONE) — the wata modules' crossing-hatch residue
# audit, MIGRATED from the sgola gate's crossing inventory (which now audits
# only its own corpus). The M9 pinned expectation for the wata modules:
# ZERO blessed crossings in every production-shaped module (wataclient,
# wata-server, wata-fb) — the DRF story's hatch-rate claim. A NEW hatch fails
# this gate; the surface only moves by an explicit expectation edit here.
#
# The crossing checker is DEFAULT-ON; every sgo compile of a wata module writes
# $SGOLA_HOME/.sgo/hello/crossings-<crosskey>.txt (the shared staging under the
# toolchain home). Assumes the suite just built all three modules (the wata ci
# wrapper + wata-sil.sh do).
set -euo pipefail
: "${SGOLA_HOME:?crossing-residue: SGOLA_HOME must be set}"
OUT="$SGOLA_HOME/.sgo/hello"

fail=0
for mod in wataclient wata-server wata-fb; do
  f="$OUT/crossings-$mod.txt"
  if [ ! -f "$f" ]; then
    echo "crossing-residue: MISSING report for $mod ($f) — was the module compiled this run?" >&2
    fail=1; continue
  fi
  tot=$(grep -o 'total-blessed-crossings=[0-9]*' "$f" | head -1 || true)
  if [ "$tot" != "total-blessed-crossings=0" ]; then
    echo "crossing-residue: $mod has a NON-ZERO blessed-crossing residue (${tot:-no total line}):" >&2
    grep -E '^  crossing ' "$f" >&2 || true
    fail=1; continue
  fi
  echo "crossing-residue: $mod total-blessed-crossings=0"
done
[ "$fail" = 0 ] || exit 1
echo "crossing-residue: all wata modules at zero blessed crossings (M9 pinned expectation)"
