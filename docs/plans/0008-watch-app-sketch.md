# 0008 — Apple clients (iPhone + Watch): architecture sketch

Status: proposed

`[WATCH-APP]`

A sketch, not yet a commitment: first-party Apple clients speaking the
wata dialect, for parents. Platform claims below were verified against
Apple docs and beta reporting (2026-08-04).

Market context: Apple's own Walkie-Talkie app (FaceTime-audio based,
watchOS-only) is gone from the watchOS 27 betas (June 2026, no Apple
statement) — the niche wata exists to fill is being vacated, and the
PushToTalk framework is now Apple's only sanctioned walkie-talkie path
(the old unrestricted VoIP PushKit entitlement is disabled in the
iOS 26 SDK). PTT does **not** exist on watchOS, so the roles are:
**iPhone = first-class PTT client** (PushToTalk framework), **Watch =
push-woken listener** (no persistent background networking; TN3135
additionally bans sockets/UDP there).

## iPhone: the PushToTalk framework client

iOS 16+, entitlement `com.apple.developer.push-to-talk` (self-service,
no Apple approval). The framework supplies signaling, the system PTT
UI (Dynamic Island pill with speaker + talk/leave controls, usable from
the lock screen), and audio-session arbitration — **transport is
entirely ours**: we bring our own protocol and codec (Apple's guide
even suggests QUIC, which is exactly the iroh direction).

- **Receive**: server sends `apns-push-type: pushtotalk` (topic
  `<bundle-id>.voip-ptt`) to an **ephemeral channel token** — a new
  token on every channel join, delivered via
  `channelManager(_:receivedEphemeralPushToken:)`, dead after leave.
  The `/_wata/v1/push` registration must model this as per-session
  state, distinct from the watch's stable APNs token. On push the app
  wakes in the background, reports the active speaker, and streams the
  audio itself — background playback with no fixed time limit.
- **Transmit**: begin-transmit works from the system UI, wired-headset/
  CarPlay toggles, CoreBluetooth accessory events, and an App Intent
  (iOS 17.4+ — Action button as a hardware PTT key). The framework owns
  audio-session activation; recording must go through AVAudioEngine-level
  APIs (AVAudioRecorder self-activation breaks PTT).
- **Constraints that shape us**: joining a channel requires a
  foregrounded user action (the server can never conscript a phone into
  a channel — after reboot or an explicit Leave, the user must reopen
  the app once); one PTT channel system-wide, so the whole family is
  one channel whose descriptor mutates, not a channel per room;
  half-duplex default; cellular calls preempt transmit.

## Watch receive path (fully Apple-sanctioned)

- Standalone watch apps hold their own APNs token (watchOS 6+); wata-server
  gains a direct APNs pusher (HTTP/2, `.p8` token auth, no push gateway)
  and a registration endpoint `/_wata/v1/push`. Pushes are `alert` type,
  time-sensitive interruption (watchOS 8+), `mutable-content`.
- The ~4KB payload carries `{room_id, event_id, sender}`; a
  `UNNotificationServiceExtension` (watchOS 6+) fetches the audio on
  arrival and the custom long look (SwiftUI) shows sender + Play.
  Caveat from the field: read/copy attachment data, don't rely on
  security-scoped URLs (quirky on watch).

## Watch transport tiers (TN3135 rules the watch)

BSD sockets/UDP do not work on watchOS at all, and Network Extension is
absent — so iroh (userspace QUIC) can never run on the watch, despite
Rust targeting watchOS fine. Everything watch-side is URLSession.

1. **Home LAN**: direct HTTPS to the server.
2. **Away, phone nearby** (the normal parent case): the iPhone app —
   now a first-class PTT client, not a mere relay — embeds iroh via the
   official Swift bindings (iOS is supported) and relays to the watch
   over WatchConnectivity. Note the ceiling: WatchConnectivity cannot
   wake the phone into background *transmission* (PTT begin-transmit
   triggers are limited to system UI, accessories, headset toggles, and
   App Intents), so the watch relays received audio fine but wrist-
   initiated talk goes over HTTPS, not through the phone's PTT channel.
3. **Away, standalone watch**: APNs still arrives (Apple infra), but
   the media fetch has no path to a NAT'd server — v1 shows "message
   waiting", played when a path returns. Revisit only if a reachable
   HTTPS endpoint ever exists (a hosted tier would provide exactly
   this).

## Audio: transcode at the server

Ogg is unsupported on Apple platforms; Opus-in-CAF on watch is
undocumented. So the server serves an AAC/M4A rendition of voice media
on request (lazy transcode, cached) and accepts AAC uploads, transcoding
to Ogg Opus for the fleet — no client-side codecs on the watch. The
iPhone PTT client may later carry its own Opus codec (transport is
app-owned there), making the AAC rendition a watch-only concern; the
server endpoint serves both regardless. Send path on watch: foreground
hold-to-talk, AAC upload via AVAudioEngine-level recording (on iOS,
AVAudioRecorder's session self-activation breaks the PTT framework;
using the same recording layer on both keeps one code path).

## Accepted asymmetry

APNs is the one place self-hosting purity bends: pushes require an
Apple developer account and the server holding a push key, and the app
reaches real watches via personal-team sideload or TestFlight. The cost
is per-family-with-Apple-users; the Pi and the BQ268 fleet stay pure.

## Prerequisites in this repo (before any Swift is written)

- Plan 0007 landed (the watch client resolves DMs via the pair
  endpoint; no `m.direct` machinery to port).
- Server: APNs pusher + `/_wata/v1/push` registration + AAC media
  rendition endpoint. The pusher speaks two push shapes: `alert` to the
  watch's stable token, `pushtotalk` to the phone's ephemeral per-join
  channel token — registration models token lifetime accordingly. All
  three are useful independent of any single Apple client.
