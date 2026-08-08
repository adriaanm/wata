# 0043 — arrival priming that survives a split backlog (FB-UI-PRIME-SPLIT-AUTOPLAY)

Status: done

## The problem

`Notify.step` primes on the first snapshot that carries any
conversations, and announces on a conversation's unplayed count RISING.
Both halves are wrong for a backlog that reaches the client in more than
one piece:

- the runtime's backfill walk appends older unplayed messages to a room
  AFTER its first snapshot, raising the count with no live event behind
  it — and backfill deliberately emits no SyncEvents ("backfill repairs
  message history"), so nothing marks these as history;
- a first sync whose earliest snapshot is still partial primes on the
  partial picture, and the rest of the backlog reads as arrivals.

In play mode (the handset default) a false arrival is not cosmetic: the
client AUTO-PLAYS the backlog tail and RECEIPTS it — a stale message
plays out loud on a handset that just booted, and the unplayed badge is
permanently eaten. Observed twice as fb-ui golden flakes under ci load
(`badges-before`/`badges-restored` pinned the Alice row at 2, got 1 —
one auto-played receipt); the scripted mitigation (forcing quiet mode
before badge checkpoints) keeps the suite honest but leaves the product
bug, and `bob-view`/`bob-play`/`family3-*` still carry the exposure.

## The decisions

Two independent guards, both in the shared model (`notify.scala`), so
both clients heal identically:

**1. Priming latches on sync-caught-up, not on first-non-empty.** The
sync engine learns `syncedOnce` — true once the FIRST `/sync` response
has been fully processed (set at the end of `process()`'s first
successful round, cleared by `reset()`) — and the snapshot carries it as
`caughtUp: Boolean`. `Notify.step` primes on the first snapshot with
`caughtUp` true, whether or not any conversations exist yet. A fresh
account primes on an empty picture, so the first thing anyone ever says
still announces (the DM-created-by-its-first-message case the original
design protected); a backlogged session primes on the complete
first-round picture instead of a partial one.

**2. An arrival is a NEW newest message, not a bare count rise.** The
marks grow from `(roomId, count)` to a small case class
`NotifyMark(roomId, count, newestId)` where `newestId` is the event id
of the newest unplayed message not our own (the same message `newest`
already selects for announcing). `step` announces only when the count
rose AND the newest unplayed id CHANGED. Backfill appends older
messages — the newest stays the newest — so a backfill-raised count
updates the badge and never announces. A real arrival is by definition a
new newest. (A case class, not a triple: `NotifyStep` set the precedent,
and it reads better than `_3`.)

Guard 1 alone would not stop backfill (walks run long after the first
round); guard 2 alone would not stop a split first sync (the tail IS a
new newest). Together they reduce to: announce exactly the messages that
arrive live, after the client has its feet under it.

**The fb-ui quiet-mode forces stay.** The scenarios that force
`notifymode quiet` before badge checkpoints document a real invariant
(badge state must not depend on notify mode) and cost nothing; removing
them would only re-couple the goldens to this model.

## What changes (file-level)

- `wataclient/src/main/scala/syncengine.scala`: `syncedOnceC` cell, set
  after the first processed round, cleared in `reset()`; exposed to the
  snapshot builder.
- `wataclient/src/main/scala/domain.scala`: `StateSnapshot.caughtUp:
  Boolean` (constructor-site sweep — the snapshot is built in one place
  and faked in the harnesses; every fake must say `caughtUp = true`
  unless the scenario is about priming).
- `wataclient/src/main/scala/notify.scala`: `NotifyMark`; `NotifyState`
  carries `List[NotifyMark]`; `step`'s priming reads `snap.caughtUp`;
  the announce predicate gains the newest-id comparison. Doc header
  updated — it currently states the first-non-empty rule as the design.
- `wataclient/src/main/scala/notifyoracle.scala` + expected file: new
  pinned cases — split backlog (partial then complete first sync: no
  announcement either round), backfill raise (count up, newest
  unchanged: badge moves, no announcement), fresh-account first message
  (still announces), plus the existing cases re-based on `caughtUp`.
- `wata-mac`/`wata-fb` harness fakes and any snapshot literals: the new
  field.
- `docs/design/wata-fb.md` + `docs/design/wata-mac.md`: the arrival
  sections' priming paragraphs rewritten to the caught-up + new-newest
  rule; the `[FB-UI-PRIME-SPLIT-AUTOPLAY]` tag and its residue note
  come out of wata-fb.md.

## Verification

- `wata-fb notifytest` (the byte oracle) grows the three cases above —
  the expected file changes deliberately, reviewed as part of this plan.
- fb-ui suite: all 23 scenarios green 3 consecutive runs, no golden
  regenerated; the previously-exposed scenarios (`bob-view`,
  `bob-play`, `family3-*`) run with play mode intact.
- `just mac-notify-smoke` green (the mac consumes the same step).
- Full `just ci` + `just mac-smoke` green, foreground.

## Out of scope

- Announcing "you have N missed messages" as a summary after a long
  offline gap (a presentation idea, not a correctness need).
- Persisting marks across restarts (priming-per-session is the design;
  restarts re-prime on the caught-up first round, which is correct).
- The transaction-id restart issue (WATA-TODO, unrelated receipt path).
