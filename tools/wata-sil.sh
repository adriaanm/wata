#!/usr/bin/env bash
# GRADUATION H2b (SGOLA STANDS ALONE) — the wata-as-consumer SOURCE-IN-LINK
# byte-identity proof + the publish-idempotence assertions, MIGRATED from the
# sgola gate (old sgola ci steps 11 (a)/(c) + 30) into the repo where the
# consumers live. Assertions preserved verbatim (BUILD E2a/E2b):
#
#   (a) wataclient's COMMITTED payload is IDEMPOTENT: `sgo emit` must refresh
#       it byte-identically — drift = loud (the publish discipline);
#   (b) hermetic file-GOPROXY + pkg/mod for json@v0.2.0 (from the sgola repo's
#       committed json/ artifact — the real module path) AND wataclient@v0.2.0
#       (this repo's committed payload);
#   (c) NORMAL build of each proving app (deps compile from their local dirs —
#       an EMPTY module cache in the env, so no swap; asserted);
#   (d) SOURCE-IN-LINK build (the COMMITTED go.mod requires + the populated
#       cache ARE the trigger — asserted per app);
#   (e) DIFF (c) vs (d): the emitted trees are BYTE-IDENTICAL — wata-server
#       (json direct) AND wata-fb (json + wataclient: fully external
#       provenance);
#   (f) BUILD-6 rides the source payload: a doctored pkg/mod meta (tasty 99.0)
#       fails the SIL build LOUDLY.
#
# Requires $SGOLA_HOME (the wata ci wrapper exports it) and the ci wrapper's
# hermetic cache via $WATA_HERM (optional; a fresh one is made otherwise).
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
: "${SGOLA_HOME:?wata-sil: SGOLA_HOME must be set}"
export SGOLA_HOME
: "${SGO:=$SGOLA_HOME/sgo/sgo}"

JSONMOD="github.com/adriaanm/sgola/json";       JSONVER="v0.2.0"
WCMOD="github.com/adriaanm/wata/wataclient";    WCVER="v0.2.0"
JSONART="$SGOLA_HOME/json"
[ -d "$JSONART/sgola" ] || { echo "wata-sil: sgola committed json/ artifact missing under $SGOLA_HOME" >&2; exit 1; }

# --- (a) wataclient committed-payload idempotence ------------------------------
echo "== wata-sil: (a) wataclient payload refresh (must be byte-identical) =="
WC_SNAP="$(mktemp -d)"
cp -R "$WATA/wataclient/sgola" "$WC_SNAP/sgola.before"
"$SGO" emit "$WATA/wataclient" >/dev/null || { echo "wata-sil: emit wataclient failed"; rm -rf "$WC_SNAP"; exit 1; }
if ! diff -r "$WC_SNAP/sgola.before" "$WATA/wataclient/sgola" >/dev/null 2>&1; then
  echo "wata-sil: wataclient payload DRIFTED from the committed sgola/ tree —" >&2
  echo "  re-emit + commit it here, designer-reviewed" >&2
  diff -r "$WC_SNAP/sgola.before" "$WATA/wataclient/sgola" | head -10 >&2
  rm -rf "$WC_SNAP"; exit 1
fi
rm -rf "$WC_SNAP"
echo "wata-sil: wataclient payload refresh byte-identical to the committed payload"

# --- (b) hermetic file-GOPROXY + pkg/mod ---------------------------------------
echo "== wata-sil: (b) hermetic pkg/mod serving json@$JSONVER + wataclient@$WCVER =="
HERM="${WATA_HERM:-}"
if [ -z "$HERM" ]; then
  HERM="$(mktemp -d)"
  trap 'chmod -R u+w "$HERM" 2>/dev/null || true; rm -rf "$HERM"' EXIT
fi
export GOPATH="$HERM/gopath"
GOMODCACHE_HERM="$GOPATH/pkg/mod"
export GOSUMDB=off GOFLAGS=-mod=mod
PROXY="$SGOLA_HOME/.sgo/bind/proxy"
export GOPROXY="file://$PROXY,off"
mkdir -p "$GOMODCACHE_HERM"
if [ ! -d "$GOMODCACHE_HERM/$JSONMOD@$JSONVER" ] || [ ! -d "$GOMODCACHE_HERM/$WCMOD@$WCVER" ]; then
  rm -rf "$PROXY"; mkdir -p "$PROXY"
  "$SGO" bind-proxy "$JSONART" "$JSONMOD" "$JSONVER" >/dev/null
  "$SGO" bind-proxy "$WATA/wataclient" "$WCMOD" "$WCVER" >/dev/null
  GETDIR="$(mktemp -d)"
  ( cd "$GETDIR" && GOMODCACHE="$GOMODCACHE_HERM" go mod init wata.ci/silget >/dev/null 2>&1 \
      && GOMODCACHE="$GOMODCACHE_HERM" go get "$JSONMOD@$JSONVER" \
      && GOMODCACHE="$GOMODCACHE_HERM" go get "$WCMOD@$WCVER" )
  rm -rf "$GETDIR"
