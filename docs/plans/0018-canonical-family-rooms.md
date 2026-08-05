# 0018 — canonical family and group rooms

Status: done (landed 2026-08-06 — server: family.scala/group.scala +
store transactions; client: stamp classification + GroupConv; fb/tui
rendering + goldens; verification: full ci green, conformance 84/84,
new integ scenarios group-room and family-no-leave, persist/admin smoke
extended. Owner rulings 2026-08-05: server-side membership writes
approved — no invite/accept round-trip for family or groups; leaving
the family room is not offered, the account list is the roster)

## Problem

`[SRV-FAMILY-ROOMS]` The family room is a convention, not a concept: an
admin creates a public room aliased `#family:<server>` once per server
(docs/family-model.md "Admin Operations"), the client classifies by
alias prefix (`SyncEngine.findFamily`: first room whose alias starts
with `"#family:"`), and membership rides on auto-join. Nothing owns it:
a fresh server has no family room until someone runs setup; a second
room with a `#family.*` alias would misclassify; a newly provisioned
account is in the family only once its client happens to join. DMs had
exactly this shape before plan 0007, and the fix is the same move:
**the server owns the identity**.

## Decision

### The family room is server-minted and server-membered

- At boot, after journal replay, the server ensures THE family room
  exists: minted with alias `#family:<server>`, name `Family`, the
  public-chat shape family-model.md already specifies, and a
  `net.wata.family` state event (`{}` — the stamp is the identity, like
  `net.wata.dm`). An existing `#family:<server>` room is stamped
  instead of duplicated (the `Dm.migrate` pattern: oldest qualifying
  room wins, nothing is deleted).
- **Every account is joined, always.** Boot and account provisioning
  write the `m.room.member` join for each configured user that is not
  already joined. There is no join path for the family room because
  there is no unjoined state; leaving is not offered (a family member
  cannot leave the family — the account list is the roster). This
  replaces client-side auto-join as the thing membership rests on; the
  client's auto-join stays as harmless compat for stock clients' invite
  flows.

### Groups are the same concept with a member list

A group ("kids", "camping trip") is a room stamped
`net.wata.group` with `{"name": …}`, minted through one dialect
endpoint:

```
POST /_wata/v1/group   {"name": "kids", "members": ["a", "b", …]}
  -> {"room_id": …}
```

Auth required; the caller is included implicitly. The server creates
the room, stamps it, and **joins every listed member server-side** —
the DM-resolve precedent: nobody accepts an invitation, membership is
an act of whoever created the group, and the trust model (the server
population is the family) makes that acceptable in a way it wouldn't
be on federated Matrix. Members can be added later by re-POSTing the
same room's name with more members (idempotent get-or-extend, keyed on
the stamp's name — one group per name, like one DM per pair); nothing
removes members in v1 (kick/ban exist for the pathological case, power
levels: creator 100, members 0 with `events_default` 0 so everyone
speaks).

### Client: classification by stamp, groups in the list

- `SyncEngine`: family = the room stamped `net.wata.family` (alias
  prefix matching retired); a `net.wata.group` room becomes a
  `Conversation` of a new `GroupConv` type, named by the stamp, listed
  after Family in stamp-creation order.
- UI (`wata-fb`): groups render in the conversation list like Family
  does (name, unplayed count); PTT into a group is identical to PTT
  into Family. Group *creation* on the handset is deliberately absent —
  a keypad device is a bad place to name a room; groups are created
  from the tui (plan 0016's `raw` covers it until a `group` command
  earns its place) or the phone client (plan 0017).

## What changes (file-level)

- `wata-server`: `family.scala` (new — boot ensure/migrate/stamp,
  provisioning join hook), `group.scala` (new — the endpoint,
  get-or-extend), `server.scala`/`handlers.scala` (route), journal: no
  new op kinds needed (rooms/events/aliases cover it; the stamps are
  ordinary state events).
- `wataclient`: `syncengine.scala` (stamp classification, `GroupConv`),
  `domain.scala` (the type), sync oracles + fixtures re-pinned.
- `wata-fb`: conversation list rendering; goldens re-pinned.
- `docs/family-model.md` + `docs/design/wata-server.md` +
  `docs/design/wataclient.md` updated in the landing commits.

## Verification

- Conformance suite stays green (it exercises the conventional family
  room; the migration path is what keeps it passing).
- New integ scenarios: fresh-boot server has a stamped family room with
  all accounts joined; a provisioned-later account (users.json edit +
  reboot) appears joined; `POST /_wata/v1/group` from one member shows
  the room, stamped and joined, in another member's next sync;
  re-POST extends membership idempotently; persistence smoke: stamps
  and memberships survive `kill -9` + replay.
- UI golden: a group in the conversation list.

## Out of scope

- Group deletion/rename, member removal UX, per-group notification
  rules.
- Handset-side group creation.
- Multi-family servers (there is exactly one `net.wata.family` room).
