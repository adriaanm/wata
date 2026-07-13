#!/usr/bin/env bash
# wata-fb-smoke.sh — M8 chunk 2, ci step 13: prove the THIRD app.
#   (1) NATIVE build (`sgo build --app wata-fb`) + run: the skeleton compiles and
#       links against the darwin AUDIO STUB with no cgo/C-toolchain/device, and
#       runs green (the stub errors loudly on newEncoder → caught → reported).
#   (2) CROSS build for armv7-musl (`--goos linux --goarch arm --goarm 7 --cgo`):
#       proves the cross-cgo pipeline (mklibs → static libopus/libtinyalsa →
#       `go build -a` → a static ARM ELF). NOT run — the device is never a ci
#       dependency (the brief). GATED on zig: a bare CI host without the C
#       cross-toolchain SKIPs this half with a notice, so `sgo ci` stays
#       hermetic and green everywhere (the core gate never needs zig).
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
# E2b: the suite runs FROM the wata repo; the sgola toolchain rides $SGOLA_HOME.
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set (the sgola toolchain home)"; exit 1; }
SGO="${SGO:-$SGOLA_HOME/tools/sgo/sgo}"
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers (E2b)

echo "-- wata-fb (1/2): native build + run (audio stub) --"
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "native wata-fb build failed"; exit 1; }
FB_EMIT="$(emitdir wata-fb)"; FB_BIN="$(binname wata-fb)"   # .sgo/fb + wata-fb, discovered
OUT="$("$FB_EMIT/$FB_BIN")" || { echo "native wata-fb run failed"; exit 1; }
printf '%s\n' "$OUT"
for want in "wata-fb skeleton" "48000Hz 1ch S16_LE" "state 3 = RUNNING"; do
  printf '%s\n' "$OUT" | grep -qF "$want" || { echo "wata-fb: missing expected line: $want"; exit 1; }
done
# On the host stub the encode path is unavailable; assert we reported it (not crashed).
printf '%s\n' "$OUT" | grep -qF "audio unavailable" || { echo "wata-fb: host stub did not report unavailable audio"; exit 1; }
echo "   native OK (stub path reported cleanly)"

echo "-- wata-fb (2/2): cross build armv7-musl (cgo opus + tinyalsa) --"
if ! command -v zig >/dev/null 2>&1; then
  echo "   SKIP: zig not installed (cross-cgo needs the C cross-toolchain; the device is never a ci dependency)"
  exit 0
fi
( cd "$WATA/wata-fb" && "$SGO" build --goos linux --goarch arm --goarm 7 --cgo \
  --cc "zig cc -target arm-linux-musleabihf" ) >/dev/null || { echo "cross wata-fb build failed"; exit 1; }
BIN="$FB_EMIT/$FB_BIN-linux-arm"    # driver cross-name convention: <binname>-<goos>-<goarch>
[ -f "$BIN" ] || { echo "cross binary not produced: $BIN"; exit 1; }
DESC="$(file "$BIN")"
echo "   $DESC"
printf '%s\n' "$DESC" | grep -qi "ARM" || { echo "cross binary is not ARM"; exit 1; }
printf '%s\n' "$DESC" | grep -qi "statically linked" || { echo "cross binary is not static"; exit 1; }
echo "   cross OK (static armv7 ELF; NOT run — device is never a ci dependency)"

# -- (2c) foreign-container decode guard: cross-COMPILE the go-pkgs/audio test
# suite (foreign_decode_test.go — the pinned TUI-shaped fixture through the
# REAL opus DecodeFrame; the chunk-6 OPUS_BUFFER_TOO_SMALL regression class).
# The real opus is linux/arm cgo only, so ci compiles the test binary (the .a
# libs are fresh from the sgo cross build above) and never runs it — run it on
# the BQ268: scp .sgo/fb/audio-arm.test root@bq268:/dev/shm/ && ssh root@bq268
# 'mount -o remount,exec /dev/shm && /dev/shm/audio-arm.test -test.v'.
# (The container-parsing half of the same fixture DOES run host-side: ci step
# 14, wataclient-tests check 7/7.)
echo "-- wata-fb (2c): cross-compile go-pkgs/audio foreign-decode test --"
( cd "$WATA/go-pkgs/audio" && CGO_ENABLED=1 GOOS=linux GOARCH=arm GOARM=7 \
    CC="zig cc -target arm-linux-musleabihf" \
    go test -c -o "$FB_EMIT/audio-arm.test" . ) \
  || { echo "cross go test compile failed (go-pkgs/audio)"; exit 1; }
[ -f "$FB_EMIT/audio-arm.test" ] || { echo "audio-arm.test not produced"; exit 1; }
echo "   cross test binary OK ($FB_EMIT/audio-arm.test; run on-device, never at ci)"
