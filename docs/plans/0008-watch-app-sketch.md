# 0008 — Apple Watch client: architecture sketch

Status: proposed

`[WATCH-APP]`

A sketch, not yet a commitment: a first-party watch app speaking the
wata dialect, for parents. The governing platform fact: watchOS apps get
no persistent background networking, so the client is push-woken rather
than sync-driven. Platform claims below were verified against Apple
docs (2026-08-04); the load-bearing one is TN3135.

## Receive path (fully Apple-sanctioned)

- Standalone watch apps hold their own APNs token (watchOS 6+); wata-server
  gains a direct APNs pusher (HTTP/2, `.p8` token auth, no push gateway)
  and a registration endpoint `/_wata/v1/push`. Pushes are `alert` type,
  time-sensitive interruption (watchOS 8+), `mutable-content`.
- The ~4KB payload carries `{room_id, event_id, sender}`; a
  `UNNotificationServiceExtension` (watchOS 6+) fetches the audio on
  arrival and the custom long look (SwiftUI) shows sender + Play.
  Caveat from the field: read/copy attachment data, don't rely on
  security-scoped URLs (quirky on watch).

## Transport tiers (TN3135 rules the watch)

BSD sockets/UDP do not work on watchOS at all, and Network Extension is
absent — so iroh (userspace QUIC) can never run on the watch, despite
Rust targeting watchOS fine. Everything watch-side is URLSession.

1. **Home LAN**: direct HTTPS to the server.
2. **Away, phone nearby** (the normal parent case): the iPhone companion
   app embeds iroh via the official Swift bindings (iOS is supported)
   and relays over WatchConnectivity. The companion doubles as the
   parent's phone client.
3. **Away, standalone watch**: APNs still arrives (Apple infra), but
   the media fetch has no path to a NAT'd server — v1 shows "message
   waiting", played when a path returns. Revisit only if a reachable
   HTTPS endpoint ever exists.

## Audio: transcode at the server

Ogg is unsupported on Apple platforms; Opus-in-CAF on watch is
undocumented. So the server serves an AAC/M4A rendition of voice media
on request (lazy transcode, cached) and accepts AAC uploads, transcoding
to Ogg Opus for the fleet — no client-side codecs. Send path on watch:
foreground hold-to-talk, `AVAudioRecorder` (watchOS 4+) AAC upload.
Apple's Push To Talk framework is iOS-only and not needed.

## Accepted asymmetry

APNs is the one place self-hosting purity bends: pushes require an
Apple developer account and the server holding a push key, and the app
reaches real watches via personal-team sideload or TestFlight. The cost
is per-family-with-Apple-users; the Pi and the BQ268 fleet stay pure.

## Prerequisites in this repo (before any Swift is written)

- Plan 0007 landed (the watch client resolves DMs via the pair
  endpoint; no `m.direct` machinery to port).
- Server: APNs pusher + `/_wata/v1/push` registration + AAC media
  rendition endpoint. All three are useful independent of the watch
  (any future iOS client uses them unchanged).