fi
JSONCACHE="$GOMODCACHE_HERM/$JSONMOD@$JSONVER"
test -f "$JSONCACHE/sgola/src/json.scala" || { echo "wata-sil: json sgola/src not in pkg/mod" >&2; exit 1; }
test -f "$GOMODCACHE_HERM/$WCMOD@$WCVER/sgola/src/ogg.scala" || { echo "wata-sil: wataclient sgola/src not in pkg/mod" >&2; exit 1; }
echo "wata-sil: json@$JSONVER + wataclient@$WCVER in the hermetic pkg/mod (payload + sgola/src)"

# An EMPTY module cache for the NORMAL legs: goModCache() falls back to
# `go env GOMODCACHE` (which follows the exported GOPATH), so "no cache" must be
# an empty dir, not an unset var — else the normal build would swap too and the
# byte-identity proof would be vacuous.
EMPTYCACHE="$HERM/empty-modcache"; mkdir -p "$EMPTYCACHE"

# proveApp <app-dir-name> <emitname> <expected-sil-report>
proveApp() {
  local dir="$1" emit="$2" want="$3"
  local EMIT="$WATA/$dir/.sgo/$emit"
  echo "== wata-sil: proving $dir (local-dirs vs source-in-link byte-identity) =="

  local NLOG="$HERM/$dir-normal.log"
  ( cd "$WATA/$dir" && env -u SGOLA_SIL_MODCACHE GOMODCACHE="$EMPTYCACHE" "$SGO" build ) >"$NLOG" 2>&1 || {
    cat "$NLOG" >&2; echo "wata-sil: NORMAL build failed for $dir" >&2; exit 1; }
  if grep -q "source-in-link active" "$NLOG"; then
    cat "$NLOG" >&2; echo "wata-sil: the NORMAL build swapped to pkg/mod ($dir) — the local-dirs leg is not local" >&2; exit 1; fi
  local NORMAL="$HERM/$dir-normal"
  rm -rf "$NORMAL"; cp -R "$EMIT" "$NORMAL"

  local SILLOG="$HERM/$dir-sil.log"
  ( cd "$WATA/$dir" && GOMODCACHE="$GOMODCACHE_HERM" "$SGO" build ) >"$SILLOG" 2>&1 || {
    cat "$SILLOG" >&2; echo "wata-sil: SIL build failed for $dir" >&2; exit 1; }
  grep -q "source-in-link active for \[$want\]" "$SILLOG" || {
    cat "$SILLOG" >&2
    echo "wata-sil: SIL build did not report source-in-link active for [$want] ($dir)" >&2; exit 1; }
  local SIL="$HERM/$dir-sil"
  rm -rf "$SIL"; cp -R "$EMIT" "$SIL"

  if ! diff -r --exclude='.sgo' "$NORMAL" "$SIL" >"$HERM/$dir.diff" 2>&1; then
    echo "wata-sil: BYTE-IDENTITY FAILED for $dir — the source-in-link tree differs:" >&2
    head -40 "$HERM/$dir.diff" >&2
    exit 1
  fi
  echo "wata-sil: $dir emitted tree BYTE-IDENTICAL local-dirs vs source-in-link (only provenance changed)"
}

# wata-server: json direct — the plain source-in-link consumer.
proveApp wata-server wata "$JSONMOD"
# wata-fb: json + wataclient BOTH source-in-link (fully external provenance).
proveApp wata-fb fb "$JSONMOD $WCMOD"

# --- (f) BUILD-6 handshake rides source-in-link --------------------------------
echo "== wata-sil: (f) BUILD-6 handshake on a source payload =="
chmod -R u+w "$JSONCACHE"
cp "$JSONCACHE/sgola/meta" "$HERM/meta.orig"
printf 'sgola v0.1.0-spike\ntasty 99.0\nroot \n' > "$JSONCACHE/sgola/meta"
set +e
BADLOG="$( cd "$WATA/wata-server" && GOMODCACHE="$GOMODCACHE_HERM" "$SGO" build 2>&1 )"
BADRC=$?
set -e
cp "$HERM/meta.orig" "$JSONCACHE/sgola/meta"
if [ $BADRC -eq 0 ]; then
  echo "wata-sil: BUILD-6 NEGATIVE FAILED — a source-in-link payload with tasty 99.0 built" >&2; exit 1; fi
echo "$BADLOG" | grep -q 'BUILD-6' || {
  echo "wata-sil: source-in-link handshake failed for the WRONG reason:" >&2; echo "$BADLOG" | tail -10 >&2; exit 1; }
echo "wata-sil: a source-in-link payload with an out-of-window TASTy 99.0 walls loudly (BUILD-6)"

# Re-arm the normal local-dirs builds so later scenarios see the committed shape.
( cd "$WATA/wata-server" && env -u SGOLA_SIL_MODCACHE GOMODCACHE="$EMPTYCACHE" "$SGO" build ) >/dev/null
( cd "$WATA/wata-fb"     && env -u SGOLA_SIL_MODCACHE GOMODCACHE="$EMPTYCACHE" "$SGO" build ) >/dev/null

echo "wata-sil: all gates green (payload idempotent; wata trees byte-identical local-dirs vs source-in-link; BUILD-6 rides the source payload)"
