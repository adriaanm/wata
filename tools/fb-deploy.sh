#!/usr/bin/env bash
# ONE command from .scala to a running armv7-musl binary on the BQ268.
# Cross-cgo-builds wata-fb via `sgo`, scp's it to /dev/shm
# (the 192MB tmpfs — root FS has ~9MB free), remounts /dev/shm exec (it is
# noexec by default; non-persistent, gone on reboot), runs it, echoes the
# output, and cleans up. Nothing is installed to /opt/wata: device state stays
# clean, and the run does not touch the speaker or mic.
#
# The startup chirp (wata-fb/assets/chirp.ogg) ships alongside the binary in
# both modes — beside it in /dev/shm for a run (WATA_CHIRP points the app at
# it), and as /opt/wata/chirp.ogg for an install, where the app finds it by
# default.
#
#   tools/fb-deploy.sh                 # build + deploy + run the app (default)
#   tools/fb-deploy.sh install         # replace /opt/wata/wata-fb — the binary
#                                      #   tty1 respawns — and restart it. The
#                                      #   iroh build, the previous binary kept
#                                      #   as wata-fb.prev.
#   tools/fb-deploy.sh fbsmoke         # device smoke: draw the test
#                                      #   pattern to /dev/fb0, blink the LEDs,
#                                      #   echo evdev keys ~20s ([HUMAN-VERIFY]:
#                                      #   pattern on the panel + keys register)
#   BQ268_HOST=192.168.1.9 tools/fb-deploy.sh fbsmoke
#
# IROH MODE (plan 0014). Setting BQ268_IROH_PEER provisions the device's iroh
# config at /etc/wata/iroh.json — server node id and relay — and runs this
# deploy against it:
#
#   BQ268_IROH_PEER=<server node id> tools/fb-deploy.sh
#
# The enrolment QR then points at http://wata.local:8008, the Bonjour name the
# server install publishes (server-service.py). Set WATA_ADMIN_URL to pin a
# different admin base URL instead (it is baked into the config AND exported
# for the run).
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
CHIRP="$WATA/wata-fb/assets/chirp.ogg"            # the startup bleep, shipped beside the binary
REMOTE_CHIRP="/dev/shm/chirp.ogg"
FB_CMD="${1:-}"   # e.g. `fbsmoke`; empty = plain run
# `install` is not a run mode: it replaces the binary tty1 respawns
# (/opt/wata/wata-fb) instead of running one out of /dev/shm. It implies the
# iroh build — an installed handset's transport is iroh — and it never touches
# /etc/wata/iroh.json, which carries the device's minted identity.
INSTALL=0
if [ "$FB_CMD" = "install" ]; then INSTALL=1; FB_CMD=""; fi

echo "== fb-deploy: cross-build wata-fb (armv7-musl) =="
( cd "$WATA/wata-fb" && "$SGO" build --goos linux --goarch arm --goarm 7 --cgo --cc "$CC" )
if [ -n "${BQ268_IROH_PEER:-}" ] || [ "$INSTALL" = 1 ]; then
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

if [ "$INSTALL" = 1 ]; then
  # Land it beside the running one, rotate, then kill the running app: tty1
  # respawns /opt/wata/start.sh, so the new binary is up within a second and
  # the previous one is one `mv` away.
  echo "== fb-deploy: install -> $HOST:/opt/wata/wata-fb (previous kept as .prev) =="
  scp -q "$BIN" "root@$HOST:/opt/wata/wata-fb.new"
  scp -q "$CHIRP" "root@$HOST:/opt/wata/chirp.ogg"
  # `pkill -f` matches the ssh session's OWN command line, so no part of this
  # remote command may carry the literal text "wata-fb ui" — or pkill kills
  # the shell mid-install and the deploy exits 255 having done the work. The
  # bracket keeps BOTH the pkill pattern and the verifying grep from matching
  # the command line while still matching the process.
  ssh "root@$HOST" "cd /opt/wata && chmod +x wata-fb.new && mv -f wata-fb wata-fb.prev && \
    mv wata-fb.new wata-fb && sync && pkill -f 'wata-fb[ ]ui'; sleep 2; ls -la /opt/wata; \
    ps aux | grep 'wata-fb[ ]ui' || true"
  echo "== fb-deploy: installed (tty1 respawned it) =="
  exit 0
fi

echo "== fb-deploy: scp -> $HOST:$REMOTE (+ chirp.ogg) =="
if ! scp -q "$BIN" "$CHIRP" "root@$HOST:/dev/shm/"; then
  echo "################################################"
  echo "## fb-deploy FAILED: cannot scp to $HOST (wifi/DHCP? update ~/.ssh/config HostName)"
  echo "################################################"
  exit 1
fi

# the app looks for the chirp beside the INSTALLED binary; a run out of
# /dev/shm has to be told where it landed.
RUN_ENV="WATA_CHIRP=$REMOTE_CHIRP"
if [ -n "${BQ268_IROH_PEER:-}" ]; then
  REMOTE_IROH="/etc/wata/iroh.json"
  IROH_JSON="{\"peer\":\"$BQ268_IROH_PEER\",\"relay\":\"${BQ268_IROH_RELAY:-n0}\"}"
  RUN_ENV="$RUN_ENV WATA_IROH_CONFIG=$REMOTE_IROH"
  if [ -n "${WATA_ADMIN_URL:-}" ]; then
    IROH_JSON="{\"peer\":\"$BQ268_IROH_PEER\",\"relay\":\"${BQ268_IROH_RELAY:-n0}\",\"adminUrl\":\"$WATA_ADMIN_URL\"}"
    RUN_ENV="$RUN_ENV WATA_ADMIN_URL=$WATA_ADMIN_URL"
  fi
  echo "== fb-deploy: iroh mode -> $REMOTE_IROH (no secret; the device mints its own) =="
  ssh "root@$HOST" "mkdir -p /etc/wata; [ -f $REMOTE_IROH ] || printf '%s\n' \
    '$IROH_JSON' \
    > $REMOTE_IROH; chmod 600 $REMOTE_IROH; cat $REMOTE_IROH"
fi

echo "== fb-deploy: remount /dev/shm exec + run ($REMOTE $FB_CMD) =="
# /dev/shm is noexec by default (README shortcut); remount, run, remove. The
# smoke ($FB_CMD=fbsmoke) runs ~20s+ polling input — stream its output live.
ssh "root@$HOST" "mount -o remount,exec /dev/shm && chmod +x $REMOTE && env $RUN_ENV $REMOTE $FB_CMD; rc=\$?; rm -f $REMOTE $REMOTE_CHIRP; exit \$rc"
echo "== fb-deploy: done (binary removed from /dev/shm) =="
