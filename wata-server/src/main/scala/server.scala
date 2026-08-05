import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** The wata-server boot + the HTTP mux edge. One `WataHandler` is registered on
 *  the Go-1.22 ServeMux for every route; a catch-all "/" `NotFound` handles
 *  unmatched paths (Matrix `{errcode,error}` 404) and CORS OPTIONS preflight.
 *  JSON in/out exclusively through the `json` module.
 */

/** The single handler behind every registered pattern. Reads the body at the
 *  top of the try (the only real `throws sgo.GoError`), routes, and serializes
 *  the `Either[MErr, Json]` result to the wire.
 *
 *  The body read is BOUNDED: the reader is capped at `maxBodyBytes + 1` via
 *  `io.LimitReader` (go.iolimit, iolimit.scala), so one request can hand the
 *  server at most that much memory; a result longer than `maxBodyBytes` means
 *  the body was over the cap -> 413 `M_TOO_LARGE`. The cap is sized for the
 *  largest legitimate payload — device voice-message Ogg uploads run tens of
 *  KB (~120 KB/min at 16 kbps opus) — with orders-of-magnitude headroom. */
class WataHandler() extends go.net.http.Handler:
  def serveHTTP(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit =
    try
      val raw = go.io.readAll(go.iolimit.limitReader(r.body, MediaEdge.maxBodyBytes.toLong + 1L))
      MediaEdge.dispatchSized(w, r, raw)
    catch case e: sgo.GoError =>
      Respond.finish(w, 500, Json.write(errEnvelope(MErr(500, M_UNKNOWN(), "Internal server error"))))

/** The edge split: media DOWNLOAD writes raw bytes with the stored
 *  Content-Type; everything else (incl. media UPLOAD) is the JSON pipeline. The
 *  request body arrives as a byte-preserving Go String (`string([]byte)`), so an
 *  upload's binary bytes survive the round-trip to `Store.storeMedia`. */
object MediaEdge:
  /** the request-body cap, bytes (8 MiB). */
  def maxBodyBytes: scala.Int = 8388608

  /** the over-cap check: a read that filled past `maxBodyBytes` is a 413. */
  def dispatchSized(w: go.net.http.ResponseWriter, r: go.net.http.Request, raw: go.Bytes): Unit =
    if raw.length > maxBodyBytes then
      Respond.finish(w, 413, Json.write(errEnvelope(MErr(413, M_TOO_LARGE(), "Request body too large"))))
    else dispatch(w, r, go.string(raw))

  def dispatch(w: go.net.http.ResponseWriter, r: go.net.http.Request, reqBody: String): Unit =
    if r.uRL.path == "/admin" then AdminPage.serve(w)
    else if isDownload(r.uRL.path) then download(w, r)
    else jsonReply(w, r, reqBody)

  /** exact match on the three registered download patterns —
   *  `/_matrix/media/{v3,v1}/download/{serverName}/{mediaId}` and
   *  `/_matrix/client/v1/media/download/{serverName}/{mediaId}` — segment
   *  count + literal segments, like `Router.routeSeg`. */
  def isDownload(path: String): Boolean =
    isMediaDl(path, Router.segCount(path)) || isClientDl(path, Router.segCount(path))

  def isMediaDl(path: String, n: scala.Int): Boolean =
    n == 6 && Router.seg(path, 0) == "_matrix" && Router.seg(path, 1) == "media"
      && isDlVersion(Router.seg(path, 2)) && Router.seg(path, 3) == "download"

  def isDlVersion(v: String): Boolean = v == "v3" || v == "v1"

  def isClientDl(path: String, n: scala.Int): Boolean =
    n == 7 && Router.isClientV(path, "v1") && Router.seg(path, 3) == "media"
      && Router.seg(path, 4) == "download"

  def jsonReply(w: go.net.http.ResponseWriter, r: go.net.http.Request, reqBody: String): Unit = Router.route(r, reqBody) match
    case rr: Right[MErr, Json] => Respond.finish(w, 200, Json.write(rr.right))
    case l: Left[MErr, Json]   => Respond.finish(w, l.left.status, Json.write(errEnvelope(l.left)))

  def download(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Respond.finish(w, l.left.status, Json.write(errEnvelope(l.left)))
    case _: Right[MErr, Auth] => download2(w, r)

  def download2(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit =
    if TestHooks.failMedia() then
      Respond.finish(w, 500, Json.write(errEnvelope(TestHooks.mediaErr)))
    else download3(w, r)

  def download3(w: go.net.http.ResponseWriter, r: go.net.http.Request): Unit = Store.getMedia(r.pathValue("mediaId")) match
    case s: Some[MediaItem] => Respond.raw(w, s.value.contentType, s.value.data)
    case None => Respond.finish(w, 404, Json.write(errEnvelope(MErr(404, M_NOT_FOUND(), "Media not found"))))

/** `GET /admin` — the admin web page (plan 0021), served as HTML rather than
 *  through the JSON pipeline. It is UNAUTHENTICATED, and deliberately so: the
 *  page is inert markup that carries no data, and it is what a parent's
 *  browser has to be able to load in order to reach the login form. Every
 *  fetch it then makes is admin-gated (adminapi.scala).
 *
 *  The bytes are compiled in (`go.webembed` -> `wata-server/adminui`, a
 *  `go:embed`ed file), so a deployment copies one binary and nothing else. */
object AdminPage:
  def serve(w: go.net.http.ResponseWriter): Unit =
    Respond.raw(w, "text/html; charset=utf-8", go.webembed.indexHTML())

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
  /** the CORS origin every response advertises: `*` unless `WATA_CORS_ORIGIN`
   *  is set, in which case that exact origin is echoed instead and browsers
   *  on any other origin fail their CORS check. Wide open is the deliberate
   *  DEFAULT — the trust boundary is the family network, and both the
   *  devices and local dev tooling talk to the server from whatever origin
   *  they happen to run on; the knob exists for a deployment that fronts the
   *  server to a known web origin. Read per response (one getenv, like
   *  `TestHooks.enabled`), so no boot-order dependency. */
  def corsOrigin(): String =
    val v = go.sys.getenv("WATA_CORS_ORIGIN")
    if v == "" then "*" else v

  def finish(w: go.net.http.ResponseWriter, status: scala.Int, body: String): Unit =
    w.header().set("Content-Type", "application/json")
    w.header().set("Access-Control-Allow-Origin", corsOrigin())
    w.header().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    w.header().set("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization")
    w.writeHeader(go.Int.of(status))
    writeBody(w, body)

  /** a raw (non-JSON) 200 response — the media download path: the stored
   *  Content-Type + the bytes (a byte-preserving String -> `[]byte`). */
  def raw(w: go.net.http.ResponseWriter, contentType: String, data: String): Unit =
    w.header().set("Content-Type", contentType)
    w.header().set("Access-Control-Allow-Origin", corsOrigin())
    w.writeHeader(go.Int.of(200))
    writeBody(w, data)

  /** write the body; a failed write — usually the client hanging up mid-response
   *  — is logged (one line, like every other server log) and dropped, since the
   *  connection is already gone. */
  def writeBody(w: go.net.http.ResponseWriter, body: String): Unit =
    try
      w.write(go.bytes(body))
      ()
    catch case e: sgo.GoError => println("wata: response write failed: " + e.message)

object Server:
  def serve(addr: String): Unit =
    Admin.bootClock()                   // uptime, for the admin status panel
    Store.init()
    MediaFiles.boot()                   // before Journal.boot: replay migrates blobs out
    Journal.boot()
    Dm.migrate()                        // derive the pair map from replayed rooms
    Family.ensure()                     // the family room exists, everyone joined
    Retain.boot()                       // media retention: sweep now, then daily
    val mux = go.net.http.newServeMux()
    val h = new WataHandler()
    registerRoutes(mux, h)
    mux.handle("/", new NotFound())
    // Transport selection (plan 0013): `WATA_IROH_CONFIG=<json>` serves the
    // SAME mux over an embedded iroh listener. Unset (the default, and what
    // every harness uses) is plain HTTP.
    val irohCfg = go.sys.getenv("WATA_IROH_CONFIG")
    if irohCfg != "" then serveIroh(irohCfg, addr, mux) else serveTcp(addr, mux)

  /** iroh mode serves BOTH listeners over the one mux (plan 0021): the iroh
   *  endpoint the devices dial, and a plain TCP HTTP listener a browser on the
   *  LAN can reach `/admin` on — no browser can dial iroh, and the TCP port
   *  sits inside exactly the same trust boundary the default mode already
   *  relies on. The HTTP address is `WATA_LISTEN`, else the positional listen
   *  argument (`:8008` by default). The TCP listener runs on a spawned
   *  goroutine; the iroh one keeps the main goroutine, so a terminal iroh
   *  error still stops the process as before. */
  def serveIroh(cfgPath: String, addr: String, mux: go.net.http.ServeMux): Unit =
    go.spawn(() => serveTcp(httpAddr(addr), mux))
    println("Wata server listening over iroh (" + cfgPath + ")")
    val fin = go.irohnet.serve(cfgPath, mux)
    println("wata stopped " + fin.message)

  def httpAddr(addr: String): String =
    val v = go.sys.getenv("WATA_LISTEN")
    if v == "" then addr else v

  /** the TCP edge always serves through the node-id strip: a request that
   *  arrives where peer identity cannot be proven must never carry the
   *  trusted header, forged or otherwise (go-pkgs/irohnet/nodeid.go — the
   *  strip half exists in stub builds too). The iroh listener does its own
   *  strip-and-inject inside `irohnet.Serve`. */
  def serveTcp(addr: String, mux: go.net.http.ServeMux): Unit =
    val server = go.net.http.newServer()
    server.addr = addr
    server.handler = go.irohnet.stripNodeId(mux)
    println("Wata server listening on " + addr)
    val fin = server.listenAndServe()
    println("wata stopped " + fin.message)

  /** the routing table, as method-qualified Go-1.22 patterns. */
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
    // the wata dialect: canonical DMs (dm.scala).
    mux.handle("POST /_wata/v1/dm/{userId}", h)
    // the wata dialect: groups (group.scala) — get-or-extend by stamp name.
    mux.handle("POST /_wata/v1/group", h)
    // the wata dialect: favorites (favorite.scala) — the joined-member rule a
    // raw state PUT cannot express.
    mux.handle("POST /_wata/v1/favorite/{roomId}/{eventId}", h)
    // the wata dialect: the admin surface (adminapi.scala) + the page it
    // drives. Every /_wata/v1/admin/… route is admin-gated; /admin itself is
    // inert markup and is not.
    mux.handle("GET /admin", h)
    // first-run setup (plan 0021 C): the only two admin routes outside the
    // gate — the page asks which screen to draw, and claims the install.
    mux.handle("GET /_wata/v1/admin/mode", h)
    mux.handle("POST /_wata/v1/admin/setup", h)
    mux.handle("GET /_wata/v1/admin/status", h)
    mux.handle("GET /_wata/v1/admin/users", h)
    mux.handle("POST /_wata/v1/admin/users", h)
    mux.handle("DELETE /_wata/v1/admin/users/{user}", h)
    mux.handle("POST /_wata/v1/admin/users/{user}/password", h)
    mux.handle("POST /_wata/v1/admin/users/{user}/displayname", h)
    mux.handle("POST /_wata/v1/admin/users/{user}/admin", h)
    // device enrolment (enroll.scala): the announce is UNAUTHENTICATED by
    // design — a device outside the allowlist has no token to present — and
    // grants nothing; the approve/deny pair behind the admin gate is what
    // moves a node id into the allowlist.
    mux.handle("POST /_wata/v1/enroll", h)
    // device-login (plan 0027): no credentials — the iroh transport's proven
    // node id, delivered as the trusted header, is exchanged for a session;
    // any request without that header (every TCP-path request) is 403.
    mux.handle("POST /_wata/v1/device-login", h)
    // the device-command mailbox (devicecmd.scala, plan 0020): queueing and
    // report-reading are ADMIN-gated; poll/report authenticate as the
    // device's account (trusted node-id header, else bearer token). The two
    // literal patterns win over `{userId}` by mux specificity.
    mux.handle("POST /_wata/v1/cmd/{userId}", h)
    mux.handle("GET /_wata/v1/cmd/poll", h)
    mux.handle("POST /_wata/v1/cmd/report", h)
    mux.handle("GET /_wata/v1/cmd/{userId}/report", h)
    mux.handle("GET /_wata/v1/admin/enroll", h)
    mux.handle("POST /_wata/v1/admin/enroll/{nodeId}/approve", h)
    mux.handle("POST /_wata/v1/admin/enroll/{nodeId}/deny", h)
    // the fail-on-demand test hook (testhooks.scala): REGISTERED only under
    // WATA_TEST_HOOKS=1 — without the env var the path 404s like any other
    // unknown path, so the production surface is unchanged.
    if TestHooks.enabled then mux.handle("POST /_wata/v1/test/fail", h)
    // rooms / messaging / receipts / media.
    mux.handle("POST /_matrix/client/v3/createRoom", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomIdOrAlias}/join", h)
    mux.handle("POST /_matrix/client/v3/join/{roomIdOrAlias}", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/invite", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/leave", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/kick", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/ban", h)
    // The state key is optional in the path. Clients write it three ways —
    // omitted, empty (a trailing slash), or present — and a plain `{stateKey}`
    // segment matches none of the first two, so the second pattern is a
    // trailing WILDCARD (`{stateKey...}`), which matches an empty remainder.
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/state/{eventType}", h)
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/state/{eventType}/{stateKey...}", h)
    mux.handle("GET /_matrix/client/v3/rooms/{roomId}/messages", h)
    mux.handle("GET /_matrix/client/v1/directory/room/{roomAlias}", h)
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}", h)
    mux.handle("PUT /_matrix/client/v3/rooms/{roomId}/redact/{eventId}/{txnId}", h)
    mux.handle("POST /_matrix/client/v3/rooms/{roomId}/receipt/{receiptType}/{eventId}", h)
    // E2EE device-key handshake — no-op stubs (keys.scala).
    mux.handle("POST /_matrix/client/v3/keys/query", h)
    mux.handle("POST /_matrix/client/v3/keys/upload", h)
    mux.handle("POST /_matrix/client/v3/keys/device_signing/upload", h)
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
    dmDemo()
    syncDemo()
    pwhashDemo()
    familyDemo()
    groupDemo()

  def printProfile(userId: String): Unit = Store.getProfile(userId) match
    case s: Some[Profile] => println("profile " + Json.write(Router.profileJson(s.value)))
    case None => println("profile none")

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
      case s: Some[AcctData] => Json.write(s.value.content)
      case None => "none"

  // ---- membership state machine, IDs, room flow -------------------------------

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

  /** ID formatting: prefix/suffix shape (the random middle is not printed, to
   *  keep this check deterministic). */
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
    Rooms.addStateEvent(roomId, alice, "m.room.member", bob, Rooms.memberInviteContent(bob, true))
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
    case s: Some[Room] => s.value
    case None => Room(roomId, "10", Nil, Nil)

  def sendRedactDemo(roomId: String, sender: String): Unit =
    Store.addEvent(roomId, "m.room.message", sender, JsonNav.obj1("body", JStr("hi")), false, "", false, "", JNull()) match
      case s: Some[Event] => sendRedactDemo2(roomId, sender, s.value)
      case None => println("send none")

  def sendRedactDemo2(roomId: String, sender: String, ev: Event): Unit =
    println("send-added true")
    val red = Store.addEvent(roomId, "m.room.redaction", sender, JsonNav.obj1("redacts", JStr(ev.eventId)), false, "", true, ev.eventId, JNull())
    redactApply(roomId, ev.eventId, red)
    println("redacted-content " + eventContentStr(roomId, ev.eventId))

  def redactApply(roomId: String, eventId: String, red: Option[Event]): Unit = red match
    case s: Some[Event] => Store.redactTarget(roomId, eventId, s.value)
    case None => ()

  def eventContentStr(roomId: String, eventId: String): String = Store.getEventById(roomId, eventId) match
    case s: Some[Event] => Json.write(s.value.content)
    case None => "none"

  def memberDisplayName(roomId: String, userId: String): String = Store.getRoom(roomId) match
    case s: Some[Room] => memberDisplayName2(s.value, userId)
    case None => "none"

  def memberDisplayName2(room: Room, userId: String): String = Store.stateContent(room, "m.room.member", userId) match
    case s: Some[Json] => JsonNav.strField(s.value, "displayname", "")
    case None => "none"

  // ---- canonical DMs (pair ordering, boot migration, compat projection) -------

  /** the pair key's ordering, the boot migration over the legacy DM room
   *  `roomsDemo` left behind, and the `m.direct` projection derived from it.
   *  Booleans and ids only — never the volatile room id. */
  def dmDemo(): Unit =
    val alice = "@alice:localhost"
    val bob = "@bob:localhost"
    println("dm-order " + boolStr(Store.strLess(alice, bob) && !Store.strLess(bob, alice)))
    println("dm-pair-symmetric " + boolStr(Store.pairLo(bob, alice) == alice && Store.pairHi(alice, bob) == bob))
    println("dm-alias " + Dm.aliasOf(bob, alice))
    Dm.migrate()
    println("dm-peers " + JsonNav.longStr(peerCount(Store.dmPeersOf(alice))))
    println("dm-peer " + firstPeer(Store.dmPeersOf(alice)))
    println("dm-mapped " + boolStr(dmRoomMatches(alice, bob)))
    println("dm-direct-projected " + boolStr(directHasPeer(alice, bob)))
    Dm.migrate()
    println("dm-migrate-idempotent " + JsonNav.longStr(peerCount(Store.dmPeersOf(alice))))

  def peerCount(ps: List[DmPeer]): scala.Long = ps match
    case _ :: t => 1L + peerCount(t)
    case Nil  => 0L

  def firstPeer(ps: List[DmPeer]): String = ps match
    case h :: _ => h.peer
    case Nil  => "none"

  /** the pair map and the room's own membership agree. */
  def dmRoomMatches(a: String, b: String): Boolean = Store.dmRoomFor(a, b) match
    case s: Some[String] => Mem.str(Store.getMembership(s.value, b)) == "join"
    case None => false

  /** the projected `m.direct` names the peer (the stored content is whatever
   *  `acctDemo` left, so this proves re-assertion, not a fresh write). */
  def directHasPeer(user: String, peer: String): Boolean =
    hasPeerKey(Dm.project(user, Store.allAccountData(user, false, "")), peer)

  def hasPeerKey(items: List[AcctData], peer: String): Boolean = items match
    case h :: t => hasPeerKeyStep(h, t, peer)
    case Nil  => false

  def hasPeerKeyStep(h: AcctData, t: List[AcctData], peer: String): Boolean =
    if h.dtype == "m.direct" && fieldPresent(h.content, peer) then true else hasPeerKey(t, peer)

  def fieldPresent(c: Json, key: String): Boolean = JsonNav.getField(c, key) match
    case _: Some[Json] => true
    case None => false

  // ---- /sync building (deterministic — no age/IDs printed) --------------------

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
    case s: Some[Json] => s.value
    case None => JsonNav.emptyObj

  def objLen(j: Json): scala.Long = j match
    case o: JObj => pairLen(o.fields, 0L)
    case _       => 0L

  def pairLen(xs: List[(String, Json)], n: scala.Long): scala.Long = xs match
    case _ :: t => pairLen(t, n + 1L)
    case Nil  => n

  def arrLen(j: Json): scala.Long = j match
    case a: JArr => jsonLen(a.items, 0L)
    case _       => 0L

  def jsonLen(xs: List[Json], n: scala.Long): scala.Long = xs match
    case _ :: t => jsonLen(t, n + 1L)
    case Nil  => n

  def firstRoom(rooms: List[Room]): Room = rooms match
    case h :: _ => h
    case Nil  => Room("", "10", Nil, Nil)

  def firstStr(xs: List[String]): String = xs match
    case h :: _ => h
    case Nil  => "none"

  // ---- PBKDF2-HMAC-SHA256: the published test vectors -------------------------

  /** The password hasher's ORACLE (plan 0021): the widely published
   *  PBKDF2-HMAC-SHA256 vectors — the SHA-256 counterparts of RFC 6070's
   *  SHA-1 set, same inputs — rendered as lowercase hex, so the derivation
   *  (pwhash.scala) is byte-compared against numbers this repo did not
   *  produce. The last vector uses dkLen 40 > the 32-byte digest, which is the
   *  only case that exercises the multi-block `T1 || T2` path.
   *
   *  Then the stored form: a fixed-salt hash round-trips through `verify`, a
   *  wrong password does not, and a bare plaintext string is never accepted as
   *  a stored hash. */
  def pwhashDemo(): Unit =
    println("pbkdf2-c1 " + vec("password", "salt", 1, 32))
    println("pbkdf2-c2 " + vec("password", "salt", 2, 32))
    println("pbkdf2-c4096 " + vec("password", "salt", 4096, 32))
    println("pbkdf2-long " + vec("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 40))
    val h = Pwhash.hashWith("hunter2", go.bytes("0123456789abcdef"), 1000)
    println("pwhash-format " + h)
    println("pwhash-verify " + boolStr(Pwhash.verify(h, "hunter2")))
    println("pwhash-verify-wrong " + boolStr(Pwhash.verify(h, "hunter3")))
    println("pwhash-plaintext-never " + boolStr(Pwhash.verify("hunter2", "hunter2")))

  def vec(pw: String, salt: String, iters: scala.Int, dkLen: scala.Int): String =
    Pwhash.hex(Pwhash.derive(go.bytes(pw), go.bytes(salt), iters, dkLen))

  // ---- the canonical family room + groups (plan 0018) --------------------------

  /** `Family.ensure()` over the default alice/bob pair: the room exists at the
   *  canonical alias, stamped, everyone joined, and a second ensure changes
   *  nothing. Booleans and membership strings only — never the volatile id. */
  def familyDemo(): Unit =
    Family.ensure()
    val r1 = famRoomId()
    println("family-minted " + boolStr(r1 != ""))
    println("family-stamped " + boolStr(Family.isFamilyRoom(r1)))
    println("family-name " + roomNameOf(r1))
    println("family-joined " + Mem.str(Store.getMembership(r1, "@alice:localhost"))
      + " " + Mem.str(Store.getMembership(r1, "@bob:localhost")))
    Family.ensure()
    println("family-ensure-idempotent " + boolStr(famRoomId() == r1))

  def famRoomId(): String = Store.getRoomIdByAlias(Family.aliasName) match
    case s: Some[String] => s.value
    case None => ""

  def roomNameOf(roomId: String): String = Store.getRoom(roomId) match
    case s: Some[Room] => nameState(s.value)
    case None => "none"

  def nameState(room: Room): String = Store.stateContent(room, "m.room.name", "") match
    case s: Some[Json] => JsonNav.strField(s.value, "name", "")
    case None => "none"

  /** `Group.getOrExtend` — the endpoint's core, driven directly: one room per
   *  name, the caller and members joined, a re-ask is the same room, another
   *  name is another room. */
  def groupDemo(): Unit =
    val alice = "@alice:localhost"
    val bob = "@bob:localhost"
    val g1 = Group.getOrExtend(alice, "kids", oneStr(bob))
    println("group-minted " + boolStr(g1 != ""))
    println("group-stamp-name " + Group.nameOf(g1))
    println("group-joined " + Mem.str(Store.getMembership(g1, alice))
      + " " + Mem.str(Store.getMembership(g1, bob)))
    println("group-idempotent " + boolStr(Group.getOrExtend(alice, "kids", Nil) == g1))
    println("group-distinct " + boolStr(Group.getOrExtend(alice, "camping", Nil) != g1))
    println("group-not-family " + boolStr(!Family.isFamilyRoom(g1)))

  def oneStr(s: String): List[String] =
    var xs: List[String] = Nil
    xs = s :: xs
    xs
