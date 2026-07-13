#!/usr/bin/env bash
# M7 chunk 2 — the wata-server smoke test. Two checks in one:
#
#   (1) STORE-ADT PARITY: `wata-server selfcheck` exercises the pure store core
#       (error envelope serialization, ID formatting, account-data set/replace/
#       get, the server-controlled-type predicate) deterministically and the
#       output is byte-compared to wata-selfcheck.expected.txt. This is the
#       out-vs-expected parity for the ADT heart (a hello-style scenario would
#       force hello to depend on wata-server, so the app runs its own mode).
#
#   (2) LIVE SESSION: boots the real `wata-server` binary on a random high port
#       and drives a scripted Matrix curl session (login -> whoami -> set/get
#       displayname -> set/get global account data -> a server-controlled-type
#       405 -> an unknown-token 401 -> a missing-token 401), asserting on the
#       EXACT deterministic response bodies (the access token is extracted and
#       substituted; the volatile device_id is dropped). This is first boot of
#       the real binary — part of the deliverable.
#
#   tools/wata-smoke.sh            # run + check against the expected fixtures
#   tools/wata-smoke.sh --accept   # (re)write both expected fixtures
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
# E2b: the suite runs FROM the wata repo; the sgola toolchain rides $SGOLA_HOME.
[ -n "${SGOLA_HOME:-}" ] || { echo "SGOLA_HOME not set (the sgola toolchain home)"; exit 1; }
ACCEPT="${1:-}"

SGO="${SGO:-$SGOLA_HOME/tools/sgo/sgo}"
[ -x "$SGO" ] || { echo "wata-smoke: sgo driver not built ($SGO)"; exit 1; }
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers (E2b)

# Build the wata-server binary via the driver's second-app path (core+json are
# shared + hash-gated, so this is cheap once they're warm).
echo "wata-smoke: building wata-server…"
( cd "$WATA/wata-server" && "$SGO" build ) >/dev/null || { echo "wata-smoke: sgo build (wata-server) failed"; exit 1; }
WATA_EMIT="$(emitdir wata-server)"               # .sgo/wata, discovered not hardcoded
# M7 chunk 4: build a `-race` binary from the emitted wata-server sources and run
# the WHOLE session (selfcheck + sessions 1/2 + the concurrency driver) against
# it, so the long-poll waiter/notify/select path is race-checked end to end.
echo "wata-smoke: building -race binary…"
( cd "$WATA_EMIT" && go build -race -o wata-server_race . ) || { echo "wata-smoke: go build -race failed"; exit 1; }
BIN="$WATA_EMIT/wata-server_race"

SELF_EXP="$WATA/tools/wata-selfcheck.expected.txt"
SESS_EXP="$WATA/tools/wata-smoke.expected.txt"
SELF_TX="$(mktemp)"; SESS_TX="$(mktemp)"; LOG="$(mktemp)"
VOICE="$(mktemp)"; HDR="$(mktemp)"; DLV="$(mktemp)"
PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null || true; rm -f "$SELF_TX" "$SESS_TX" "$LOG" "$VOICE" "$HDR" "$DLV"; }
trap cleanup EXIT

# ---- (1) store-ADT selfcheck ------------------------------------------------
"$BIN" selfcheck >"$SELF_TX" || { echo "wata-smoke: selfcheck run failed"; cat "$SELF_TX"; exit 1; }

# ---- (2) live session -------------------------------------------------------
PORT=$(( 20000 + (RANDOM % 20000) ))
BASE="http://127.0.0.1:$PORT"
"$BIN" ":$PORT" >"$LOG" 2>&1 &
PID=$!

ready=0
for _ in $(seq 1 100); do
  if curl -s -o /dev/null "$BASE/_matrix/client/versions" 2>/dev/null; then ready=1; break; fi
  sleep 0.1
done
if [ "$ready" -ne 1 ]; then echo "wata-smoke: server never became ready"; cat "$LOG"; exit 1; fi

jget() { python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])'; }

LOGIN=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"alice"},"password":"testpass123"}')
TOKEN=$(printf '%s' "$LOGIN" | jget access_token)
AUTH=(-H "Authorization: Bearer $TOKEN")

