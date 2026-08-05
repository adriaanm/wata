#!/usr/bin/env bash
# The wata-server persistence restart smoke.
#
# Boots wata-server with WATA_LOG set, creates durable state (login/room/message/
# media/displayname/receipt), KILLS the process (kill -9 — no graceful shutdown),
# reboots from the SAME log, and asserts the state is served back verbatim:
#   - the pre-restart access TOKEN still authenticates (devices/tokens replayed)
#   - the displayname set before the crash survives (profile replayed)
#   - the message is in the room timeline (room + events replayed, seq faithful)
#   - the uploaded media downloads BYTE-IDENTICAL, served from its BLOB FILE
#     ($WATA_DATA/media/<mediaId>, here derived from the journal's directory);
#     the journal itself carries only {media_id, content_type, size}
#   - redacting a media message RECLAIMS its blob (file deleted, download 404),
#     and the reclaim survives the replay
#   - an OLD-STYLE journal (base64url `data` payload in the media op) is
#     MIGRATED on boot: the blob is written out to the media dir and serves
#   - a media op whose blob file is MISSING replays as log-and-skip (404 on
#     fetch, boot not aborted)
#   - FAVORITES exempt a message from the sweep (plan 0019): a favorited voice
#     message survives the aggressive-retention reboot with its blob intact,
#     and once unfavorited the next sweep reclaims it — both across kill -9
#     replays, since the marker is an ordinary journaled state event.
#   - the RETENTION SWEEP (WATA_MEDIA_RETAIN_DAYS) reclaims an aged voice
#     message at boot: blob file deleted, the event server-side-redacted, and
#     the sweep's redaction journaled like any other, so a further reboot
#     (default retention) converges on the same state. Age is FAKED by
#     rewriting the message's origin_server_ts in the journal — event ts is
#     the sweep's age basis (a blob file's mtime would reset on migration),
#     so aging the journaled ts is the honest lever.
#   - /sync serves the joined room (derived from replayed state)
#   - the redaction survives (target content emptied)
#   - a state event set through PUT /state/... survives
#   - a ban survives (the banned user still cannot join after the reboot)
#   - a canonical DM's pair -> room claim survives, so re-resolving the pair
#     (from either side, or via a DM-shaped createRoom) returns the SAME room
#     rather than minting a second one, and its alias still resolves
# Deterministic: asserts on stable facts (booleans / known bodies), never a
# volatile id. Exit non-zero on any failure.
#
#   tools/wata-persist-smoke.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                          # SGOLA_HOME (pinned clone by default), GOTOOLCHAIN, SGO
[ -x "$SGO" ] || { echo "persist-smoke: sgo driver not built ($SGO)"; exit 1; }
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
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

