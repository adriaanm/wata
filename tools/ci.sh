#!/usr/bin/env bash
# The Wata suite — the wata repo's OWN gate (GRADUATION H2b: SGOLA STANDS
# ALONE — sgola's gate is self-contained and never runs this; the
# chunk-boundary law is TWO gates, two repos). Scenarios 1–6 are the six
# scenarios that used to be sgola ci steps 11–16, preserved VERBATIM (moved
# with the code in BUILD chunk E2b); steps 7–9 are the wata-as-consumer legs
# of sgola's old steps 11/29/30, migrated here at H2b (source-in-link
# byte-identity + payload idempotence, the wata-server linux/amd64 smoke, and
# the wata modules' crossing residue).
#
# Requires: the sgola toolchain reachable via $SGOLA_HOME (the `sgo` driver on
# PATH or under $SGOLA_HOME/sgo), and the module deps (json, wataclient)
# resolved into a Go module cache (the caller — the wata ci wrapper or the sgola
# proving-consumer step — populates a hermetic file-GOPROXY + pkg/mod; a
# developer with the real proxies gets the same via plain `go get`).
#
#   tools/ci.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"

# The sgola toolchain home (the v1 packaging stand-in — README shortcut).
if [ -z "${SGOLA_HOME:-}" ]; then
  echo "wata-ci: SGOLA_HOME is not set — the wata suite needs the sgola toolchain." >&2
  echo "wata-ci: set SGOLA_HOME=/path/to/sgola (or a released sgola home)." >&2
  exit 1
fi
export SGOLA_HOME
# The `sgo` driver: on PATH, else built/located under the toolchain home.
if command -v sgo >/dev/null 2>&1; then
  SGO="$(command -v sgo)"
else
  SGO="$SGOLA_HOME/sgo/sgo"
  ( cd "$SGOLA_HOME/sgo" && go build -o sgo . ) || { echo "wata-ci: failed to build sgo driver"; exit 1; }
fi
export SGO

# ---- dep resolution: the requires are REAL (BUILD E2b) ----------------------
# Every wata module's Sgola deps are go.mod `require` lines (json from the sgola
# tree's published module; wataclient from THIS repo's published payload),
# resolved through a hermetic file-GOPROXY into a module cache — `sgo build`
# takes them SOURCE-IN-LINK from pkg/mod. A developer with real proxies
# configured gets the same via plain `go get` (README shortcut). The caller may
# pre-set WATA_HERM to reuse a cache (the sgola proving-consumer step does).
if [ -z "${WATA_HERM:-}" ]; then
  WATA_HERM="$(mktemp -d)"
  trap 'chmod -R u+w "$WATA_HERM" 2>/dev/null || true; rm -rf "$WATA_HERM"' EXIT
fi
export GOPATH="$WATA_HERM/gopath"
export GOMODCACHE="$GOPATH/pkg/mod"
export GOSUMDB=off GOFLAGS=-mod=mod
PROXY="$SGOLA_HOME/.sgo/bind/proxy"
export GOPROXY="file://$PROXY,off"
# GRADUATION H2b: the requires graduated to the REAL module paths —
# github.com/adriaanm/sgola/json (served from the sgola repo's COMMITTED
# top-level json/ artifact) + github.com/adriaanm/wata/wataclient (this repo's
# committed payload) — resolved LOCALLY via the file-GOPROXY until the repos
# have fetchable remotes (the recorded insteadOf/GOPROXY posture is the release
# path; the hermetic proxy is the ci path).
JSONMOD="github.com/adriaanm/sgola/json";    JSONVER="v0.2.0"
WCMOD="github.com/adriaanm/wata/wataclient"; WCVER="v0.2.0"
if [ ! -d "$GOMODCACHE/$JSONMOD@$JSONVER" ] || [ ! -d "$GOMODCACHE/$WCMOD@$WCVER" ]; then
  echo "wata-ci: populating the hermetic module cache (json + wataclient via file-GOPROXY)"
  rm -rf "$PROXY"; mkdir -p "$PROXY" "$GOMODCACHE"
  [ -d "$SGOLA_HOME/json/sgola" ] || { echo "wata-ci: sgola committed json/ artifact missing under $SGOLA_HOME"; exit 1; }
  "$SGO" bind-proxy "$SGOLA_HOME/json" "$JSONMOD" "$JSONVER" >/dev/null \
    || { echo "wata-ci: bind-proxy json failed"; exit 1; }
  "$SGO" bind-proxy "$WATA/wataclient" "$WCMOD" "$WCVER" >/dev/null \
    || { echo "wata-ci: bind-proxy wataclient failed"; exit 1; }
  GETDIR="$(mktemp -d)"
  ( cd "$GETDIR" && go mod init wata.ci/get >/dev/null 2>&1 \
      && go get "$JSONMOD@$JSONVER" && go get "$WCMOD@$WCVER" ) \
    || { echo "wata-ci: go get into the hermetic cache failed"; exit 1; }
  rm -rf "$GETDIR"
