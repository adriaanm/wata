#!/usr/bin/env bash
# Capture /sync FIXTURES from a live wata-server for the wataclient sync-engine
# fixture oracle (`just client-tests`, leg 3/5). Mechanical regeneration:
#
#   tools/wataclient-fixtures.sh          # rewrites wataclient/test-fixtures/*.json
#
# Provenance: boots a wata-server built from THIS tree (fresh in-memory
# state), drives a scripted two-user session (alice+bob, the wata-smoke.sh
# credentials), and saves the VERBATIM /sync response bodies. The fixtures are
# checked in; regenerating changes volatile ids/timestamps, so the expected
# oracle output (tools/wataclient-fixtures.expected.txt) must be re-pinned after
# a regen (designer-reviewed, like baselines).
#
# Session (in order):
#   alice: login, set displayname, resolve the DM with bob (the dialect endpoint)
#   alice: /sync initial                       -> alice__01-initial.json
#   bob:   login, join room
#   alice: upload voice bytes, send m.audio, send m.text
#   alice: /sync since=S1                      -> alice__02-incr.json
#   bob:   receipt m.read on the audio event
#   alice: /sync since=S2                      -> alice__03-incr.json
#   bob:   /sync initial                       -> bob__01-initial.json
set -euo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
FIXDIR="$WATA/wataclient/test-fixtures"
# A RANDOM port per run (FIXTURES_PORT overrides) — a fixed port collides with
# a sibling checkout's harnesses (see tools/wataclient-integ.sh).
PORT="${FIXTURES_PORT:-$(( 20000 + (RANDOM % 20000) ))}"
BASE="http://127.0.0.1:$PORT"

mkdir -p "$FIXDIR"
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null

. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
TMP=$(mktemp -d); LOG="$TMP/server.log"
"$(emitdir wata-server)/$(binname wata-server)" ":$PORT" >"$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT

ready=0
for _ in $(seq 1 100); do
  # OUR process must be THE LISTENER (a bind loser exits zero, and a foreign
  # squatter would answer a bare curl probe; see tools/wataclient-integ.sh)
  kill -0 "$PID" 2>/dev/null || { echo "fixtures: server exited (port :$PORT taken?)"; cat "$LOG"; exit 1; }
  if lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null | grep -qx "$PID"; then
    if curl -s -o /dev/null "$BASE/_matrix/client/versions" 2>/dev/null; then ready=1; break; fi
  fi
  sleep 0.1
done
[ "$ready" -eq 1 ] || { echo "fixtures: server never became ready"; cat "$LOG"; exit 1; }

jget() { python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])'; }

# ---- logins ------------------------------------------------------------------
ALOGIN=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"alice"},"password":"testpass123"}')
ATOK=$(printf '%s' "$ALOGIN" | jget access_token)
A=(-H "Authorization: Bearer $ATOK")
BLOGIN=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"bob"},"password":"testpass123"}')
BTOK=$(printf '%s' "$BLOGIN" | jget access_token)
B=(-H "Authorization: Bearer $BTOK")

# display names (exercises the snapshot's display-name resolution)
curl -s -X PUT "${A[@]}" -d '{"displayname":"Alice W"}' \
  "$BASE/_matrix/client/v3/profile/@alice:localhost/displayname" >/dev/null
curl -s -X PUT "${B[@]}" -d '{"displayname":"Bob B"}' \
  "$BASE/_matrix/client/v3/profile/@bob:localhost/displayname" >/dev/null

# ---- the canonical DM ----------------------------------------------------------
# One idempotent call: the server mints the room, stamps net.wata.dm + the
# canonical alias, joins alice and invites bob, and derives the compat m.direct.
ROOMID=$(curl -s -X POST "${A[@]}" "$BASE/_wata/v1/dm/bob" | jget room_id)

# ---- alice initial sync ---------------------------------------------------------
curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" > "$FIXDIR/alice__01-initial.json"
S1=$(jget next_batch < "$FIXDIR/alice__01-initial.json")

# ---- bob joins; alice sends voice (m.audio) + text ------------------------------
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOMID/join" >/dev/null
printf 'OggS\x00\x02\x00\xff\xfe\x01\x02\x03VOICE-FIXTURE' > "$TMP/voice.ogg"
CU=$(curl -s -X POST "${A[@]}" -H "Content-Type: audio/ogg" --data-binary @"$TMP/voice.ogg" \
  "$BASE/_matrix/media/v3/upload" | jget content_uri)
AUDIO_EV=$(curl -s -X PUT "${A[@]}" \
  -d "{\"msgtype\":\"m.audio\",\"body\":\"Voice message\",\"url\":\"$CU\",\"info\":{\"duration\":2640,\"mimetype\":\"audio/ogg\",\"size\":22}}" \
  "$BASE/_matrix/client/v3/rooms/$ROOMID/send/m.room.message/fixtxn1" | jget event_id)
curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"hello bob"}' \
  "$BASE/_matrix/client/v3/rooms/$ROOMID/send/m.room.message/fixtxn2" >/dev/null

# ---- alice incremental sync 1 ----------------------------------------------------
curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?since=$S1&timeout=0" > "$FIXDIR/alice__02-incr.json"
S2=$(jget next_batch < "$FIXDIR/alice__02-incr.json")

# ---- bob reads the voice message (receipt), alice sees it in incr 2 ---------------
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOMID/receipt/m.read/$AUDIO_EV" >/dev/null
curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?since=$S2&timeout=0" > "$FIXDIR/alice__03-incr.json"

# ---- bob initial sync --------------------------------------------------------------
curl -s "${B[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" > "$FIXDIR/bob__01-initial.json"

kill $PID 2>/dev/null || true
trap - EXIT
echo "fixtures captured to $FIXDIR:"
ls -la "$FIXDIR"
echo "room=$ROOMID audio_event=$AUDIO_EV content_uri=$CU"
