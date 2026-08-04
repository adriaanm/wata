# 0019 — favorites: keeping a voice message

Status: accepted

## Problem

`[FAVORITE-RETAIN]` wata is ephemeral by ruling (plan 0012): voice media
older than the retention window is swept. The intended exception — mark
a message to keep it — exists only as a seam: `Retain.exemptEventIds`
(retain.scala) is empty and has no caller. There is no way to keep the
message a kid wants to keep.

## Decision

**A favorite is room state the server writes.** Marking a message puts a
`net.wata.favorite` state event in its room, `state_key` = the event id,
content `{"by": userId}`; unmarking rewrites it with empty content (the
Matrix state-resolution idiom for clearing a slot). Storing the marker
as an ordinary state event buys everything at once: it journals and
replays as an existing `event` op (no new persistence kind), it reaches
every client through ordinary `/sync` state (the star renders with no
new transport), and the sweep reads it out of the store it already
walks.

**The write path is a dialect endpoint**, not a raw state PUT:

```
POST /_wata/v1/favorite/{roomId}/{eventId}   -> {"favorite": true|false}
```

Auth required; toggles. The rule it applies is "any *joined member* of
the room may favorite/unfavorite", which a raw `PUT /state` cannot
express — `state_default` is 50 and members sit at 0, and lowering the
level for one event type in every existing room is a migration this
doesn't need. The target must be an unredacted `m.room.message` in that
room (favoriting a text message is allowed and simply has no retention
meaning today). Favorites are global in effect: retention is
server-global, so *anyone's* favorite keeps the message for everyone —
`by` records who, for the UI and for a future per-user view.

**The sweep honors it.** `Retain.exemptEventIds` gets its caller:
during the sweep's per-room walk, an event whose room state carries a
`net.wata.favorite` slot with non-empty content under its id is
skipped. (The implementation reads the room's state directly rather
than materializing one global list; the seam's list shape stays for
tests.)

**Device UI.** In the conversation view, holding OK on the selected
message toggles its favorite (the hold gesture mirrors PTT's
hold-to-act grammar; a short OK press stays play). A favorited row
shows a star glyph (one font-table addition alongside FB-CONN-STATUS's
CELL glyph). No separate favorites list view — the message list just
stops forgetting starred rows.

## What changes (file-level)

- `wata-server`: `favorite.scala` (new — the endpoint + membership
  rule), route registration, `retain.scala` (the exempt check).
- `wataclient`: `syncengine.scala` ingests the state event into
  `VoiceMessage.isFavorite`; `mhttp.scala` gains the endpoint call;
  `runtime.scala` an `ActFavorite` action.
- `wata-fb`: hold-OK gesture + star glyph; goldens for a starred row.
- Docs: retention section of `docs/design/wata-server.md`,
  `docs/design/wataclient.md` domain model, wata-fb interaction table.

## Verification

- Server integ: favorite → age past retention → reboot-sweep leaves the
  event and blob; unfavorite → next sweep redacts and reclaims; both
  survive `kill -9` replay (extends `tools/wata-persist-smoke.sh`'s
  retention leg).
- Conformance stays 84/84 (no existing surface changes).
- UI golden: star toggling on a message row.

## Out of scope

- A favorites-only browse view; per-user favorite visibility.
- Storage quotas for favorites (plan 0012's media bounds already cap
  upload size; a pathological number of favorites is a family-scale
  non-problem until it isn't).
