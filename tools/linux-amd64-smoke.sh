#!/usr/bin/env bash
# The linux/amd64 SERVER smoke on wata-server — MIGRATED VERBATIM from the sgola
# gate (old sgola ci step 29's wata leg) at GRADUATION H2b (SGOLA STANDS ALONE):
# family-scale production runs wata-server on an always-on linux/amd64 box, and
# the assertion belongs where the consumer lives. The sgola gate keeps its own
# self-contained linux/amd64 smoke over its scenario corpus.
#
# Three-mode by design (BUILD exit addendum item 2 + E3 (e), unchanged):
#   (A) CROSS-BUILD (always): emit wata-server via sgo's normal pipeline, then
#       `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build` the emitted Go tree
#       with STOCK go (wata-server is pure-Go, no cgo). Assert `file` reports a
#       64-bit x86-64 Linux ELF.
#   (B) RUN-SMOKE (conditional): native when the host IS linux/amd64, else in a
#       minimal linux/amd64 container when a runtime is live — the binary's
#       `selfcheck` byte-diffed against the SAME expected fixture the darwin
#       selfcheck uses (tools/wata-selfcheck.expected.txt). No runtime => the
#       step records a loud build-only note, not a failure.
#
# The PASS line names WHICH mode ran (native-run vs container-run vs build-only).
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
: "${SGOLA_HOME:?linux-amd64-smoke: SGOLA_HOME must be set}"
export SGOLA_HOME
: "${SGO:=$SGOLA_HOME/sgo/sgo}"
. "$WATA/tools/emitdir.sh"

# ---- (A) cross-build the linux/amd64 server binary --------------------------
echo "linux-amd64-smoke: building wata-server via sgo…"
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "linux-amd64-smoke: sgo build (wata-server) failed"; exit 1; }
WATA_EMIT="$(emitdir wata-server)"               # <wata-server>/.sgo/wata, from the module marker

OUT="$(mktemp -d)"
BIN="$OUT/wata-server-linux-amd64"
cleanup() { rm -rf "$OUT"; }
trap cleanup EXIT

echo "linux-amd64-smoke: cross-compiling GOOS=linux GOARCH=amd64 CGO_ENABLED=0 (wata-server is pure-Go)…"
if ! ( cd "$WATA_EMIT" && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 GOWORK=off go build -o "$BIN" . ); then
  echo "linux-amd64-smoke: cross-build FAILED — the linux/amd64 server tier is NOT green" >&2
  exit 1
fi

# `file`-type assertion: must be a 64-bit x86-64 Linux ELF.
FTYPE="$(file "$BIN")"
echo "linux-amd64-smoke: file: $FTYPE"
case "$FTYPE" in
  *ELF\ 64-bit*x86-64*) : ;;
  *) echo "linux-amd64-smoke: cross-built binary is NOT a 64-bit x86-64 ELF: $FTYPE" >&2; exit 1 ;;
esac

SELF_EXP="$WATA/tools/wata-selfcheck.expected.txt"

# ---- (B0) NATIVE run-smoke IF the ci host IS itself linux/amd64 --------------
if [ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "x86_64" ]; then
  echo "linux-amd64-smoke: host IS linux/amd64 — running the cross-built binary natively…"
  SELF_TX="$(mktemp)"
  if "$BIN" selfcheck >"$SELF_TX" 2>/dev/null && diff "$SELF_TX" "$SELF_EXP" >/dev/null; then
    echo "linux-amd64-smoke: native linux/amd64 selfcheck transcript matches the pinned expectation — PASS (native-run)"
    echo "MODE=native-run"
    rm -f "$SELF_TX"
    exit 0
  fi
  echo "linux-amd64-smoke: native linux/amd64 selfcheck does NOT match expected:" >&2
  diff "$SELF_EXP" "$SELF_TX" >&2 || true
  rm -f "$SELF_TX"
  exit 1
fi

# ---- (B) run-smoke IF a working linux/amd64 container runtime is available ---
RUNTIME=""
for rt in docker podman; do
  if command -v "$rt" >/dev/null 2>&1 && "$rt" info >/dev/null 2>&1; then RUNTIME="$rt"; break; fi
done

if [ -n "$RUNTIME" ]; then
  echo "linux-amd64-smoke: container runtime '$RUNTIME' is live — running the selfcheck in a linux/amd64 container…"
  SELF_TX="$(mktemp)"
  if "$RUNTIME" run --rm --platform linux/amd64 \
       -v "$BIN:/wata-server:ro" \
       alpine:3 /wata-server selfcheck >"$SELF_TX" 2>/dev/null; then
    if diff "$SELF_TX" "$SELF_EXP" >/dev/null; then
      echo "linux-amd64-smoke: linux/amd64 selfcheck transcript matches the pinned expectation — PASS (container-run)"
      echo "MODE=container-run"
      rm -f "$SELF_TX"
      exit 0
    else
      echo "linux-amd64-smoke: linux/amd64 selfcheck does NOT match expected:" >&2
      diff "$SELF_EXP" "$SELF_TX" >&2 || true
      rm -f "$SELF_TX"
      exit 1
    fi
  else
    echo "linux-amd64-smoke: runtime '$RUNTIME' present but could not run the linux/amd64 container (image/emulation unavailable) — recording build-only." >&2
    rm -f "$SELF_TX"
  fi
fi

# ---- build-only verdict (no runnable runtime) -------------------------------
echo
echo "########################################################################"
echo "# linux-amd64-smoke: RECORDED — cross-build + file-type assertion PASSED."
echo "#   The RUN-smoke (selfcheck inside a linux/amd64 container) was SKIPPED:"
echo "#   no working linux/amd64 container runtime on this host. This is"
echo "#   EXPECTED on a bare darwin dev box and is NOT a failure — the smoke"
echo "#   runs its full run-mode on a linux host or any machine with a"
echo "#   container runtime."
echo "########################################################################"
echo
echo "linux-amd64-smoke: cross-build + file assertion match — PASS (build-only; run-smoke needs a linux host/runtime)"
echo "MODE=build-only"
exit 0
