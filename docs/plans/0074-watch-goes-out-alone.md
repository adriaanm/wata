# 0074 — The watch goes out alone

Status: accepted

Owner rulings (2026-08-23):

- **Self-hosters bring their own Apple developer account.** APNs pushes
  go through the operator's own key and bundle id; wata does not proxy
  pushes for third-party servers. The hosted tier uses ours.
- **The ingress is Tailscale Funnel** — the owner already runs
  tailscale. Funnel terminates TLS *on the served node* (its ingress
  relays are SNI proxies carrying ciphertext), which keeps "the network
  is the trust boundary" intact; the ts.net hostname and HTTPS-only
  ports are fine because the URL is client config and the watch speaks
  HTTPS anyway.
- **Open question, deliberately not decided here:** once Funnel gives
  every self-hosted server a public HTTPS name, is iroh still earning
  its keep on the handsets? Its remaining distinct value: direct LAN
  paths when a kid is home, node-id-as-credential enrolment, and no
  dependence on Tailscale as a third party. Queued as
  `IROH-STILL-EARNING-KEEP`; nothing in this plan depends on the
  answer.

## The problem

The product moment the watch exists for: the owner walks out with just
the watch — no phone, no laptop — and the kids can still reach them over
wata. That means a standalone watch app on LTE (or foreign wifi) must
both reach the family's wata-server and be *reachable* while the app is
not running, because watchOS suspends everything within seconds of the
wrist dropping.

Two hardware facts frame the solution space (both proven on the Series
10, 2026-08-23; `docs/design/wata-watch.md`, "The socket wall"):

- **watchOS denies BSD sockets to third-party apps.** Go's net stack
  and `go-pkgs/irohnet`'s Rust (UDP for QUIC, TCP for relay and
  discovery) can never dial from the wrist. Networking is URLSession or
  nothing.
- Even a heroic port of iroh onto URLSession streams (iroh's WASM
  build proves relay-over-WebSocket is possible in principle) would be
  **relay-only forever** — no UDP means no holepunching — and
  relay-only iroh is functionally "all traffic through one public
  server", i.e. the same topology as plain HTTPS to a public endpoint,
  bought at the price of surgery inside a vendored Rust dependency.

## The decision

**The watch never speaks iroh.** Its transport is Matrix C-S HTTP over
NSURLSession, to a public HTTPS name; its inbound wake is an APNs alert.
iroh stays exactly what it is today: the handset↔server transport, the
thing that makes the self-hosted tier work with no port forwarding.

The public name is a server-edge concern, chosen per tier, and the
watch client is identical under every choice:

- **Self-hosted tier:** a tunnel on the home box (Tailscale Funnel or
  Cloudflare Tunnel) gives wata-server a public HTTPS URL with no port
  forwarding and no infrastructure to operate. Still "anyone who can
  set up a Pi."
- **Hosted tier:** the commercial server is public anyway; the watch
  talks to it directly.

Costs accepted with the decision: the watch loses iroh's
node-id-as-credential property (it authenticates by ordinary Matrix
login over TLS, which the wrist harness already does), and inbound
latency is notification-shaped — watchOS has no PushToTalk framework
and no VoIP PushKit, so "kid talks" lands as a time-sensitive banner
the parent taps to hear, not an instant speaker episode. That is the
best any third-party watch app can do.

## What changes

Most of the chain already exists. In dependency order:

1. **`WATCH-URLSESSION-HTTP`** (queued, top of `TODO.jsonl`): the
   NSURLSession-backed `HttpDo` behind `caps.scala` — purego,
   delegate-based, macaudio's shape. Required under every branch of
   this plan; nothing else on the watch moves until it lands.
2. **Foreground round trip on hardware**: `just watch-wrist` legs
   (login, sync, send, receive+play) against the LAN server, now over
   the URLSession HttpDo. Closes `WATCH-AUDIO`'s receive half.
3. **Watch push registration**: the watch client POSTs its APNs token
   to the existing `/_wata/v1/push/register` with a `watchos` platform
   value; `apnspush.scala` needs the watch app's bundle-id topic next
   to the iOS one. The fan-out, journaling, and 410 handling in
   `push.scala` are already built and need nothing.
4. **Notification receive in `go-pkgs/watchshell`**: the remote-
   notification delegate, carrying the payload's `room_id`/`event_id`
   so a tap opens the conversation and plays.
5. **The public name**: stand up a tunnel in front of the dev server
   (a `just` recipe once the shape settles), point the watch's config
   at it, and run the chain out of the house on LTE.

## Verification

- Steps 1–2: the existing simulator gates plus `just watch-wrist` on
  LAN — same oracles as today, transport swapped underneath.
- Step 3–4: a wrist leg where the app is *terminated*, a kid-side send
  fires, and the banner arrives and plays on tap.
- Step 5 is the acceptance test and is the plan's title: leave the
  house with only the watch; a message from a BQ268 in the kitchen
  reaches the wrist on LTE, and the reply reaches the kitchen.

## Out of scope

- Porting iroh (relay-over-WebSocket or otherwise) to watchOS —
  recorded here as rejected, with the relay-only argument above, so it
  is not re-derived.
- The hosted tier itself; this plan only requires *a* public HTTPS
  name, and a tunnel on the dev Mac satisfies it.
- Streaming/live PTT on the watch; inbound stays notification-shaped.
