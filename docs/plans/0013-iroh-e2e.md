# 0013 — iroh end to end, embedded: no sidecar, no ports

Status: accepted

`[IROH-E2E]`

## Problem

Phase 4 of [0003](0003-parity-and-beyond.md) (as reordered). The iroh
spike proved a Rust sidecar tunnels on the host, but a sidecar is a
second process to ship, supervise, and provision on every node. The
ruling (2026-08-04): **push hard to avoid the sidecar** — both apps
embed iroh and stay drop-in single binaries.

## Shape

**`go-pkgs/irohnet`** — a plain Go cgo module (the `go-pkgs/audio`
pattern exactly: vendored native code, static link, cross-compiled by
our own scripts) wrapping iroh-ffi's C ABI. On top of it, the one piece
we write: `net.Conn` glue over iroh bidirectional streams (deadlines,
half-close), plus a `net.Listener` for the accept side. Iroh does all
networking — NAT traversal, relays, key auth, roaming; the glue is
boilerplate, not protocol.

- **Server**: `http.Server.Serve(irohnet.Listen(...))` — wata-server
  IS the iroh endpoint. The node-id allowlist is checked at accept,
  in-process. **No TCP port exists, not even loopback.**
- **Client**: an iroh-dialing implementation of the `HttpDo`
  capability (custom transport whose `DialContext` returns the
  stream-conn). Nothing above the capability line changes; LAN-direct
  HTTP remains available as a config choice (home use, harnesses).
- Both keep their existing plain-HTTP modes; iroh is a transport the
  config selects, so every test harness runs unchanged.

Provisioning for this sweep: a JSON config per node (node key, peer
node id, relay hint, allowlist) written by `just iroh-enroll`. The
no-camera device enrollment-by-sound is its own follow-up plan — a
behavior port of the original repo's proven **AudioCode**
(`src/shared/lib/audiocode.ts`: 16-MFSK 1500–3375 Hz + Reed-Solomon,
111-byte payload in 13.3s; `tui/bootstrap.ts` and the web
`OnboardingAudioService` are the reference implementations and oracle
— the web encoder means any phone browser can play the enrollment
sound). It rides this config format but must not gate the tunnel.

## Milestones (each a commit, riskiest first)

1. **The glue proves out on the Mac**: `go-pkgs/irohnet` builds
   (darwin), wata-server serves over an iroh listener and wataclient's
   iroh transport completes a full session, two processes on one
   machine. This is the go/no-go on iroh-ffi's stream semantics.
2. **armv7-musl cross-build**: Rust `armv7-unknown-linux-musleabihf`
   static lib + the audio module's cross-compile scripts extended;
   on-device smoke over the home LAN (device iroh → Mac server).
3. **Foreign-network E2E (the acceptance bar)**: device on a phone
   hotspot, full walkie-talkie round trip both directions; record
   connect latency, PTT-to-played latency, relay-vs-direct path.
4. **Roaming**: hotspot to LAN mid-session; reconnect rides the
   client's existing sync backoff; record recovery time.
5. **Allowlist enforcement**: an unenrolled node id is refused at
   accept (E2E negative test).

**Fallback, explicit**: if milestone 1 shows iroh-ffi cannot honestly
back `net.Conn` (stream semantics, callback model, or an unshippable
lib size), the spike's sidecar returns as a bounded plan revision —
recorded as a finding with the specific blocker, not silently.

## Out of scope

- Enrollment-by-sound (follow-up plan; independently useful).
- Pi server, invite flows, key rotation (phase 6 refinements).
- Self-hosted relay (recorded option; n0's relay fine for the sweep).
- Any change above the client's capability line or to the server's
  handler surface.

## Verification

Milestone 3 is acceptance: voice both ways, Mac kitchen server to
hotspot device, latencies recorded in the outcome. `just ci` gains
nothing network-dependent; milestone 1 adds a `tunnel-smoke` recipe
(two local processes) that CAN join the gate since it needs no real
network, and does.
