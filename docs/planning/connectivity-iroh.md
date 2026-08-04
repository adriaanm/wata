# Connectivity via Iroh

Status: **decided — iroh; spike in progress**
Owner: — 
Last updated: 2026-07-12

## Decision

Transport = **iroh**. The two hard product constraints — **no subscription /
account** and **deployable by non-technical users** — eliminate every
alternative: Tailscale and Cloudflare Tunnel need an account (and Cloudflare a
domain); go-libp2p / Nebula / Headscale need a self-hosted public VPS; routable
IPv6 isn't universal and needs firewall config. iroh is the only option that
needs no VPS, no account, and no ISP cooperation while still hole-punching
directly (E2E; only ~10% relayed). See the alternatives table below.

**The decision is actually binary.** The moment a VPS or paid subscription
enters the picture, you may as well host `wata-server` *on that VPS* directly —
plain client-server, no NAT traversal, no P2P. That makes the VPS-based options
(Nebula lighthouse, go-libp2p relay, Headscale) **strictly dominated**: they cost
a public node *and* add P2P machinery *and* still require the home box. So there
are only two coherent deployment models:

- **(A) VPS-hosted `wata-server`** — traditional, but costs money and needs a
  technical operator (or a hosted offering). Rejected by our constraints.
- **(B) Home-box `wata-server` + iroh** — free, no account, data stays home,
  E2E. The only no-cloud model, and the one we're building.

iroh isn't merely the best-scoring option; it's the *only* tool that makes model
(B) exist. (Aside: even if you accepted a VPS, a privacy/data-locality argument
exists for "VPS as dumb relay, data stays home" — but iroh delivers exactly that
benefit, home data + E2E, for free, so the nuance is moot here.)

### Roadmap: two tiers, one server (no fork)

Product direction: ship **model (B), free & self-hostable, first**; add a
**hosted tier (model A)** later for users who can't self-host. Crucially these
are **not an architectural fork** — both tiers run the *same* `wata-server`; only
how the client reaches it differs:

- **Self-host (B):** client `base_url` → local iroh-tunnel port (`127.0.0.1:8009`)
  → iroh → home box.
- **Hosted (A):** client `base_url` → `https://<public-host>` → the same
  `wata-server` on a VPS. No tunnel, no iroh.

The client already treats `base_url` / `homeserverUrl` as configurable, so
connectivity is effectively pluggable per-deployment with **zero client
divergence**. Choosing iroh now therefore carries no cost for the future hosted
tier — the hosted tier is just "we run the home box for you, with a public URL."

**Where the difficulty actually lives now:** iroh makes the *connection*
non-technical, not the *deployment*. The remaining hard problem is packaging the
always-on server so a non-technical parent can install and keep it running on
home hardware (Mac mini / old Android phone), plus QR-based NodeID provisioning
at setup. That work belongs in `onboarding-research.md` — connectivity is solved,
onboarding is the frontier.

