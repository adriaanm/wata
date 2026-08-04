# 0007 — canonical DM rooms: the pair is the key

Status: proposed

`[CANONICAL-DM]`

## Problem

Matrix has no first-class DMs: `m.direct` is client-written per-user
account data, `is_direct` is a hint on invites, and nothing enforces
uniqueness. Most of our historical brittleness lives here — the dedup
rules, the concurrent-creation convergence spec, buffer-and-retry for
events beating `m.direct`, the sticky `isDm` inference, and two
*different* "primary room" selection rules inside today's client
(`resolveDmRoom` takes `m.direct[contact][0]` from the network;
`buildSnapshot` takes the first room the engine has seen). The
documented oldest-wins dedup is unimplementable against current state
(no `m.room.create` timestamps tracked). Today's compiler miscompile
was in `upsertRoomList` — the append-only `m.direct` serializer.

Upstream offers nothing to adopt: MSC2199 (canonical DMs) is the only
serious prior art, stalled since 2019, and its hard parts (federation
glare, tombstone consensus) don't apply to a single-homeserver world.

## The model (per the product decision, 2026-08-04)

One family room, plus at most one 1:1 DM room per unordered pair of
family members. Ad-hoc rooms are a future room class, not designed here.
Interop stance: first-party clients (wata-fb, a possible watch app)
speak our dialect; stock Matrix clients (a parent's phone) get a
best-effort degraded experience via a compat projection that we can
delete without touching the core.

## Decision

**The server owns DM identity.** A DM room is keyed by its sorted user
pair; the store holds `pair -> roomId` under the existing single mutex,
so uniqueness is a map lookup, not a distributed protocol.

1. **Dialect endpoint** — `POST /_wata/v1/dm/{userId}` → `{room_id}`:
   idempotent get-or-create of THE room for (caller, userId). Both users
   are joined/invited by the server; repeat calls and concurrent calls
   from both sides return the same room. This replaces the client's
   entire resolve/create/update dance.
2. **Structural identity in room state** — the server stamps each DM
   room with a `net.wata.dm` state event (`{"members": [a, b]}`, sorted)
   and canonical alias `#dm.<a>.<b>:server` (localparts sorted). Clients
   classify a room the moment its state arrives — no account data, no
   ordering races, no inference. No custom `m.room.create` `type`:
   Element hides unknown-type rooms, which would kill the degraded mode.
3. **Compat projection (one-way, disposable)** — the server *derives*
   `m.direct` for both users and `is_direct` on member events from its
   pair map (fixing the current asymmetry where `joinPerform` drops the
   flag). Client writes to `m.direct` are accepted and merged, never
   load-bearing: the server re-asserts its pairs on top. Stock clients
   therefore see spec-shaped DMs; deleting this projection later touches
   nothing in the core.
4. **Element-side creation stays safe** — a `createRoom` that is
   DM-shaped (`is_direct` + exactly one invitee) is answered
   idempotently: if the pair already has a canonical room, return that
   `room_id` instead of creating (MSC2199's `create_dm` semantics folded
   into the standard endpoint). No duplicate rooms even from a stock
   client.
5. **Client simplification** — `wataclient` drops the `m.direct`
   machinery: contacts stay derived from the family roster; a
   conversation's room resolves via the dialect endpoint on first send
   (cached thereafter); classification reads `net.wata.dm` state.
   Deleted outright: `rebuildDirect`/`upsertRoomList`/`resolveDmRoom`'s
   read-modify-write, the sticky `isDm` flag, and the dedup/convergence
   flow specs they implement.

## What changes (file-level)

- `wata-server`: `store.scala` (pair map + journal op), new
  `dm.scala` handler + route, `rooms.scala` (stamp state/alias, fix
  `joinPerform` flag, idempotent DM-shaped createRoom), `sync.scala`
  (derived `m.direct` in account data).
- `wataclient`: `runtime.scala` (`resolveDmRoom` → one endpoint call),
  `syncengine.scala` (classification via `net.wata.dm`; delete the
  `m.direct` ingest/serializer and DM inference), `mhttp.scala` (delete
  `upsertRoomList` family), oracle fixtures updated to the new
  semantics.
- Boot migration: derive the pair map from existing rooms (oldest
  `m.room.create` wins; losers stay joined but unmapped) — cheap, and
  only dev deployments exist.
- Docs: `wata-server.md`/`wataclient.md` design updates; the DM flow
  specs under `specs/flows/` get a header noting they describe the
  retired mechanism (they stay as the record of why).

## Verification

- New integ scenarios: both-sides-concurrent first send converge on one
  room; endpoint idempotency across restart (`just persist`);
  DM-shaped `createRoom` from a stock-client shape returns the existing
  room.
- `just ci` green; `just conformance` (84/84) green — the jest suite's
  own `m.direct` reads/writes must keep working through the merge-based
  projection, which is the compat gate.
- Sync oracle fixtures re-recorded (`just fixtures`, reviewed diff).

## Out of scope

- Ad-hoc/group rooms (future room class; the pair map generalizes to a
  member-set key when needed).
- Upstreaming an MSC (we align with MSC2199's spirit; submitting is not
  a goal).
- Any UI change — the applet already keys conversations by contact.
