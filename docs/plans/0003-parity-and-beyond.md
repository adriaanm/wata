# 0003 — Parity with the original wata, and beyond it to daily use

Status: accepted

`[PARITY]`

## Problem

This branch is the Sgola port of wata; the original implementation
(TypeScript server + client, Zig framebuffer client) lives in-tree on
`main` and under `src/`, `test/`, `specs/`, `docs/planning/` here, as
reference and oracle. The port has a working server, client core, and
device client skeleton, but it is not yet something a family can use:

- There is **no way to iterate on the device UI locally**. The Zig client
  ran the identical framebuffer code in an SDL window on the Mac; here,
  `wata-fb ui` exits unless a real `/dev/fb0` exists, and the applet layer
  is untested (`[FB-UI-UNTESTED]`).
- The device client has **two thin applets** where the Zig client had a
  feature-complete walkie-talkie UI (contacts, conversations, PTT overlay,
  send/play feedback, settings with echo test, session persistence,
  screensaver, status LEDs).
- The server has known **spec gaps the client works around** (`limited`
  never set, `/messages` tokens are event ids), no state-event route, no
  leave/kick/ban, no power-level enforcement, and hardcoded alice/bob.
- **Deploy is transient** (`/dev/shm`), and the device boots into
  `system-menu.py`, not wata. The rootfs already has the seam: tty1's
  respawn job is the single-app slot, and `/opt/wata/start.sh` exists.
- **Connectivity is LAN-only.** The original repo already decided this
  (`docs/planning/connectivity-iroh.md`, 2026-07): keep Matrix
  client-server semantics, tunnel the transport over **iroh**, because the
  product constraints — no subscription, no account, no VPS, deployable by
  a Raspberry-Pi-capable person — eliminate Tailscale/Headscale/Cloudflare
  et al. A 178-line Rust sidecar spike (`src/iroh-tunnel`, iroh 1.0.2)
  exists and works.

## What the original repo gives us (mined, not rewritten)

- **Conformance oracle**: 83 black-box integration tests
  (`test/integration/`, jest) that were the oracle the TS server was built
  against. `just conformance` already runs them against our server.
- **Server requirements**: `docs/wata-matrix-spec.md` (the exact C-S API
  subset), `docs/planning/wata-server.md` (lessons learned),
  `spec/sync.specl` (formal sync-token model).
- **Product model**: `docs/family-model.md` — contacts + one family
  channel, PTT hold-to-send, users never see Matrix concepts; the network
  is the trust boundary.
- **Device-client parity checklist**: the Zig client's feature set and its
  hard-won Q6/ALSA constraints (`src/fbclient/`, `docs/voice.md`,
  `docs/planning/asm-stream-volume.md`).
- **Connectivity decision record + spike**: `connectivity-iroh.md`,
  `src/iroh-tunnel/`.

## Decisions

1. **Local iteration is phase 1**, before any feature work — every later
   phase gets cheaper once the UI runs on the host.
2. **Simulator form: host backend behind the existing display/input
   seams.** `display.scala` already renders into a memory buffer;
   `input.scala` decodes evdev. Add a host `present`/input pair. Preferred
   order of attempts: (a) terminal renderer — ANSI half-block cells from
   the RGB565 buffer plus raw-stdin keys; pure stdlib, works over ssh,
   trivially scriptable for tests; (b) SDL2 via cgo if the terminal proves
   too crude for real use. Scripted-input + golden-frame tests ride the
   same seam and close `[FB-UI-UNTESTED]`.
3. **Iroh, per the standing decision — as a sidecar.** Research (2026-08)
   confirms: iroh hit 1.0 (wire-stable, MIT/Apache-2), but Go bindings are
   a third-party experimental cgo project, aarch64-musl only — useless for
   the ARMv7 device. The sidecar keeps wata pure Go and works identically
   on the Pi (server side) and the device (client side). tsnet (pure Go,
   embeddable) was evaluated and rejected on the same grounds the original
   decision rejected Tailscale: it needs an account or a self-hosted
   coordination server on a reachable host. Revisit only if n0 ships
   first-party Go support.
4. **The server keeps its persistence lead.** The TS server was
   in-memory-by-design; ours has the JSONL journal. Journal-on becomes the
   default for real deployments; compaction and media caps move from debt
   to scheduled work.
5. **Rootfs integration follows the alpine repo's handoff protocol**:
   changes on that side are specified in `bq268-alpine/docs/planning/` +
   a `TASKS.md` line, not edited from here.

## Phases

Each phase lands as its own plan doc (or directly, where mechanical) and
its own TODO.jsonl items; this doc is the map.

**Phase 1 — Local dev loop.** Host display/input backend for `wata-fb`;
`just fb-sim` runs the real applet UI against `just server` on the Mac.
Scripted-input harness + golden frames for the applet layer. Exit
criterion: send a voice message alice→bob entirely on the host (audio may
stay stubbed; inject a canned Ogg).

