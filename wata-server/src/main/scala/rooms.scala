import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** The room / messaging / receipt / media handlers. Every step returns
 *  `Either[MErr, Json]`; the edge serializes it. Membership decisions go through
 *  the `Mem.transition` table (membership.scala). Media DOWNLOAD is the one
 *  raw-bytes response and is handled at the mux edge (server.scala `MediaEdge`),
 *  not here; UPLOAD returns JSON and flows through the normal pipeline.
 *
 *  Known simplifications:
 *   - createRoom applies presets + is_direct + invite + name + alias; it does NOT
 *     yet apply `initial_state`, `creation_content`, or `power_level_content_
 *     override` (rarely used; deferred).
 *   - /publicRooms is not implemented.
 */
/** A resolved `/messages` pagination boundary. `ok` is false when the `from`
 *  parameter named neither an `s<seq>` position nor an event in this room. */
case class Anchor(ok: Boolean, seq: scala.Long)

object Rooms:

  // ---- createRoom --------------------------------------------------------

  def createRoom(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => createRoom1(rr.right.userId, body)

  def createRoom1(userId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Right(j) => createRoomDo(userId, j)
    case Left(_)  => createRoomDo(userId, emptyObj)

  def createRoomDo(userId: String, j: Json): Either[MErr, Json] =
    val roomId = Store.createRoom()
    val preset = presetOf(j)
    val direct = boolField(j, "is_direct")
    val invites = invitesOf(j)
    addStateEvent(roomId, userId, "m.room.create", "", createContent(userId))
    applyPreset(roomId, userId, preset)
    addStateEvent(roomId, userId, "m.room.power_levels", "", powerLevels(userId, preset, invites))
    addStateEvent(roomId, userId, "m.room.member", userId, memberJoinContent(userId, direct))
    addName(roomId, userId, j)
    addAlias(roomId, userId, j)
    addInvites(roomId, userId, invites, direct)
    Store.notifyUser(userId)
    Right(obj1("room_id", JStr(roomId)))

  /** preset ?? (visibility == 'public' ? 'public_chat' : 'private_chat'). */
  def presetOf(j: Json): String = getField(j, "preset") match
    case s: Some[Json] => strOr(s.value, presetFromVisibility(j))
    case None => presetFromVisibility(j)

  def presetFromVisibility(j: Json): String =
    if strField(j, "visibility", "") == "public" then "public_chat" else "private_chat"

  def applyPreset(roomId: String, userId: String, preset: String): Unit =
    if preset == "public_chat" then applyPublic(roomId, userId)
    else applyPrivate(roomId, userId)

  def applyPrivate(roomId: String, userId: String): Unit =
    addStateEvent(roomId, userId, "m.room.join_rules", "", obj1("join_rule", JStr("invite")))
    addStateEvent(roomId, userId, "m.room.history_visibility", "", obj1("history_visibility", JStr("shared")))
    addStateEvent(roomId, userId, "m.room.guest_access", "", obj1("guest_access", JStr("can_join")))

  def applyPublic(roomId: String, userId: String): Unit =
    addStateEvent(roomId, userId, "m.room.join_rules", "", obj1("join_rule", JStr("public")))
    addStateEvent(roomId, userId, "m.room.history_visibility", "", obj1("history_visibility", JStr("shared")))
    addStateEvent(roomId, userId, "m.room.guest_access", "", obj1("guest_access", JStr("forbidden")))

  def powerLevels(creator: String, preset: String, invites: List[String]): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("users", usersMap(creator, preset, invites)) :: fs
    fs = ("users_default", JInt(0L)) :: fs
    fs = ("events_default", JInt(0L)) :: fs
    fs = ("state_default", JInt(50L)) :: fs
    fs = ("ban", JInt(50L)) :: fs
    fs = ("kick", JInt(50L)) :: fs
    fs = ("redact", JInt(50L)) :: fs
    fs = ("invite", JInt(0L)) :: fs
    endObj(fs)

  def usersMap(creator: String, preset: String, invites: List[String]): Json =
    var us: List[(String, Json)] = Nil
    us = (creator, JInt(100L)) :: us
    if preset == "trusted_private_chat" then us = addTrusted(us, invites) else ()
    endObj(us)

  def addTrusted(us: List[(String, Json)], invites: List[String]): List[(String, Json)] = invites match
    case h :: t => addTrustedStep(us, h, t)
    case Nil  => us

  def addTrustedStep(us: List[(String, Json)], h: String, t: List[String]): List[(String, Json)] =
    var us2: List[(String, Json)] = us
    us2 = (h, JInt(100L)) :: us2
    addTrusted(us2, t)

  def createContent(creator: String): Json =
    obj2("creator", JStr(creator), "room_version", JStr("10"))

  def memberJoinContent(userId: String, direct: Boolean): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("membership", JStr("join")) :: fs
    fs = ("displayname", JStr(displayNameOf(userId))) :: fs
    if direct then fs = ("is_direct", JBool(true)) :: fs else ()
    endObj(fs)

  def memberInviteContent(direct: Boolean): Json =
    obj2("membership", JStr("invite"), "is_direct", JBool(direct))

  def displayNameOf(userId: String): String = Config.userByLocalpart(Store.localpartOf(userId)) match
    case s: Some[UserCfg] => s.value.displayName
    case None => Store.localpartOf(userId)

  def addName(roomId: String, userId: String, j: Json): Unit = getField(j, "name") match
    case s: Some[Json] => addName2(roomId, userId, strOr(s.value, ""))
    case None => ()

  def addName2(roomId: String, userId: String, name: String): Unit =
    if name == "" then () else addStateEvent(roomId, userId, "m.room.name", "", obj1("name", JStr(name)))

  def addAlias(roomId: String, userId: String, j: Json): Unit = getField(j, "room_alias_name") match
    case s: Some[Json] => addAlias2(roomId, userId, strOr(s.value, ""))
    case None => ()

  def addAlias2(roomId: String, userId: String, name: String): Unit =
    if name == "" then () else addAlias3(roomId, userId, "#" + name + ":" + Config.serverName)

  def addAlias3(roomId: String, userId: String, alias: String): Unit =
    Store.setAlias(alias, roomId)
    addStateEvent(roomId, userId, "m.room.canonical_alias", "", obj1("alias", JStr(alias)))

  def addInvites(roomId: String, userId: String, invites: List[String], direct: Boolean): Unit = invites match
    case h :: t => addInvitesStep(roomId, userId, h, t, direct)
    case Nil  => ()

  def addInvitesStep(roomId: String, userId: String, target: String, t: List[String], direct: Boolean): Unit =
    addStateEvent(roomId, userId, "m.room.member", target, memberInviteContent(direct))
    Store.notifyUser(target)
    addInvites(roomId, userId, t, direct)

  def invitesOf(j: Json): List[String] = getField(j, "invite") match
    case s: Some[Json] => arrStrings(s.value)
    case None => Nil

  def arrStrings(j: Json): List[String] = j match
    case a: JArr => collectStrings(a.items, Nil)
    case _       => Nil

  def collectStrings(xs: List[Json], acc: List[String]): List[String] = xs match
    case h :: t => collectStrings(t, prependStr(h, acc))
    case Nil  => ListOps.reverse(acc)

  def prependStr(h: Json, acc: List[String]): List[String] = asStr(h) match
    case s: Some[String] => s.value :: acc
    case None => acc

  /** a state event: type + non-empty state key, no redacts/unsigned. */
  def addStateEvent(roomId: String, sender: String, etype: String, sk: String, content: Json): Unit =
    Store.addEvent(roomId, etype, sender, content, true, sk, false, "", JNull())
    ()

  // ---- join -----------------------------------------------------------------

  def joinRoute(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => joinResolve(rr.right.userId, r.pathValue("roomIdOrAlias"))

  def joinResolve(userId: String, target: String): Either[MErr, Json] =
    if target.startsWith("#") then joinByAlias(userId, target)
    else doJoinRoom(userId, target)

  def joinByAlias(userId: String, alias: String): Either[MErr, Json] = Store.getRoomIdByAlias(alias) match
    case s: Some[String] => doJoinRoom(userId, s.value)
    case None => Left(MErr(404, M_NOT_FOUND(), "Room alias not found"))

  def doJoinRoom(userId: String, roomId: String): Either[MErr, Json] = Store.getRoom(roomId) match
    case s: Some[Room] => joinWithRoom(userId, roomId, s.value)
    case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def joinWithRoom(userId: String, roomId: String, room: Room): Either[MErr, Json] = Store.getMembership(roomId, userId) match
    case _: MJoin => Right(obj1("room_id", JStr(roomId)))
    case _        => joinDecide(userId, roomId, room)

  def joinDecide(userId: String, roomId: String, room: Room): Either[MErr, Json] =
    Mem.transition(Store.getMembership(roomId, userId), AJoin()) match
      case _: Allowed  => joinPerform(userId, roomId)
      case _: IfPublic => joinIfPublic(userId, roomId, room)
      case _: Denied   => Left(MErr(403, M_FORBIDDEN(), "You are not invited to this room"))

  def joinIfPublic(userId: String, roomId: String, room: Room): Either[MErr, Json] =
    if joinRuleOf(room) == "public" then joinPerform(userId, roomId)
    else Left(MErr(403, M_FORBIDDEN(), "You are not invited to this room"))

  def joinRuleOf(room: Room): String = Store.stateContent(room, "m.room.join_rules", "") match
    case s: Some[Json] => strField(s.value, "join_rule", "")
    case None => ""

  def joinPerform(userId: String, roomId: String): Either[MErr, Json] =
    addStateEvent(roomId, userId, "m.room.member", userId, memberJoinContent(userId, false))
    Store.notifyRoomMembers(roomId)
    Right(obj1("room_id", JStr(roomId)))

  // ---- invite ---------------------------------------------------------------

  def inviteRoute(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => invite1(rr.right.userId, r, body)

  def invite1(userId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getMembership(roomId, userId) match
      case _: MJoin => invite2(roomId, body)
      case _        => Left(MErr(403, M_FORBIDDEN(), "You are not in this room"))

  def invite2(roomId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Right(j) => invite3(roomId, j)
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))

  def invite3(roomId: String, j: Json): Either[MErr, Json] = getField(j, "user_id") match
    case s: Some[Json] => invite4(roomId, strOr(s.value, ""))
    case None => Left(MErr(400, M_BAD_JSON(), "Missing user_id"))

  def invite4(roomId: String, target: String): Either[MErr, Json] =
    if target == "" then Left(MErr(400, M_BAD_JSON(), "Missing user_id"))
    else invite5(roomId, target)

  /** gate the target through the membership transition table (membership.scala)
   *  rather than overwriting their member event unconditionally. */
  def invite5(roomId: String, target: String): Either[MErr, Json] =
    Mem.transition(Store.getMembership(roomId, target), AInvite()) match
      case _: Denied => Left(MErr(403, M_FORBIDDEN(), "User cannot be invited"))
      case _         => invite6(roomId, target)

  def invite6(roomId: String, target: String): Either[MErr, Json] =
    Store.addEvent(roomId, "m.room.member", target, obj1("membership", JStr("invite")), true, target, false, "", JNull())
    Store.notifyUser(target)
    Right(emptyObj)

  // ---- leave / kick / ban ---------------------------------------------------
  //
  // The three membership actions the transition table (membership.scala) covers
  // besides join and invite. LEAVE moves the CALLER out; KICK and BAN move the
  // `user_id` from the body out, with the caller as the event's sender. All
  // three write an ordinary `m.room.member` state event, so /sync, the journal,
  // and the state map need no special case.
  //
  // A departure is notified to the room AND to the departed user explicitly:
  // once their member event says leave/ban, `notifyRoomMembers` no longer counts
  // them, so their own long-poll would otherwise sit until timeout instead of
  // waking on the `rooms.leave` block that tells them they are gone.

  def leaveRoute(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => leave1(rr.right.userId, r, body)

  def leave1(userId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getRoom(roomId) match
      case _: Some[Room] => leave2(userId, roomId, body)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def leave2(userId: String, roomId: String, body: String): Either[MErr, Json] =
    Mem.transition(Store.getMembership(roomId, userId), ALeave()) match
      case _: Denied => Left(MErr(403, M_FORBIDDEN(), "You cannot leave this room"))
      case _         => leave3(userId, roomId, body)

  def leave3(userId: String, roomId: String, body: String): Either[MErr, Json] =
    depart(roomId, userId, userId, "leave", reasonOf(body))
    Right(emptyObj)

  def kickRoute(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => evict1(rr.right.userId, r, body, ALeave(), "leave")

  def banRoute(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => evict1(rr.right.userId, r, body, ABan(), "ban")

  def evict1(actor: String, r: go.net.http.Request, body: String, act: MAction, to: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getMembership(roomId, actor) match
      case _: MJoin => evict2(actor, roomId, body, act, to)
      case _        => Left(MErr(403, M_FORBIDDEN(), "You are not in this room"))

  def evict2(actor: String, roomId: String, body: String, act: MAction, to: String): Either[MErr, Json] =
    Json.tryParse(body) match
      case Right(j) => evict3(actor, roomId, j, act, to)
      case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))

  def evict3(actor: String, roomId: String, j: Json, act: MAction, to: String): Either[MErr, Json] =
    val target = strField(j, "user_id", "")
    if target == "" then Left(MErr(400, M_BAD_JSON(), "Missing user_id"))
    else evict4(actor, roomId, target, strField(j, "reason", ""), act, to)

  def evict4(actor: String, roomId: String, target: String, reason: String, act: MAction, to: String): Either[MErr, Json] =
    Mem.transition(Store.getMembership(roomId, target), act) match
      case _: Denied => Left(MErr(403, M_FORBIDDEN(), "User cannot be removed from this room"))
      case _         => evict5(actor, roomId, target, reason, to)

  def evict5(actor: String, roomId: String, target: String, reason: String, to: String): Either[MErr, Json] =
    depart(roomId, actor, target, to, reason)
    Right(emptyObj)

  /** write the departing member event and wake both the room and the departed. */
  def depart(roomId: String, sender: String, target: String, to: String, reason: String): Unit =
    addStateEvent(roomId, sender, "m.room.member", target, memberLeaveContent(to, reason))
    Store.notifyRoomMembers(roomId)
    Store.notifyUser(target)

  def memberLeaveContent(to: String, reason: String): Json =
    if reason == "" then obj1("membership", JStr(to))
    else obj2("membership", JStr(to), "reason", JStr(reason))

  /** an optional `reason` from a body that may not even be JSON (leave takes an
   *  empty body from most clients). */
  def reasonOf(body: String): String = Json.tryParse(body) match
    case Right(j) => strField(j, "reason", "")
    case Left(_)  => ""

  // ---- state events ---------------------------------------------------------
  //
  // PUT /rooms/{roomId}/state/{eventType}/{stateKey} — the generic state-setting
  // endpoint. The state key is optional in the path; absent means "". Content
  // must be a JSON object; the event goes through the same `Store.addEvent` as
  // everything else, so the room's state map, /sync, and the journal all pick it
  // up with no special case.

  def setState(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => setState1(rr.right.userId, r, body)

  def setState1(userId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getRoom(roomId) match
      case _: Some[Room] => setState2(userId, roomId, r, body)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def setState2(userId: String, roomId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    Store.getMembership(roomId, userId) match
      case _: MJoin => setState3(userId, roomId, r, body)
      case _        => Left(MErr(403, M_FORBIDDEN(), "User is not in the room"))

  def setState3(userId: String, roomId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    Json.tryParse(body) match
      case Right(j) => setState4(userId, roomId, r, j)
      case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))

  def setState4(userId: String, roomId: String, r: go.net.http.Request, j: Json): Either[MErr, Json] =
    if !isObj(j) then Left(MErr(400, M_BAD_JSON(), "Request body is not a JSON object"))
    else setState5(userId, roomId, r.pathValue("eventType"), r.pathValue("stateKey"), j)

  def setState5(userId: String, roomId: String, etype: String, sk: String, j: Json): Either[MErr, Json] =
    Store.addEvent(roomId, etype, userId, j, true, sk, false, "", JNull()) match
      case s: Some[Event] => setState6(roomId, s.value)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def setState6(roomId: String, ev: Event): Either[MErr, Json] =
    Store.notifyRoomMembers(roomId)
    Right(obj1("event_id", JStr(ev.eventId)))

  // ---- resolve alias --------------------------------------------------------

  def resolveAlias(r: go.net.http.Request): Either[MErr, Json] = Store.getRoomIdByAlias(r.pathValue("roomAlias")) match
    case s: Some[String] => Right(obj2("room_id", JStr(s.value), "servers", arr1(JStr(Config.serverName))))
    case None => Left(MErr(404, M_NOT_FOUND(), "Room alias not found"))

  // ---- send message ---------------------------------------------------------

  def sendEvent(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => send1(rr.right, r, body)

  def send1(auth: Auth, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getRoom(roomId) match
      case _: Some[Room] => send2(auth, roomId, r, body)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def send2(auth: Auth, roomId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    Store.getMembership(roomId, auth.userId) match
      case _: MJoin => send3(auth, roomId, r, body)
      case _        => Left(MErr(403, M_FORBIDDEN(), "User is not in the room"))

  def send3(auth: Auth, roomId: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val txnId = r.pathValue("txnId")
    Store.getTxn(auth.deviceId, txnId) match
      case s: Some[String] => Right(obj1("event_id", JStr(s.value)))
      case None => send4(auth, roomId, r.pathValue("eventType"), txnId, body)

  def send4(auth: Auth, roomId: String, etype: String, txnId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Right(j) => send5(auth, roomId, etype, txnId, j)
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))

  def send5(auth: Auth, roomId: String, etype: String, txnId: String, content: Json): Either[MErr, Json] =
    Store.addEvent(roomId, etype, auth.userId, content, false, "", false, "", unsignedTxn(txnId)) match
      case s: Some[Event] => send6(auth, roomId, txnId, s.value)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def send6(auth: Auth, roomId: String, txnId: String, ev: Event): Either[MErr, Json] =
    Store.notifyRoomMembers(roomId)
    Store.setTxn(auth.deviceId, txnId, ev.eventId)
    Right(obj1("event_id", JStr(ev.eventId)))

  def unsignedTxn(txnId: String): Json = obj1("transaction_id", JStr(txnId))

  // ---- redaction ------------------------------------------------------------
  //
  // Redaction requires only that the redactor be joined to the room; there is
  // no power-level check.

  def redact(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => redact1(rr.right, r, body)

  def redact1(auth: Auth, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getMembership(roomId, auth.userId) match
      case _: MJoin => redact2(auth, roomId, r, body)
      case _        => Left(MErr(403, M_FORBIDDEN(), "User is not in the room"))

  def redact2(auth: Auth, roomId: String, r: go.net.http.Request, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Right(j) => redact3(auth, roomId, r, j)
    case Left(_)  => redact3(auth, roomId, r, emptyObj)

  def redact3(auth: Auth, roomId: String, r: go.net.http.Request, j: Json): Either[MErr, Json] =
    val eventId = r.pathValue("eventId")
    Store.getEventById(roomId, eventId) match
      case _: Some[Event] => redact4(auth, roomId, r, eventId, j)
      case None => Left(MErr(404, M_NOT_FOUND(), "Event not found"))

  def redact4(auth: Auth, roomId: String, r: go.net.http.Request, eventId: String, j: Json): Either[MErr, Json] =
    val txnId = r.pathValue("txnId")
    Store.addEvent(roomId, "m.room.redaction", auth.userId, redactionContent(eventId, j), false, "", true, eventId, unsignedTxn(txnId)) match
      case s: Some[Event] => redact5(roomId, eventId, s.value)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def redact5(roomId: String, eventId: String, red: Event): Either[MErr, Json] =
    Store.redactTarget(roomId, eventId, red)
    Store.notifyRoomMembers(roomId)
    Right(obj1("event_id", JStr(red.eventId)))

  def redactionContent(eventId: String, j: Json): Json = getField(j, "reason") match
    case s: Some[Json] => obj2("redacts", JStr(eventId), "reason", s.value)
    case None => obj1("redacts", JStr(eventId))

  // ---- receipts -------------------------------------------------------------

  def receipt(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => receipt1(rr.right, r)

  def receipt1(auth: Auth, r: go.net.http.Request): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getMembership(roomId, auth.userId) match
      case _: MJoin => receipt2(auth, roomId, r)
      case _        => Left(MErr(403, M_FORBIDDEN(), "User is not joined to room"))

  def receipt2(auth: Auth, roomId: String, r: go.net.http.Request): Either[MErr, Json] =
    Store.setReceipt(roomId, r.pathValue("receiptType"), auth.userId, r.pathValue("eventId"))
    Store.notifyRoomMembers(roomId)
    Right(emptyObj)

  // ---- media upload; download is handled at the edge -------------------------

  def upload(r: go.net.http.Request, rawBody: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => upload1(r, rawBody)

  def upload1(r: go.net.http.Request, rawBody: String): Either[MErr, Json] =
    val id = Store.storeMedia(rawBody, ctOr(r.header.get("Content-Type")))
    Right(obj1("content_uri", JStr("mxc://" + Config.serverName + "/" + id)))

  def ctOr(ct: String): String = if ct == "" then "application/octet-stream" else ct

  // ---- GET /messages (timeline pagination) -----------------------------------
  //
  // Pagination positions are the SAME opaque `s<seq>` tokens /sync hands out as
  // `next_batch` and `prev_batch`: `s<N>` names the boundary just after the event
  // whose sequence is N. Backward (`dir=b`, also the default) returns the newest
  // `limit` events at or below the boundary, newest-first; forward returns the
  // oldest `limit` events above it, oldest-first. `end` is the boundary to
  // continue from, so repeated calls walk history without gap or overlap — which
  // is what makes a `limited` /sync timeline recoverable.
  //
  // An EVENT ID is also accepted as `from`, for a client paginating from an event
  // it already holds; it resolves to the boundary just OUTSIDE that event in the
  // requested direction, so a page never repeats its own anchor. A `from` that is
  // neither a position nor an event of this room yields an empty chunk rather
  // than an error — the same thing a client sees paging a foreign token.
  //
  // The wire `chunk` is a FLAT array of events (matching the client's
  // GetMessagesResponse type and the Matrix spec), not a nested
  // `[{room_id, events}]` shape.

  def messages(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => messages1(rr.right.userId, r)

  def messages1(userId: String, r: go.net.http.Request): Either[MErr, Json] =
    val roomId = r.pathValue("roomId")
    Store.getRoom(roomId) match
      case s: Some[Room] => messages2(userId, roomId, s.value, r)
      case None => Left(MErr(404, M_NOT_FOUND(), "Room not found"))

  def messages2(userId: String, roomId: String, room: Room, r: go.net.http.Request): Either[MErr, Json] =
    Store.getMembership(roomId, userId) match
      case _: MJoin => messages3(room, r)
      case _        => Left(MErr(403, M_FORBIDDEN(), "You are not in this room"))

  def messages3(room: Room, r: go.net.http.Request): Either[MErr, Json] =
    val q = r.uRL.query()
    val from = q.get("from")
    val dir = q.get("dir")
    val limit = parseLimit(q.get("limit"))
    val reverse = dir == "b" || dir == ""
    if from == "" then page(room, "", tailAnchor(room, reverse), reverse, limit)
    else messagesFrom(room, from, reverse, limit)

  /** no `from`: a backward page starts at the newest position in the room, a
   *  forward one at the beginning of history. */
  def tailAnchor(room: Room, reverse: Boolean): scala.Long =
    if reverse then lastSeqOf(room.timeline, 0L) else 0L

  def messagesFrom(room: Room, from: String, reverse: Boolean, limit: scala.Int): Either[MErr, Json] =
    val a = anchorOf(room, from, reverse)
    if a.ok then page(room, from, a.seq, reverse, limit)
    else Right(obj3("start", JStr(from), "end", JStr(from), "chunk", JArr(Nil)))

  def anchorOf(room: Room, from: String, reverse: Boolean): Anchor =
    if isSeqToken(from) then Anchor(true, Sync.parseLong(from.substring(1)))
    else eventAnchor(room, from, reverse)

  /** an `s` followed by at least one digit and nothing else. Event ids start
   *  with `$`, so the two token spaces never collide. */
  def isSeqToken(s: String): Boolean =
    if s.length < 2 then false else tokenTail(s)

  def tokenTail(s: String): Boolean =
    if s.startsWith("s") then allDigits(s, 1) else false

  def allDigits(s: String, i: scala.Int): Boolean =
    if i >= s.length then true else digitStep(s, i)

  def digitStep(s: String, i: scala.Int): Boolean =
    val c = s.charAt(i)
    if c < '0' then false else digitStep2(s, i, c)

  def digitStep2(s: String, i: scala.Int, c: Char): Boolean =
    if c > '9' then false else allDigits(s, i + 1)

  def eventAnchor(room: Room, from: String, reverse: Boolean): Anchor = Store.findEv(room.timeline, from) match
    case s: Some[Event] => Anchor(true, anchorSeq(s.value.seq, reverse))
    case None => Anchor(false, 0L)

  /** the boundary just outside the anchor event, in the paging direction. */
  def anchorSeq(seq: scala.Long, reverse: Boolean): scala.Long =
    if reverse then seq - 1L else seq

  def page(room: Room, from: String, b: scala.Long, reverse: Boolean, limit: scala.Int): Either[MErr, Json] =
    if reverse then backPage(room, from, b, limit) else fwdPage(room, from, b, limit)

  /** the newest `limit` events at or below `b`, sent newest-first; `end` is one
   *  position below the oldest one sent. */
  def backPage(room: Room, from: String, b: scala.Long, limit: scala.Int): Either[MErr, Json] =
    val kept = takeLast(eventsUpTo(room.timeline, b, Nil), limit)
    pageJson(from, tokenOf(b), backEnd(kept, from, b), ListOps.reverse(kept))

  /** the oldest `limit` events above `b`, sent oldest-first; `end` is the newest
   *  one sent. */
  def fwdPage(room: Room, from: String, b: scala.Long, limit: scala.Int): Either[MErr, Json] =
    val kept = takeFirst(eventsAbove(room.timeline, b, Nil), limit)
    pageJson(from, tokenOf(b), tokenOf(lastSeqOf(kept, b)), kept)

  def pageJson(from: String, startTok: String, endTok: String, chunk: List[Event]): Either[MErr, Json] =
    Right(obj3("start", JStr(startOr(from, startTok)), "end", JStr(endTok), "chunk", Sync.eventsArr(chunk)))

  def startOr(from: String, dflt: String): String = if from == "" then dflt else from

  /** `kept` is oldest-first, so its head is the oldest event on the page. */
  def backEnd(kept: List[Event], from: String, b: scala.Long): String = kept match
    case h :: _ => tokenOf(h.seq - 1L)
    case Nil  => startOr(from, tokenOf(b))

  def tokenOf(seq: scala.Long): String = Sync.tok(clampSeq(seq))

  def clampSeq(seq: scala.Long): scala.Long = if seq < 0L then 0L else seq

  def lastSeqOf(xs: List[Event], acc: scala.Long): scala.Long = xs match
    case h :: t => lastSeqOf(t, h.seq)
    case Nil  => acc

  def eventsUpTo(xs: List[Event], b: scala.Long, acc: List[Event]): List[Event] = xs match
    case h :: t => eventsUpToStep(h, t, b, acc)
    case Nil  => ListOps.reverse(acc)

  def eventsUpToStep(h: Event, t: List[Event], b: scala.Long, acc: List[Event]): List[Event] =
    var acc2: List[Event] = acc
    if h.seq <= b then acc2 = h :: acc2 else ()
    eventsUpTo(t, b, acc2)

  def eventsAbove(xs: List[Event], b: scala.Long, acc: List[Event]): List[Event] = xs match
    case h :: t => eventsAboveStep(h, t, b, acc)
    case Nil  => ListOps.reverse(acc)

  def eventsAboveStep(h: Event, t: List[Event], b: scala.Long, acc: List[Event]): List[Event] =
    var acc2: List[Event] = acc
    if h.seq > b then acc2 = h :: acc2 else ()
    eventsAbove(t, b, acc2)

  def parseLimit(s: String): scala.Int =
    var out = 10
    try
      val v = go.strconv.atoi(s)
      out = v.toInt
      ()
    catch case e: sgo.GoError => out = 10
    out

  def takeFirst(xs: List[Event], n: scala.Int): List[Event] = takeFirstAcc(xs, n, Nil)

  def takeFirstAcc(xs: List[Event], n: scala.Int, acc: List[Event]): List[Event] =
    if n <= 0 then ListOps.reverse(acc) else takeFirstStep(xs, n, acc)

  def takeFirstStep(xs: List[Event], n: scala.Int, acc: List[Event]): List[Event] = xs match
    case h :: t => takeFirstAcc(t, n - 1, h :: acc)
    case Nil  => ListOps.reverse(acc)

  def takeLast(xs: List[Event], n: scala.Int): List[Event] = ListOps.reverse(takeFirst(ListOps.reverse(xs), n))
