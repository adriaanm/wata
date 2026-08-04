#!/usr/bin/env bash
# ONE command from .scala to a running armv7-musl binary on the BQ268.
# Cross-cgo-builds wata-fb via `sgo`, scp's it to /dev/shm
# (the 192MB tmpfs — root FS has ~9MB free), remounts /dev/shm exec (it is
# noexec by default; non-persistent, gone on reboot), runs it, echoes the
# output, and cleans up. Nothing is installed to /opt/wata: device state stays
# clean, and the run does not touch the speaker or mic.
#
#   tools/fb-deploy.sh                 # build + deploy + run the app (default)
#   tools/fb-deploy.sh fbsmoke         # device smoke: draw the test
#                                      #   pattern to /dev/fb0, blink the LEDs,
#                                      #   echo evdev keys ~20s ([HUMAN-VERIFY]:
#                                      #   pattern on the panel + keys register)
#   BQ268_HOST=192.168.1.9 tools/fb-deploy.sh fbsmoke
#
# The device is ssh host `bq268` (wifi/DHCP — if it is unreachable, update
# ~/.ssh/config HostName). A failed ssh exits non-zero with a clear banner
# rather than silently "passing".
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
HOST="${BQ268_HOST:-bq268}"
CC="${FB_CC:-zig cc -target arm-linux-musleabihf}"
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
BIN="$(emitdir wata-fb)/wata-fb-linux-arm"
REMOTE="/dev/shm/wata-fb"
FB_CMD="${1:-}"   # e.g. `fbsmoke`; empty = plain run

echo "== fb-deploy: cross-build wata-fb (armv7-musl) =="
( cd "$WATA/wata-fb" && "$SGO" build --goos linux --goarch arm --goarm 7 --cgo --cc "$CC" )
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