{
  printf 'login user_id=%s\n'  "$(printf '%s' "$LOGIN" | jget user_id)"
  printf 'whoami user_id=%s\n' "$(curl -s "${AUTH[@]}" "$BASE/_matrix/client/v3/account/whoami" | jget user_id)"
  printf 'set-displayname %s\n' "$(curl -s -X PUT "${AUTH[@]}" -d '{"displayname":"Alice W"}' "$BASE/_matrix/client/v3/profile/@alice:localhost/displayname")"
  printf 'get-profile %s\n'    "$(curl -s "$BASE/_matrix/client/v3/profile/@alice:localhost")"
  printf 'set-acct %s\n'       "$(curl -s -X PUT "${AUTH[@]}" -d '{"@bob:localhost":["!r"]}' "$BASE/_matrix/client/v3/user/@alice:localhost/account_data/m.direct")"
  printf 'get-acct %s\n'       "$(curl -s "${AUTH[@]}" "$BASE/_matrix/client/v3/user/@alice:localhost/account_data/m.direct")"
  req_ce() { # req_ce <label> <curl-args...>  -> "<label> code=<code> body=<body>"
    local label=$1; shift
    local body code
    body=$(curl -s "$@")
    code=$(curl -s -o /dev/null -w '%{http_code}' "$@")
    printf '%s code=%s body=%s\n' "$label" "$code" "$body"
  }
  req_ce "acct-405"      -X PUT "${AUTH[@]}" -d '{"x":1}' "$BASE/_matrix/client/v3/user/@alice:localhost/account_data/m.fully_read"
  req_ce "unknown-token" -H "Authorization: Bearer nope" "$BASE/_matrix/client/v3/account/whoami"
  req_ce "missing-token" "$BASE/_matrix/client/v3/account/whoami"
} >"$SESS_TX"

# ---- (3) second-user session: DM room, messaging, receipt, media, alias -----
# A full two-user Matrix flow (M7 chunk 3). Volatile IDs (room/event/media) are
# extracted and either format-validated or substituted with a fixed token, so
# the transcript is deterministic. bob is the second config user.
BOB_LOGIN=$(curl -s -X POST "$BASE/_matrix/client/v3/login" \
  -d '{"identifier":{"type":"m.id.user","user":"bob"},"password":"testpass123"}')
BOB_TOKEN=$(printf '%s' "$BOB_LOGIN" | jget access_token)
BOBAUTH=(-H "Authorization: Bearer $BOB_TOKEN")

# alice creates a direct room inviting bob; bob accepts the invite (join).
CREATE=$(curl -s -X POST "${AUTH[@]}" -d '{"is_direct":true,"invite":["@bob:localhost"]}' "$BASE/_matrix/client/v3/createRoom")
ROOMID=$(printf '%s' "$CREATE" | jget room_id)
JOIN=$(curl -s -X POST "${BOBAUTH[@]}" "$BASE/_matrix/client/v3/rooms/$ROOMID/join")

# send a message with a txnId, then replay the SAME txnId — same event_id back.
SEND1=$(curl -s -X PUT "${AUTH[@]}" -d '{"msgtype":"m.text","body":"hi"}' "$BASE/_matrix/client/v3/rooms/$ROOMID/send/m.room.message/txn1")
SEND2=$(curl -s -X PUT "${AUTH[@]}" -d '{"msgtype":"m.text","body":"hi"}' "$BASE/_matrix/client/v3/rooms/$ROOMID/send/m.room.message/txn1")
EVID=$(printf '%s' "$SEND1" | jget event_id)
EVID2=$(printf '%s' "$SEND2" | jget event_id)

# bob posts an m.read receipt; alice redacts her message.
RECEIPT=$(curl -s -X POST "${BOBAUTH[@]}" "$BASE/_matrix/client/v3/rooms/$ROOMID/receipt/m.read/$EVID")
REDACT=$(curl -s -X PUT "${AUTH[@]}" -d '{"reason":"oops"}' "$BASE/_matrix/client/v3/rooms/$ROOMID/redact/$EVID/txn2")
REDID=$(printf '%s' "$REDACT" | jget event_id)

# m.audio voice-message round-trip through media upload/download: binary bytes
# (incl. 0xff/0xfe/NUL) must come back byte-identical with the stored Content-Type.
printf 'OggS\x00\x02\x00\xff\xfe\x01\x02\x03VOICE' > "$VOICE"
UPLOAD=$(curl -s -X POST "${AUTH[@]}" -H "Content-Type: audio/ogg" --data-binary @"$VOICE" "$BASE/_matrix/media/v3/upload")
CONTENT_URI=$(printf '%s' "$UPLOAD" | jget content_uri)
MEDIAID="${CONTENT_URI##*/}"
curl -s -D "$HDR" -o "$DLV" "${AUTH[@]}" "$BASE/_matrix/media/v3/download/localhost/$MEDIAID"
DL_CT=$(grep -i '^content-type:' "$HDR" | tr -d '\r' | awk '{print $2}')

