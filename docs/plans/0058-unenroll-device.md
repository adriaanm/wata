# 0058 — un-enrolling a device

Status: proposed (owner observation 2026-08-16, after the /data
adoption incident orphaned an enrolled node id: "we should have a way
to un-enroll a device")

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
  rewrite), `bindings.scala` (`unbind` + journal + replay),
  `persist.scala`/`store.scala` (`node_id` on device rows; revoke by
  node id), the admin page's enrol section, `irohnet.scala` facade.
- `docs/design/wata-server.md` enrolment section.

## Verification

An integ scenario: enrol + bind + exchange a message, revoke, then
assert (a) a fresh dial is refused at the transport, (b) the old
token is dead on a TCP request too, (c) re-announce → approve →
device-login works again. `just ci`; admin-page action exercised
against a live server.

## Out of scope

- Bulk/cascade semantics (removing a user already revokes sessions;
  it still won't touch the allowlist — an account and a handset are
  separate trust grants, revoked separately).
- Auto-expiring stale allowlist entries.