**Phase 2 — Server conformance.** Real pagination tokens + `limited`
(then delete the client backfill workaround), state-event `PUT` route,
leave/kick/ban, power-level enforcement, E2EE stub endpoints for
Element/FluffyChat interop, users from config instead of hardcoded
alice/bob. Gate: `just conformance` (the 83-test TS suite) green, plus
`just ci`. Re-test the original repo's recorded rapid-send message-loss
scenario against our server.

**Phase 3 — Device-client parity.** Port the Zig client's feature set
into `applets.scala`/`shell.scala`: contacts + conversation views with
unplayed badges, PTT recording overlay + SENT/FAILED banners, settings
(echo test, brightness, screen timeout, display name), session/config
persistence, screensaver, connection-status LEDs. Verified in the
simulator; `just fb-deploy` for on-device checks. The Zig source is the
spec — port behavior, not code.

*Reordered (2026-08-04, product ruling): iroh moves ahead of
boot-into-wata — it is the biggest unknown, and the first sweep is a
personal end-to-end setup (server on the dev Mac) whose security is
iroh's node-id allowlist; refinement passes follow once the family
actually uses it. Getting there fast is the point.*

**Phase 4 — Iroh connectivity** (plan 0013). Adopt the Rust sidecar into
this repo (`iroh-tunnel/`, pinned iroh 1.x), `just` recipes for
listen/connect; server side on the dev Mac first (a Pi is phase 6),
client side on the device (ARMv7 cross-build of the sidecar); NodeID
provisioning (config file first, QR later); node-id allowlist as the
whole access story for the personal sweep; E2E test across two real
networks. Later: evaluate self-hosted relay to drop the n0 dependency.

**Phase 5 — Boot into wata.** Durable install (`/opt/wata`, rootfs build
step that fails loudly if the binary is missing, like the metricsd one);
tty1 inittab slot swapped from `system-menu` to a launcher that unbinds
fbcon and respawns wata-fb; keep a debug VT; metrics heartbeat to
`/run/wata.tick`; the `Playback 0 Volume`-after-first-write audio quirk
handled in `audiothread.scala`; dedicated `wata` user per the alpine
privilege audit; the sidecar ships in the image (dormant until
configured). Cross-repo spec handed to `bq268-alpine`. The
`FB-SIM-DEVICE-VERIFY` human pass is done (2026-08-04): the full
loop ran on hardware against a live server — system-menu launch into
the UI, sync-populated contact list, PTT record/upload, and
receive/decode/speaker playback, confirming the UiDevice refactor
left the real fbdev/evdev/audio path intact. Runs in parallel with the Mac server deployment
hardening from phase 4's sweep.

System-menu retires outright (ruling 2026-08-04): wata is then the
only framebuffer occupant, which is what makes the tty1 swap clean.
The bare minimum of its diagnostics is absorbed into wata's settings
applet (done 2026-08-04): the wlan0-IP and cellular-data info rows and
the power off / reboot-to-bootloader / reboot-to-EDL actions, each
mirroring system-menu's own source (`ip -4 addr show`, the ppp0 sysfs
node, and the same three commands — see `Diag` in wata-fb and the
settings section of docs/design/wata-fb.md; battery percent was
already in Device Info). The info rows carry sim coverage in the
`settings-walk` goldens, honest `n/a` off-device; the power actions
render and arm everywhere but run only behind the on-device guard,
and their **on-device verification remains part of this phase's
boot-into-wata checkpoint** — nothing has exercised them on hardware
yet.

`[FB-SETTINGS-FULL]` audit ruled 2026-08-05, folds landed — the full
system-menu feature delta (source: `bq268-alpine/tools/system-menu.py`,
816 lines) and each item's fate, which is the retirement checklist:

