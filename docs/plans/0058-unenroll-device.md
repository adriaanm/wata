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
  (`unbind` + journal + replay — written only by revoke and explicit
  rebind), `persist.scala`/`store.scala` (`node_id` on device rows;
  revoke by node id), the admin page's enrol section (remove action;
  pick-or-create on unbound rows; the dangling state named with a
  create-account action), `irohnet.scala` facade.
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
   observed ("provisioning nicely went through"). **Owner ruling:
   KEEP binding by name.** This server is for small family groups —
   a name IS the identity, deleting an account is shallow (history,
   membership, and handset bindings all survive under the name), and
   recreating the name is the undo. `removeUser` stays as it is; the
   gap was never the behavior but its invisibility, which item 3
   fixes. `unbind` (journaled) still exists — but only revoke and an
   explicit rebind ever write it.

2. **"Enrolled, no account" is a real state with no exit in the UI.**
   An admitted node with no binding sits at device-login 404 forever;
   the enrolled table says "no account yet" and offers nothing (the
   picker exists only on pending rows, which an enrolled handset no
   longer has). **The owner's ask, adopted:** the enrolled table's
   unbound rows get the same pick-or-create account control the
   pending rows have — binding (and creating) an account for an
   already-enrolled handset in place.

3. **A dangling binding renders as if healthy.** A binding whose
   localpart has no account shows as "bound to alma" with no hint
   that alma does not exist and the handset is meanwhile refused
   (403). With ruling 1 this is a legitimate parked state, so the
   enrolled table says what it is and what fixes it: "bound to alma
   (account deleted — recreate the name to restore)" with a
   one-click create-account action. History for the name is KEPT
   (owner ruling) — recreation reattaches everything, which is the
   point.

Also observed: **the handset kept showing the QR after the admin
side was resolved.** Walked back through the states, that was most
plausibly CORRECT behavior mid-fumble: with the bound account
deleted, device-login answers 403 and the refused arc puts the QR
up; recreating the name should have cleared it within the 60s retry
ceiling — but the owner (reasonably) restarted the app first, so it
was never observed. The verification below settles it permanently:
both approval and account-recreation must propagate to a running
client with no restart, so a real defect (e.g. the iroh dial layer
caching a refusal) cannot hide behind "restart fixed it".

## Verification

An integ scenario walking the lifecycle: enrol + bind + exchange a
message; revoke → (a) fresh dial refused at the transport, (b) the
old token dead on a TCP request too, (c) re-announce → approve →
device-login works again. Account-lifecycle legs: delete the bound
account → the handset is refused (403) and the enrolled table shows
the dangling state; recreate the same name → the RUNNING client
recovers within its backoff ceiling with no restart (the owner's
unobserved case, made an asserted property) and the handset is bound
as before; bind-from-enrolled-table works for an unbound node.
Approval propagation: after approve, the client connects within its
backoff ceiling with no restart. `just ci`; admin-page actions
exercised against a live server.

## Out of scope

- Auto-expiring stale allowlist entries.
- A last-seen column on the enrolled table (the server has the data;
  nice, separate).
- Severing name reattachment on account deletion (owner ruling:
  binding by name is the model — deletion is shallow, recreation is
  the undo, for handsets and history alike).