fi

declare -a RESULTS
banner() { echo; echo "======== [wata $1] $2 ========"; }
fail()   { echo; echo "## WATA-CI FAILED at $1: $2"; exit 1; }
pass()   { RESULTS+=("  $1  PASS  $2"); }

banner 1 "wata-server smoke (selfcheck + live Matrix session + long-poll + -race)"
bash tools/wata-smoke.sh || fail 1 "wata-smoke.sh"
pass 1 "wata-server selfcheck + live Matrix session + long-poll concurrency, -race clean"

banner 2 "wata-server persistence restart smoke (kill+reboot from JSONL)"
bash tools/wata-persist-smoke.sh || fail 2 "wata-persist-smoke.sh"
pass 2 "wata-server state survives kill+reboot from the JSONL log"

banner 3 "wata-fb native (audio stub) + armv7 cross-cgo build"
bash tools/wata-fb-smoke.sh || fail 3 "wata-fb-smoke.sh"
pass 3 "wata-fb native (stub) + armv7-musl cross-cgo build"

banner 4 "wataclient library (tripwire + emit + JVM/sgola oracles + sync/fixture)"
bash tools/wataclient-tests.sh || fail 4 "wataclient-tests.sh"
pass 4 "wataclient go-free + JVM/sgola byte-oracles + sync unit/fixture oracles"

banner 5 "wataclient live oracle (10 scenarios, fresh server each, -race client)"
bash tools/wataclient-integ.sh || fail 5 "wataclient-integ.sh"
pass 5 "10/10 live scenarios, fresh server each, -race clean"

banner 6 "wata-fb framebuffer golden frame"
bash tools/fb-golden.sh || fail 6 "fb-golden.sh"
pass 6 "framebuffer golden frame byte-identical (160x128 RGB565 -> PNG)"

# ---- 7–9: the legs MIGRATED from the sgola gate (GRADUATION H2b — SGOLA ------
# STANDS ALONE: sgola's ci carries no wata dependency; the wata-as-consumer
# assertions its old steps 11/29/30 made live HERE, where the consumers live).

banner 7 "source-in-link byte-identity + payload idempotence (tools/wata-sil.sh)"
WATA_HERM="$WATA_HERM" bash tools/wata-sil.sh || fail 7 "wata-sil.sh"
pass 7 "wataclient payload idempotent; wata trees BYTE-IDENTICAL local-dirs vs source-in-link; BUILD-6 rides the source payload"

banner 8 "linux/amd64 server smoke (tools/linux-amd64-smoke.sh)"
LA_TX="$(mktemp)"
if bash tools/linux-amd64-smoke.sh >"$LA_TX" 2>&1; then
  cat "$LA_TX"
  LA_MODE="$(awk -F= '/^MODE=/{print $2}' "$LA_TX")"
  rm -f "$LA_TX"
  pass 8 "linux/amd64 server: cross-build (x86-64 Linux ELF) + selfcheck (mode: $LA_MODE)"
else
  cat "$LA_TX"; rm -f "$LA_TX"
  fail 8 "linux-amd64-smoke.sh"
fi

banner 9 "crossing-hatch residue: zero blessed crossings in the wata modules (tools/crossing-residue.sh)"
bash tools/crossing-residue.sh || fail 9 "crossing-residue.sh"
pass 9 "wataclient/wata-server/wata-fb at zero blessed crossings (M9 pinned)"

echo
echo "================= WATA-CI PASS ================="
for r in "${RESULTS[@]}"; do echo "$r"; done
echo "==============================================="
