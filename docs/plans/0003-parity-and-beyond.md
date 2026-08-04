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
`FB-SIM-DEVICE-VERIFY` human pass folds into this phase's first
on-device checkpoint. Runs in parallel with the Mac server deployment
hardening from phase 4's sweep.

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
