import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** M7 chunk 3 — the room / messaging / receipt / media handlers, ported from
 *  `handlers/rooms.ts`, `handlers/events.ts`, `handlers/receipts.ts`,
 *  `handlers/media.ts`. Same shape as chunk 2: every step returns
 *  `Either[MErr, Json]`; the edge serializes it. Membership decisions go through
 *  the `Mem.transition` table (membership.scala). Media DOWNLOAD is the one
 *  raw-bytes response and is handled at the mux edge (server.scala `MediaEdge`),
 *  not here; UPLOAD returns JSON and flows through the normal pipeline.
 *
 *  Ported-with-simplification (recorded in the chunk report / README):
 *   - createRoom applies presets + is_direct + invite + name + alias; it does NOT
 *     yet apply `initial_state`, `creation_content`, or `power_level_content_
 *     override` (rarely used; deferred).
 *   - GET /messages (timeline pagination) and /publicRooms are deferred to a
 *     later chunk (not needed for the DM smoke; the age field is wall-clock).
 */
object Rooms:

  // ---- createRoom (rooms.ts handleCreateRoom) --------------------------------

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

  /** rooms.ts: preset ?? (visibility == 'public' ? 'public_chat' : 'private_chat'). */
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

  // ---- join (rooms.ts handleJoinRoom / handleJoinRoomById) -------------------

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

  // ---- invite (rooms.ts handleInvite) ----------------------------------------

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

  /** spec-correction (membership.scala): gate the target through the transition
   *  table; the TS overwrites unconditionally. */
  def invite5(roomId: String, target: String): Either[MErr, Json] =
    Mem.transition(Store.getMembership(roomId, target), AInvite()) match
      case _: Denied => Left(MErr(403, M_FORBIDDEN(), "User cannot be invited"))
      case _         => invite6(roomId, target)

  def invite6(roomId: String, target: String): Either[MErr, Json] =
    Store.addEvent(roomId, "m.room.member", target, obj1("membership", JStr("invite")), true, target, false, "", JNull())
    Store.notifyUser(target)
    Right(emptyObj)

  // ---- resolve alias (rooms.ts handleResolveAlias) ---------------------------

  def resolveAlias(r: go.net.http.Request): Either[MErr, Json] = Store.getRoomIdByAlias(r.pathValue("roomAlias")) match
    case s: Some[String] => Right(obj2("room_id", JStr(s.value), "servers", arr1(JStr(Config.serverName))))
    case None => Left(MErr(404, M_NOT_FOUND(), "Room alias not found"))

  // ---- send message (events.ts handleSendEvent) ------------------------------

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

  // ---- redaction (events.ts handleRedactEvent) -------------------------------
  //
  // TS FIDELITY: handleRedactEvent does NOT do a power-level check — it only
  // requires the redactor be joined (the brief's "power-level check the TS does"
  // does not exist in the source). We match the oracle: membership-join only.

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

  // ---- receipts (receipts.ts handleReceipt) ----------------------------------

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

  // ---- media upload (media.ts handleUpload); download is at the edge ---------

  def upload(r: go.net.http.Request, rawBody: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => upload1(r, rawBody)

  def upload1(r: go.net.http.Request, rawBody: String): Either[MErr, Json] =
    val id = Store.storeMedia(rawBody, ctOr(r.header.get("Content-Type")))
    Right(obj1("content_uri", JStr("mxc://" + Config.serverName + "/" + id)))

  def ctOr(ct: String): String = if ct == "" then "application/octet-stream" else ct

  // ---- GET /messages (timeline pagination, chunk 5) --------------------------
  //
  // The client (SyncEngine.backfillRoom) calls this with `from` = a /sync
  // prev_batch token (an "sN" seq token, never an event id) and dir='b'. Matching
  // the TS reference (messages.ts): when `from` is not an event id in the timeline
  // we return an EMPTY chunk — the messages were already delivered by /sync
  // (immediate consistency), so backfill is a no-op safety net here. When `from`
  // IS an event id we paginate, for spec-correct clients.
  //
  // The wire `chunk` is a FLAT array of events (the client's own
  // GetMessagesResponse: `chunk: MatrixEvent[]`), NOT the TS reference's nested
  // `[{room_id, events}]` — that shape is a dormant-server bug, contradicting both
  // the client type and the Matrix spec, so the oracle (client) wins.

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
    if from == "" then messagesSlice(room.timeline, "", reverse, limit, false)
    else messagesFrom(room, from, reverse, limit)

  def messagesFrom(room: Room, from: String, reverse: Boolean, limit: scala.Int): Either[MErr, Json] =
    if timelineHas(room.timeline, from) then messagesBase(room, from, reverse, limit)
    else Right(obj3("start", JStr(from), "end", JStr(from), "chunk", JArr(Nil)))

  def messagesBase(room: Room, from: String, reverse: Boolean, limit: scala.Int): Either[MErr, Json] =
    val base = if reverse then eventsBefore(room.timeline, from, Nil) else eventsAfter(room.timeline, from)
    messagesSlice(base, from, reverse, limit, true)

  /** `base` is oldest-first; backward pagination returns the last `limit` events
   *  newest-first, forward returns the first `limit` oldest-first. */
  def messagesSlice(base: List[Event], from: String, reverse: Boolean, limit: scala.Int, hasFrom: Boolean): Either[MErr, Json] =
    val chosen = if reverse then ListOps.reverse(takeLast(base, limit)) else takeFirst(base, limit)
    val startTok = if hasFrom then from else headEventId(chosen)
    val endTok = lastEventId(chosen, from)
    Right(obj3("start", JStr(startTok), "end", JStr(endTok), "chunk", Sync.eventsArr(chosen)))

  def parseLimit(s: String): scala.Int =
    var out = 10
    try
      val v = go.strconv.atoi(s)
      out = v.toInt
      ()
    catch case e: sgo.GoError => out = 10
    out

  def timelineHas(xs: List[Event], id: String): Boolean = xs match
    case h :: t => timelineHasStep(h, t, id)
    case Nil  => false

  def timelineHasStep(h: Event, t: List[Event], id: String): Boolean =
    if h.eventId == id then true else timelineHas(t, id)

  def eventsBefore(xs: List[Event], from: String, acc: List[Event]): List[Event] = xs match
    case h :: t => eventsBeforeStep(h, t, from, acc)
    case Nil  => ListOps.reverse(acc)

  def eventsBeforeStep(h: Event, t: List[Event], from: String, acc: List[Event]): List[Event] =
    if h.eventId == from then ListOps.reverse(acc)
    else eventsBefore(t, from, h :: acc)

  def eventsAfter(xs: List[Event], from: String): List[Event] = xs match
    case h :: t => eventsAfterStep(h, t, from)
    case Nil  => Nil

  def eventsAfterStep(h: Event, t: List[Event], from: String): List[Event] =
    if h.eventId == from then t else eventsAfter(t, from)

  def takeFirst(xs: List[Event], n: scala.Int): List[Event] = takeFirstAcc(xs, n, Nil)

  def takeFirstAcc(xs: List[Event], n: scala.Int, acc: List[Event]): List[Event] =
    if n <= 0 then ListOps.reverse(acc) else takeFirstStep(xs, n, acc)

  def takeFirstStep(xs: List[Event], n: scala.Int, acc: List[Event]): List[Event] = xs match
    case h :: t => takeFirstAcc(t, n - 1, h :: acc)
    case Nil  => ListOps.reverse(acc)

  def takeLast(xs: List[Event], n: scala.Int): List[Event] = ListOps.reverse(takeFirst(ListOps.reverse(xs), n))

  def headEventId(xs: List[Event]): String = xs match
    case h :: _ => h.eventId
    case Nil  => "s0"

  def lastEventId(xs: List[Event], from: String): String = ListOps.reverse(xs) match
    case h :: _ => h.eventId
    case Nil  => lastFallback(from)

  def lastFallback(from: String): String = if from == "" then "s0" else from
