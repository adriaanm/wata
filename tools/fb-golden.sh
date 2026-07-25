#!/usr/bin/env bash
# fb-golden.sh — M8 chunk 5, ci step 16: the HEADLESS-HOST golden-frame oracle
# (decision 4). Builds wata-fb NATIVE, runs the deterministic draw script
# (`wata-fb fbdump`) which encodes the RGB565 test pattern to a byte-stable PNG
# (a single stored-DEFLATE block — Go-version-independent) and writes the raw
# PNG bytes to stdout, then byte-compares against the checked-in golden
# `tools/fb-golden.png`. The golden is regenerated designer-reviewed like a
# baseline: `.sgo/fb/wata-fb fbdump > tools/fb-golden.png` then eyeball it.
# Host-only, hermetic — no device, no cgo (the draw layer + PNG encoder are
# pure Sgola; the syscall.Write(1,...) sink exists on darwin too).
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                          # SGOLA_HOME (pinned clone by default), GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers (E2b)
GOLDEN="$WATA/tools/fb-golden.png"

echo "-- fb-golden: build wata-fb (native) + draw the test pattern --"
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "fb-golden: wata-fb build failed"; exit 1; }
FB_EMIT="$(emitdir wata-fb)"; GOT="$FB_EMIT/frame.png"
"$FB_EMIT/$(binname wata-fb)" fbdump > "$GOT" || { echo "fb-golden: fbdump run failed"; exit 1; }

# The dump must be a real PNG of the right geometry (guards a silent empty write).
DESC="$(file "$GOT")"
echo "   $DESC"
printf '%s\n' "$DESC" | grep -qi "PNG image data, 160 x 128" || {
  echo "fb-golden: fbdump did not produce a 160x128 PNG"; exit 1; }

if [ ! -f "$GOLDEN" ]; then
  echo "fb-golden: no golden yet — commit $GOT as tools/fb-golden.png (designer-reviewed)"; exit 1
fi
if ! cmp -s "$GOT" "$GOLDEN"; then
  echo "################################################"
  echo "## fb-golden MISMATCH: $GOT != $GOLDEN"
  echo "## (a draw-layer/font/PNG change; if intended, regenerate the golden:"
  echo "##   <emitdir>/wata-fb fbdump > tools/fb-golden.png   — designer-reviewed)"
  echo "################################################"
  exit 1
fi
echo "   golden-frame byte-identical ($(wc -c < "$GOLDEN" | tr -d ' ') bytes)"
