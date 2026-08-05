#!/usr/bin/env bash
# The wata-fb build smoke, in two halves.
#   (1) NATIVE build + run: the app compiles and links against the darwin AUDIO
#       STUB with no cgo, no C toolchain, and no device, and runs green (the
#       stub errors loudly on newEncoder → caught → reported).
#   (2) CROSS build for armv7-musl (`--goos linux --goarch arm --goarm 7 --cgo`):
#       proves the cross-cgo pipeline (mklibs → static libopus/libtinyalsa →
#       `go build -a` → a static ARM ELF). NOT run: no test here depends on the
#       device being reachable. GATED on zig — a host without the C
#       cross-toolchain SKIPs this half with a notice rather than failing.
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                          # SGOLA_HOME (pinned clone by default), GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers

echo "-- wata-fb (1/2): native build + run (audio stub) --"
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "native wata-fb build failed"; exit 1; }
FB_EMIT="$(emitdir wata-fb)"; FB_BIN="$(binname wata-fb)"   # from the module markers
OUT="$("$FB_EMIT/$FB_BIN")" || { echo "native wata-fb run failed"; exit 1; }
printf '%s\n' "$OUT"
for want in "wata-fb skeleton" "48000Hz 1ch S16_LE" "state 3 = RUNNING"; do
  printf '%s\n' "$OUT" | grep -qF "$want" || { echo "wata-fb: missing expected line: $want"; exit 1; }
done
# On the host stub the encode path is unavailable; assert we reported it (not crashed).
printf '%s\n' "$OUT" | grep -qF "audio unavailable" || { echo "wata-fb: host stub did not report unavailable audio"; exit 1; }
echo "   native OK (stub path reported cleanly)"

# PNG stored-block selfcheck: Png.zlib chunks the DEFLATE stream at the
# 65535-byte stored-block cap; the golden only ever exercises the single-block
# size, so this holds the multi-block path green (png.scala PngCheck header).
echo "-- wata-fb (1b): PNG stored-block selfcheck (pngtest) --"
PNGOUT="$("$FB_EMIT/$FB_BIN" pngtest)" || { echo "wata-fb pngtest run failed"; exit 1; }
printf '%s\n' "$PNGOUT" | sed 's/^/   /'
printf '%s\n' "$PNGOUT" | grep -qF "pngtest: PASS" || { echo "wata-fb: pngtest did not PASS"; exit 1; }

# -- (1c) the Gio blit pipeline (plan 0023 M2): the RGB565->RGBA conversion and
# the integer nearest-neighbour scaler that go-pkgs/gioshell blits the panel
# with. Pure Go, no window and no GPU — this is the assertion that the window
# path cannot touch frame CONTENT, so it belongs in the gate even though the
# window itself does not (`just phone-blit` runs the GPU-backed draw-path test).
echo "-- wata-fb (1c): gioshell blit pipeline (go test) --"
( cd "$WATA/go-pkgs/gioshell" && go test ./... ) \
  || { echo "go-pkgs/gioshell tests failed"; exit 1; }
echo "   blit pipeline OK"

echo "-- wata-fb (2/2): cross build armv7-musl (cgo opus + tinyalsa) --"
if ! command -v zig >/dev/null 2>&1; then
  echo "   SKIP: zig not installed (cross-cgo needs the C cross-toolchain; no test needs the device)"
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
echo "   cross OK (static armv7 ELF; NOT run — no test needs the device)"

# -- (2c) foreign-container decode guard: cross-COMPILE the go-pkgs/audio test
# suite (foreign_decode_test.go — the pinned TUI-shaped fixture through the
# REAL opus DecodeFrame; the OPUS_BUFFER_TOO_SMALL regression class).
# The real opus is linux/arm cgo only, so ci compiles the test binary (the .a
# libs are fresh from the sgo cross build above) and never runs it — run it on
# the BQ268: scp wata-fb/.sgo/wata-fb/audio-arm.test root@bq268:/dev/shm/ && ssh root@bq268
# 'mount -o remount,exec /dev/shm && /dev/shm/audio-arm.test -test.v'.
# (The container-parsing half of the same fixture DOES run host-side:
# wataclient-tests leg 5/5.)
echo "-- wata-fb (2c): cross-compile go-pkgs/audio foreign-decode test --"
( cd "$WATA/go-pkgs/audio" && CGO_ENABLED=1 GOOS=linux GOARCH=arm GOARM=7 \
    CC="zig cc -target arm-linux-musleabihf" \
    go test -c -o "$FB_EMIT/audio-arm.test" . ) \
  || { echo "cross go test compile failed (go-pkgs/audio)"; exit 1; }
[ -f "$FB_EMIT/audio-arm.test" ] || { echo "audio-arm.test not produced"; exit 1; }
echo "   cross test binary OK ($FB_EMIT/audio-arm.test; run on-device, never at ci)"