| system-menu offers | settings has | fate |
|---|---|---|
| launch wata | n/a — wata becomes the tty1 program | retire with the flip |
| sysinfo: battery, wifi SSID/IP, uptime, free mem, signal dBm, live refresh | battery, IP row, cellular info row | DONE: uptime + `MemAvailable` read straight out of `/proc` into Device Info, signal dBm appended to the cellular row (the ppp0 address moved to that row's detail line to make room) |
| net test: ping gateway/1.1.1.1/8.8.8.8 + DNS probe | absent | DONE: a "Net test" row running the same four probes through the exec facade, verdicts in its detail block; synchronous, as system-menu's is |
| cellular start/stop data (`pppd call cellular` / stop) | info only | DONE: a "Data link" toggle row, same commands, behind the two-OK confirm; failure lands on the row and is NEVER retried (one data call per boot) |
| wifi ON/OFF (`rc-service wifi start/stop`) | absent | DONE: a "Wifi" toggle row, same two-OK confirm (provisioning a *new* network stays TUI-WIFI-PANEL's job) |
| brightness, screen timeout | present | done |
| reboot / power off / reboot-to-BL (+EDL in wata) | present, on-device-unverified | done pending the hardware pass above |
| dmesg-VT chord toggle (F3+F4) | absent | retire — kernel-log debugging lives on the serial console/ssh, not on a kid's screen |

The toggles and the net test were the only new code; all reuse the
`Diag`/exec patterns the absorbed rows established, and each landed
with `settings-walk` golden coverage (honest `n/a`/no-op off-device) —
twenty checkpoints now, six of them new. `Diag.onDevice()` gates every
new read and command, including the `/proc` ones, so a Linux CI host
cannot drift the goldens. What remains of this phase's settings work is
the ON-DEVICE verification of the power actions and the two toggles:
nothing has run them on hardware yet, and the `[FB-EARLY-BOOT]` tty1
flip is gated on that pass.

Later polish, same phase family (queued, not blocking the first boot):
a boot logo instead of the kernel bootlog — the device has a splash
path in aboot (sibling `bq268-aboot`) for the earliest frame, plus
`quiet` and fbcon logo suppression for the rest, ending in wata-fb's
first frame; and **boot into wata as early as init allows**, before
the network/modem are up — the client already survives a dead server
(sync backoff), so the UI change is a calm "starting up / waiting for
network" presentation of the existing connection state rather than an
error surface. `[FB-BOOT-LOGO]`, `[FB-EARLY-BOOT]`.

`[FB-BOOT-LOGO]` split ruled 2026-08-05 (recorded ahead of pickup; no
handoffs written yet — it stays queued polish):
- **aboot splash** (`bq268-aboot`): LK's `display_image_on_screen` /
  `fetch_image_from_partition` path already exists and works (the MPP
  backlight fix proved it); the work is a wata 160×128 frame in LK's
  splash format replacing the stock logo.
- **cmdline** (`bq268-alpine`): DONE 2026-08-05 — the live boot
  partition's header cmdline now carries `quiet logo.nologo
  vt.global_cursor_default=0` (patched in place, backup kept; alpine
  `ae0aa01` has the method and the carry-forward note for future
  boot.img assemblies). Boot is dark-to-wata together with the tty1
  flip (alpine `be06c0e`; owner verified settings power-off on hardware
  2026-08-05 and the device rode a reboot cycle — the gate's substance
  is met, reboot-to-bootloader/EDL still untried from settings).
  Post-flip fix that made it clean: start.sh unbinds the fbcon
  vtconsole (alpine `3264fef`) — without it the VT paints and echoes
  over wata's frames; the mechanism had been buried in system-menu's
  launcher.
- **the last gap** — kernel-to-wata handoff — is already covered by the
  tty1 flip spec (`wata-fb-early-boot.md` there) plus the calm boot
  presentation below; wata itself needs nothing new beyond supplying
  the logo frame asset.

`[FB-EARLY-BOOT]` split ruled 2026-08-05: the init half is handed off to
`bq268-alpine` (`docs/planning/wata-fb-early-boot.md` there — the tty1
respawn flips from system-menu to wata-fb, respawn doubling as crash
supervision, escape hatches kept). The flip is **gated on wata verifying
the settings power actions on-device** (this phase's open boot-into-wata
checkpoint) — until then a parent's only power-off path is system-menu.
Wata's half is the calm waiting-state UI: until the first successful
sync of a session, the wata applet presents "starting up" /
"waiting for network" derived from the same pipe-and-health state the
FB-CONN-STATUS element computes (plan 0013 M4's design), instead of the
error surface; after first connect, ordinary reconnect presentation
takes over.

Outcome: wata's half LANDED — `NetStatus.everLive` latches on the first
`NetLive` frame of a session, and until it does the wata applet draws a
centered `starting up...` / `waiting for network` under the unchanged
header instead of `renderConnecting`'s error line; a later drop shows
the ordinary reconnect presentation, never the boot screen again. Pinned
by the `early-boot` uitest scenario (four goldens, the last of them the
non-return), described in `docs/design/wata-fb.md` ("The boot
presentation"). The alpine tty1 flip remains gated on the on-device
settings power-action pass.

**Phase 6 — Family deployment.** Raspberry Pi target (arm64 build +
service unit, extending `amd64-smoke`), journal-on by default with
compaction, provisioning the actual family accounts, invite security
(validate inviter is a family member) and the other refinement passes
deferred from the personal sweep. Then usage-driven: offline outgoing
queue. (Disappearing messages landed early as plan 0012's retention
sweep; media caps likewise.)

## Also in scope (phase 0, mechanical)

- Commit the dirty working tree (toolchain pin, `caps.scala`, workspace).
- CLAUDE.md synthesis: add the product mission, a reference-repos section
  (original wata, bq268-alpine, device ssh host, hardware one-liner), the
  cross-repo handoff protocol, and the commit-before-flash/record-outcome
  discipline for device work. Cross-repo/device gotchas keep going to
  `~/g/bq268/CLAUDE.md`'s learnings log; wata facts go in design docs.

## Out of scope

- Federation, real E2EE, push notifications, presence — same exclusions as
  the original spec (`wata-matrix-spec.md`); the network is the trust
  boundary.
- Android/web/iOS clients.
- Anything about the sgola compiler itself.
- Full P2P (device-to-device) audio over iroh — the tunnel keeps Matrix
  semantics; revisit only if the star topology ever hurts.

## Verification

`just ci` stays the gate throughout; phase 2 adds `just conformance` to
it (or to a nightly recipe if runtime is prohibitive). Phase 1's simulator
harness becomes part of `ci` (host-only, hermetic). Phases 4–6 get
on-device smoke recipes with recorded outcomes.
