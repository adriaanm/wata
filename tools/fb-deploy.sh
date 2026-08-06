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
# IROH MODE (plan 0014). Setting BQ268_IROH_PEER provisions the device's iroh
# config at /etc/wata/iroh.json — server node id, relay, and the admin base URL
# the enrolment QR encodes — and runs this deploy against it:
#
#   BQ268_IROH_PEER=<server node id> WATA_ADMIN_URL=http://192.168.1.4:8008 \
#     tools/fb-deploy.sh
#
# The file carries NO secret: the handset mints its own key into it on first
# boot (irohnet.EnsureKey) and only its public node id ever leaves. An existing
# config is left alone, so re-deploying never re-mints an identity that is
# already enrolled. This deploy is a RUN, not an install — making iroh the
# device's permanent transport is a separate, deliberate edit on the hardware;
# see the deploy section of docs/design/wata-fb.md.
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
if [ -n "${BQ268_IROH_PEER:-}" ]; then
  # The sgo cross-build produces the STUB transport; iroh mode needs the
  # -tags iroh binary linked against a current arm staticlib (the clib is
  # gitignored and goes stale whenever the FFI changes — a stale one either
  # fails to link or, worse, deploys yesterday's transport). mklib is a
  # cargo build: cached, cheap when nothing changed.
  echo "== fb-deploy: iroh build (fresh arm staticlib, then -tags iroh) =="
  python3 "$WATA/go-pkgs/irohnet/mklib.py" arm
  ( cd "$(emitdir wata-fb)" && env GOWORK=off GOOS=linux GOARCH=arm GOARM=7 \
      CGO_ENABLED=1 CC="$CC" go build -tags iroh -o "$BIN" . )
fi
ls -la "$BIN"; file "$BIN" || true

echo "== fb-deploy: scp -> $HOST:$REMOTE =="
if ! scp -q "$BIN" "root@$HOST:$REMOTE"; then
  echo "################################################"
  echo "## fb-deploy FAILED: cannot scp to $HOST (wifi/DHCP? update ~/.ssh/config HostName)"
  echo "################################################"
  exit 1
fi

RUN_ENV=""
if [ -n "${BQ268_IROH_PEER:-}" ]; then
  : "${WATA_ADMIN_URL:?BQ268_IROH_PEER needs WATA_ADMIN_URL (the base URL the enrolment QR encodes)}"
  REMOTE_IROH="/etc/wata/iroh.json"
  echo "== fb-deploy: iroh mode -> $REMOTE_IROH (no secret; the device mints its own) =="
  ssh "root@$HOST" "mkdir -p /etc/wata; [ -f $REMOTE_IROH ] || printf '%s\n' \
    '{\"peer\":\"$BQ268_IROH_PEER\",\"relay\":\"${BQ268_IROH_RELAY:-n0}\",\"adminUrl\":\"$WATA_ADMIN_URL\"}' \
    > $REMOTE_IROH; chmod 600 $REMOTE_IROH; cat $REMOTE_IROH"
  RUN_ENV="env WATA_IROH_CONFIG=$REMOTE_IROH WATA_ADMIN_URL=$WATA_ADMIN_URL"
fi

echo "== fb-deploy: remount /dev/shm exec + run ($REMOTE $FB_CMD) =="
# /dev/shm is noexec by default (README shortcut); remount, run, remove. The
# smoke ($FB_CMD=fbsmoke) runs ~20s+ polling input — stream its output live.
ssh "root@$HOST" "mount -o remount,exec /dev/shm && chmod +x $REMOTE && $RUN_ENV $REMOTE $FB_CMD; rc=\$?; rm -f $REMOTE; exit \$rc"
echo "== fb-deploy: done (binary removed from /dev/shm) =="
