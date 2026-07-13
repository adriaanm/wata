#!/usr/bin/env bash
# M7 chunk 6 (decision 8) — the PERSISTENCE restart smoke.
#
# Boots wata-server with WATA_LOG set, creates durable state (login/room/message/
# media/displayname/receipt), KILLS the process (kill -9 — no graceful shutdown),
# reboots from the SAME log, and asserts the state is served back verbatim:
#   - the pre-restart access TOKEN still authenticates (devices/tokens replayed)
#   - the displayname set before the crash survives (profile replayed)
#   - the message is in the room timeline (room + events replayed, seq faithful)
#   - the uploaded media downloads BYTE-IDENTICAL (base64url round-trip)
#   - /sync serves the joined room (derived from replayed state)
#   - the redaction survives (target content emptied)
# Deterministic: asserts on stable facts (booleans / known bodies), never a
# volatile id. Exit non-zero on any failure.
#
#   tools/wata-persist-smoke.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
# E2b: the suite runs FROM the wata repo; the sgola toolchain rides $SGOLA_HOME.
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set (the sgola toolchain home)"; exit 1; }
SGO="${SGO:-$SGOLA_HOME/tools/sgo/sgo}"
[ -x "$SGO" ] || { echo "persist-smoke: sgo driver not built ($SGO)"; exit 1; }
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers (E2b)
BIN="$(emitdir wata-server)/$(binname wata-server)"
echo "persist-smoke: building wata-server…"
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "persist-smoke: sgo build (wata-server) failed"; exit 1; }
[ -x "$BIN" ] || { echo "persist-smoke: $BIN missing"; exit 1; }

TMP="$(mktemp -d)"
LOG="$TMP/wata.jsonl"
PORT=$(( 20000 + (RANDOM % 20000) ))
BASE="http://127.0.0.1:$PORT"
PID=""
cleanup() { [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

jget() { python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])'; }
boot() {
  WATA_LOG="$LOG" "$BIN" ":$PORT" >"$TMP/server.log" 2>&1 &
  PID=$!
  local i
  for i in $(seq 1 100); do
    curl -s -o /dev/null "$BASE/_matrix/client/versions" 2>/dev/null && return 0
    sleep 0.1
  done
  echo "persist-smoke: server never became ready"; cat "$TMP/server.log"; return 1
}
kill_server() { kill -9 "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""; }

fail=0
check() { # check <label> <actual> <expected>
  if [ "$2" = "$3" ]; then printf '  ok   %-28s %s\n' "$1" "$2"
  else printf '  FAIL %-28s got=%s want=%s\n' "$1" "$2" "$3"; fail=1; fi
}

# ---- session 1: create durable state ---------------------------------------
boot || exit 1
TOKEN=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"alice"},"password":"testpass123"}' | jget access_token)
A=(-H "Authorization: Bearer $TOKEN")
BOB=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"bob"},"password":"testpass123"}' | jget access_token)
B=(-H "Authorization: Bearer $BOB")

ROOM=$(curl -s -X POST "${A[@]}" -d '{"is_direct":true,"invite":["@bob:localhost"]}' "$BASE/_matrix/client/v3/createRoom" | jget room_id)
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/join" >/dev/null
EVID=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"persist-me"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx1" | jget event_id)
DOOMED=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"redact-me"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx2" | jget event_id)
curl -s -X PUT "${A[@]}" -d '{"reason":"oops"}' "$BASE/_matrix/client/v3/rooms/$ROOM/redact/$DOOMED/tx3" >/dev/null
curl -s -X PUT "${A[@]}" -d '{"displayname":"Alice Persisted"}' "$BASE/_matrix/client/v3/profile/@alice:localhost/displayname" >/dev/null
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/receipt/m.read/$EVID" >/dev/null
printf 'OggS\x00\x02\x00\xff\xfe\x01\x02\x03VOICE' > "$TMP/voice.ogg"
CU=$(curl -s -X POST "${A[@]}" -H "Content-Type: audio/ogg" --data-binary @"$TMP/voice.ogg" "$BASE/_matrix/media/v3/upload" | jget content_uri)
MID="${CU##*/}"
LINES=$(wc -l < "$LOG" | tr -d ' ')

# ---- crash + reboot from the log -------------------------------------------
kill_server
echo "persist-smoke: killed server; $LINES journal lines; rebooting from log…"
boot || exit 1

# ---- assertions: state served back -----------------------------------------
echo "--- restart assertions ---"
check "journal-nonempty"        "$([ "$LINES" -gt 0 ] && echo true)"                                                      "true"
check "token-survives"          "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/account/whoami" | jget user_id)"           "@alice:localhost"
check "profile-survives"        "$(curl -s "$BASE/_matrix/client/v3/profile/@alice:localhost" | jget displayname)"       "Alice Persisted"
check "message-survives"        "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=20" \
                                    | python3 -c 'import json,sys; c=json.load(sys.stdin)["chunk"]; print("persist-me" in [e.get("content",{}).get("body") for e in c])')" "True"
check "redaction-survives"      "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=20" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
red=[e for e in c if e.get("event_id")=="'"$DOOMED"'"]
print(bool(red) and red[0].get("content")=={})')" "True"
curl -s -o "$TMP/dl.ogg" "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/$MID"
check "media-bytes-match"       "$(cmp -s "$TMP/voice.ogg" "$TMP/dl.ogg" && echo true || echo false)"                     "true"
check "sync-serves-room"        "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" \
                                    | python3 -c 'import json,sys; print("'"$ROOM"'" in json.load(sys.stdin).get("rooms",{}).get("join",{}))')" "True"
# idempotency replayed: re-sending tx1 returns the SAME event id
check "txn-idempotency-survives" "$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"persist-me"}' \
                                    "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx1" | jget event_id)"       "$EVID"

[ "$fail" -eq 0 ] || { echo "persist-smoke: FAILED"; exit 1; }
echo "persist-smoke: state survived kill+reboot from the JSONL log — PASS"
