#!/usr/bin/env bash
# The Wata scenario suite — the six scenarios that used to be sgola ci steps
# 11–16, preserved VERBATIM (moved with the code in BUILD chunk E2b). This is the
# wata repo's own gate; the sgola gate runs the SAME suite from a hermetic build
# of this repo (its "wata proving consumer" step), so the compiler-regression
# coverage is preserved while the fixtures live where the code lives.
#
# Requires: the sgola toolchain reachable via $SGOLA_HOME (the `sgo` driver on
# PATH or under $SGOLA_HOME/tools/sgo), and the module deps (json, wataclient)
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
  echo "wata-ci: set SGOLA_HOME=/path/to/sgola/spike (or a released sgola home)." >&2
  exit 1
fi
export SGOLA_HOME
# The `sgo` driver: on PATH, else built/located under the toolchain home.
if command -v sgo >/dev/null 2>&1; then
  SGO="$(command -v sgo)"
else
  SGO="$SGOLA_HOME/tools/sgo/sgo"
  ( cd "$SGOLA_HOME/tools/sgo" && go build -o sgo . ) || { echo "wata-ci: failed to build sgo driver"; exit 1; }
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
if [ ! -d "$GOMODCACHE/sgola.spike/json@v0.1.0" ] || [ ! -d "$GOMODCACHE/sgola.spike/wataclient@v0.1.0" ]; then
  echo "wata-ci: populating the hermetic module cache (json + wataclient via file-GOPROXY)"
  rm -rf "$PROXY"; mkdir -p "$PROXY" "$GOMODCACHE"
  "$SGO" bind-proxy "$SGOLA_HOME/json" sgola.spike/json v0.1.0 >/dev/null \
    || { echo "wata-ci: bind-proxy json failed"; exit 1; }
  "$SGO" bind-proxy "$WATA/wataclient" sgola.spike/wataclient v0.1.0 >/dev/null \
    || { echo "wata-ci: bind-proxy wataclient failed"; exit 1; }
  GETDIR="$(mktemp -d)"
  ( cd "$GETDIR" && go mod init wata.ci/get >/dev/null 2>&1 \
      && go get sgola.spike/json@v0.1.0 && go get sgola.spike/wataclient@v0.1.0 ) \
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

echo
echo "================= WATA-CI PASS ================="
for r in "${RESULTS[@]}"; do echo "$r"; done
echo "==============================================="