**Caveat (record, don't worry):** the zero-config path depends on n0's free
public relays + DNS. No cost, but a third-party-continuity dependency. If n0 ever
stopped, connectivity degrades to needing a self-hosted relay (= a VPS).
Mitigated by the stable 1.0 wire protocol and self-hostable `iroh-relay` /
`iroh-dns-server`. Acceptable for a family project.

## Problem

To use Wata, a client (a kid's walkie-talkie, on cellular or foreign wifi) must
reach the always-on Wata homeserver running somewhere on the family's home
network (Mac mini, Apple TV, old phone plugged in). That box sits behind a
residential NAT with:

- no static IP,
- no port forwarding (we don't want users touching their router),
- a dynamic address that churns.

Today the only ways across are port-forwarding + dynamic DNS (fragile, requires
router access) or a cloud VPS (the thing we explicitly want to avoid). We want
"anyone can run this, no cloud account, no router config."

## Why not fully P2P

Wata is **asynchronous** voice messaging: the recipient is usually offline when
you send. That requires an always-on store-and-forward node **regardless of
transport**. A pure-P2P model where both parties must be online simultaneously
defeats the core use case. And a P2P model with a designated always-on relay
node lands you right back at "an always-on box everyone syncs through" — i.e.
the server — after a large rewrite (throwing away Matrix's rooms, sync, state
resolution, media repo; cf. the abandoned Matrix-P2P / Dendrite-Pinecone work).

**Decision: keep Matrix client-server. Fix the transport, not the model.**

## Why Iroh

[Iroh](https://docs.iroh.computer) shipped **1.0** (June 2026; local checkout at
`~/g/iroh`, crate `iroh 1.0.2`). It gives us:

- **Dial-by-public-key.** Clients connect to a stable `NodeID` (Ed25519 pubkey),
  not an IP. The home box's address can change freely and nothing breaks. We
  provision the NodeID *once* at setup (QR code) — this eliminates dynamic DNS,
  the single most fragile piece of any self-hosted setup.
- **Hole punching (~90%) + stateless relay fallback.** When direct P2P fails,
  encrypted packets route through a relay the user never runs. Relays are
  stateless, can't decrypt (E2E), free from Number 0, and self-hostable
  (`iroh-relay` is in-tree).
- **Automatic discovery.** The `N0` preset
  (`~/g/iroh/iroh/src/endpoint/presets.rs`) bundles a **pkarr publisher** (the
  box signs & publishes its *current* relay + direct addresses under its own
  key) + **DNS address lookup** (clients resolve those by NodeID) + **default
  relays**. Practical consequence: we provision **only the 32-byte NodeID** into
  clients — short, stable, QR-able — and discovery resolves the live location
  every time. No IPs, no full addresses hand-copied.
- **Carries QUIC streams**, so we tunnel the Matrix Client-Server API over it
  unchanged.
- **Licensing:** MIT/Apache-2.0. Fine.
- **Wire-protocol + binding stability guarantees** as of 1.0.
- **Post-quantum** key exchange (X25519MLKEM768) is available (the docs FAQ is
  stale on this). Caveat: n0's public relay/discovery don't do PQ yet, so
  PQ-*only* forces self-hosting relay+DNS; *preferred*-PQ (hybrid) keeps n0
  infra. Nice-to-have, not a blocker.

### The one dependency to be honest about

By default we rely on Number 0's relay + DNS infra (free, stateless,
E2E-encrypted so they see nothing). If that becomes unacceptable, `iroh-relay`
and `iroh-dns-server` in the same repo let us self-host both. That's a v2
concern, not a blocker for the spike.

## Open question — can the client just link an Iroh lib? (no sidecar)

Yes, for most clients. Iroh 1.0 ships official, registry-published bindings via
[`iroh-ffi`](https://github.com/n0-computer/iroh-ffi) — a minimal FFI mirroring
the Rust 1.0 API — plus a native Node binding. Per-client picture:

| Client | Language | In-process option | Notes |
|---|---|---|---|
| **TUI** | Node/TS | `@number0/iroh` (napi Node binding) | In-process. No sidecar. |
| **Android** | Kotlin | official Kotlin binding via `iroh-ffi`, **Maven Central** | In-process. Best fit — one app, no second process. |
| **Web** | browser | iroh **wasm** build (`wasm_browser` support is in the main crate) | Works, but **relay-only**: browsers can't hole-punch (need WebRTC), so all traffic goes via a relay. |
| **fbclient** | Zig | **no official binding** → thin Rust `cdylib` exposing a small C ABI, linked via Zig `@cImport` | ~100 lines. The `iroh` crate is already `crate-type = ["lib","cdylib"]`. `iroh-ffi` is uniffi-based (Swift/Kotlin/Python/JS), so it does **not** hand us a clean C header for Zig — we'd write our own tiny shim. |

So "link a client lib" is real and easy for Android and Node; a relay-limited
wasm story for the browser; and for the Zig client it means writing a small
C-ABI Rust shim (no off-the-shelf Zig binding exists).

### But: prefer the sidecar for the spike (and probably v1)

Every Wata client already talks HTTP to a single configurable base URL:

- TUI/Web → `MATRIX_CONFIG.homeserverUrl` (`src/shared/config/matrix.ts:34`),
  already `http://localhost:8008`.
- Android → `MatrixConfig.kt`.
- fbclient → `std.http.Client` against `base_url` (`src/fbclient/src/matrix/http.zig`).

A **tunnel sidecar** — one small Rust binary per side — lets every client stay
dumb: point the base URL at a local tunnel port and change nothing else.

```
[client app] --HTTP--> 127.0.0.1:PORT  ==iroh QUIC==>  server tunnel --HTTP--> 127.0.0.1:8008 [wata-server]
```

Trade-off:

- **Sidecar:** one integration, four dumb clients, a supervised process per
  device. Zero FFI anywhere — notably keeps the Zig fbclient pure Zig, avoiding
  Rust in the ARM cross-compile.
- **In-process link:** no second process at runtime, but N binding integrations
  and (for Zig) a custom C shim.

**Recommendation:** use the sidecar uniformly for the spike and likely v1. Later,
consider in-process linking *only* where it removes operational pain — most
compelling for Android (a foreground service, no separate process) and the Node
TUI. Keep the sidecar for the Zig fbclient regardless: a supervised localhost
tunnel is simpler on the device than Rust FFI in the Zig build.

## Transport alternatives considered

Iroh is the current pick, but the transport choice is coupled to the **language
choice** — especially a possible standardization on Go (moving the fbclient off
Zig, and/or a Go server). Iroh has **no native Go binding** (uniffi-bindgen-go is
cgo + version-alignment friction), so "all-Go + iroh" effectively means keeping
the Rust sidecar forever — the one non-Go component.

### The topology that decides this

Wata is a **star, not a mesh**: every client only ever reaches **one** node — the
always-on home box (homeserver / store-and-forward). Clients never dial each
other. So the problem is not general P2P; it is precisely:

> roaming clients must reach one fixed server that sits behind residential NAT.

### The reachability trap (why the home box can't be the coordinator)

Every coordination-based transport needs a **publicly reachable** coordinator/
relay: nodes report their address to it and query it, and NAT'd nodes can only
reach it if it has a routable inbound address. **The home box is behind
residential NAT, so it cannot be that coordinator** — that's the very problem
we're solving. So in the self-hosted alternatives the always-on device plays *no*
infra role; it stays just the app server, and you additionally need a separate
publicly reachable node.

| Option | Role of the always-on home box | Reachable coordinator/relay | Provided by |
|---|---|---|---|
| **iroh** | App server only; reaches n0 **outbound** (publishes addr to DNS, dials relay). No inbound ever. | yes | **n0 — free, hosted** |
| **Tailscale (hosted)** | App server; joins tailnet outbound | yes (control plane + DERP) | **Tailscale — free, needs account** |
| **Headscale (self-host)** | App server | yes | **You — public VPS** |
| **Nebula** | App server; *not* the lighthouse | yes (lighthouse, routable IP) | **You — public VPS** |
| **go-libp2p** | App server | yes (bootstrap + Circuit Relay v2) | **You — public VPS** |
| **pion/webrtc** | App server | yes (STUN/TURN + signaling) | **You / hosted** |

Only **iroh** (and Tailscale-hosted) give a reachable coordinator for free. iroh's
design specifically rescues the NAT'd box by making it work **entirely outbound**:
it publishes a signed pkarr record *up* to n0 DNS (clients read it), and holds an
*outbound* connection to a relay that brokers the hole-punch / relays on fallback.
No inbound to the box, no router config, no account. The self-hosted native-Go
options all push you back to renting a public node — the cloud dependency we're
trying to eliminate.

### Escape hatch: routable IPv6

If a user's home box has a **routable IPv6 address with inbound allowed** (some
residential ISPs now provide this, though firewalls often block inbound), the box
*is* directly reachable and the native-Go options open up without a VPS. Can't be
relied on across arbitrary networks, so it's an optimization, not a baseline —
but worth testing on the real home connection, since if it holds the all-Go story
gets clean (go-libp2p / Nebula / plain QUIC-over-v6).

None of these interoperate with an iroh server, so choosing one commits the
**whole** transport to it. The crux:

- **Value free hosted relay+discovery, don't mind one Rust binary** → iroh +
  sidecar (works with any client language, Go included).
- **Want all-Go purity AND accept one public coordinator node (VPS or account)**
  → Tailscale (hosted, needs account) or self-hosted Headscale / Nebula /
  go-libp2p.
- **Confirm the home box has routable inbound IPv6** → native-Go transports
  become viable with no extra infra; strongest all-Go path.

This decision should be made together with the Zig→Go question, not before it.

## Spike plan

Prove hole punching works on a real home network before touching Android/Zig.

1. **`iroh-tunnel` Rust binary** (`src/iroh-tunnel/`) with two modes:
   - `listen` (server side): accept iroh connections on an ALPN, pipe each bidi
     stream to a local TCP addr (the homeserver). Prints its NodeID at startup.
   - `connect <nodeid>` (client side): listen on a local TCP port, pipe each
     accepted connection over a fresh iroh stream to `<nodeid>`.
2. **`just` recipes** to run both ends.
3. **Wire the TUI**: point `homeserverUrl` at the client tunnel port.
4. **Test**: TUI ↔ wata-server across two real networks (home box + phone
   hotspot). Confirm a direct hole-punched connection (check `remote-info` /
   connection type), then confirm relay fallback by forcing it.

Follow-ups once proven: NodeID provisioning UX (QR from the server box),
Android foreground-service tunnel, on-device (BQ268) tunnel, self-hosted
relay/DNS evaluation, preferred-PQ.

## References

- Iroh FAQ: https://docs.iroh.computer/about/faq
- Languages/bindings: https://docs.iroh.computer/languages
- `iroh-ffi`: https://github.com/n0-computer/iroh-ffi
- Local checkout: `~/g/iroh` (crate `iroh 1.0.2`), esp.
  `iroh/examples/echo.rs`, `iroh/src/endpoint/presets.rs`.
