import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** Favorites: keeping a voice message past the retention window (plan 0019).
 *
 *  A favorite is ROOM STATE the server writes: `net.wata.favorite`, `state_key`
 *  = the favorited event's id, content `{"by": userId}`; unfavoriting rewrites
 *  the slot with `{}` (the Matrix state-resolution idiom for clearing one).
 *  Storing the marker as an ordinary state event buys everything at once — it
 *  journals and replays as the existing `event` op, it reaches every client
 *  through ordinary `/sync` state (no new transport), and the retention sweep
 *  reads it out of the room it already walks (retain.scala).
 *
 *  The write path is a DIALECT ENDPOINT rather than a raw state PUT:
 *
 *    POST /_wata/v1/favorite/{roomId}/{eventId} -> {"favorite": true|false}
 *
 *  because the rule it applies — "any JOINED MEMBER may favorite" — is one a
 *  `PUT /state` cannot express: `state_default` is 50 and ordinary members sit
 *  at 0, and lowering the level for one event type in every existing room is a
 *  migration this does not need.
 *
 *  Favorites are GLOBAL in effect: retention is server-global, so anyone's
 *  favorite keeps the message for everyone. `by` records who, for the UI and
 *  for a future per-user view. */
object Favorite:

  /** the state event type carrying the marker. */
  def etype: String = "net.wata.favorite"

  // ---- the dialect endpoint ---------------------------------------------------

  def route(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] => toggle(rr.right.userId, r.pathValue("roomId"), r.pathValue("eventId"))

  /** the toggle, gated in this order: the room exists (404), the caller is a
   *  joined member of it (403), the target is an event of that room (404), and
   *  it is an unredacted `m.room.message` (400). Favoriting a TEXT message is
   *  allowed and simply has no retention meaning today. */
  def toggle(caller: String, roomId: String, eventId: String): Either[MErr, Json] =
    Store.getRoom(roomId) match
      case s: Some[Room] => toggleRoom(caller, s.value, roomId, eventId)
      case None => Left(MErr(404, M_NOT_FOUND(), "Unknown room"))

  def toggleRoom(caller: String, room: Room, roomId: String, eventId: String): Either[MErr, Json] =
    if !isJoined(roomId, caller) then Left(MErr(403, M_FORBIDDEN(), "Not a joined member of this room"))
    else toggleTarget(caller, room, roomId, eventId)

  def isJoined(roomId: String, userId: String): Boolean = Store.getMembership(roomId, userId) match
    case _: MJoin => true
    case _        => false

  def toggleTarget(caller: String, room: Room, roomId: String, eventId: String): Either[MErr, Json] =
    Store.getEventById(roomId, eventId) match
      case s: Some[Event] => toggleEvent(caller, room, roomId, s.value)
      case None => Left(MErr(404, M_NOT_FOUND(), "Unknown event"))

  def toggleEvent(caller: String, room: Room, roomId: String, ev: Event): Either[MErr, Json] =
    if ev.etype != "m.room.message" then Left(MErr(400, M_BAD_JSON(), "Not a message event"))
    else if isRedacted(ev) then Left(MErr(400, M_BAD_JSON(), "Event is redacted"))
    else write(caller, roomId, ev.eventId, !isFavorited(room, ev.eventId))

  /** a redacted event has empty content (`Store.redactTarget`), which is also
   *  what makes it invisible to the retention sweep. */
  def isRedacted(ev: Event): Boolean = emptyContent(ev.content)

  /** write the marker through `Store.addEvent`, so the journal and every
   *  client's `/sync` ride the ordinary state-event path; then wake the room's
   *  long-polls, as every other state write does. */
  def write(caller: String, roomId: String, eventId: String, on: Boolean): Either[MErr, Json] =
    Store.addEvent(roomId, etype, caller, content(caller, on), true, eventId, false, "", JNull()) match
      case _: Some[Event] => written(roomId, on)
      case None => Left(MErr(404, M_NOT_FOUND(), "Unknown room"))

  def written(roomId: String, on: Boolean): Either[MErr, Json] =
    Store.notifyRoomMembers(roomId)
    Right(obj1("favorite", JBool(on)))

  /** `{"by": userId}` to set, `{}` to clear. */
  def content(caller: String, on: Boolean): Json =
    if on then obj1("by", JStr(caller)) else emptyObj

  // ---- the read side (the sweep's exemption, retain.scala) --------------------

  /** does this room's state carry a NON-EMPTY `net.wata.favorite` slot under
   *  `eventId` — i.e. is that event favorited right now. */
  def isFavorited(room: Room, eventId: String): Boolean =
    Store.stateContent(room, etype, eventId) match
      case s: Some[Json] => !emptyContent(s.value)
      case None => false

  def emptyContent(c: Json): Boolean = c match
    case o: JObj => noFields(o.fields)
    case _       => true

  def noFields(fs: List[(String, Json)]): Boolean = fs match
    case _ :: _ => false
    case Nil  => true
