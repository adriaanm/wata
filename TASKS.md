# Tasks

**Current focus: Framebuffer client (Zig).** Planning doc: [docs/planning/framebuffer-client.md](docs/planning/framebuffer-client.md) — treat as a working document, update as things change. The **TUI is the most complete frontend** — use it as the reference implementation when docs are incomplete (read the TUI source, not just the docs).

## Framebuffer Client (Zig)

### Active
- [x] Streaming playback stutter — decode in 480ms chunks (12 periods), write each chunk as a single pcm_writei. Kernel handles period-by-period DMA internally. Needs on-device testing to confirm smooth playback.
- [x] DM room creation — create room with `is_direct: true`, update `m.direct`. Lazy creation on first send.
- [x] Auto-join invites — auto-join all invited rooms during sync (trusted family environment).
- [x] DM room deduplication — skip stale/left rooms, use first joined room from m.direct.
- [x] Send status feedback — show "SENT" / "SEND FAILED" overlay so errors aren't silent.

### Testing — port from TypeScript test suite
Existing Zig tests: 7 in sync_engine.zig (sync state machine). Run with `cd src/fbclient && zig build test`. Test entry point: `src/test_main.zig`.

- [x] **Ogg container tests** (13 tests) — CRC32, OpusHead/OpusTags structure, page CRC integrity, mux/demux roundtrip, large payloads, EOS, reader skip logic.
- [x] **Queue tests** (6 tests) — push/pop, FIFO ordering, full buffer, wraparound, drain.
- [x] **HTTP helpers tests** (4 tests) — parseRetryAfterMs, parseMxcUrl, parseRoomId.
- [x] **Sync engine tests (expand)** (7 new, 14 total) — m.direct dedup, family room detection, roomless members, self-exclusion, read receipts, unplayed count.
- [ ] **Opus codec roundtrip** — port from `audio-codec.test.ts`. Encode PCM→Opus→Ogg→decode→PCM, verify sample count and basic signal preservation. Requires `use_audio` build flag.
- [x] **MatrixClient extraction + integration harness** — extracted `matrix/client.zig` (MatrixClient runtime: owns sync+action threads, queues, state store, auth; exposes start/stop/sendAction/pollEvent/acquireSnapshot + waitForConnection/waitForSnapshot test helpers). New `zig build test-integration` step runs `matrix/client_integration_test.zig` against a live homeserver (defaults `localhost:8008`, override via `WATA_TEST_HOMESERVER`/`WATA_TEST_USER1` etc). 5 E2E tests scaffolded: login+sync, dual-client connect, voice send, read receipt, redact. Audio stubbed via `audio_cmd_queue=null`. Skip cleanly when homeserver unreachable. Run with `just fb-test-integration`.

### Concurrency redesign
Planning doc: [docs/planning/concurrency-redesign.md](docs/planning/concurrency-redesign.md) — treat as a working document.
- [x] Step 1: Fix snapshot arena leak (`OwnedSnapshot` + swap-and-free in `StateStore`)
- [x] Step 2: Add `Mailbox` primitive (blocking bounded queue with futex)
- [x] Step 3: Migrate action thread to `Mailbox` (remove 50ms sleep-poll)
- [x] Step 4: Migrate audio thread to `Mailbox` (remove 10ms sleep-poll, route echo test through it)
- [x] Step 5: Separate stop signals (disconnect stops network only, audio keeps running)
- [x] Step 6: Flatten thread hierarchy (spawn all threads from main, not nested in sync thread)
- [x] Step 7: Reuse HTTP clients (one per thread instead of per-request)

### Backlog
- [ ] Event buffering for out-of-order sync — messages can arrive before their room is classified as a DM (m.direct update lags). TUI/Android have 300ms retry buffer. Fbclient may drop or misroute early messages.
- [x] Sync gap handling — backfill via GET /messages when timeline is limited.
- [x] Rate limit handling — retry up to 3× on HTTP 429 with retry_after_ms sleep.

## Android App (Kotlin)

### Backlog
- [ ] Offline message queue — store outgoing voice messages when disconnected, send on reconnect.

## TUI (TypeScript)

_(No open tasks)_

## All Frontends

### Backlog
- [ ] Disappearing messages — auto-delete after 24hrs once listened to. Needs server-side or client-coordinated retention policy.
- [ ] Group chat UX — family room works for broadcast, but no dedicated group conversation view distinct from DMs.

## Backend / Infrastructure

### Backlog
- [ ] Push notifications — requires switching from Conduit to Synapse (Conduit lacks push rule support).
- [ ] App store build — remove hardcoded credentials from all clients, add provisioning flow.
- [ ] Invite security — validate inviter is a family room member before auto-accepting. Needed for multi-tenant / public deployment.
