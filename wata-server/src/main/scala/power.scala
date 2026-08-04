import ListOps.*
import JsonNav.*

/** Power levels: `m.room.power_levels` read as the authorization table it is.
 *
 *  Every rule here is a comparison of two integers pulled out of that one state
 *  event — the actor's level, and the level the thing they are attempting
 *  requires — so the whole module is lookups with defaults and `>=`. The
 *  defaults are the spec's, and are also what `Rooms.powerLevels` writes into a
 *  new room: `users_default` 0, `events_default` 0, `state_default` 50,
 *  `invite` 0, `kick`/`ban`/`redact` 50, and the creator at 100.
 *
 *  Membership is NOT decided here. Whether a transition is legal at all is the
 *  `Mem.transition` table (membership.scala); power levels only say whether
 *  *this actor* may drive it. The two are asked in that order, and a room with
 *  no `m.room.power_levels` event at all falls through to the defaults, which
 *  leaves message sending open and state setting closed.
 */
object Power:

  /** the room's power-levels content, `{}` when the room has no such event. */
  def levels(room: Room): Json = Store.stateContent(room, "m.room.power_levels", "") match
    case s: Some[Json] => s.value
    case None => emptyObj

  /** a user's level: `users[userId]`, else `users_default`, else 0. */
  def userLevel(pl: Json, userId: String): scala.Long =
    subLong(pl, "users", userId, longOrDflt(pl, "users_default", 0L))

  /** the level an event type needs to be sent: `events[etype]`, else
   *  `state_default` for a state event or `events_default` for a message. */
  def eventLevel(pl: Json, etype: String, isState: Boolean): scala.Long =
    subLong(pl, "events", etype, typeDefault(pl, isState))

  def typeDefault(pl: Json, isState: Boolean): scala.Long =
    if isState then longOrDflt(pl, "state_default", 50L)
    else longOrDflt(pl, "events_default", 0L)

  /** `pl[outer][key]` as an int64, or `dflt` when either level is absent. */
  def subLong(pl: Json, outer: String, key: String, dflt: scala.Long): scala.Long = getField(pl, outer) match
    case s: Some[Json] => longOrDflt(s.value, key, dflt)
    case None => dflt

  // ---- the checks the handlers ask ------------------------------------------

  /** may `userId` send an event of this type — `isState` picks which default
   *  applies, which is the whole difference between posting a message and
   *  rewriting the room's configuration. */
  def canSend(room: Room, userId: String, etype: String, isState: Boolean): Boolean =
    val pl = levels(room)
    userLevel(pl, userId) >= eventLevel(pl, etype, isState)

  /** redaction takes the send level for `m.room.redaction`; redacting SOMEONE
   *  ELSE's event additionally takes the `redact` level. */
  def canRedact(room: Room, userId: String, target: Event): Boolean =
    val pl = levels(room)
    if userLevel(pl, userId) < eventLevel(pl, "m.room.redaction", false) then false
    else redactOthers(pl, userId, target)

  def redactOthers(pl: Json, userId: String, target: Event): Boolean =
    if target.sender == userId then true
    else userLevel(pl, userId) >= longOrDflt(pl, "redact", 50L)

  def canInvite(room: Room, userId: String): Boolean =
    val pl = levels(room)
    userLevel(pl, userId) >= longOrDflt(pl, "invite", 0L)

  def canKick(room: Room, actor: String, target: String): Boolean =
    evict(room, actor, target, "kick")

  def canBan(room: Room, actor: String, target: String): Boolean =
    evict(room, actor, target, "ban")

  /** kick/ban both take their named level AND require the actor to outrank the
   *  target strictly — equals may not remove equals, which is what stops two
   *  co-admins from evicting each other. */
  def evict(room: Room, actor: String, target: String, key: String): Boolean =
    val pl = levels(room)
    if userLevel(pl, actor) < longOrDflt(pl, key, 50L) then false
    else userLevel(pl, actor) > userLevel(pl, target)