# An ORDINARY private room (creator 100, invitee 0) — the ban assertion needs an
# asymmetric room. A canonical DM is co-owned by its pair (both 100), so neither
# side can ban the other out of their own DM; DM identity gets its own section.
ROOM=$(curl -s -X POST "${A[@]}" -d '{"invite":["@bob:localhost"]}' "$BASE/_matrix/client/v3/createRoom" | jget room_id)
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/join" >/dev/null
EVID=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"persist-me"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx1" | jget event_id)
DOOMED=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"redact-me"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx2" | jget event_id)
curl -s -X PUT "${A[@]}" -d '{"reason":"oops"}' "$BASE/_matrix/client/v3/rooms/$ROOM/redact/$DOOMED/tx3" >/dev/null
curl -s -X PUT "${A[@]}" -d '{"displayname":"Alice Persisted"}' "$BASE/_matrix/client/v3/profile/@alice:localhost/displayname" >/dev/null
curl -s -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/receipt/m.read/$EVID" >/dev/null
printf 'OggS\x00\x02\x00\xff\xfe\x01\x02\x03VOICE' > "$TMP/voice.ogg"
CU=$(curl -s -X POST "${A[@]}" -H "Content-Type: audio/ogg" --data-binary @"$TMP/voice.ogg" "$BASE/_matrix/media/v3/upload" | jget content_uri)
MID="${CU##*/}"
# a voice MESSAGE referencing the blob (the retention sweep, session 3, needs a
# referring event; harmless to the earlier assertions).
AEV=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.audio","body":"voice","url":"'"$CU"'"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx4" | jget event_id)
# a second blob + message, redacted LIVE: redaction must reclaim the blob file.
printf 'OggS\x00\x02DOOMEDVOICE' > "$TMP/voice2.ogg"
CU2=$(curl -s -X POST "${A[@]}" -H "Content-Type: audio/ogg" --data-binary @"$TMP/voice2.ogg" "$BASE/_matrix/media/v3/upload" | jget content_uri)
MID2="${CU2##*/}"
DAEV=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.audio","body":"doomed","url":"'"$CU2"'"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx5" | jget event_id)
curl -s -X PUT "${A[@]}" -d '{"reason":"reclaim"}' "$BASE/_matrix/client/v3/rooms/$ROOM/redact/$DAEV/tx6" >/dev/null
# a THIRD blob + message, FAVORITED: the retention sweep must spare it while
# the marker stands (plan 0019). The marker is an ordinary state event, so it
# rides the journal like everything else here.
printf 'OggS\x00\x02KEEPVOICE' > "$TMP/voice3.ogg"
CU3=$(curl -s -X POST "${A[@]}" -H "Content-Type: audio/ogg" --data-binary @"$TMP/voice3.ogg" "$BASE/_matrix/media/v3/upload" | jget content_uri)
MID3="${CU3##*/}"
FEV=$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.audio","body":"keep","url":"'"$CU3"'"}' "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx7" | jget event_id)
FAVSET=$(curl -s -X POST "${A[@]}" "$BASE/_wata/v1/favorite/$ROOM/$FEV" | jget favorite)
# the generic state route and a membership eviction: both are ordinary
# m.room.member / state events in the journal, so this is what proves it.
curl -s -X PUT "${A[@]}" -d '{"topic":"family channel"}' "$BASE/_matrix/client/v3/rooms/$ROOM/state/m.room.topic/" >/dev/null
curl -s -X POST "${A[@]}" -d '{"user_id":"@bob:localhost","reason":"persist-test"}' "$BASE/_matrix/client/v3/rooms/$ROOM/ban" >/dev/null
# canonical DM identity: the pair -> room claim is journalled post-generation, so
# the SAME room must come back after the reboot.
DM=$(curl -s -X POST "${A[@]}" "$BASE/_wata/v1/dm/bob" | jget room_id)
LINES=$(wc -l < "$LOG" | tr -d ' ')

# ---- crash + reboot from the log -------------------------------------------
kill_server
echo "persist-smoke: killed server; $LINES journal lines; rebooting from log…"
# seed two crafted media ops for the reboot to chew on:
#  - an OLD-STYLE base64url op (a journal written before the file-backed
#    store): boot must MIGRATE the blob out to the media dir
#  - a SLIM op whose blob file does not exist: boot must log and SKIP it
python3 - "$LOG" <<'EOF'
import base64, json, sys
with open(sys.argv[1], "a") as f:
    payload = base64.urlsafe_b64encode(b"OggS\x00LEGACYVOICE").decode()
    f.write(json.dumps({"op": "media", "media_id": "legacymid1",
                        "content_type": "audio/ogg", "data": payload}) + "\n")
    f.write(json.dumps({"op": "media", "media_id": "ghostmid1",
                        "content_type": "audio/ogg", "size": 11}) + "\n")
