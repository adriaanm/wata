/** The wata-server domain model.
 *
 *  ADTs for a minimal Matrix homeserver (Client-Server API). This file carries
 *  the value families the store and handlers thread; the store itself is
 *  `store.scala`, the JSON glue is `jsonnav.scala`, routing/handlers are
 *  `handlers.scala`, and boot/self-check are `server.scala`.
 *
 *  Some shapes are chosen for this language subset rather than mirroring
 *  Matrix's own JSON shapes directly:
 *   - `errcode` is a SEALED FAMILY, not a stringly union: the `{errcode,
 *     error}` envelope reads `codeStr(c)` off the tag.
 *   - Matrix's `roomId: string | null` (global vs per-room account data) becomes
 *     `(hasRoom: Boolean, roomId: String)` — the subset has no nullable, and an
 *     `Option[String]` would force Option equality in the linear scan. A flat
 *     pair keeps the key compare a boolean/string compare.
 *   - account-data `content` is a parsed `Json` value (json module), not an
 *     opaque `Record<string, unknown>`.
 *   - `Device` carries no per-device transaction-id map; that idempotency
 *     tracking lives in the store's flat `txns` map instead (store.scala).
 */

// ---- the Matrix error envelope: a sealed family --------------------------------
sealed trait ErrCode
case class M_FORBIDDEN() extends ErrCode
case class M_NOT_FOUND() extends ErrCode
case class M_UNKNOWN_TOKEN() extends ErrCode
case class M_MISSING_TOKEN() extends ErrCode
case class M_UNRECOGNIZED() extends ErrCode
case class M_BAD_JSON() extends ErrCode
case class M_TOO_LARGE() extends ErrCode
case class M_UNKNOWN() extends ErrCode

/** A Matrix-level failure as a VALUE (not a thrown exception): the handler
 *  pipeline returns `Either[MErr, Json]` and the edge serializes a `Left` to the
 *  `{errcode, error}` envelope with `status`. Routine 401/403/404/405 control
 *  flow is modeled as data, not exceptions; the only genuine `throws` in the
 *  server is `sgo.GoError` from `io.ReadAll`. */
case class MErr(status: scala.Int, code: ErrCode, msg: String)

/** The authenticated principal an access token resolves to. */
case class Auth(userId: String, deviceId: String)

// ---- config + store records --------------------------------------------------

/** A configured user, immutable, from `Config` (config.scala). */
case class UserCfg(localpart: String, password: String, displayName: String)

/** A device/session created on login. */
case class Device(deviceId: String, userId: String, accessToken: String)

/** A user profile: `avatarUrl == ""` means unset. */
case class Profile(displayname: String, avatarUrl: String)

/** One account-data entry, keyed by (userId, hasRoom, roomId, dtype);
 *  `content` is the stored JSON value. `seq` is the global sequence at
 *  set-time — /sync's incremental global-account-data delta
 *  (`getAccountDataSince`) reads it. */
case class AcctData(userId: String, hasRoom: Boolean, roomId: String, dtype: String, content: Json, seq: scala.Long)

// ---- rooms / events / media / receipts -----------------------------------------
//
// Shapes chosen for this language subset:
//   - a room's `state` map becomes an insertion-ordered `List[(String, Event)]`
//     keyed by `stateKeyOf(type, sk)` — the subset has no mutable Map and rooms
//     are tiny, so replace-or-append over a list mirrors the acct slice. The
//     composite key uses a `|SK|` separator (never a NUL: avoids a control char
//     in an emitted Go literal) — safe since neither event types nor state keys
//     contain it.
//   - optional Matrix event fields (`state_key?`, `redacts?`, `unsigned?`)
//     become flat (has-flag, value) pairs / a `JNull` sentinel — the subset has
//     no nullable, same reading as account-data's `(hasRoom, roomId)`.
//   - media bytes are stored as a Go `String` (byte-preserving: `string([]byte)`
//     round-trips any bytes, incl. invalid UTF-8), not a `[]byte` field — keeps an
//     opaque `go.Bytes` out of a case class; the download edge writes `go.bytes`.
//   - per-device txnId idempotency is a flat `HashMap[String, String]` keyed by
//     `deviceId + "|SK|" + txnId` (a composite key), not a nested per-device map.

/** One Matrix event. `content`/`unsigned` are parsed `Json` values; `ts` is
 *  the int64 `origin_server_ts` (json's `JInt(Long)`). */
case class Event(
    eventId: String, etype: String, sender: String, roomId: String, ts: scala.Long,
    content: Json, hasStateKey: Boolean, stateKey: String,
    hasRedacts: Boolean, redacts: String, unsigned: Json, seq: scala.Long)

/** A room: state = keyed current state events, timeline = chronological
 *  event log. */
case class Room(roomId: String, version: String, state: List[(String, Event)], timeline: List[Event])

/** An uploaded media blob. In file-backed mode (MediaFiles enabled) the store
 *  holds METADATA only — `data` is "" and the bytes live in the blob file,
 *  read on demand by `Store.getMedia`; stateless runs keep the bytes here, as
 *  a byte-preserving String (see above). */
case class MediaItem(mediaId: String, data: String, contentType: String)

/** A read receipt, with `roomId` embedded so the whole set is ONE flat list
 *  (like the acct slice) rather than a per-room map. */
case class Receipt(roomId: String, userId: String, eventId: String, ts: scala.Long, receiptType: String, seq: scala.Long)

/** One canonical DM: the unordered user pair `{a, b}` (stored SORTED, so the
 *  pair is a key rather than two orderings) and the room that IS that DM. The
 *  whole set is ONE flat `List[DmPair]` in the store, like the acct and receipt
 *  slices — family-sized, so a linear scan is the right shape and the list can
 *  also be walked per user (`Store.dmPeersOf`). */
case class DmPair(a: String, b: String, roomId: String)

/** one of a user's canonical DMs, from their side: who it is with, and where. */
case class DmPeer(peer: String, roomId: String)

/** the result of `Store.dmGetOrCreate`: the canonical room, and whether THIS
 *  call is the one that created it (only the creator does the after-effects —
 *  the compat `m.direct` write and the two wakes). */
case class DmRoom(roomId: String, created: Boolean)

/** one state event to stamp into a freshly created room, inside the SAME store
 *  transaction that mints it (dm.scala builds the list; store.scala applies
 *  it). Sender is the room's creator for all of them. */
case class StateSeed(etype: String, sk: String, content: Json)

/** A registered long-poll waiter. ONE flat `List[Waiter]` in the store.
 *  `id` is a monotonic tag so removal never needs channel equality; `ch` is
 *  an UNBUFFERED `Chan[Boolean]` used purely as a close-signalled wake
 *  (never sent to — see store.scala `notifyUser`/`waitForEvents`). Copying a
 *  Waiter copies the channel HEADER (Go channel semantics: same underlying
 *  channel), so the value-struct shape is exactly what we want. */
case class Waiter(id: scala.Long, userId: String, ch: sgo.Chan[Boolean])

