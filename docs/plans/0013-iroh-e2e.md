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
4. **Roaming**: a network flip mid-session (cellular to LAN or back);
   reconnect rides the client's existing sync backoff — transport-level
   reconnect, NOT seamless QUIC path migration (ruled 2026-08-04:
   seamless migration explicitly out of scope; PTT messages are async,
   so seconds of reconnect are acceptable). Acceptance: after the flip,
   sync resumes within 30s of the new network being usable, no messages
   lost, and — the ruling's condition — the UI is clear about it: the
   wata UI grows a connectivity status element (wifi / cellular /
   reconnecting) so a kid can see why the radio went quiet
   ([FB-CONN-STATUS]). Each cellular flip test costs a reboot (the
   one-data-call-per-boot kernel constraint above).

   Design for the status element (ruled 2026-08-05):
   - **Placement**: the wata applet's header, right end — the slot the
     `ok`/`..`/`ERR`/`off` indicator occupies today; this element
     *replaces* that indicator rather than sitting beside it (two
     truth-claims about the connection would have to agree).
   - **Two inputs, one glyph + one word.** The *pipe* comes from the
     diagnostics sources settings already polls every ~5s
     (`Diag.wlanIp` for wlan0, the ppp0 sysfs node for cellular): a
     wifi glyph (font 0x80-0x8B already has one) or a new `CELL` glyph
     (one font-table addition, regenerated the way the table documents).
     The *health* comes from `wataclient`'s `ConnectionState`, which is
     the only honest source — an interface with an IP whose sync is
     erroring is "reconnecting", not "on wifi".
   - **States a kid sees**: glyph alone (connected via that pipe);
     glyph + `..` alternating at the existing tick rate (reconnecting —
     `Connecting`/`ConnError` with an interface up); `OFF` (no
     interface has an address); the 1px status line keeps its existing
     colors and stays consistent with the header by deriving from the
     same computed state.
   - **Off-device** (host builds, sim, uitest): the diag sources answer
     `n/a`, which maps to a plain `NET` label with the same health
     states, so goldens stay deterministic without faking interfaces;
     a uitest scenario pins connected/reconnecting/off frames by
     scripting the connection state.

   Status: the element LANDED (the flip test itself is still owed).
   `wata-fb/src/main/scala/netstatus.scala` computes one `NetState` per
   frame — pipe from the diag sources on their ~5s cadence, health from
   `ConnectionState` — and both the wata applet's header slot (the old
   `ok`/`..`/`ERR`/`off` indicator is gone, not moved) and the 1px
   status line draw from it, so they cannot disagree. The cellular mast
   went into the font table by hand at 0x8C (the generator script is
   absent; the table says so). The `conn-status` uitest scenario pins
   live / reconnecting on both phases of the `..` / disconnected /
   wifi / cellular / `OFF`, using two uitest-only overrides (`conn`,
   `netpipe`) because the sync loop republishes `Syncing` every
   snapshot and a host has no wlan0 to point at. What remains for this
   milestone is the roaming flip itself on hardware — the element is
   what makes that test readable. See docs/design/wata-fb.md, "The
   connectivity element".