EOF
printf 'OggS\x00LEGACYVOICE' > "$TMP/legacy.ogg"
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
# the blob rides a FILE next to the journal, and the journal op is slim
# (no base64 payload) — the whole point of plan 0012.
check "media-file-backed"       "$(cmp -s "$TMP/voice.ogg" "$TMP/media/$MID" && echo true || echo false)"                 "true"
check "journal-media-slim"      "$(grep '"op":"media"' "$LOG" | grep -v legacymid1 | grep -c '"data"' | tr -d ' ')"       "0"
# the live redaction reclaimed blob 2, and the reclaim survived the replay.
check "reclaim-file-deleted"    "$([ ! -e "$TMP/media/$MID2" ] && echo true)"                                             "true"
check "reclaim-download-404"    "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/$MID2")" "404"
# the crafted old-style op was migrated out to a file and serves.
curl -s -o "$TMP/legacy-dl.ogg" "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/legacymid1"
check "old-journal-migrated"    "$(cmp -s "$TMP/legacy.ogg" "$TMP/media/legacymid1" && echo true || echo false)"          "true"
check "migrated-blob-serves"    "$(cmp -s "$TMP/legacy.ogg" "$TMP/legacy-dl.ogg" && echo true || echo false)"             "true"
# the crafted file-less slim op was skipped with a log line, not fatal.
check "missing-blob-404"        "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/ghostmid1")" "404"
check "missing-blob-logged"     "$(grep -c 'media blob missing on replay, skipping ghostmid1' "$TMP/server.log" | tr -d ' ')" "1"
check "sync-serves-room"        "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" \
                                    | python3 -c 'import json,sys; print("'"$ROOM"'" in json.load(sys.stdin).get("rooms",{}).get("join",{}))')" "True"
check "state-put-survives"      "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" \
                                    | python3 -c 'import json,sys
st=json.load(sys.stdin)["rooms"]["join"]["'"$ROOM"'"]["state"]["events"]
print([e["content"].get("topic") for e in st if e["type"]=="m.room.topic"]==["family channel"])')" "True"
check "ban-survives"            "$(curl -s -o /dev/null -w '%{http_code}' -X POST "${B[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/join")" "403"
# idempotency replayed: re-sending tx1 returns the SAME event id
check "txn-idempotency-survives" "$(curl -s -X PUT "${A[@]}" -d '{"msgtype":"m.text","body":"persist-me"}' \
                                    "$BASE/_matrix/client/v3/rooms/$ROOM/send/m.room.message/tx1" | jget event_id)"       "$EVID"

# ---- canonical DM identity across the reboot ---------------------------------
# The pair map is journalled, so no room is minted a second time — asked from
# either side, or through the DM-shaped createRoom a stock client would send.
check "dm-pair-survives"        "$(curl -s -X POST "${A[@]}" "$BASE/_wata/v1/dm/bob" | jget room_id)"                     "$DM"
check "dm-pair-symmetric"       "$(curl -s -X POST "${B[@]}" "$BASE/_wata/v1/dm/alice" | jget room_id)"                   "$DM"
check "dm-stock-create-survives" "$(curl -s -X POST "${A[@]}" -d '{"is_direct":true,"invite":["@bob:localhost"]}' \
                                    "$BASE/_matrix/client/v3/createRoom" | jget room_id)"                                "$DM"
check "dm-alias-survives"       "$(curl -s "${A[@]}" "$BASE/_matrix/client/v1/directory/room/%23dm.alice.bob:localhost" | jget room_id)" "$DM"

check "favorite-set"            "$FAVSET"                                                                                  "True"
check "favorite-survives"       "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/sync?timeout=0" \
                                    | python3 -c 'import json,sys
st=json.load(sys.stdin)["rooms"]["join"]["'"$ROOM"'"]["state"]["events"]
print([e["content"].get("by") for e in st if e["type"]=="net.wata.favorite" and e["state_key"]=="'"$FEV"'"]==["@alice:localhost"])')" "True"

# ---- retention sweep: an aged voice message is reclaimed at boot -------------
kill_server
# fake age: rewrite the voice message's origin_server_ts to 2 days ago.
python3 - "$LOG" "$AEV" "$FEV" <<'EOF'
import json, sys, time
path = sys.argv[1]
aged_ids = set(sys.argv[2:])
aged = int(time.time() * 1000) - 2 * 86400000
out = []
for line in open(path):
    line = line.rstrip("\n")
    if not line:
        continue
    o = json.loads(line)
    if o.get("op") == "event" and o.get("event_id") in aged_ids:
        o["ts"] = aged
        line = json.dumps(o, separators=(",", ":"))
    out.append(line)
open(path, "w").write("\n".join(out) + "\n")
EOF
echo "persist-smoke: aged the voice message 2 days; rebooting with WATA_MEDIA_RETAIN_DAYS=1…"
WATA_MEDIA_RETAIN_DAYS=1 boot || exit 1

echo "--- retention sweep assertions ---"
check "sweep-file-deleted"      "$([ ! -e "$TMP/media/$MID" ] && echo true)"                                              "true"
check "sweep-download-404"      "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/$MID")" "404"
check "sweep-event-redacted"    "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=30" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
sw=[e for e in c if e.get("event_id")=="'"$AEV"'"]
print(bool(sw) and sw[0].get("content")=={})')" "True"
check "sweep-logged"            "$(grep -c "retention swept $AEV" "$TMP/server.log" | tr -d ' ')"                          "1"
# the unreferenced (migrated) blob is NOT swept — only event-referenced media is
check "sweep-spares-unreferenced" "$([ -e "$TMP/media/legacymid1" ] && echo true)"                                        "true"
# the FAVORITED message is the same age and was NOT swept: blob intact, event
# not redacted. The marker replayed out of the journal before the boot sweep ran.
check "sweep-spares-favorite"   "$([ -e "$TMP/media/$MID3" ] && echo true)"                                               "true"
check "favorite-download-200"   "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" "$BASE/_matrix/media/v3/download/localhost/$MID3")" "200"
check "favorite-event-intact"   "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=30" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
kept=[e for e in c if e.get("event_id")=="'"$FEV"'"]
print(bool(kept) and kept[0].get("content",{}).get("body")=="keep")')" "True"

