# 0059 — session visibility: which device, still in use?

Status: accepted (owner ruling 2026-08-16, closing plan 0058's
"last-seen column" out-of-scope note: "to safely revoke an enrolled
handset, you'd have to see for each account on which device it's been
in use — a list of sessions with last-seen times")

## The problem

Revocation (plan 0058) gives the admin the knife but no light: the
enrolled table shows node ids and bindings, and nothing says which of
two handsets bound to the same name is the live one. Today's stale
entry was only identifiable because we happened to know the history.
Generally, an admin choosing what to revoke needs to see, per
account, the sessions that exist and when each was last heard from.

## The decision

**Visibility only — no new mutations.** The admin surface learns to
answer "when was this session last used", in two places:

- **Per account**: the users area lists each account's sessions —
  device id, the node id it was minted through (or "password"),
  created time, last-seen time.
- **Per enrolled handset**: the enrolled table gains a "last seen"
  column — the freshest last-seen over the sessions that node minted
  ("—" when it has none), which is the number that separates a stale
  enrollment from the live one at revoke time.

Mechanics:

- `Device` gains `createdMs` (journaled like `nodeId`; old journal
  rows replay as 0 and render as "unknown"). Creation time is
  durable.
- **Last-seen is in-memory and deliberately not journaled**: it is
  touched on every authenticated request, and journaling that would
  turn the append-only journal into a per-request write stream. After
  a restart a session's last-seen reads "not since restart" until its
  next request — honest, and for the revoke question ("is this
  handset alive?") a session that has not spoken since the last
  restart is exactly the cold one. The touch lives in the token-auth
  path, one map write under the store lock already taken there.

## What changes

`wata-server`: `model.scala` (`createdMs`), `store.scala` (mint time;
the last-seen map + touch in token auth; per-user and per-node
session queries), `persist.scala` (`device` op carries `created_ms`,
replay tolerates absence), `adminapi.scala`/`enroll.scala` (sessions
in the users payload, `last_seen` per allowlisted id in the enroll
listing), `adminui/index.html` (sessions under each account row; the
enrolled table's last-seen column), `docs/design/wata-server.md`.

## Verification

`tools/wata-admin-smoke.py`: a fresh login's session appears under
its account with a recent last-seen; an authenticated request
advances it; the enrolled listing carries `last_seen` for a node with
sessions and none for a node without; reboot leg — `created_ms`
survives replay, last-seen honestly resets. `just ci`, `just
mac-build`.

## Out of scope

- Per-session admin logout (revoke stays at handset granularity;
  `removeUser` at account granularity).
- Journaling last-seen (the tradeoff above is the decision).
- Client-side display of "your other sessions".
