# 0076 — NsHttp: per-request state, concurrent sends

Status: accepted

## Problem

`NsHttp` (`wata-watch/src/main/scala/nsurlsession.scala`) carries all
per-request state in process globals: one accumulator, one completion
channel, one arm flag, fixed temp paths. `Runtime.start` forks `syncLoop`
and `actionLoop`, both driving the same `HttpDo`, so two requests overlap
whenever a user acts while the ~25s sync long-poll is out — i.e. nearly
always. An action's reset wipes the sync's accumulated bytes and can
swallow its token; the two requests then trade tokens and bodies.

A review (of 188aaac..4c8ba4a) also found: a timeout leaves poison that can
deadlock or spuriously fail the next request; the completion IMP's type
encoding is missing an argument; and three minor issues (response-file
mode, per-request mkdirAll, `asInt` swallowing garbage as status 0).

Interactivity is a product requirement: the user sends a quick audio
message and network conditions vary, so queueing an action behind the
sync poll is not acceptable. That rules out serializing whole requests.

## Decision

**Per-request state, routed off the task handle**, instead of global
state behind a mutex:

- A `ReqState` (`sgo.Shareable`) holds a fresh `NSMutableData`
  accumulator and a fresh buffered(1) completion channel per send. A
  request's `recv()` can only ever return its own task's token, so no
  stale-token discipline is needed at all.
- A registry `Mutex[List[(go.Uintptr, ReqState)]]` maps the NSURLSession
  task pointer to its state; the delegate IMPs look up their state by the
  task argument they already receive. The session's delegate queue is
  serial, so callbacks serialize among themselves; the registry is the
  only sender↔delegate shared cell.
- Unique temp paths from an `Atomic[Int]` counter.
- On timeout: `cancel` then block on `recv()` — cancellation guarantees
  exactly one more delegate callback with an error token — then
  deregister. Every post-`resume` exit goes through that single path, so
  no callback can outlive its request and nothing is "reset between
  requests". This deletes `doneC`, `drainDone`, and with them the
  SELECT2-ARM-VAR-MUT workaround.
- The session itself is not serialized (`delegateQueue:nil` serializes
  callbacks, not transfers), so the long-poll and the action genuinely
  run in parallel on the wire.

Alongside, the review's smaller findings land in the same change:

- `"v@:@@@"` for `URLSession:task:didCompleteWithError:` (three object args).
- The response temp file is pre-created Sgola-side at mode 0600;
  `NSMutableData writeToFile:` truncates in place and preserves it. It
  carries access tokens.
- `FbConfig.mkdirAll` moves to module init (process-wide setup, not
  per-request work).
- `asInt` returns -1 on any non-digit; callers treat < 0 like status 0,
  so garbage never masquerades as a transport result or HTTP code.
- The error-description diagnostic stays on `NSString writeToFile:` — the
  honest `dataUsingEncoding:NSUTF8StringEncoding` route needs integer
  argument 8 across msgSend, unspellable under UINTPTR-INT-ARGS; the
  comment names the blocker instead of claiming honesty.

## What changes (file-level)

- `wata-watch/src/main/scala/nsurlsession.scala`: everything above.
- `WATA-TODO.md`: SELECT2-ARM-VAR-MUT entry struck once verified green
  (workaround removed); UINTPTR-INT-ARGS entry notes `asInt` now
  signals garbage as -1.
- `docs/design/wata-watch.md`: threading paragraph updated if it
  describes the old single-goroutine assumption.

Out of scope: ATS exception bookkeeping for the commercial build
(tracked separately); any change above the `caps.httpDo()` seam.

## Verification

`just watch-interptest` and `just watch-e2e` green, plus the wrist leg of
`just ci`. watch-e2e should drive a send while the sync poll is
outstanding — if it does not cover that today, a queue item is added for
the e2e gap before this plan is called done. After green, a verification
ticket goes to the sgola inbox noting SELECT2-ARM-VAR-MUT's workaround
removal.
