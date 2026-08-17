# 0062 — iroh + enrollment on iOS: the phone logs in like a handset

Status: accepted (owner ruling 2026-08-17)

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
on darwin. Stage 2–3: the simulator gates — ios-smoke grows an enroll
leg (fresh sandbox, assert the enroll screen and the composed URL;
`simctl openurl` is not needed since the link opens outward). Stage 4
is owner-in-the-loop on the phone.

## Out of scope

- The keychain crossing for the node key and token (sandbox files
  stay; plan 0061's ruling).
- APNs wake/background receive — foreground-only sessions until then.
- Retiring wata-mac's password login: the mac keeps its sheet; only
  iOS is enrollment-only.
