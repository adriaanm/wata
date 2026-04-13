# fbclient integration test parity with TUI

Working doc tracking the gap between the TypeScript integration suite
(`test/integration/*.ts`, run against Conduit via `test/docker`) and the
Zig fbclient integration suite (`src/fbclient/src/matrix/client_integration_test.zig`).
Both hit the same Conduit instance.

## Current Zig coverage (baseline)

Five tests:

1. `login and reach syncing state`
2. `alice and bob both reach syncing`
3. `alice sends voice to bob, bob sees the message`
4. `bob acks alice's message with a read receipt`
5. `alice deletes (redacts) a message`

## Coverage matrix

| Category | TS cases | Zig |
|---|---|---|
| Login / sync state | ~7 | ✓ basic only |
| DM room create / list / sort / m.direct | 8+ | ✗ |
| Single voice send/receive | 3 | ✓ |
| Bidirectional (A↔B) conversation | 4 | ✗ |
| Rapid/stress (10–50 msgs) | 7 | ✗ |
| Message ordering | 5 | ✗ |
| Read receipts — `readBy` propagation | 2 | ~ sends action only |
| Redaction — recipient sees redaction | 1 | ~ sends action only |
| Family room (create/join/invite/members/send) | 7+ | ✗ |
| Media download path | 2 | ~ implicit; audio stubbed |
| Pagination / history on join | 3 | ✗ |
| Edge metadata (duration, ts, eventId uniqueness) | 5 | ✗ |
| Latency / perf metrics | 2 | ✗ |

Zig tests are currently a strict subset of TS coverage — no Zig-only scenarios.

## Top gaps (ordered by importance)

1. **No round-trip verification for mutations.** Read-receipt and redaction
   tests only assert `sendAction(...)` is accepted; they don't verify the
   other client actually sees the state change.
2. **No bidirectional conversation.** Every Zig test is alice→bob one-way.
3. **No multi-message / ordering.** Dedup (`timeline_event_ids`) and
   ordering are effectively untested.
4. **No family-room coverage.** ~70 lines of `buildSnapshot` family logic
   are gated only by unit tests.
5. **No media download test.** Integration runs with `audio_cmd_queue=null`
   so `download_and_play` always emits `playback_error`.

## Tasks

Each task adds an integration test to `client_integration_test.zig`,
mirroring a TS scenario. Keep tests cheap — no sleeps beyond what
`waitForSnapshot` already does.

- [x] **T1 — redact round-trip**: implemented in `e4d3f2b`. Required
  adding `m.room.redaction` handling to the sync engine — it previously
  ignored redactions entirely. Dropped redacted messages from the
  snapshot rather than marking them, matching TS behavior.
- [x] **T2 — receipt round-trip**: implemented in `2b3cee5`. Verifies
  bob's own snapshot marks the message `is_played=true` after he sends
  the receipt, which exercises the full ephemeral round-trip through
  Conduit.
- [x] **T3 — multi-turn conversation**: implemented in `9ffcfa2`. Needed
  a supporting fix in `a0d2413` — the action thread used to create a
  new DM room for every empty-room send; now it looks up existing rooms
  via `m.direct` first, matching TS `getOrCreateDmRoom`. Also wired
  `fb-test-integration` to wipe Conduit volumes for determinism.
- [x] **T4 — family room**: implemented in `68c5994`. Uses a direct
  `MatrixHttpClient` to create the room with `room_alias_name=family`
  outside the action thread (which only knows DMs), then verifies both
  sides see `snapshot.family` populated and a `conv_type=.family`
  conversation carrying the voice message.
- [x] **T5 — media download**: implemented in `8d5b26a`. Reads the
  mxc URL from bob's snapshot, logs in bob via a direct
  `MatrixHttpClient`, and downloads the media — asserting the bytes
  equal `FAKE_OGG`. Closes the `upload → mxc → download` loop that the
  audio-stubbed integration build otherwise never exercises.

## Outcome

Started at 41 integration tests, ended at 47 (+6 test-cases across the
5 tasks — T1 modified an existing test, the others added new ones).
Required three real behavior fixes along the way:

- `is_direct` DM recognition on the invitee side (`3332e52`)
- DM room reuse via `m.direct` lookup in the action thread (`a0d2413`)
- `m.room.redaction` event handling in the sync engine (`e4d3f2b`)

All three were pre-existing parity gaps with the TypeScript client that
only surfaced once round-trip assertions were added.

## Non-goals for this round

- Stress/perf tests (T50+ messages). Nice to have but lower value per
  minute of test runtime.
- Pagination/backfill. Hard to trigger reliably without a fresh Conduit
  restart mid-test.
- Invalid-credential paths. TS covers these; for Zig they're unit-test
  territory against mocked HTTP.
