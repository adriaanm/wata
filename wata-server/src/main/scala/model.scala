/** M7 chunk 2 — the wata-server domain model.
 *
 *  ADTs for a minimal Matrix homeserver (Client-Server API), ported from
 *  `~/g/bq268/wata/src/server`. This file carries the value families the store
 *  and handlers thread; the store itself is `store.scala`, the JSON glue is
 *  `jsonnav.scala`, routing/handlers are `handlers.scala`, and boot/self-check
 *  are `server.scala`.
 *
 *  DEPARTURES FROM store.ts (dormancy caveat — store.ts is a guide, not gospel;
 *  LANGUAGE.md direct-style + good-ADT sense wins):
 *   - `errcode` is a SEALED FAMILY (decision 9), not a stringly union: the
 *     `{errcode, error}` envelope reads `codeStr(c)` off the tag.
 *   - Matrix's `roomId: string | null` (global vs per-room account data) becomes
 *     `(hasRoom: Boolean, roomId: String)` — the subset has no nullable, and an
 *     `Option[String]` would force Option equality in the linear scan. A flat
 *     pair keeps the key compare a boolean/string compare.
 *   - `Device` drops `txnIdMap` (per-device idempotency is chunk-3 messaging).
 *   - account-data `content` is a parsed `Json` value (json module), not an
 *     opaque `Record<string, unknown>`.
 */

// ---- the Matrix error envelope: a sealed family (decision 9) ------------------
sealed trait ErrCode
case class M_FORBIDDEN() extends ErrCode
case class M_NOT_FOUND() extends ErrCode
case class M_UNKNOWN_TOKEN() extends ErrCode
case class M_MISSING_TOKEN() extends ErrCode
case class M_UNRECOGNIZED() extends ErrCode
case class M_BAD_JSON() extends ErrCode
case class M_UNKNOWN() extends ErrCode

/** A Matrix-level failure as a VALUE (not a thrown exception): the handler
 *  pipeline returns `Either[MErr, Json]` and the edge serializes a `Left` to the
 *  `{errcode, error}` envelope with `status`. This is the json module's
 *  errors-are-values-inside house pattern applied to routine 401/403/404/405
 *  control flow (decision 9's "returns the envelope value" arm — see the report).
 *  The only genuine `throws` in the server is `sgo.GoError` from `io.ReadAll`. */
case class MErr(status: scala.Int, code: ErrCode, msg: String)

/** The authenticated principal an access token resolves to. */
case class Auth(userId: String, deviceId: String)

// ---- config + store records --------------------------------------------------

/** A configured user (config.ts `UserConfig`): immutable, from `Config`. */
case class UserCfg(localpart: String, password: String, displayName: String)

/** A device/session created on login (store.ts `Device`, minus `txnIdMap`). */
case class Device(deviceId: String, userId: String, accessToken: String)

/** A user profile (profile.ts `ProfileData`): `avatarUrl == ""` means unset. */
case class Profile(displayname: String, avatarUrl: String)

/** One account-data entry (store.ts `AccountDataItem`): keyed by
 *  (userId, hasRoom, roomId, dtype); `content` is the stored JSON value. `seq`
 *  is the global sequence at set-time (store.ts `_seq`) — /sync's incremental
 *  global-account-data delta (`getAccountDataSince`) reads it (M7 chunk 4). */
case class AcctData(userId: String, hasRoom: Boolean, roomId: String, dtype: String, content: Json, seq: scala.Long)

// ---- rooms / events / media / receipts (M7 chunk 3) --------------------------
//
// DEPARTURES FROM store.ts (dormancy caveat):
//   - a room's `state` map (`${type}\0${stateKey}`) becomes an insertion-ordered
//     `List[(String, Event)]` keyed by `stateKeyOf(type, sk)` — the subset has no
//     mutable Map and rooms are tiny (family-sized), so replace-or-append over a
//     list mirrors the acct slice. The composite key uses a `|SK|` separator
//     (never a NUL: avoids a control char in an emitted Go literal) — safe since
//     neither event types nor state keys contain it.
//   - `MatrixEvent` optional fields (`state_key?`, `redacts?`, `unsigned?`) become
//     flat (has-flag, value) pairs / a `JNull` sentinel — the subset has no
//     nullable, same reading as account-data's `(hasRoom, roomId)`.
//   - media bytes are stored as a Go `String` (byte-preserving: `string([]byte)`
//     round-trips any bytes, incl. invalid UTF-8), not a `[]byte` field — keeps an
//     opaque `go.Bytes` out of a case class; the download edge writes `go.bytes`.
//   - per-device txnId idempotency is a flat `HashMap[String, String]` keyed by
//     `deviceId + "|SK|" + txnId` (a composite key), not a nested per-device map.

