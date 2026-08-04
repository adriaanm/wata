# 0005 — server conformance: real tokens, full room lifecycle, config users

Status: accepted

`[SRV-CONFORMANCE]`

## Problem

Phase 2 of [0003](0003-parity-and-beyond.md). The server deviates from
the Matrix C-S contract in ways the client currently papers over, and it
hardcodes its users:

- `/sync` never sets `limited`, and `/messages` `from`/`end` are event
  ids rather than opaque tokens — `wataclient` carries a backfill
  workaround (`backfillNewJoins`/`backfillTail`) because of it.
- No `PUT /rooms/{id}/state/{type}/{key}` route; no `/leave`, `/kick`,
  `/ban` (the membership table rows exist, unreached).
- No power-level enforcement on send/redact/state (`[SRV-POWER-LEVELS]`).
- Users are hardcoded alice/bob; a family needs its accounts from config.
- E2EE stub endpoints (`/keys/query`, `/keys/upload`,
  `/keys/device_signing/upload`) that let Element/FluffyChat get past the
  device-key handshake are missing here.

## Decision

Implement against the two oracles we already have, in this order of
authority: the in-tree jest conformance suite (`just conformance`, the
suite the TS server was built against — MUST NOT be altered), then
`docs/wata-matrix-spec.md` for anything the suite doesn't pin down.
`spec/sync.specl` governs sync-token semantics: tokens are opaque
`s<seq>` positions, monotonic per the model.

Config users replace the hardcoded pair: a JSON file (path via
`WATA_USERS`, default the current alice/bob pair compiled in for tests)
listing `{user, password, displayname}`. No open registration — the
family's accounts are provisioned, matching the trust model.

Once `limited` + real pagination land, delete the client backfill
workaround in the same plan (the spec path `backfillIfLimited` stays)
and re-run the full gate — that deletion is the proof the server-side
fix is real.

## What changes

- `wata-server/src/main/scala/`: `sync.scala` (`limited` + `prev_batch`
  per the model), `events.scala` (opaque `/messages` tokens), new routes
  in `server.scala`/`handlers.scala` (state PUT, leave/kick/ban, keys
  stubs), power-level checks in `messages`/`rooms`/`membership`,
  `model.scala` (users from `WATA_USERS`).
- `wataclient/src/main/scala/runtime.scala`: remove
  `backfillNewJoins`/`backfillTail`.
- `docs/design/wata-server.md` + `wataclient.md` updated in the same
  commits; `TODO.jsonl`: closes `[SRV-POWER-LEVELS]`.

## Verification

`just ci` green throughout; `just conformance` green (all suites) as the
phase gate; the persist journal replays the new event kinds
(`just persist`); re-test the original repo's recorded rapid-send
message-loss scenario (20 sequential sends, zero loss).

## Out of scope

- Registration UX, federation, real E2EE, push, presence
  (per 0003 / `wata-matrix-spec.md` exclusions).
- Journal compaction and media caps (phase 6).