# alias resolution: create a room with an alias, resolve it back.
curl -s -X POST "${AUTH[@]}" -d '{"room_alias_name":"fam"}' "$BASE/_matrix/client/v3/createRoom" >/dev/null
RESOLVE=$(curl -s "$BASE/_matrix/client/v1/directory/room/%23fam:localhost")
RID_ALIAS=$(printf '%s' "$RESOLVE" | jget room_id)

# illegal membership transition: alice makes a private room WITHOUT inviting bob;
# bob's join is rejected (not invited, not public) -> 403 M_FORBIDDEN.
CR3=$(curl -s -X POST "${AUTH[@]}" -d '{}' "$BASE/_matrix/client/v3/createRoom")
RID3=$(printf '%s' "$CR3" | jget room_id)
BADJOIN_BODY=$(curl -s -X POST "${BOBAUTH[@]}" "$BASE/_matrix/client/v3/rooms/$RID3/join")
BADJOIN_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BOBAUTH[@]}" "$BASE/_matrix/client/v3/rooms/$RID3/join")

idok() { [[ "$1" == "$2"*"$3" ]] && echo true || echo false; }
{
  printf 'bob-login user_id=%s\n' "$(printf '%s' "$BOB_LOGIN" | jget user_id)"
  printf 'create-dm room_id_ok=%s\n' "$(idok "$ROOMID" '!' ':localhost')"
  printf 'bob-join %s\n' "${JOIN//$ROOMID/ROOM}"
  printf 'send1 event_id_ok=%s\n' "$(idok "$EVID" '$' ':localhost')"
  printf 'send2-replay-matches %s\n' "$([ "$EVID" = "$EVID2" ] && echo true || echo false)"
  printf 'receipt %s\n' "$RECEIPT"
  printf 'redact event_id_ok=%s differs=%s\n' "$(idok "$REDID" '$' ':localhost')" "$([ "$REDID" != "$EVID" ] && echo true || echo false)"
  printf 'upload content_uri=%s\n' "${CONTENT_URI//$MEDIAID/MEDIA}"
  printf 'download-ctype %s\n' "$DL_CT"
  printf 'download-bytes-match %s\n' "$(cmp -s "$VOICE" "$DLV" && echo true || echo false)"
  printf 'resolve %s\n' "${RESOLVE//$RID_ALIAS/ROOM}"
  printf 'illegal-join code=%s body=%s\n' "$BADJOIN_CODE" "$BADJOIN_BODY"
} >>"$SESS_TX"

echo "--- selfcheck transcript ---"; cat "$SELF_TX"
echo "--- session transcript ---";   cat "$SESS_TX"

# ---- (4) /sync long-poll concurrency driver (M7 chunk 4, THE CONC SHOWCASE) --
# N goroutine clients long-poll /sync while another client sends; all must wake
# WITH the event under a sane latency bound; a timeout-expiry case returns empty
# after ~its timeout. Nondeterministic timing => bounds-asserted (exit code),
# NOT byte-compared. Runs against the SAME `-race` server, so DATA RACE below
# covers the waiter/notify/select path exercised here.
echo "--- concurrency (long-poll) ---"
CONC_RC=0
( cd "$WATA/tools/wata-conc" && go run . "$BASE" ) || CONC_RC=1

# ---- (5) -race check: no DATA RACE anywhere in the whole session ------------
RACE_RC=0
if grep -q "DATA RACE" "$LOG"; then
  echo "wata-smoke: DATA RACE detected in wata-server:" >&2; grep -A 30 "DATA RACE" "$LOG" >&2; RACE_RC=1
fi

if [ "$ACCEPT" = "--accept" ]; then
  cp "$SELF_TX" "$SELF_EXP"; cp "$SESS_TX" "$SESS_EXP"
  echo "wata-smoke: wrote $SELF_EXP and $SESS_EXP"
  [ "$CONC_RC" -eq 0 ] && [ "$RACE_RC" -eq 0 ] || exit 1
  exit 0
fi

fail=0
if ! diff "$SELF_TX" "$SELF_EXP" >/dev/null; then
  echo "wata-smoke: selfcheck does NOT match expected"; diff "$SELF_EXP" "$SELF_TX" || true; fail=1
fi
if ! diff "$SESS_TX" "$SESS_EXP" >/dev/null; then
  echo "wata-smoke: session does NOT match expected"; diff "$SESS_EXP" "$SESS_TX" || true; fail=1
fi
if [ "$CONC_RC" -ne 0 ]; then echo "wata-smoke: concurrency driver FAILED"; fail=1; fi
if [ "$RACE_RC" -ne 0 ]; then echo "wata-smoke: -race check FAILED"; fail=1; fi
[ "$fail" -eq 0 ] || exit 1
echo "wata-smoke: selfcheck + live session + long-poll concurrency match expected, -race clean — PASS"
