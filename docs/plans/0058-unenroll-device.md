# 0058 — un-enrolling a device, and the enrolment lifecycle gaps around it

Status: proposed (owner observation 2026-08-16, after the /data
adoption incident orphaned an enrolled node id: "we should have a way
to un-enroll a device"; extended same day after the owner's re-enrol
fumble surfaced the lifecycle gaps below)

## The problem

Enrolment is a one-way door. `approve` appends the node id to the
durable allowlist (the iroh config file) and admits it live
(`irohnet.Allow`); `deny` only clears a PENDING row; removing an
account revokes its sessions but leaves its node id allowlisted
forever. There is no way to say "this handset is no longer trusted" —
needed when a device is lost or retired, and left visible by today's
incident: the handset's old identity is still allowlisted and bound
though its key is gone.

Two gaps under the surface make this more than an allowlist edit:

1. **Sessions can't be traced to the node.** `device-login` mints a
   fresh device row per login, but the row (`Device(deviceId, userId,
   token)`) does not record the proven node id it came through. A
   revocation could refuse the transport yet leave every minted token
   fully valid — and tokens work over plain TCP, where the transport
   ban means nothing.
2. **The live listener only ever widens.** The Go facade has `Allow`
   but no inverse; without one, a revoked node keeps its admission
   until the server restarts.

## The decision

`POST /_wata/v1/admin/enroll/{nodeId}/revoke` (admin-gated, beside
approve/deny), mirroring approve's durable-first-live-second shape:

1. rewrite the allowlist file WITHOUT the id (same atomic temp+rename
   as `writeAllow`); 404 if the id is not in it;
2. `irohnet.Disallow(nodeId)` on the live listener (failure reported
   in the response like approve's `live`/`note`, never fatal);
3. `Bindings.unbind(nodeId)` — new journaled `unbind` op; a revoked
   handset that somehow still dials gets device-login 404, the
   already-built "enrolled but no account" arm;
4. revoke every device row minted through that node — which requires
   gap 1 closed first: device rows gain an optional `node_id` (set by
   device-login, journal `device` op carries it, replay tolerates its
   absence so existing journals stay readable). Pre-existing rows
   without a node id are untouchable by revoke, stated in the
   response (`revoked_sessions` count) rather than silently skipped.

Admin page: the allowlisted-ids list (already shown beside pending
rows) gets a per-id remove action with a confirm step naming the
bound user.

Device experience after revocation: the next dial is refused at the
transport, the handset lands in the refused arc (plan 0027) and shows
the enrol QR again — re-admission is a fresh approval, exactly like
today's recovery.

## What changes

- `go-pkgs/irohnet`: `Disallow(nodeId)` (live allowlist removal; an
  open connection from the node is dropped if the library offers it,
  otherwise it dies at its next dial — recorded in the facade doc).
- `wata-server`: `enroll.scala` (the revoke route + allowlist
  rewrite; a bind route for an already-enrolled node), `bindings.scala`
  (`unbind` + journal + replay), `adminapi.scala` (`removeUser`
  unbinds), `persist.scala`/`store.scala` (`node_id` on device rows;
  revoke by node id), the admin page's enrol section (remove action;
  pick-or-create on unbound/dangling enrolled rows), `irohnet.scala`
  facade.
- `docs/design/wata-server.md` enrolment section.

## The lifecycle gaps (the owner's fumble, walked critically)

The state space is allowlist × binding × account × client, and three
of its corners are reachable today with nothing marking them:

1. **A binding is a NAME, and it outlives the account.** `removeUser`
   revokes sessions but never unbinds, and the user id is derived
   (`@<localpart>:<server>`), so deleting an account and later
   creating one with the same localpart silently reattaches the same
   Matrix identity — history, room membership, and every enrolled
   handset bound to the name. That is exactly what the owner
   observed ("provisioning nicely went through"), and as an
   accidental-delete undo it is genuinely convenient — but it is
   inheritance by name reuse, invisible and undecided. **Decision:
   make it deliberate.** `removeUser` UNBINDS the account's node ids
   (journaled, same `unbind` op as revoke), so a deleted account's
   handsets drop to the visible "enrolled, no account" state below
   and a same-name account never silently inherits hardware. The
   identity reattachment (history/membership) remains — that part is
   Matrix-shaped and fine — but re-granting a handset is an explicit
   admin act again.

2. **"Enrolled, no account" is a real state with no exit in the UI.**
   An admitted node with no binding sits at device-login 404 forever;
   the enrolled table says "no account yet" and offers nothing (the
   picker exists only on pending rows, which an enrolled handset no
   longer has). With decision 1 this state also becomes the landing
   spot for deleted accounts' handsets. **The owner's ask, adopted:**
   the enrolled table's unbound rows get the same pick-or-create
   account control the pending rows have — binding (and creating) an
   account for an already-enrolled handset in place.

3. **A dangling binding renders as if healthy.** Until decision 1
   lands (and for journals replaying the old behavior), a binding
   whose localpart has no account shows as "bound to alma" with no
   hint that alma does not exist and the handset is getting 403.
   The enrolled table marks it ("account deleted") and offers the
   same pick-or-create control.

Also observed and to be pinned down: **the client seemed not to
notice its own approval until a restart.** The code says it must —
a rejected login retries at a 60s ceiling forever, and today's
recovery was observed to connect unprompted within that window — so
the fumble most plausibly hit the window where wata-fb was
exit-looping with no config at all. The verification below makes
approval-propagation-without-restart an explicit asserted property so
a real defect (e.g. the iroh dial layer caching a refusal) cannot
hide behind "restart fixed it".

## Verification

An integ scenario walking the lifecycle: enrol + bind + exchange a
message; revoke → (a) fresh dial refused at the transport, (b) the
old token dead on a TCP request too, (c) re-announce → approve →
device-login works again. Account-lifecycle legs: delete the bound
account → handset drops to "enrolled, no account" (device-login 404,
not 403) and a same-name re-create does NOT reattach the handset;
bind-from-enrolled-table works. Approval propagation: after approve,
the client connects within its backoff ceiling with no restart.
`just ci`; admin-page actions exercised against a live server.

## Out of scope

- Auto-expiring stale allowlist entries.
- A last-seen column on the enrolled table (the server has the data;
  nice, separate).
- Preventing same-name identity reattachment at the Matrix layer
  (deliberately kept — it is the accidental-delete undo).
