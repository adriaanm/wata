import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** The canonical family room: server-minted, server-membered.
 *
 *  The family room is a server concept, not a convention an admin follows:
 *  `ensure()` makes THE room exist — alias `#family:<server>`, name `Family`,
 *  the public-chat shape family-model.md specifies — stamped with the
 *  `net.wata.family` state event (`{}`: the stamp is the identity, like
 *  `net.wata.dm`), and writes an `m.room.member` join for every configured
 *  account that is not already joined. It runs at boot (after journal replay)
 *  and again after every account-provisioning write (the adminapi setup and
 *  create paths), so a newly provisioned account is in the family before its
 *  client ever syncs.
 *
 *  Membership is not the client's act: there is no join flow because there is
 *  no unjoined state, and leaving is not offered — the account list IS the
 *  roster (`Rooms.leaveRoute` refuses the family room with a 403). A ban is
 *  the one membership `ensure()` does not walk over: kick undoes itself at
 *  the next ensure, ban holds until lifted — the pathological-case escape
 *  hatch. Client-side auto-join survives only as compat for stock clients.
 *
 *  Rooms that predate the stamp converge instead of duplicating (the
 *  `Dm.migrate` rule, one transaction in `Store.familyGetOrCreate`): a room
 *  already stamped wins outright — journal replay lands here — else the room
 *  the alias names is stamped in place; nothing is deleted. With no accounts
 *  at all (first-run setup) nothing is minted: a room needs a creator, and
 *  the first claimed account becomes it.
 */
object Family:

  def stampType: String = "net.wata.family"
  def aliasName: String = "#family:" + Config.serverName

  // ---- classification ---------------------------------------------------------

  /** is this room THE family room — does it carry the `net.wata.family` stamp. */
  def isFamilyRoom(roomId: String): Boolean = Store.getRoom(roomId) match
    case s: Some[Room] => hasStamp(s.value)
    case None => false

  def hasStamp(room: Room): Boolean = Store.stateContent(room, stampType, "") match
    case _: Some[Json] => true
    case None => false

  // ---- ensure -----------------------------------------------------------------

  /** the family room exists, stamped, with every account joined. Idempotent
   *  and cheap (one scan of a family-sized room list), so provisioning calls
   *  it unconditionally. */
  def ensure(): Unit = creator(Config.allUsers()) match
    case s: Some[String] => ensureAs(s.value)
    case None => ()

  /** the minting sender: the first admin account, else the first account —
   *  a room needs a creator, and the admin who set the server up is the
   *  honest one. Only the first ensure ever uses it. */
  def creator(us: List[UserCfg]): Option[String] = firstAdmin(us) match
    case s: Some[String] => s
    case None => firstUser(us)

  def firstAdmin(us: List[UserCfg]): Option[String] = us match
    case h :: t => firstAdminStep(h, t)
    case Nil  => None

  def firstAdminStep(h: UserCfg, t: List[UserCfg]): Option[String] =
    if h.admin then Some(Store.userIdOf(h.localpart)) else firstAdmin(t)

  def firstUser(us: List[UserCfg]): Option[String] = us match
    case h :: _ => Some(Store.userIdOf(h.localpart))
    case Nil  => None

  def ensureAs(creatorId: String): Unit =
    val res = Store.familyGetOrCreate(aliasName, creatorId, stampSeed(), seeds(creatorId))
    val joined = joinUsers(res.roomId, userIds(Config.allUsers(), Nil))
    if res.created || joined then Store.notifyRoomMembers(res.roomId) else ()

  def userIds(us: List[UserCfg], acc: List[String]): List[String] = us match
    case h :: t => userIdsStep(h, t, acc)
    case Nil  => ListOps.reverse(acc)

  def userIdsStep(h: UserCfg, t: List[UserCfg], acc: List[String]): List[String] =
    var acc2: List[String] = acc
    acc2 = Store.userIdOf(h.localpart) :: acc2
    userIds(t, acc2)

  // ---- server-side joins (shared with Group) ----------------------------------

  /** write a join for every listed user not already joined (a ban holds);
   *  each joined user's long-poll is woken. Returns whether anything was
   *  written, so the caller can wake the room exactly when it changed. */
  def joinUsers(roomId: String, ids: List[String]): Boolean = joinGo(roomId, ids, false)

  def joinGo(roomId: String, ids: List[String], changed: Boolean): Boolean = ids match
    case h :: t => joinGo(roomId, t, joinOne(roomId, h) || changed)
    case Nil  => changed

  def joinOne(roomId: String, userId: String): Boolean = Store.getMembership(roomId, userId) match
    case _: MJoin => false
    case _: MBan  => false
    case _        => writeJoin(roomId, userId)

  def writeJoin(roomId: String, userId: String): Boolean =
    Rooms.addStateEvent(roomId, userId, "m.room.member", userId, Rooms.memberJoinContent(userId, false))
    Store.notifyUser(userId)
    true

  // ---- the minted room's state ------------------------------------------------

  def stampSeed(): StateSeed = StateSeed(stampType, "", emptyObj)

  /** the family room's state, in the order it is written: the public-chat
   *  preamble (family-model.md), name, alias, then the stamp. Member joins
   *  are NOT seeds — `joinUsers` writes them, so the mint path and every
   *  later ensure share one code path. */
  def seeds(creatorId: String): List[StateSeed] =
    var xs: List[StateSeed] = Nil
    xs = stampSeed() :: xs
    xs = StateSeed("m.room.canonical_alias", "", obj1("alias", JStr(aliasName))) :: xs
    xs = StateSeed("m.room.name", "", obj1("name", JStr("Family"))) :: xs
    xs = StateSeed("m.room.power_levels", "", Rooms.powerLevels(creatorId, "public_chat", Nil)) :: xs
    xs = StateSeed("m.room.guest_access", "", obj1("guest_access", JStr("forbidden"))) :: xs
    xs = StateSeed("m.room.history_visibility", "", obj1("history_visibility", JStr("shared"))) :: xs
    xs = StateSeed("m.room.join_rules", "", obj1("join_rule", JStr("public"))) :: xs
    xs = StateSeed("m.room.create", "", Rooms.createContent(creatorId)) :: xs
    xs
