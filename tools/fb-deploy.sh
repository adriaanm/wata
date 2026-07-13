#!/usr/bin/env bash
# fb-deploy.sh — M8 chunk 2: ONE command from .scala to a running armv7-musl
# binary on the BQ268. Cross-cgo-builds wata-fb via `sgo`, scp's it to /dev/shm
# (the 192MB tmpfs — root FS has ~9MB free), remounts /dev/shm exec (it is
# noexec by default; non-persistent, gone on reboot), runs it, echoes the
# output, and cleans up. NO /opt/wata install — that is the exit gate (chunk 7)
# ONLY. Device state stays clean (the skeleton does not touch the speaker/mic).
#
#   tools/fb-deploy.sh                 # build + deploy + run the skeleton (default)
#   tools/fb-deploy.sh fbsmoke         # M8 chunk 5 device smoke: draw the test
#                                      #   pattern to /dev/fb0, blink the LEDs,
#                                      #   echo evdev keys ~20s ([HUMAN-VERIFY]:
#                                      #   pattern on the panel + keys register)
#   BQ268_HOST=192.168.1.9 tools/fb-deploy.sh fbsmoke
#
# Device rules (M8-BRIEF): ssh host `bq268` (wifi/DHCP — if unreachable, STOP;
# Adriaan updates ~/.ssh/config HostName). If the ssh fails, this exits non-zero
# with a clear banner rather than silently "passing".
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set"; exit 1; }
# E2b NOTE: moved from the sgola tree with the wata code. Dev tool, NOT in
# either ci gate; needs $SGOLA_HOME (the sgola toolchain) and builds the wata
# modules from THIS repo. Emission stays under $SGOLA_HOME/.sgo/ (E2b shortcut).

SGO="$SGOLA_HOME/tools/sgo/sgo"
HOST="${BQ268_HOST:-bq268}"
CC="${FB_CC:-zig cc -target arm-linux-musleabihf}"
BIN="$SPIKE/$SGOLA_HOME/.sgo/fb/wata-fb-linux-arm"
REMOTE="/dev/shm/wata-fb"
FB_CMD="${1:-}"   # e.g. `fbsmoke` (chunk 5); empty = the skeleton

echo "== fb-deploy: cross-build wata-fb (armv7-musl) =="
( cd "$SPIKE/tools/sgo" && go build -o sgo . )
( cd "$WATA/wata-fb" && "$SGO" build ) --goos linux --goarch arm --goarm 7 --cgo --cc "$CC"
ls -la "$BIN"; file "$BIN" || true

echo "== fb-deploy: scp -> $HOST:$REMOTE =="
if ! scp -q "$BIN" "root@$HOST:$REMOTE"; then
  echo "################################################"
  echo "## fb-deploy FAILED: cannot scp to $HOST (wifi/DHCP? update ~/.ssh/config HostName)"
  echo "################################################"
  exit 1
fi

echo "== fb-deploy: remount /dev/shm exec + run ($REMOTE $FB_CMD) =="
# /dev/shm is noexec by default (README shortcut); remount, run, remove. The
# smoke ($FB_CMD=fbsmoke) runs ~20s+ polling input — stream its output live.
ssh "root@$HOST" "mount -o remount,exec /dev/shm && chmod +x $REMOTE && $REMOTE $FB_CMD; rc=\$?; rm -f $REMOTE; exit \$rc"
echo "== fb-deploy: done (binary removed from /dev/shm) =="
