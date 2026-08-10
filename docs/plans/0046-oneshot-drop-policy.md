# 0046 — a full action queue must not silently eat a delete

Status: proposed

## Problem

Plan 0045's evidence inventory (item 10) flagged the client core's
drop-on-full policy. `Runtime.sendAction` is a `trySend` — correct: the
UI goroutine must never block on a channel — and `dropped` classifies
what a drop costs (runtime.scala's QUEUE-SEMANTICS NOTE): a voice send
or a playback surfaces its ordinary failure event, everything else
drops silently on the theory that "a later one supersedes it".

That theory holds for receipts and name pushes. It does not hold for
the user-initiated one-shots: a **redaction** (hold Back on a message —
delete) or a **favorite** (hold OK) that drops is simply gone. No later
action supersedes it, nothing marks it queued, and the UI's optimistic
render is corrected only when the next sync shows the message undead or
unstarred — a silent lie with a delay. The window is real, if narrow:
the action queue (64 deep) fills exactly when the action loop is stuck
against a non-answering server, which after plan 0045 slice 1 is a
bounded-but-minutes-long state, and an outage is precisely when someone
tidies a conversation.

## Decision (proposed)

**Retry from the frame loop; no new words, no new state machine.**
`ClientHandle.sendAction` already answers whether the queue took the
action. The applet keeps a tiny pending list of REFUSED one-shots
(favorite/redact only) in `WataState`, and the frame tick re-offers the
head each frame until the queue accepts — the same shape as the outbox:
the intent is durable, the delivery retries, the user is not asked to
notice or repeat. Bounded (a handful of entries; a second delete of the
same message coalesces), and dropped wholesale on session end — these
are session intents, not persisted outbox items.

Why not surface a failure flash instead: plan 0045 kept the shared grid
minimal, and "DELETE FAILED, try again" is strictly worse than not
losing the delete — the user cannot do anything with the information
except retry, which the pump can do better and silently. Receipts,
name pushes and `ActRetryOutbox` keep the current silent-drop policy;
their semantics really are superseded-by-later.

## What changes (file-level)

- `wataclient/handle.scala`/`runtime.scala`: no queue changes;
  `sendAction`'s Boolean answer becomes the seam the applet consumes
  for one-shots (voice send/play keep the classified failure events).
- `wata-fb/src/main/scala/applets.scala` (shared): `WataState` gains a
  small `pendingOneshots: List[Action]`; `favoriteSelected` /
  `deleteSelected` push through it; `update` re-offers per frame.
- A client-tests case: fill the action queue (a stub client whose loop
  is parked), favorite + delete, drain, assert both actions arrive
  exactly once, in order.

## Verification

The new client-tests case; `just ci` (golden, fb-ui-tests unchanged —
no rendering is touched); `just mac-ui-tests` stays green.

## Out of scope

- Persisting one-shots across restarts (the outbox exists for
  messages; a favorite is not worth a store).
- Any change to receipts/name/retry drop policy.
