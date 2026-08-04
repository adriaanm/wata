# 0012 — media out of the journal, and a retention decision

Status: accepted

`[MEDIA-BOUNDS]`

## Problem

`SRV-MEDIA-UNBOUNDED`: every uploaded blob lives forever in the
in-memory store AND rides the journal as a base64url `media` op. Three
costs compound: memory grows without bound, the journal grows ~1.33x
the media volume on top of that, and boot replay re-ingests every blob
ever sent. A family sending 50 voice messages a day (~15 KB each)
journals ~1 MB/day of base64 — fine for weeks, corrosive over years,
and redaction today deletes the event but strands the blob forever.

## Decision

**Blobs become files; the journal keeps only metadata.**

- `$WATA_DATA/media/<mediaId>` (mediaId is already server-minted and
  path-safe), one file per blob, written before the journal op so a
  crash between the two leaves an orphan file, never a dangling ref.
- The `media` journal op shrinks to `{media_id, content_type, size}`;
  replay stats the file instead of decoding base64. A missing file on
  replay logs and skips (the events referencing it 404 on fetch —
  degraded, not fatal).
- In-memory store holds metadata only; `getMedia` reads the file on
  demand (voice blobs are ~15 KB; no cache until profiling says so).
- **Migration**: on boot, a journal containing old-style base64 `media`
  ops is replayed by writing each blob out to the media dir once; the
  next journal compaction (already existing? if not, out of scope —
  the write-out alone stops the memory cost) drops the payload.
- **Redaction reclaims**: redacting a voice message deletes its blob
  file (the only referrer is the event; media ids are not shared).

## Retention (product ruling, 2026-08-04)

**Wata is ephemeral by design** — a walkie-talkie, not an archive;
memories can also be stored in brains. Voice media older than
`WATA_MEDIA_RETAIN_DAYS` (default **7**) is swept: blob file deleted,
the event redacted server-side, so clients render the same
message-removed row a manual redaction produces. `0` disables the
sweep for anyone who wants an archive. The sweep runs at boot and
daily thereafter, and is journaled like any redaction so a replay
converges.

A future "favorite a message" is the intended way to *keep* one —
favoriting would exempt the event and its blob from the sweep. That
mechanism (a state marker + UI) is out of scope here; the sweep just
needs to leave a seam for an exempt-set so favorites can slot in.

## What changes (file-level)

- `wata-server`: `store.scala` (metadata map, file-backed `getMedia`),
  `persist.scala` (slim `media` op + old-op migration replay),
  `rooms.scala`/`sync.scala` (redaction → blob delete), `server.scala`
  (`$WATA_DATA` resolution — already exists for the journal path).
- `tools/wata-persist-smoke.sh`: media survives restart via file, not
  journal; redaction-reclaims assertion; old-journal migration case.
- `docs/design/wata-server.md`: persistence section rewrite.

## Out of scope

- Journal compaction (separate mechanism; this plan only stops NEW
  payload growth and migrates old payloads out at boot).
- The favorite-a-message mechanism (the sweep leaves the exempt-set
  seam; the marker and UI are a future plan).
- Any client change (media fetch surface is unchanged; the swept
  events arrive as ordinary redactions).

## Verification

`just persist` extended as above (incl. a sweep case: an aged blob is
reclaimed and its event reads as redacted after replay); `just ci`
green; `just conformance`
84/84 (media upload/download suites exercise the new path end to end).