5. **Allowlist enforcement**: an unenrolled node id is refused at
   accept (E2E negative test). DONE (2026-08-04): lives as the
   `allowlist-negative` leg of `just tunnel-smoke`, so every `just ci`
   run proves it — a server allowlisting one key refuses an intruder
   key's dial at accept while the positive scenarios pass. Observed:
   the QUIC close reason (401 "not allowlisted") does not propagate
   into the intruder's client error text, which reads as a generic
   closed connection — enforcement is server-side at accept, which is
   the property that matters. Surfacing the reason is queued as
   [IROH-REFUSAL-LOUD]; design ruled 2026-08-05 after reading the FFI:

   - Where it is lost: the server `conn.close(401, b"not allowlisted")`
     lands on the client as a `ConnectionError::ApplicationClosed`
     carrying code+reason, but `irohnet_client_dial`
     (`go-pkgs/irohnet/rust/src/lib.rs`) flattens every connect/open_bi
     failure to `format!("{e:?}")` — and the cached-connection branch
     (`Err(_) => *guard = None`) drops the close error entirely before
     redialing.
   - Fix at the layer that has the information: match
     `ApplicationClosed` in both dial branches and format
     `"server refused: <code> <reason-utf8>"`; remember the last
     refusal on the client handle so the redial loop stays quiet after
     the first loud line.
   - Go/caps: the dial error string already reaches `go.irohnet` and
     the app edge; log it once per distinct reason (the portable core's
     `HttpResponse(0, "")` contract stays — the core never sees Go
     errors). A distinguishable "NOT ENROLLED" UI state rides the
     FB-CONN-STATUS element later, gated on enrolment existing (plan
     0014) — until a device *can* enrol, a dedicated screen for "not
     enrolled" has no action to offer beyond the log line.

   [IROH-REFUSAL-LOUD] outcome (2026-08-05): landed. The reading above
   undersold the gap — the close reason is lost at *two* independent
   points, not one, because opening a bidirectional stream is local-only
   (bounded by the peer's already-negotiated stream limit), so it
   routinely succeeds against a connection the peer is already closing;
   the refusal then only surfaces on the first stream read/write, not on
   `open_bi` itself. So `irohnet_client_dial` matches
   `ConnectionError::ApplicationClosed` on the cached-connection branch,
   the fresh `connect()` (`ConnectError::Connection { source }`), *and*
   the fresh `open_bi()` — three sites, not two — formatting each as
   `"server refused: <code> <reason-utf8>"`
   (`format_application_close`). A fourth site had no reason-carrying
   error at all: `irohnet_stream_read`/`irohnet_stream_write`'s
   `Err(_) => RET_ERR` collapse was, and stays, protocol-only (no
   `err_out` parameter) — so a client-side `StreamState` now carries an
   `Option<Arc<ClientState>>` back-reference, and a read/write that
   unwraps to `ReadError`/`WriteError::ConnectionLost(ApplicationClosed)`
   records the reason on `ClientState.last_refusal`
   (`record_stream_refusal_read`/`_write`) instead of losing it. A new
   FFI call, `irohnet_client_last_refusal`, lets the Go `Conn` recover
   that reason when a stream op returns `RET_ERR`
   (`Conn.errForRet`, `irohnet_cgo.go`) — this is also what "remember the
   last refusal on the client handle" turned out to buy operationally:
   once a connection has been refused, its handle is *kept* (not cleared
   to force a redial) so every later dial call fails fast, locally, on
   the same dead connection instead of repeating a doomed handshake.
   `Dialer.logDialError` (`irohnet_cgo.go`) prints the reason once per
   distinct string — both the dial path and the stream-I/O path funnel
   through it, so server-side client use and wata-fb/wata-tui share the
   one log line for free. `tools/tunnel-smoke.py`'s allowlist-negative
   leg now asserts `"not allowlisted" in (stdout+stderr)` and `fail()`s
   the gate outright if a refusal is ever loud=False again (previously it
   only printed the marker). Verified: `just tunnel-smoke` and `just ci`
   both green, `refusal loud: True`. The intruder sees exactly
   `irohnet: dial <peer>: server refused: 401 not allowlisted` at the
   `net.Conn`/HttpDo boundary — the portable core still never observes
   it (folded into `HttpResponse(0, "")` as before), only the process's
   stdout does, via `logDialError`. No UI change; deferred to enrolment
   (plan 0014) as ruled.

   Review amendment (same day): the keep-the-dead-connection fast-fail is
   bounded by a 30s `REFUSAL_COOLDOWN` (`ClientState.refused_at`) —
   cached-forever would have locked a refused client out until process
   restart, which breaks plan 0014's approve-while-the-device-retries
   enrolment loop. Within the cooldown a dial answers fast and locally
   from the dead connection; past it, one fresh handshake is allowed, so
   an approved device connects on its next backoff round. Every refusal
   sighting (dial or stream I/O) re-stamps the cooldown.

**Milestone 1 outcome**: done (merged) — `go-pkgs/irohnet` proves out on
darwin; `just tunnel-smoke` joins `just ci`.

**Milestone 2 outcome**: done. The Rust staticlib cross-builds for
`armv7-unknown-linux-musleabihf` (`go-pkgs/irohnet/mklib.py arm` — rustup
target std + `zig cc` for ring's C sources), the same cgo glue compiles for
linux/arm (`-lunwind` added there: Rust std wants `_Unwind_*`, satisfied
statically by zig's bundled LLVM libunwind), and the on-device smoke passed:
`just iroh-lan-smoke` cross-builds an iroh-tagged wata-fb (static musl ELF,
~29 MB), boots wata-server over an embedded iroh listener on the host,
deploys to the BQ268's `/dev/shm`, and the device completes the
`login-syncing` integ scenario over iroh — relay "none", direct LAN
addresses, no TCP port on the server. Announce now expands an unspecified
bind host to the host's interface addresses so a LAN peer gets dialable
addrs. The smoke needs hardware, so it stays out of `just ci`; it is the
on-device gate for this plan. Toolchain specifics milestone 3 inherits: the
`iroh` tag never rides `sgo build` (no tag passthrough) — iroh-tagged
binaries are a manual `go build -tags iroh` in the emit dir, cross ones
under `GOOS=linux GOARCH=arm GOARM=7 CC="zig cc -target arm-linux-musleabihf"`
with `-ldflags "-linkmode=external -extldflags=-static"`; cargo/rustc must
resolve via `rustup which` (a distro rustc earlier on PATH has no cross
std); milestone 3 swaps relay "none" for "n0" in the two configs and needs
no new build machinery.

Milestone 3: DONE (2026-08-04). Cellular replaced the phone hotspot as
the foreign network — the BQ268 has live LTE data (PPP over the modem's
SMD tty), a strictly stronger test (CGNAT, no shared LAN). The gate is
`just iroh-roam-smoke`: stage over ssh, then wifi-down → cellular-only
dial (relay "n0", id-only, no peerAddrs) → wifi-restore, driven over
the USB serial console since ssh dies with wifi. Measured on LTE
(~300ms RTT): `login-syncing` 10.4s wall, `voice-to-bob` (the full
voice round trip) 30.3s wall — each including a cold endpoint
bring-up, n0 discovery, and the relay dial.

Device-layer constraints the gate encodes (bq268 kernel/alpine side,
outside this repo): the one-data-call-per-boot limit is FIXED as of
kernel `dde87bce66ae` (`smd_tty` had no `.hangup`, so a closed port
was never torn down; see the alpine repo's kernel-fixes doc) — on an
older kernel the second `cell-data up` oopses and a rerun needs a
reboot first, on a fixed one cellular cycles freely, which is what
makes the milestone-4 flip test affordable; the
PPP ip-up hook installs no peer DNS, so the smoke sets public DNS for
the wifi-down window; wifi restore leaves the whole bring-up to the
wifi service (a bare `ifconfig up` first can wedge the CAF driver's
connect state machine, which only a reboot clears); the serial console
wraps at 80 columns, so the driver runs it `stty -echo`.

**The Apple leg** (plan 0034, 2026-08-06): wata-mac is an iroh client
too, and the seam cost nothing to carry across — `WATA_IROH_CONFIG`, the
same `go.irohnet` facade wata-tui declares, the same two-step
`sgo build` → `go build -tags iroh`. What is NOT shared is the failure
policy: the mac follows wata-fb and latches `transport unavailable`
rather than downgrading to `DefaultClient` the way the tui does, because
a GUI client that downgrades silently shows "waiting for network"
forever. `just mac-iroh-smoke` is the gate (macOS + cargo, not in ci,
like `mac-smoke`), including that negative. The staticlib also
cross-builds for `aarch64-apple-ios` and `aarch64-apple-ios-sim`
(`mklib.py ios` / `ios-sim`, ~17 MB each, arch AND Mach-O platform
asserted by the script) — no zig and no linker work, since a staticlib
is not linked there. Nothing consumes those two yet; they are
build provenance for `IOS-CLIENT-ASSEMBLY`.

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
