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

- [ ] **T1 — redact round-trip**: `bob sees alice's message deleted`.
  Alice sends, bob sees it (existing predicate), alice redacts, bob's
  snapshot eventually shows the message gone (or marked redacted — match
  whatever `RoomState` currently does with redactions).
- [ ] **T2 — receipt round-trip**: `alice sees readBy after bob marks played`.
  Alice sends, bob sees and sends read receipt, alice's snapshot shows
  the message as read by bob. Requires a predicate over
  `conversation.messages[i]` read-state.
- [ ] **T3 — multi-turn conversation**: `alice ↔ bob exchange 3 messages each`.
  Assert final ordering matches send order on both sides and dedup
  produces 6 distinct messages (not 12).
- [ ] **T4 — family room**: `both members see a voice message in family`.
  Create a room aliased `#family:localhost` with both users, send a
  voice message, assert both snapshots expose it under
  `snapshot.family` / the family conversation (conv_type=.family).
- [ ] **T5 — media download**: `bob downloads alice's audio and bytes match`.
  Bypass the audio command queue — drive `downloadMedia` directly
  through the http client (or a new test helper) and assert the bytes
  returned equal `FAKE_OGG`.

## Non-goals for this round

- Stress/perf tests (T50+ messages). Nice to have but lower value per
  minute of test runtime.
- Pagination/backfill. Hard to trigger reliably without a fresh Conduit
  restart mid-test.
- Invalid-credential paths. TS covers these; for Zig they're unit-test
  territory against mocked HTTP.
