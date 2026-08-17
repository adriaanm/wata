# 0062 — iroh + enrollment on iOS: the phone logs in like a handset

Status: done (2026-08-17 — all four stages; stage 4 owner-verified on
the phone, including the cellular/relay leg)

## The problem

Plan 0061 stage 1 put wata-ios on the owner's iPhone but left it
loginless: iOS dropped the mac's login sheet, a phone has no env vars,
and plan 0061 parked the transport story at "LAN HTTP is enough to
iterate". Choosing a login surface exposed the fork: a
credential-carrying link into a password account works today but forks
the product's auth model — wata-fb's enrollment is iroh-native (the QR
carries the node id, approval allowlists it, the bound account is
passwordless, login is the iroh connection proving its identity) —
and stays LAN-only. The owner's ruling: no second login model. The
phone enrolls exactly like a handset, which also buys the thing daily
use actually needs — the phone working over cellular, away from home,
through iroh's relay.

The lift is smaller than it looks: plan 0034 already cross-builds the
irohnet Rust staticlib for `aarch64-apple-ios` and `-ios-sim`
(`go-pkgs/irohnet/mklib.py ios|ios-sim`, archives platform-asserted),
and left exactly one gap, named in its own comment: the Go-side iOS
cgo stanza belongs with the client that links it. The transport swap
is one seam (`caps.scala` → `go.irohnet.newHTTPClient`), and the
enrollment logic (`enrol.scala`: EnsureKey, the enroll URL, the
refused-because-not-allowlisted detection) is portable Sgola.

An iPhone improves on the handset's flow: it needs no QR and no second
device — the app opens its own enroll link in Safari
(`<adminUrl>/admin#enroll/<nodeId>/<nonce>`, default
`http://wata.local:8008`), the owner approves on that page, and the
app connects itself.

## Stages

1. **irohnet links on iOS.** Split the cgo stanza: GOOS=ios also
   carries the `darwin` tag, so today's darwin LDFLAGS line (with
   CoreWLAN, absent on iOS) must become `darwin,!ios`, and an `ios`
   line points at the staged lib (no CoreWLAN). Device vs simulator
   archives are arch-identical and NOT interchangeable and cgo tags
   cannot tell them apart, so the build harness stages the right one
   (mklib.py's platform assert is the guard). `ios-build-check` grows
   the irohnet leg.
2. **wata-ios grows the identity and the transport.** The iroh config
   is a sandbox file beside config.json (no env on a phone; the same
   JSON irohnet already speaks, adminUrl defaulting to wata.local),
   EnsureKey mints the node key at first boot, and the caps seam
   swaps the HTTP client for the iroh one when the config exists —
   wata-fb's shape with the env read replaced by the derived path.
3. **The enroll surface.** Port enrol.scala's core: when the connect
   wait ends refused-because-not-allowlisted, the boot screen becomes
   the enroll screen — the node-id short code plus one action: OK
   opens the enroll link in Safari (UIApplication openURL: — shell
   glue). Approval on the admin page follows the existing contract
   (announce-from-page, approve binds a passwordless account, the
   client connects itself on its own backoff).
4. **The device leg.** On foon over home LAN: enroll, approve in
   Safari, device-login, contacts. Then the real prize, checked
   explicitly: wifi off, the same session over cellular through the
   relay.

## Verification

Stage 1: irohnet builds for both iOS sysroots plus its existing tests
on darwin. Stage 2–3: the simulator gates — a dedicated
`just ios-enroll-smoke` (the plan's "grow ios-smoke" call was revised:
the enroll arc needs an iroh server, a fresh sandbox, and TWO harness
actors, its own gate keeps ios-smoke's stage-4 contract stable). It
drives the WHOLE flow — `simctl openurl` turned out to be both needed
and nontrivial: the configure link must come back IN (the revised
option-1 design), and delivering a custom scheme in the simulator
needs a pre-seeded scheme approval plus an explicit re-foreground
after every delivery (gotchas recorded in wata-ios.md and
apple-dev-tooling.md). Green 2026-08-17: fresh install → setup screen
→ configure → announce 200 → approve → restart → `ready
@phone:localhost` → contacts painted, 7.5s launch-to-verdict, no
password anywhere. Stage 4, owner-verified on the phone 2026-08-17:
the iroh-linked device build (`just ios-device` grew the archive
activation + `-tags iroh`) installed on the owner's iPhone, the home
server redeployed with the card (release 20260817-6f87d63), then the
real walkthrough — setup screen, Safari, "Add this phone", approve,
contacts — and the prize: with wifi OFF, the same session confirmed
live over cellular through the n0 relay. The phone logs in like a
handset, everywhere. (Voice itself is still the audio stub — MIC
FAILED by design; that is PTT-MIC-ROUNDTRIP, plan 0061 stage 4.)

## Out of scope

- The keychain crossing for the node key and token (sandbox files
  stay; plan 0061's ruling).
- APNs wake/background receive — foreground-only sessions until then.
- Retiring wata-mac's password login: the mac keeps its sheet; only
  iOS is enrollment-only.
