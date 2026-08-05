# 0025 — the client handle: wataclient under a shell that owns the loop

Status: proposed

## Problem

The M1 spike surfaced the one architectural gap between wataclient and
a phone shell: `Runtime.start` forks its goroutines inside an
`sgo.supervised` scope whose join is the scope exit, so the only way to
"run the client" is one blocking call that never returns while the
session lives. The fb app is shaped around that (the frame loop and the
client share a process lifetime); a UIKit app is not — the toolkit owns
the main loop, and the app needs to start the client, observe it, poke
it, and stop it, all from callbacks.

The spike's shim faked it by parking the blocking call on a spawned
thread and sleeping. That is not a design; this plan is.

## Decision

**wataclient grows a handle; nothing about the runtime's internals
changes.** The supervised scope stays exactly where it is — it moves
into a goroutine the handle owns, and the handle is the outside view:

```scala
object ClientHandle:
  /** start the runtime in its own supervised goroutine; never blocks. */
  def start(cfg: ClientConfig, caps: Caps): Handle

final class Handle:
  def sendAction(a: Action): Boolean   // trySend, as today
  def snapshot(): StateSnapshot        // the current immutable snapshot
  def connection(): ConnState          // health, as NetStatus reads it
  def events(): sgo.Chan[Event]        // see below
  def stop(): Unit                     // idempotent (closedC guard, as today)
```

This is a *packaging* of surfaces the runtime already has — the action
channel, the snapshot cell, the net-status cell, stopClient — behind
one object with a non-blocking constructor. The fb frame loop can adopt
it or keep its current wiring; nothing forces a migration.

## The event pump

The fb loop polls: every frame it reads the snapshot and cells. A phone
shell must not poll at 30 Hz to notice a message; it needs a push.

- The runtime already has natural push points: the end of a sync round,
  a connection-state transition, an outbox transition, an audio event.
- `events()` is a bounded `sgo.Chan[Event]` (cap ~16) the runtime
  publishes to via **trySend, dropping oldest-style semantics**: an
  `Event` is a *dirty flag with a topic* (`EvSnapshot`, `EvConn`,
  `EvOutbox`, …), never a payload. The consumer reacts by reading the
  current snapshot/cell — so a dropped event is harmless as long as one
  later event of that topic survives, which trySend-into-bounded-chan
  guarantees for a live consumer. No unbounded queue, no blocked
  runtime, no stale payloads.
- gobind mapping: interfaces with callbacks are the gobind idiom. The
  mobile shim (plain Go, per-app) wraps `events()` in a goroutine that
  invokes a registered `EventSink.onEvent(topic string)` — Swift/Kotlin
  implement `EventSink`. The Sgola side knows nothing about gobind.

## What changes (file-level)

- `wataclient/src/main/scala/handle.scala` — Handle + start (thin over
  Runtime); event publication points added in runtime.scala (a few
  trySend calls at the round/state boundaries — the same places the fb
  cells are written today).
- `wataclient` design doc: the handle is the documented public surface
  for shells; the fb wiring noted as the polling alternative.
- The spike shim (tools/phone-spike/watamobile) rewires onto the
  handle, deleting its thread-park hack — that rewire is the
  verification that the handle is sufficient.

## Verification

- A client-core test driving Handle end to end against a scratch
  server: start → EvConn(live) arrives → seed a message externally →
  EvSnapshot arrives → snapshot shows it → stop returns and the
  goroutine exits (no leak; assert via a second start).
- Event-drop soundness: a consumer that sleeps through N rounds still
  converges to the true snapshot on next read.
- `just phone-spike` still green after the shim rewire.
- Full `just ci`.

## Out of scope

Push notifications / server wake (M3's PTT integration owns that),
any change to fb's polling loop, multi-handle (one client per process).
