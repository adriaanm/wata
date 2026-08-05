/** the wata client DOMAIN types: the reusable data model shared by the sync
 *  engine and the UI-facing snapshot.
 *
 *  Notes on the representation:
 *   - ID types (room id, user id, event id, mxc url) are plain `String`: this
 *     language subset forbids a top-level `type` alias. The semantic names
 *     live in doc only.
 *   - `ConnectionState`/`ConversationType` are sealed nullary case-class
 *     families rather than enums (there is no native enum construct here).
 *   - Optional values are represented as plain `String`s (`""` = absent) or as
 *     a flat `(hasX, x)` pair — this subset has no nullable/Option field type.
 *   - `durationMs: Long` is kept as milliseconds end-to-end (the wire carries
 *     `info.duration` in ms); any conversion to seconds is a UI-layer concern.
 *     `timestamp` is `Long` (origin_server_ts, epoch ms).
 */

// ---- connection lifecycle ------------------------------------------------------
sealed trait ConnectionState
case class Disconnected() extends ConnectionState
case class Connecting() extends ConnectionState
case class Connected() extends ConnectionState // logged in, not yet syncing
case class Syncing() extends ConnectionState   // sync loop running
case class ConnError() extends ConnectionState

// ---- conversation kind -----------------------------------------------------------
sealed trait ConversationType
case class DmConv() extends ConversationType
case class FamilyConv() extends ConversationType

// ---- people ------------------------------------------------------------------
case class User(id: String, displayName: String)
case class Contact(user: User)

// ---- a voice message in the snapshot ------------------------------------------
case class VoiceMessage(
  id: String,          // event_id
  sender: User,
  mxcUrl: String,      // mxc:// URL (audio_url derives from it, app-side)
  durationMs: Long,
  timestamp: Long,     // origin_server_ts (epoch ms)
  isPlayed: Boolean,
  // the server's `net.wata.favorite` marker for this event id: a favorited
  // message is kept past the media-retention window (plan 0019). Anyone's
  // favorite counts — retention is server-global — so this is not per-user.
  isFavorite: Boolean
)

/** a DM or family conversation. `hasContact == false` for the family thread;
 *  `roomId == ""` means "no DM room yet" — a family member nobody has messaged.
 *  The first send resolves the pair through the server's DM endpoint, which is
 *  what fills the room in. */
case class Conversation(
  roomId: String,
  convType: ConversationType,
  hasContact: Boolean,
  contact: Contact,
  messages: List[VoiceMessage],
  unplayedCount: Int
)

case class Family(id: String, name: String, members: List[Contact])

// ---- sync-engine working state --------------------------------------------------
case class MemberInfo(userId: String, displayName: String, membership: String)

/** raw voice message extracted from a timeline `m.room.message`/`m.audio`. */
case class VoiceMessageRaw(eventId: String, sender: String, mxcUrl: String, durationMs: Long, timestamp: Long)

/** read receipts for one event: eventId -> the user ids that read it
 *  (insertion-ordered; duplicates are kept, not deduplicated). */
case class ReceiptEntry(eventId: String, userIds: List[String])

/** per-room accumulated sync state. Immutable — the engine swaps whole
 *  records in its insertion-ordered room list. `canonical_alias` (optional on
 *  the wire) becomes the flat `(hasAlias, alias)` pair, since "" is not a
 *  valid alias anyway. */
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
  dmMembers: List[String],              // the `net.wata.dm` pair; empty = not a DM
  favorites: List[String]               // event ids with a live `net.wata.favorite` slot
)

/** the immutable UI view, built by the engine. Optional `User`/`Family`
 *  values are represented as has-flag pairs (this subset has no nullable). */
case class StateSnapshot(
  connection: ConnectionState,
  hasSelfUser: Boolean,
  selfUser: User,
  contacts: List[Contact],
  conversations: List[Conversation],
  hasFamily: Boolean,
  family: Family
)

// ---- emitted sync events ---------------------------------------------------------
// Pointer-shaped by design: the value-shape rule lays every case's payload
// slots side by side in one flat struct (distinct names and a <= 4-word total
// across all cases); SyncEvent's cases carry 8 string slots between them, so
// no accessor rename brings it under that budget.
sealed trait SyncEvent
case class RoomUpdated(roomId: String) extends SyncEvent
case class TimelineEventE(roomId: String, eventId: String) extends SyncEvent
case class MembershipChanged(roomId: String, userId: String, membership: String) extends SyncEvent
case class ReceiptUpdated(roomId: String, eventId: String) extends SyncEvent
