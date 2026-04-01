# Tasks

**Current focus: Framebuffer client (Zig).** Planning doc: [docs/planning/framebuffer-client.md](docs/planning/framebuffer-client.md) — treat as a working document, update as things change. The **TUI is the most complete frontend** — use it as the reference implementation when docs are incomplete (read the TUI source, not just the docs).

## Framebuffer Client (Zig)

### Active
- [ ] Streaming playback stutter — audio_thread.zig writes period-at-a-time, causing underruns on BQ268. Echo test fixed (single large pcm_writei). **Blocked on**: getting the fbclient wata applet fully working end-to-end first. **When ready**: investigate partial buffering — decode N ms of audio before starting playback, stream the rest. Start with 500ms pre-buffer and tune down to find the minimum the hardware needs for smooth playback.
- [x] DM room creation — create room with `is_direct: true`, update `m.direct`. Lazy creation on first send.
- [x] Auto-join invites — auto-join all invited rooms during sync (trusted family environment).
- [ ] DM room deduplication — Matrix allows multiple DM rooms between two users. Android/TUI pick the oldest by creation timestamp. Fbclient sync_engine doesn't deduplicate — may show duplicate conversations or route messages to wrong room.

### Backlog
- [ ] Event buffering for out-of-order sync — messages can arrive before their room is classified as a DM (m.direct update lags). TUI/Android have 300ms retry buffer. Fbclient may drop or misroute early messages.
- [ ] Sync gap handling — after disconnect, sync may return `limited: true` with a `prev_batch` token. Fbclient doesn't paginate backward to fill gaps — messages sent during offline window are lost.
- [ ] Rate limit handling — Matrix homeservers return 429 with `retry_after_ms`. Fbclient HTTP layer doesn't retry on rate limits.

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
