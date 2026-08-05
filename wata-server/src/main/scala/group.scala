import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** Groups: the family-room concept with a member list.
 *
 *  A group ("kids", "camping trip") is a room stamped `net.wata.group`
 *  (`{"name": …}` — the name IS the key: one group per name, like one DM per
 *  pair), minted through one dialect endpoint:
 *
 *    POST /_wata/v1/group   {"name": "kids", "members": ["bob", …]}
 *      -> {"room_id": …}
 *
 *  Auth required; the caller is included implicitly. The server creates the
 *  room, stamps it, and JOINS every listed member server-side — the
 *  DM-resolve precedent: nobody accepts an invitation, membership is an act
 *  of whoever created the group, which the trust model (the server
 *  population is the family) makes acceptable in a way it wouldn't be on
 *  federated Matrix. Re-POSTing the same name with more members is the
 *  idempotent GET-OR-EXTEND: the existing room comes back, the new members
 *  are joined into it. Nothing removes members here (kick/ban exist for the
 *  pathological case; power levels: creator 100, members 0, with
 *  `events_default` 0 so everyone speaks).
 *
 *  A member the server does not know is a 404 and nothing is created or
 *  extended — a misspelled localpart must not mint a half-membered group.
 */
object Group:

  def stampType: String = "net.wata.group"

  // ---- classification ---------------------------------------------------------

  /** the room's stamped group name, or "" (not a group). */
  def nameOf(roomId: String): String = Store.groupNameOf(roomId)

  // ---- the dialect endpoint ----------------------------------------------------

  def route(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => handle(rr.right.userId, body)

  def handle(caller: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => handleParsed(caller, j)

  def handleParsed(caller: String, j: Json): Either[MErr, Json] =
    val name = strField(j, "name", "")
    if name == "" then Left(MErr(400, M_BAD_JSON(), "A group name is required"))
    else handleMembers(caller, name, membersOf(j))

  def membersOf(j: Json): List[String] = getField(j, "members") match
    case s: Some[Json] => Rooms.arrStrings(s.value)
    case None => Nil

  /** every listed member must resolve to an account here BEFORE anything is
   *  created or joined. */
  def handleMembers(caller: String, name: String, raw: List[String]): Either[MErr, Json] =
    resolveAll(raw, Nil) match
      case l: Left[String, List[String]] =>
        Left(MErr(404, M_NOT_FOUND(), "Unknown user: " + l.left))
      case rr: Right[String, List[String]] =>
        Right(obj1("room_id", JStr(getOrExtend(caller, name, rr.right))))

  /** resolve each entry (bare localpart or full MXID, `Dm.normalize`);
   *  Left is the first entry that names nobody here. */
  def resolveAll(raw: List[String], acc: List[String]): Either[String, List[String]] = raw match
    case h :: t => resolveStep(h, t, acc)
    case Nil  => Right(ListOps.reverse(acc))

  def resolveStep(h: String, t: List[String], acc: List[String]): Either[String, List[String]] =
    val id = Dm.normalize(h)
    if id == "" then Left(h) else resolveNext(id, t, acc)

  def resolveNext(id: String, t: List[String], acc: List[String]): Either[String, List[String]] =
    var acc2: List[String] = acc
    acc2 = id :: acc2
    resolveAll(t, acc2)

  /** THE room for `name`, minted on first ask (one `Store` transaction), the
   *  caller and every member joined either way — which is what makes a
   *  re-POST with more members the extend. */
  def getOrExtend(caller: String, name: String, members: List[String]): String =
    val res = Store.groupGetOrCreate(name, caller, seeds(caller, name))
    // cons BOUND first: a `::` in argument position does not lower correctly.
    val all = caller :: members
    val joined = Family.joinUsers(res.roomId, all)
    if res.created || joined then Store.notifyRoomMembers(res.roomId) else ()
    res.roomId

  // ---- the minted room's state ------------------------------------------------

  /** a group's state, in the order it is written: the private-room preamble
   *  (no alias — the stamp is the only handle), power levels (creator 100,
   *  members 0, everyone speaks), the group name as the room name, then the
   *  stamp that names it. Member joins are `Family.joinUsers`', shared with
   *  the extend path. */
  def seeds(creatorId: String, name: String): List[StateSeed] =
    var xs: List[StateSeed] = Nil
    xs = StateSeed(stampType, "", obj1("name", JStr(name))) :: xs
    xs = StateSeed("m.room.name", "", obj1("name", JStr(name))) :: xs
    xs = StateSeed("m.room.power_levels", "", Rooms.powerLevels(creatorId, "private_chat", Nil)) :: xs
    xs = StateSeed("m.room.guest_access", "", obj1("guest_access", JStr("can_join"))) :: xs
    xs = StateSeed("m.room.history_visibility", "", obj1("history_visibility", JStr("shared"))) :: xs
    xs = StateSeed("m.room.join_rules", "", obj1("join_rule", JStr("invite"))) :: xs
    xs = StateSeed("m.room.create", "", Rooms.createContent(creatorId)) :: xs
    xs
