# 0050 — a receipt is per MESSAGE, not per user: accumulate, don't replace

Status: accepted (bug report 2026-08-16: "played" checkmarks not showing
on the device for messages played on the mac — only the server-received
check appears)

## The problem

The server keys read receipts by `(roomId, userId, receiptType)`
(`Store.sameReceiptKey`): each new `m.read` a user posts REPLACES their
previous one in the room. That is Matrix's semantics — `m.read` is a
"read up to here" marker, one per user per room.

But wata's clients mean something else by the same POST: **"this exact
message has been heard."** The device and the mac receipt each message
when its playback completes (`AePlaybackDone` → `pushReceipt`), the
sync engine reads `playedByPeer`/`isPlayed` per event id, and the row
draws a per-message double check. Voice messages are played out of
order — the newest-first list invites it — so "up to here" cannot stand
in for "heard".

Under replacement, the observed failure is exactly what the journal
shows: the mac receipted the device's message (seq 123), then the owner
played back two of their OWN messages (seq 126, 127) — each replacing
the last — so by the device's next sync, the peer receipt on its
message no longer existed. The double check can only ever appear on the
single most-recently-played message, and only until the peer plays
anything else. Same family: played state does not survive a fresh
initial sync for anything but each user's last-receipted message.

## The decision

Accumulate: the server keeps one receipt per `(roomId, userId,
receiptType, eventId)`. Re-receipting the same event replaces that
entry (idempotent); receipting a different event adds one. This is a
deliberate divergence from Matrix's read-marker semantics, safe because
this server serves only wata clients, which already read receipts
per-event. The clients change nothing.

Growth is bounded by plays actually performed (≤ users × messages), the
same scale as the timeline itself; retention discards receipts with
their room.

## What changes

- `wata-server/src/main/scala/store.scala` — `sameReceiptKey` also
  compares `eventId`; comments on it and `setReceipt` state the wata
  semantics and name this plan. Replay (`replayReceipt`) shares
  `replaceReceipt`, so the journal path follows automatically — old
  journals replay under the new key and simply stop losing entries.
- `wata-server/src/main/scala/model.scala` — the `Receipt` doc comment
  says per-message, not per-user.
- `wata-fb/src/main/scala/integ.scala` + `tools/wataclient-integ.sh` —
  new scenario `receipt-both-played`: bob receipts TWO of alice's
  messages (older first), then a FRESH alice session must see both
  marked played-by-peer. The fresh session is the bite: bob's own
  client accumulates receipt entries locally, so only an initial sync
  taken AFTER both receipts observes what the server actually kept.
  (The fixtures oracle was left alone — regenerating it re-pins
  volatile ids for no added coverage.)
- `docs/wata-matrix-spec.md` — a wata-divergence note in the Receipts
  section.
- `docs/design/wata-server.md` — the receipts paragraph records the
  divergence and why.

## Verification

`just ci`. The `receipt-both-played` integ scenario is the oracle: two
receipts by the same user on different events, both visible to a fresh
initial sync. On hardware: play two of the device's messages on the
mac, both rows on the device grow the second check.

## Out of scope

- Client-side changes (none needed).
- `m.read.private` / `m.fully_read` (the clients never send them).
- Receipt garbage collection beyond room retention.
