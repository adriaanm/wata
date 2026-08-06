# 0034 — the iroh transport on Apple: wata-mac dials, and an iOS staticlib

Status: proposed

## The problem

Queue item `IROH-APPLE`. The mac client talks plain TCP to a homeserver it
can reach, which is exactly the case the product does not need: a parent
at home is standing next to the Pi. **The phone and mac clients exist for
a parent who is AWAY** — a different network, behind a NAT, no port
forwarding — and that is what the iroh transport is for. Until wata-mac
can dial over iroh it is a demo, not a client.

Most of the machinery is already here. `go-pkgs/irohnet` builds a darwin
staticlib today (`mklib.py`, `clib/darwin/libirohnet_ffi.a`) and its cgo
build supports darwin; `wata-tui` shows the whole client-side wiring in
about twenty lines of `caps.scala`; `tunnel-smoke` and `iroh-lan-smoke`
show how an iroh binary is built (`sgo build`, then a second `go build
-tags iroh` in the emit dir) and how a two-process iroh session is
driven. What is missing is the wata-mac side of it, a gate that proves
it, and — for the client after this one — an iOS staticlib that has never
been cross-built at all.

## The decision

**Copy wata-tui's transport seam into wata-mac verbatim, and make the mac
report an unavailable transport the way the DEVICE does, not the way the
tui does.**

The seam itself is settled: `WATA_IROH_CONFIG=<path>` swaps the
`go.net.http.Client` underneath `HttpDo`, nothing above the capability
line changes, and unset stays plain TCP. wata-mac copies that.

Where the mac follows **wata-fb rather than wata-tui** is the failure
policy. The tui downgrades a failed iroh init to `DefaultClient` and
prints a line — fine for an operator at a terminal who can read scrollback.
wata-fb learned the hard way (its own `irohClient` comment records it)
that silently downgrading leaves a client showing "waiting for network"
forever against a transport that was never coming up, so it LATCHES a
`transportUnavailable` state the boot screen names outright. wata-mac
runs wata-fb's screens — `WataLogic.body` already takes that flag, and
`stubs.scala` currently hard-codes it `false`. Making it real is a
three-line change that turns an existing screen honest, and it is the
whole reason the mac should not copy the tui here.

**The build stays opt-in.** `-tags iroh` is a second `go build` over the
emitted tree, needs cargo, and links a 20 MB staticlib; the ordinary
`just mac-build` must stay cargo-free. So `just mac-iroh-build` is its
own recipe producing its own binary (`wata-mac-iroh`), exactly as
tunnel-smoke and iroh-lan-smoke already do for the server and wata-fb.

### The iOS staticlib

`mklib.py` gains `ios` and `ios-sim` targets (`aarch64-apple-ios`,
`aarch64-apple-ios-sim`) staging into `clib/ios/` and `clib/ios_sim/`.
These are cross-builds of a crate that already cross-builds to armv7
musl; the Apple targets need no zig (Xcode's clang is the C toolchain
cargo picks up) and no linker work, because a staticlib is not linked
here.

**Stated honestly: nothing consumes them yet, so nothing executes them.**
The check is that cargo produces an archive of the right architecture and
platform (`lipo -archs`, and the Mach-O platform load command that
distinguishes device from simulator — the one mistake that survives a
naive arch check). That is a build-provenance check, not a behavior
check, and it is worth having now because it de-risks the item that comes
after it: `IOS-CLIENT-ASSEMBLY` should discover that iroh links on iOS
when it is wiring an app, not when it is discovering that the crate needs
a feature flag. The Go-side iOS cgo stanza and anything gomobile touches
is explicitly NOT in this plan — it belongs with the consumer.

## What changes

- **new** `wata-mac/src/main/scala/irohnet.scala` — the `go.irohnet`
  facade (wata-tui's, whose declarations it matches; it is a different
  object from `go.audio`, so `facade-check` gains a second pair — the
  table in `tools/facade-check.py` is one line per pair).
- `wata-mac/src/main/scala/caps.scala` — the `WATA_IROH_CONFIG` branch
  and the latched `transportDown` cell, following wata-fb's `irohClient`.
- `wata-mac/src/main/scala/stubs.scala` — `FbCaps.transportUnavailable`
  stops being a hard-coded `false` and reads the real cell. (It leaves
  the stub file, which is for things the mac genuinely does not have.)
- `wata-mac/sgo.build` — the `irohnet` godep.
- `go-pkgs/irohnet/mklib.py` — the `ios` / `ios-sim` targets.
- **new** `tools/mac-iroh-smoke.py` + `just mac-iroh-smoke`,
  `just mac-iroh-build`.
- `justfile`, `docs/design/wata-mac.md`, `docs/plans/0013-iroh-e2e.md`
  (its milestone record gains the Apple leg).

## How it is verified

- **`just mac-iroh-smoke`** (macOS + cargo; not in ci, like `mac-smoke`):
  one fresh `wata-server` over an embedded iroh listener with NO TCP wata
  port, two provisioned node keys with the client's allowlisted, then
  headless `wata-mac-iroh` with `WATA_IROH_CONFIG` set. It asserts the
  native hierarchy shows the contact list — i.e. login, sync and media all
  completed over iroh — and then bob (a tui session over the same
  transport) sends a voice message that arrives. `tunnel-smoke.py` is the
  reference for the provisioning and announce-file steps; reuse it rather
  than reinventing it.
- **The negative, in the same smoke**: `WATA_IROH_CONFIG` pointed at a
  bad config must show the boot screen's TRANSPORT UNAVAILABLE state, not
  "waiting for network". That assertion is the whole point of following
  wata-fb's policy, and without it the choice is untested.
- **`just facade-check`** covers the second facade pair.
- **`just mac-smoke`, `just ci`** unchanged and green — the TCP path must
  not move.
- **iOS**: `mklib.py ios` and `ios-sim` produce arm64 archives whose
  Mach-O platform is `ios` and `ios-simulator` respectively, asserted by
  the script itself. Nothing links or runs them.

## Out of scope

- The iOS client, gomobile, and any Go-side iOS cgo stanza —
  `IOS-CLIENT-ASSEMBLY` owns those and this plan deliberately stops at
  the archive.
- Relay/roaming behavior on the mac: `iroh-roam-smoke` already covers
  roaming with the device as the client, and the transport does not care
  which side is Apple.
- Onboarding/enrolment UX for a mac client (how a parent's laptop gets
  approved). Plan 0027 owns provisioning; this plan uses a pre-approved
  allowlist entry the way the smokes do.
