# 0017 — the minimal phone client: a web app the server serves

Status: abandoned (2026-08-05) — superseded by plan 0021. Ruling: the
phone client should be NATIVE (plan 0008's direction, not urgent), so a
web phone client is the wrong floor; the parts of this plan that were
really administration — the enrolment approval page and the
serve-HTTP-alongside-iroh listener — moved into plan 0021 (the admin
web interface). The inbound media-normalization idea returns only if a
client that records non-Ogg ever exists.

## Problem

`[PHONE-CLIENT]` Two needs point at the parent's phone. Enrolment (plan
0014, on hold) wants a camera to scan the QR a handset displays and an
approval action behind a parent's login. And the family wants a real
second client — a parent away from a handset who can hear and answer.
Plan 0008 sketches the first-class answer (native iPhone PushToTalk
client + watch), but it is a large Swift/APNs/App-Store-adjacent
commitment, and IROH-ONBOARD should not wait for it.

## Decision

The minimal phone client is a **web app that wata-server itself serves**
(`GET /app`, static files embedded in the binary). No store, no
sideload, no per-platform build — any phone with a browser on the family
LAN has the client, which keeps the self-hosting promise intact. Plan
0008 stays the eventual first-class parent client; this is the floor
that unblocks enrolment and covers "second client" now.

Three consequences shape the design:

1. **The server must speak TCP and iroh at once.** Today the transport
   is either/or at boot (`Server.serve`); a browser cannot speak iroh,
   so iroh-mode deployments would lock phones out. Change: when
   `WATA_IROH_CONFIG` is set the server serves iroh *and* a plain HTTP
   listener; `WATA_LISTEN` controls the bind (default `:8008`). The
   handler surface is one mux either way. The trust boundary holds
   because the network does: the TCP listener is reachable only on the
   family LAN, exactly like today's default mode. (This also serves the
   tui and the admin, not just phones.)
2. **Playback needs no transcode; sending does.** Safari 18.4+ (2025)
   plays Ogg Opus natively, as do Chrome/Firefox/Android — the web
   client plays fleet voice messages as served. Recording is the
   asymmetric side: iOS Safari's MediaRecorder emits AAC-in-mp4,
   Android emits Opus-in-webm. The server accepts those uploads and
   normalizes to Ogg Opus for the fleet by shelling out to `ffmpeg`
   (`WATA_FFMPEG`, default `ffmpeg` on `$PATH`). No ffmpeg → upload of
   a non-Ogg container is refused with a clear error; the BQ268 fleet
   and every existing path are untouched (already-Ogg uploads bypass
   normalization entirely). This is the lazy half of plan 0008's
   transcode story, built inbound-first because outbound (AAC
   renditions) has no consumer until the watch exists.
3. **Enrolment approval is a page, not an app feature.** The device's
   QR (plan 0014) encodes a URL on the server's LAN address:
   `http://<server>/app#enroll/<nodeId>/<nonce>`. The stock camera app
   opens it; the parent logs in (their ordinary wata account) and taps
   approve. The approval endpoint requires auth — which parent accounts
   have and handsets' accounts also have; if "any family member can
   approve" ever becomes wrong, power levels are the existing lever.
   This drops plan 0014's dependency on a *scanning* client: the phone
   needs no camera API at all, just a browser. Plan 0014's milestones 2
   (pending-enrolment endpoint) and 3 (device QR) plus this page are
   the whole unblock.

### The client itself

Small, dependency-free, hand-written (no framework): a single-page app
of three screens — login, conversation list (contacts + family channel,
unplayed counts), conversation (message list, tap to play, hold-to-talk
button via MediaRecorder) — polling `/sync` long-poll like any wata
client, DMs resolved through `POST /_wata/v1/dm/{userId}`. Session in
`localStorage`. It reuses the wire behaviors `wataclient` documents
(net.wata.dm classification, receipts as played-markers) but is its own
tiny JS implementation — porting the Sgola sync engine to the browser is
plan 0010's wasm question, deliberately not reopened here.

## What changes (file-level)

- `wata-server/src/main/scala/server.scala` — dual-listener boot.
- `wata-server/src/main/scala/webapp.scala` (new) — static `/app`
  routes over Go `embed` (via a small app-owned facade if the dialect
  needs one).
- `wata-server/src/main/scala/normalize.scala` (new) — the inbound
  media normalization edge (`ffmpeg` exec facade, content-type sniff,
  refusal path).
- `wata-server/webapp/` (new) — the static HTML/JS/CSS.
- Enrolment endpoints land with plan 0014 milestone 2, not here; the
  `#enroll` screen ships dormant until they exist.
- `docs/design/wata-server.md` — transport + webapp + normalization
  sections; `docs/family-model.md` untouched (no new concepts: a phone
  user is just a user).

## Verification

- Server side: conformance stays 84/84; a new integ leg uploads an
  AAC-in-mp4 fixture and asserts the stored media is Ogg Opus that the
  device path can play (frame-count via the existing Ogg reader), and
  that no-ffmpeg mode refuses loudly.
- Client side: a Playwright (or plain fetch-driven) smoke against a
  fresh server — login, list, play (fetch bytes), record-shaped upload,
  message appears on a second session's sync. Kept standalone
  (`just webapp-smoke`), browser-matrix testing is manual.

## Out of scope

- Push/wake when the app is closed (needs APNs/FCM — plan 0008's
  territory). The web client hears messages only while open; that is
  accepted for the floor.
- Away-from-home phone use (browser can't iroh; a hosted/relay tier or
  the native app solves it later).
- The watch, PTT framework, AAC *renditions* (outbound transcode).
- Multi-family/hosted anything.
