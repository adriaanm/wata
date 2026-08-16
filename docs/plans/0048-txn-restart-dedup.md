# 0048 — restarting the app must not replay transaction ids

Status: done

## Problem

Owner field report (2026-08-16): two device sends recorded, flashed
SENT, and appeared nowhere — not on the device, not on the mac. The
server journal shows exactly what happened: both media uploads landed
(two orphaned `media` ops) and **no message event followed**, while the
device's txn keys `q77gBhWA|SK|1..3` from an earlier run that day were
already journaled. The server dedups transaction ids per DEVICE
(`store.txnKey = deviceId|SK|txnId`), the device id survives restarts
(login-or-resume), and the client's `txnCounterC` was a per-process
atomic starting at 0 — so after a restart the fresh sends reused txn
ids 1 and 2, the server answered each with the OLD event id and 200
(correct Matrix dedup behavior), `EvSendComplete` fired, and the SENT
flash told the truth about a lie. The repeated deploys that day made
the restarts frequent enough to hit; any single restart could.

Not caused by plans 0046/0047 — a latent client-core bug, exposed by
the deploy cadence. (The same-day device reboot remains unexplained:
the pre-reboot logs lived on tmpfs. net-watchdog does not reboot;
nothing in the persistent logs names a trigger. Watch for recurrence.)

## Decision

**Seed the counter from the wall clock at client construction, moving
it only forward.** `mk()` sets `txnCounterC` to epoch-seconds unless
the counter is already past it — several sequential clients in one
process (the mac's session loop) then can never step back onto ids a
previous session used, and a fresh process starts above every earlier
run's ids. Remaining collision window: restarting within fewer seconds
than the previous run's action count — accepted and documented in the
seed comment. A device that boots at 1970 narrows but does not reopen
the window (sends need the network; the clock steps when it arrives).

Why not persist the counter: a file write per send for a value the
clock already provides. Why not random txn ids: the core deliberately
has no randomness capability (capabilities.scala), and the clock is
already a capability.

## What changes (file-level)

- `wataclient/runtime.scala`: the monotonic clock seed in `mk()`,
  comment tagged TXN-RESTART-DEDUP.
- `wataclient/capabilities.scala`: the no-randomness note now states
  the seeding and why.
- A client-tests oracle (`wata-fb txntest`, check 8): a settable clock
  drives three constructions — seed applied, same-second
  reconstruction stays monotonic (no id reuse), clock advance re-seeds
  forward. Byte-diffed like the other oracles.

## Verification

The new oracle; `just ci`; on hardware, the owner's failing gesture:
restart the app, send — the message must appear on both clients.

## Out of scope

- Recovering the two lost messages (their media is on the server but
  no event references it; re-record).
- The unexplained device reboot (tracked by watching for recurrence).
