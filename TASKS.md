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

- [ ] **Ogg container tests** — port from `src/shared/lib/__tests__/ogg.test.ts`. CRC32 validation, OpusHead/OpusTags structure, page creation with segment tables, mux→demux roundtrip. Pure Zig, no external deps — highest value.
- [ ] **Queue tests** — BoundedQueue (queue.zig) has zero test coverage. Test push/pop, full buffer, drain, MPSC ordering.
- [ ] **Sync engine tests (expand)** — extend existing 7 tests. Port coverage from `matrix.test.ts`: m.direct dedup (multiple rooms per contact), family room detection by alias, roomless family member conversations, invite processing.
- [ ] **HTTP helpers tests** — parseRetryAfterMs, parseMxcUrl, parseRoomId, updateMDirect JSON manipulation. Pure string logic, easy to test.
- [ ] **Opus codec roundtrip** — port from `audio-codec.test.ts`. Encode PCM→Opus→Ogg→decode→PCM, verify sample count and basic signal preservation. Requires `use_audio` build flag.

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