# ---- unfavorite: the NEXT sweep reclaims it ---------------------------------
FAVCLR=$(curl -s -X POST "${A[@]}" "$BASE/_wata/v1/favorite/$ROOM/$FEV" | jget favorite)
check "unfavorite-toggles-off"  "$FAVCLR"                                                                                 "False"
kill_server
echo "persist-smoke: unfavorited the kept message; rebooting with WATA_MEDIA_RETAIN_DAYS=1…"
WATA_MEDIA_RETAIN_DAYS=1 boot || exit 1
echo "--- unfavorited sweep assertions ---"
check "unfav-sweep-file-gone"   "$([ ! -e "$TMP/media/$MID3" ] && echo true)"                                             "true"
check "unfav-sweep-redacted"    "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=30" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
sw=[e for e in c if e.get("event_id")=="'"$FEV"'"]
print(bool(sw) and sw[0].get("content")=={})')" "True"
check "unfav-sweep-logged"      "$(grep -c "retention swept $FEV" "$TMP/server.log" | tr -d ' ')"                          "1"

# the sweep journaled an ordinary redaction: a reboot WITHOUT the aggressive
# retention setting replays to the same state.
kill_server
echo "persist-smoke: rebooting with default retention; the swept state must replay…"
boot || exit 1
echo "--- sweep-replay convergence ---"
check "sweep-survives-replay"   "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=30" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
sw=[e for e in c if e.get("event_id")=="'"$AEV"'"]
print(bool(sw) and sw[0].get("content")=={})')" "True"
check "sweep-file-stays-gone"   "$([ ! -e "$TMP/media/$MID" ] && echo true)"                                              "true"
check "unfav-sweep-replays"     "$(curl -s "${A[@]}" "$BASE/_matrix/client/v3/rooms/$ROOM/messages?dir=b&limit=30" \
                                    | python3 -c 'import json,sys
c=json.load(sys.stdin)["chunk"]
sw=[e for e in c if e.get("event_id")=="'"$FEV"'"]
print(bool(sw) and sw[0].get("content")=={})')" "True"
check "unfav-file-stays-gone"   "$([ ! -e "$TMP/media/$MID3" ] && echo true)"                                             "true"

[ "$fail" -eq 0 ] || { echo "persist-smoke: FAILED"; exit 1; }
echo "persist-smoke: state survived kill+reboot from the JSONL log — PASS"
