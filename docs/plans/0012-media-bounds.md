# 0012 — media out of the journal, and a retention decision

Status: proposed

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

## The retention question (needs a product call)

The mechanism above bounds *memory and replay*, not *disk*. Options:

1. **No automatic deletion** (recommended start): a family's voice
   history is potentially precious — kids grow up — and disk is the
   cheapest resource on both a Pi and a VPS. Add a `just media-usage`
   style report so growth is visible, revisit if a deployment ever
   shows a problem.
2. Age-based sweep (`WATA_MEDIA_RETAIN_DAYS`), off by default.

Option 1 keeps deletion a deliberate human act (redaction); option 2
is one env var away if wanted later. The plan implements 1 and leaves
2 designed-but-unbuilt.

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
- Any client change (media fetch surface is unchanged).
- Retention option 2 implementation.

## Verification

`just persist` extended as above; `just ci` green; `just conformance`
84/84 (media upload/download suites exercise the new path end to end).