/** One Matrix event (store.ts `MatrixEvent`). `content`/`unsigned` are parsed
 *  `Json` values; `ts` is the int64 `origin_server_ts` (json's `JInt(Long)`). */
case class Event(
    eventId: String, etype: String, sender: String, roomId: String, ts: scala.Long,
    content: Json, hasStateKey: Boolean, stateKey: String,
    hasRedacts: Boolean, redacts: String, unsigned: Json, seq: scala.Long)

/** A room (store.ts `Room`): state = keyed current state events, timeline =
 *  chronological event log. */
case class Room(roomId: String, version: String, state: List[(String, Event)], timeline: List[Event])

/** An uploaded media blob (store.ts `MediaItem`), bytes as a byte-preserving
 *  String (see departures). */
case class MediaItem(mediaId: String, data: String, contentType: String)

/** A read receipt (store.ts `Receipt`), with `roomId` embedded so the whole set
 *  is ONE flat list (like the acct slice) — a `HashMap[String, List[Receipt]]`
 *  would instantiate the generic-valued core map `HashMap__S__R`, whose plain
 *  `Option__R{...}` construction clashes with the niche `type Option__R = any`
 *  that `Option[Json]` forces (DOGFOOD 2026-07-10). */
case class Receipt(roomId: String, userId: String, eventId: String, ts: scala.Long, receiptType: String, seq: scala.Long)

/** A registered long-poll waiter (M7 chunk 4, decision 4). ONE flat
 *  `List[Waiter]` in the store (the landmine pin: a `HashMap[String, List/Chan]`
 *  would instantiate a generic-valued core map whose plain `Option__R` clashes
 *  with the niche `type Option__R = any` that `Option[Json]` forces — DOGFOOD
 *  2026-07-10). `id` is a monotonic tag so removal never needs channel equality;
 *  `ch` is an UNBUFFERED `Chan[Boolean]` used purely as a close-signalled wake
 *  (never sent to — see store.scala `notifyUser`/`waitForEvents`). Copying a
 *  Waiter copies the channel HEADER (Go channel semantics: same underlying
 *  channel), so the value-struct shape is exactly what we want. */
case class Waiter(id: scala.Long, userId: String, ch: sgo.Chan[Boolean])

// ---- config (decision 7): mirrors config.ts / index.ts -----------------------
//
// The two config users (index.ts hardcodes exactly alice/bob) are exposed as a
// lookup + a seed hook rather than a `List[UserCfg]`. DEPARTURE (reported): a
// `List[UserCfg]` whose ONLY construction site was `Config.users` did not get
// its template stamped (the emitter dropped the parameterless generic-returning
// `def`, so the instantiation was never requested) — DOGFOOD 2026-07-10. The
// list bought nothing here (2 fixed users, keyed lookup), so it is gone; List is
// still exercised hard by the account-data + JObj-field paths.
object Config:
  // a `def` (not a `val`): an object `val` selection mis-mangles in some
  // positions (emitted raw `Config.serverName`, undefined), while a nullary
  // `def` reliably lowers to `Config_serverName()` everywhere (DOGFOOD
  // 2026-07-10).
  def serverName: String = "localhost"

  /** config.ts users, by localpart. */
  def userByLocalpart(localpart: String): Option[UserCfg] =
    if localpart == "alice" then Some(UserCfg("alice", "testpass123", "Alice"))
    else if localpart == "bob" then Some(UserCfg("bob", "testpass123", "Bob"))
    else None[UserCfg]()
