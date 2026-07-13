/** M8 chunk 3 — the wata client DOMAIN types, ported from fbclient `types.zig`
 *  (the domain half). Portable Scala (ZERO `go`): the reusable core a Scala.js
 *  client would share verbatim.
 *
 *  DEPARTURES FROM types.zig (dormancy caveat — spec + taste win; recorded in the
 *  chunk report):
 *   - the ID aliases (`RoomId`/`UserId`/`EventId`/`MxcUrl` = `[]const u8` in Zig)
 *     stay plain `String` here: the subset forbids a top-level `type` alias (the
 *     emitter's walk only handles class TypeDefs), and wata-server's model.scala
 *     set the precedent of plain `String` IDs. The semantic names live in doc.
 *   - `ConnectionState`/`ConversationType` are SEALED nullary case-class families
 *     (DATA-8 / the server's `ErrCode` precedent), not Zig `enum`s.
 *   - Zig's fixed `[128]u8`+`len` buffers and `?Contact` become plain `String`s /
 *     a `(hasContact, contact)` flat pair (the subset has no nullable; the server
 *     model.scala uses the same has-flag idiom).
 *   - `duration: f64` (seconds) is kept as `durationMs: Long` end-to-end (the
 *     wire carries `info.duration` ms); the UI second-conversion is a chunk-7
 *     concern. `timestamp` stays `Long` (origin_server_ts, epoch ms).
 */

// ---- connection lifecycle (types.zig ConnectionState) ------------------------
sealed trait ConnectionState
case class Disconnected() extends ConnectionState
case class Connecting() extends ConnectionState
case class Connected() extends ConnectionState // logged in, not yet syncing
case class Syncing() extends ConnectionState   // sync loop running
case class ConnError() extends ConnectionState // `err` in Zig (reserved-ish)

// ---- conversation kind (types.zig ConversationType) --------------------------
sealed trait ConversationType
case class DmConv() extends ConversationType
case class FamilyConv() extends ConversationType

// ---- people ------------------------------------------------------------------
case class User(id: String, displayName: String)
case class Contact(user: User)

// ---- a voice message in the snapshot (types.zig VoiceMessage) ----------------
case class VoiceMessage(
  id: String,          // event_id
  sender: User,
  mxcUrl: String,      // mxc:// URL (audio_url derives from it, app-side)
  durationMs: Long,
  timestamp: Long,     // origin_server_ts (epoch ms)
  isPlayed: Boolean
)

/** a DM or family conversation (types.zig Conversation). `hasContact == false`
 *  for the family thread; `roomId == ""` means "no DM room yet" (created on first
 *  send). */
case class Conversation(
  roomId: String,
  convType: ConversationType,
  hasContact: Boolean,
  contact: Contact,
  messages: List[VoiceMessage],
  unplayedCount: Int
)

case class Family(id: String, name: String, members: List[Contact])

// ---- sync-engine working state (sync_engine.zig MemberInfo / VoiceMessageRaw) -
case class MemberInfo(userId: String, displayName: String, membership: String, isDirect: Boolean)

/** raw voice message extracted from a timeline `m.room.message`/`m.audio`. */
case class VoiceMessageRaw(eventId: String, sender: String, mxcUrl: String, durationMs: Long, timestamp: Long)

/** read receipts for one event: eventId -> the user ids that read it (Zig's
 *  `receipt_user_ids` StringArrayHashMap entry — insertion-ordered, duplicates
 *  kept exactly as Zig appends them). */
case class ReceiptEntry(eventId: String, userIds: List[String])

/** one `m.direct` account-data entry: userId -> DM room ids (insertion-ordered). */
case class DirectEntry(userId: String, roomIds: List[String])

/** per-room accumulated sync state (sync_engine.zig RoomState). Immutable —
 *  the engine swaps whole records in its insertion-ordered room list. Zig's
 *  `canonical_alias: ?[]const u8` becomes the flat `(hasAlias, alias)` pair
 *  (the model.scala has-flag idiom; "" is not a valid alias anyway). */
case class RoomState(
  roomId: String,
  name: String,
  hasAlias: Boolean,
  alias: String,
  members: List[MemberInfo],            // insertion-ordered; replace keeps position
  timelineEventIds: List[String],       // dedup set
  voiceMessages: List[VoiceMessageRaw], // timeline order
  receipts: List[ReceiptEntry],
  prevBatch: String,
  isDm: Boolean                         // sticky is_direct flag
)

/** the immutable UI view (types.zig StateSnapshot), built by the engine.
 *  `?User`/`?Family` become has-flag pairs (subset: no nullable). */
case class StateSnapshot(
  connection: ConnectionState,
  hasSelfUser: Boolean,
  selfUser: User,
  contacts: List[Contact],
  conversations: List[Conversation],
  hasFamily: Boolean,
  family: Family
)

// ---- emitted sync events (sync_engine.zig SyncEvent) -------------------------
// optReport: POINTER, and stays so by design — the value-shape rule lays every
// case's payload slots side by side in ONE flat struct (distinct names AND a
// <= 4-word total across all cases); SyncEvent's cases carry 9 string slots, so
// no accessor rename can bring it under budget. Pointer-shaped is correct here.
sealed trait SyncEvent
case class RoomUpdated(roomId: String) extends SyncEvent
case class TimelineEventE(roomId: String, eventId: String) extends SyncEvent
case class MembershipChanged(roomId: String, userId: String, membership: String) extends SyncEvent
case class ReceiptUpdated(roomId: String, eventId: String) extends SyncEvent
case class AccountDataUpdated(dataType: String) extends SyncEvent
