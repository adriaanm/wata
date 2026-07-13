import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** M7 chunk 2 — the wata-server boot + the mux edge (server.ts / transport/
 *  node.ts / index.ts). One `WataHandler` (IOP-4 satisfaction) is registered on
 *  the Go-1.22 ServeMux for every route; a catch-all "/" `NotFound` handles
 *  unmatched paths (Matrix `{errcode,error}` 404) and CORS OPTIONS preflight.
 *  JSON in/out exclusively through the `json` module.
 */

/** The single handler behind every registered pattern. Reads the body at the
 *  top of the try (the only real `throws sgo.GoError`), routes, and serializes
 *  the `Either[MErr, Json]` result to the wire. */
class WataHandler() extends go.net.http.Handler:
  def serveHTTP(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit =
    try
      val raw = go.io.readAll(r.body)
      MediaEdge.dispatch(w, r, go.string(raw))
    catch case e: sgo.GoError =>
      Respond.finish(w, 500, Json.write(errEnvelope(MErr(500, M_UNKNOWN(), "Internal server error"))))

/** The edge split (decision 6): media DOWNLOAD writes raw bytes with the stored
 *  Content-Type; everything else (incl. media UPLOAD) is the JSON pipeline. The
 *  request body arrives as a byte-preserving Go String (`string([]byte)`), so an
 *  upload's binary bytes survive the round-trip to `Store.storeMedia`. */
object MediaEdge:
  def dispatch(w: go.net.http.ResponseWriter, r: go.net.http.Request, reqBody: String): Unit =
    if r.uRL.path.contains("/download/") then download(w, r)
    else jsonReply(w, r, reqBody)

  def jsonReply(w: go.net.http.ResponseWriter, r: go.net.http.Request, reqBody: String): Unit = Router.route(r, reqBody) match
    case rr: Right[MErr, Json] => Respond.finish(w, 200, Json.write(rr.right))
    case l: Left[MErr, Json]   => Respond.finish(w, l.left.status, Json.write(errEnvelope(l.left)))

  def download(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Respond.finish(w, l.left.status, Json.write(errEnvelope(l.left)))
    case _: Right[MErr, Auth] => download2(w, r)

  def download2(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit = Store.getMedia(r.pathValue("mediaId")) match
    case s: Some[MediaItem] => Respond.raw(w, s.v.contentType, s.v.data)
    case _: None[MediaItem] => Respond.finish(w, 404, Json.write(errEnvelope(MErr(404, M_NOT_FOUND(), "Media not found"))))

/** The catch-all: a Matrix 404 envelope for unmatched paths, 204 for CORS
 *  OPTIONS preflight. */
class NotFound() extends go.net.http.Handler:
  def serveHTTP(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit =
    var status = 404
    var body = ""
    if r.method == "OPTIONS" then status = 204
    else body = Json.write(errEnvelope(MErr(404, M_UNRECOGNIZED(), "Unrecognized request")))
    Respond.finish(w, status, body)

object Respond:
  def finish(w: go.net.http.ResponseWriter, status: scala.Int, body: String): Unit =
    w.header().set("Content-Type", "application/json")
    w.header().set("Access-Control-Allow-Origin", "*")
    w.header().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    w.header().set("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization")
    w.writeHeader(go.Int.of(status))
    writeBody(w, body)

  /** a raw (non-JSON) 200 response — the media download path: the stored
   *  Content-Type + the bytes (a byte-preserving String -> `[]byte`). */
  def raw(w: go.net.http.ResponseWriter, contentType: String, data: String): Unit =
    w.header().set("Content-Type", contentType)
    w.header().set("Access-Control-Allow-Origin", "*")
    w.writeHeader(go.Int.of(200))
    writeBody(w, data)

  def writeBody(w: go.net.http.ResponseWriter, body: String): Unit =
    try
      w.write(go.bytes(body))
      ()
    catch case e: sgo.GoError => ()

object Server:
  def serve(addr: String): Unit =
    Store.init()
    Journal.boot()
    val mux = go.net.http.newServeMux()
    val h = new WataHandler()
    registerRoutes(mux, h)
    mux.handle("/", new NotFound())
    val server = go.net.http.newServer()
    server.addr = addr
    server.handler = mux
    println("Wata server listening on " + addr)
    val err = server.listenAndServe()
    println("wata stopped " + err.message)

  /** the server.ts routing table, as method-qualified Go-1.22 patterns. */
  def registerRoutes(mux: go.net.http.ServeMux, h: go.net.http.Handler): Unit =
    mux.handle("GET /_matrix/client/versions", h)
    mux.handle("GET /_matrix/client/v3/login", h)
    mux.handle("POST /_matrix/client/v3/login", h)
    mux.handle("POST /_matrix/client/v3/logout", h)
    mux.handle("GET /_matrix/client/v3/account/whoami", h)
    mux.handle("GET /_matrix/client/v3/sync", h)
    mux.handle("GET /_matrix/client/v3/profile/{userId}", h)
    mux.handle("GET /_matrix/client/v2/profile/{userId}", h)
    mux.handle("PUT /_matrix/client/v3/profile/{userId}/displayname", h)
    mux.handle("PUT /_matrix/client/v3/profile/{userId}/avatar_url", h)
    mux.handle("GET /_matrix/client/v3/user/{userId}/account_data/{type}", h)
    mux.handle("PUT /_matrix/client/v3/user/{userId}/account_data/{type}", h)
    mux.handle("GET /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type}", h)
    mux.handle("PUT /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type}", h)
    // M7 chunk 3 — rooms / messaging / receipts / media.
    mux.handle("POST /_matrix/client/v3/createRoom", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomIdOrAlias}/join", h)
    mux.handle("POST /_matrix/client/v3/join/{roomIdOrAlias}", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/invite", h)
    mux.handle("GET /_matrix/client/v3/rooms/{roomId}/messages", h)
    mux.handle("GET /_matrix/client/v1/directory/room/{roomAlias}", h)
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}", h)
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/redact/{eventId}/{txnId}", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/receipt/{receiptType}/{eventId}", h)
    mux.handle("POST /_matrix/media/v3/upload", h)
    mux.handle("GET /_matrix/media/v3/download/{serverName}/{mediaId}", h)
    mux.handle("GET /_matrix/media/v1/download/{serverName}/{mediaId}", h)
    mux.handle("GET /_matrix/client/v1/media/download/{serverName}/{mediaId}", h)

object Main:
  def main(args: Array[String]): Unit =
    if args.length > 0 && args(0) == "selfcheck" then SelfCheck.run()
    else Server.serve(addrOf(args))

  def addrOf(args: Array[String]): String =
    if args.length > 0 then args(0) else ":8008"

/** Deterministic pure-logic check of the store's ADT core (the account-data
 *  merge, ID formatting, the error envelope) — diffed byte-for-byte against
 *  tools/wata-selfcheck.expected.txt by the smoke script. No random, no ports. */
object SelfCheck:
  def run(): Unit =
    Store.init()
    println("errcode " + codeStr(M_FORBIDDEN()))
    println("envelope " + Json.write(errEnvelope(MErr(403, M_FORBIDDEN(), "Invalid username or password"))))
    println("userid " + Store.userIdOf("alice"))
    println("localpart " + Store.localpartOf("@alice:localhost"))
    println("clean-mxid " + Router.cleanLocalpart("@alice:localhost"))
    println("clean-bare " + Router.cleanLocalpart("alice"))
    printProfile("@alice:localhost")
    acctDemo()
    memTable()
    idFormats()
    roomsDemo()
    syncDemo()

  def printProfile(userId: String): Unit = Store.getProfile(userId) match
    case s: Some[Profile] => println("profile " + Json.write(Router.profileJson(s.v)))
    case _: None[Profile] => println("profile none")

  def acctDemo(): Unit =
    val c1 = obj1("foo", JStr("bar"))
    val c2 = obj1("foo", JStr("baz"))
    Store.setAccountData("@alice:localhost", false, "", "m.direct", c1)
    println("acct-get " + acctGetStr("@alice:localhost", "m.direct"))
    Store.setAccountData("@alice:localhost", false, "", "m.direct", c2)
    println("acct-replace " + acctGetStr("@alice:localhost", "m.direct"))
    println("acct-missing " + acctGetStr("@alice:localhost", "m.nonexist"))
    println("acct-405 " + boolStr(Router.serverControlled("m.fully_read")))
    println("acct-ok " + boolStr(Router.serverControlled("m.direct")))

  def acctGetStr(userId: String, dtype: String): String =
    Store.getAccountData(userId, false, "", dtype) match
      case s: Some[AcctData] => Json.write(s.v.content)
      case _: None[AcctData] => "none"

  // ---- M7 chunk 3 parity: membership state machine, IDs, room flow -----------

  /** the membership transition table (the two wired actions, all five from-states):
   *  deterministic, store-free. */
  def memTable(): Unit =
    tRow("join", MNone(), MInvite(), MJoin(), MLeave(), MBan(), AJoin())
    tRow("invite", MNone(), MInvite(), MJoin(), MLeave(), MBan(), AInvite())

  def tRow(label: String, a: Membership, b: Membership, c: Membership, d: Membership, e: Membership, act: MAction): Unit =
    println("trans-" + label + " " +
      Mem.transStr(Mem.transition(a, act)) + " " +
      Mem.transStr(Mem.transition(b, act)) + " " +
      Mem.transStr(Mem.transition(c, act)) + " " +
      Mem.transStr(Mem.transition(d, act)) + " " +
      Mem.transStr(Mem.transition(e, act)))

  /** ID formatting (decision 5): prefix/suffix shape (the random middle is not
   *  printed — determinism law). */
  def idFormats(): Unit =
    val rid = Store.genRoomId()
    val eid = Store.genEventId()
    val mid = Store.genMediaId()
    println("roomid-fmt " + boolStr(rid.startsWith("!") && rid.endsWith(":localhost")))
    println("eventid-fmt " + boolStr(eid.startsWith("$") && eid.endsWith(":localhost")))
    println("mediaid-nonempty " + boolStr(mid != "" && !mid.startsWith("!") && !mid.startsWith("$")))

  /** a full create -> invite -> join -> send -> redact -> profile-fan-out flow
   *  driven through the STORE (no HTTP request needed), printing deterministic
   *  derived facts (never a volatile ID). */
  def roomsDemo(): Unit =
    val alice = "@alice:localhost"
    val bob = "@bob:localhost"
    val roomId = Store.createRoom()
    Rooms.addStateEvent(roomId, alice, "m.room.create", "", Rooms.createContent(alice))
    Rooms.applyPreset(roomId, alice, "private_chat")
    Rooms.addStateEvent(roomId, alice, "m.room.member", alice, Rooms.memberJoinContent(alice, true))
    Rooms.addStateEvent(roomId, alice, "m.room.member", bob, Rooms.memberInviteContent(true))
    println("mem-creator " + Mem.str(Store.getMembership(roomId, alice)))
    println("mem-invitee " + Mem.str(Store.getMembership(roomId, bob)))
    println("join-legal " + Mem.transStr(Mem.transition(Store.getMembership(roomId, bob), AJoin())))
    Rooms.addStateEvent(roomId, bob, "m.room.member", bob, Rooms.memberJoinContent(bob, false))
    println("mem-joined " + Mem.str(Store.getMembership(roomId, bob)))
    println("join-not-invited " + Mem.transStr(Mem.transition(MNone(), AJoin())))
    println("join-rule " + Rooms.joinRuleOf(roomOf(roomId)))
    sendRedactDemo(roomId, alice)
    Store.setDisplayName(alice, "Alice Z")
    println("member-updated " + memberDisplayName(roomId, alice))

  def roomOf(roomId: String): Room = Store.getRoom(roomId) match
    case s: Some[Room] => s.v
    case _: None[Room] => Room(roomId, "10", Nil[(String, Event)](), Nil[Event]())

  def sendRedactDemo(roomId: String, sender: String): Unit =
    Store.addEvent(roomId, "m.room.message", sender, JsonNav.obj1("body", JStr("hi")), false, "", false, "", JNull()) match
      case s: Some[Event] => sendRedactDemo2(roomId, sender, s.v)
      case _: None[Event] => println("send none")

  def sendRedactDemo2(roomId: String, sender: String, ev: Event): Unit =
    println("send-added true")
    val red = Store.addEvent(roomId, "m.room.redaction", sender, JsonNav.obj1("redacts", JStr(ev.eventId)), false, "", true, ev.eventId, JNull())
    redactApply(roomId, ev.eventId, red)
    println("redacted-content " + eventContentStr(roomId, ev.eventId))

  def redactApply(roomId: String, eventId: String, red: Option[Event]): Unit = red match
    case s: Some[Event] => Store.redactTarget(roomId, eventId, s.v)
    case _: None[Event] => ()

  def eventContentStr(roomId: String, eventId: String): String = Store.getEventById(roomId, eventId) match
    case s: Some[Event] => Json.write(s.v.content)
    case _: None[Event] => "none"

  def memberDisplayName(roomId: String, userId: String): String = Store.getRoom(roomId) match
    case s: Some[Room] => memberDisplayName2(s.v, userId)
    case _: None[Room] => "none"

  def memberDisplayName2(room: Room, userId: String): String = Store.stateContent(room, "m.room.member", userId) match
    case s: Some[Json] => JsonNav.strField(s.v, "displayname", "")
    case _: None[Json] => "none"

  // ---- M7 chunk 4 parity: /sync building (deterministic — no age/IDs printed) -

  /** derived facts of an initial + incremental sync over the store left by
   *  roomsDemo (alice+bob joined, a message sent+redacted) and acctDemo (alice's
   *  global m.direct). Prints counts / booleans / a stable hero id only — never a
   *  volatile room/event id or the wall-clock `age`. */
  def syncDemo(): Unit =
    val alice = "@alice:localhost"
    val joined = Store.roomsForUser(alice, "join")
    val room = firstRoom(joined)
    val initA = Sync.partsToJson(Sync.initialParts(alice, Store.globalSeq()))
    println("sync-join-rooms " + JsonNav.longStr(objLen(nav2(initA, "rooms", "join"))))
    println("sync-invite-rooms " + JsonNav.longStr(objLen(nav2(initA, "rooms", "invite"))))
    println("sync-joined-count " + JsonNav.longStr(Sync.joinedCount(room.state)))
    println("sync-invited-count " + JsonNav.longStr(Sync.invitedCount(room.state)))
    println("sync-hero " + firstStr(Sync.heroesOf(room.state, alice)))
    println("sync-next-batch-prefix " + boolStr(JsonNav.strField(initA, "next_batch", "").startsWith("s")))
    println("sync-global-acct-count " + JsonNav.longStr(arrLen(nav2(initA, "account_data", "events"))))
    val before = Store.globalSeq()
    println("sync-incr-empty " + boolStr(Sync.hasChangesOf(Sync.buildParts(alice, true, before, false))))
    Store.addEvent(room.roomId, "m.room.message", alice, JsonNav.obj1("body", JStr("later")), false, "", false, "", JNull())
    ()
    println("sync-incr-after-send " + boolStr(Sync.hasChangesOf(Sync.buildParts(alice, true, before, false))))
    println("sync-fullstate-rooms " + JsonNav.longStr(objLen(nav2(Sync.partsToJson(Sync.buildParts(alice, true, before, true)), "rooms", "join"))))

  def nav2(j: Json, k1: String, k2: String): Json = navField(navField(j, k1), k2)

  def navField(j: Json, k: String): Json = JsonNav.getField(j, k) match
    case s: Some[Json] => s.v
    case _: None[Json] => JsonNav.emptyObj

  def objLen(j: Json): scala.Long = j match
    case o: JObj => pairLen(o.fields, 0L)
    case _       => 0L

  def pairLen(xs: List[(String, Json)], n: scala.Long): scala.Long = xs match
    case _ :: t => pairLen(t, n + 1L)
    case Nil()  => n

  def arrLen(j: Json): scala.Long = j match
    case a: JArr => jsonLen(a.items, 0L)
    case _       => 0L

  def jsonLen(xs: List[Json], n: scala.Long): scala.Long = xs match
    case _ :: t => jsonLen(t, n + 1L)
    case Nil()  => n

  def firstRoom(rooms: List[Room]): Room = rooms match
    case h :: _ => h
    case Nil()  => Room("", "10", Nil[(String, Event)](), Nil[Event]())

  def firstStr(xs: List[String]): String = xs match
    case h :: _ => h
    case Nil()  => "none"
