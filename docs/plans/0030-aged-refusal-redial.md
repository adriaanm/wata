# 0030 — the aged refused client: repro, verdict, and the endpoint rebuild

Status: done

## Problem

`[FB-REDIAL-STUCK]` Field, 2026-08-06: a factory-clean handset (relay
"n0", id-only dialing — cross-network via the n0 relay at first, then
same-LAN) sat refused for 15+ minutes, logging `server refused: 401 not
allowlisted` repeatedly. The admin then approved its node id and the
server's live gate verifiably opened — the same key dialed from a
different host was admitted instantly — yet the long-refused client was
never admitted until an app restart, after which it got in immediately.
tunnel-smoke's enrolment leg approves within seconds of the first
refusal and passes, so what it never exercised is a client whose
refusals have AGED.

## What the repro proved (all logs referenced from the fix commit)

Honest 16-minute aged runs on one host, old code, stable network:

| run | layer | shape | verdict |
|-----|-------|-------|---------|
| A | irohnet (Go glue + Rust) | relay none, 2s cadence, 480 real refused handshakes | recovered **6ms** after Allow |
| B | irohnet | relay n0, 45s cadence (the sync loop's ceiling) | recovered **7ms** after Allow |
| C | full app | wata-server + `wata-fb integ refused-then-provisioned`, approval held back 960s | admitted **1s** after approval |
| D | irohnet | relay n0, id-only via discovery (the field topology) | not runnable from this host — its network cannot resolve the n0 DNS TXT records; 0 handshakes ever |

New `IROHNET_DEBUG=1` tracing (kept permanently) settles the brief's
core question: past the 5s refusal cooldown **every redial is a real
fresh QUIC handshake** — the repeated 401 log lines are genuine server
verdicts, not replayed cache. Under a stable network, nothing above the
endpoint goes stale at any age: the Rust latch/cooldown, iroh's
connection handling, the Go transport, and the app loops all recover
within milliseconds of the approval after 16 minutes of refusals.

**Verdict by elimination**: the field's stale state lives in the client
*endpoint's* network-facing state — sockets, discovery/resolver state,
relay and path state — across the device's mid-refusal network move.
That is exactly what an app restart replaces (and the restart healed the
device instantly), and exactly what a single-host stable-network repro
cannot age. Run D marks the one leg (discovery-path staleness) that
remains unexercisable here.

## Decision

Restart the transport, in process, scoped to the state that was stale:
**a client whose dials have been failing for longer than a horizon
rebuilds its endpoint** (same key, same peer; new sockets, discovery,
relay state) on its next past-cooldown dial. `maybe_rebuild_endpoint`
in `go-pkgs/irohnet/rust/src/lib.rs`:

- The failure-run clock (`fail_run_started`) starts at the first real
  handshake failure or recorded stream refusal, and ends only when
  bytes actually **arrive** on a client stream (`mark_io_worked`).
  Neither a dial nor a write can end it: opening a stream is local-only
  and a write only buffers locally, so both "succeed" on every refused
  cycle — the first gate implementation cleared the clock on dial
  success and the horizon never came due.
- Horizon: 5 minutes (`REBUILD_HORIZON_DEFAULT`) — far above any
  transient blip, far below a parent's patience. Rebuilds are
  rate-limited to one per horizon. `IROHNET_REBUILD_HORIZON_MS`
  compresses it for the gate; `0` disables rebuilds outright (the
  pre-fix behavior — the gate's red switch).
- A rebuild that fails to bind keeps the old endpoint: worse off than
  before is not an option on a path that is already failing.
- Truth fix alongside: a fresh handshake that fails with a NON-refusal
  error clears the Rust-side `last_refusal`, so `errForRet` can never
  replay a stale "401 not allowlisted" over a different live failure
  (the QR screen no longer outlives the state it describes).
- `irohnet_client_rebuilds` / `Dialer.Rebuilds()` expose the counter
  for the gate.

## Verification

- `aged_refusal_test.go` (`TestAgedRefusalThenAllow`, tags iroh; runs
  inside tunnel-smoke's step-1 `go test`): 60s of real refusals at 2s
  cadence with the horizon compressed to a third of the age, then
  Allow. Asserts real refusals happened, **the endpoint was rebuilt at
  least once**, the same dialer recovers promptly, and the refusal
  latch clears. Red on the old semantics
  (`IROHNET_REBUILD_HORIZON_MS=0`): 0 rebuilds, the gate fails; green
  by default: 2 rebuilds, recovery 2ms. Both runs recorded in the fix
  commit.
- Honest duration: a 16m aged run on the fixed code with the real 5m
  horizon (expected ~3 rebuilds, prompt recovery) — result recorded in
  the fix commit.
- The gate cannot fabricate the field wedge itself (a wedged endpoint
  needs the network move / discovery-path staleness of run D), so it
  pins the healing mechanism plus non-regression of every recovery arc
  the honest runs proved.

## Out of scope

- Server-side changes (the field evidence exonerated the live gate).
- The device/arm staticlib: `clib/linux_arm` is not rebuilt here — the
  fix rides the next `fb-deploy` (mklib.py arm) like any Rust change.
- The enrolment pending-row TTL interplay (a pending announce expires
  after `WATA_ENROLL_EXPIRY_MS` = 10 min, shorter than the field's 15+
  minute wait; the admin page's re-announce-on-load already covers it,
  which is how the field approve succeeded).
- Re-reading `peerAddrs` config on rebuild (a LAN address that changed
  needs re-provisioning; the n0 path re-resolves through discovery on
  every connect already).
