import ListOps.*
import JsonNav.*

/** Routing + the auth / profile / account-data handlers.
 *
 *  Every handler step returns `Either[MErr, Json]`: `Left` is a Matrix error,
 *  `Right` is the 200 body. The edge (`WataHandler.serveHTTP`, server.scala)
 *  serializes either to the wire. Access-token middleware is `requireAuth` — a
 *  check each handler calls directly, since there is a single dispatch handler
 *  rather than a middleware chain.
 *
 *  A single `WataHandler` is registered on the ServeMux for every route; the mux
 *  does the path + method match (Go 1.22 patterns) and param capture, and
 *  `route` re-derives which handler — the facade's `Request` does not expose
 *  the matched pattern, so the path is re-parsed here. The dispatch is EXACT: a
 *  literal path compares whole, and a parameterized pattern matches on segment
 *  count plus its literal segments (`seg`/`segCount`), mirroring the mux
 *  registration (server.scala `registerRoutes`) one for one — never a raw
 *  substring, which a parameter value could collide with. Params are read back
 *  with `Request.PathValue`. The method is not re-checked (the mux 405s a
 *  method mismatch before we run), except where one pattern serves two methods
 *  (login, account data).
 */
object Router:

  def route(r: go.net.http.Request, body: String): Either[MErr, Json] =
    routeSeg(r, r.method, body, r.uRL.path, segCount(r.uRL.path))

  def routeSeg(r: go.net.http.Request, m: String, body: String, path: String, n: scala.Int): Either[MErr, Json] =
    if path == "/_matrix/client/versions" then Right(versions())
    else if isLoginPath(path) then loginRoute(m, body)
    else if path == "/_matrix/client/v3/logout" then logoutRoute(r)
    else if path == "/_matrix/client/v3/account/whoami" then whoami(r)
    else if path == "/_matrix/client/v3/sync" then Sync.handle(r)
    else if isDmPath(path, n) then Dm.route(r)
    else if isGroupPath(path) then Group.route(r, body)
    else if isFavoritePath(path, n) then Favorite.route(r)
    else if isAdminPath(path, n) then Admin.route(r, m, body)
    else if isEnrollServerPath(path) then Enroll.serverInfo()
    else if isEnrollPath(path) then Enroll.announce(body)
    else if isDeviceLoginPath(path) then DeviceLogin.route(r)
    // the command mailbox (devicecmd.scala): the two literals BEFORE the
    // `{userId}` shapes, mirroring the mux's specificity order.
    else if DeviceCmd.isPollPath(path) then DeviceCmd.poll(r)
    else if DeviceCmd.isReportPath(path) then DeviceCmd.report(r, body)
    else if DeviceCmd.isReadReportPath(path, n) then DeviceCmd.readReport(r)
    else if DeviceCmd.isQueuePath(path, n) then DeviceCmd.queue(r, body)
    // APNs push registration (push.scala, plan 0065): both act on the calling
    // session's own device.
    else if Push.isRegisterPath(path) then Push.register(r, body)
    else if Push.isUnregisterPath(path) then Push.unregister(r)
    else if Push.isChannelJoinPath(path) then Push.channelJoin(r, body)
    else if Push.isChannelLeavePath(path) then Push.channelLeave(r)
    else if TestHooks.enabled && isTestFailPath(path) then TestHooks.route(body)
    else if path == "/_matrix/client/v3/createRoom" then Rooms.createRoom(r, body)
    else if isRoomVerb(path, n, 6, "messages") then Rooms.messages(r)
    else if isAcctPath(path, n) then acctRoute(m, path, r, body)
    else if isProfilePath(path, n) then profileRoute(path, r, body)
    else if isKeysPath(path, n) then Keys.route(path, r, body)
    else if isAliasPath(path, n) then Rooms.resolveAlias(r)
    else if path == "/_matrix/media/v3/upload" then Rooms.upload(r, body)
    else if isRoomVerb(path, n, 8, "send") then Rooms.sendEvent(r, body)
    else if isRoomVerb(path, n, 8, "redact") then Rooms.redact(r, body)
    else if isRoomVerb(path, n, 8, "receipt") then Rooms.receipt(r)
    else if isStatePath(path, n) then Rooms.setState(r, body)
    else if isRoomVerb(path, n, 6, "invite") then Rooms.inviteRoute(r, body)
    else if isRoomVerb(path, n, 6, "leave") then Rooms.leaveRoute(r, body)
    else if isRoomVerb(path, n, 6, "kick") then Rooms.kickRoute(r, body)
    else if isRoomVerb(path, n, 6, "ban") then Rooms.banRoute(r, body)
    else if isRoomVerb(path, n, 6, "join") then Rooms.joinRoute(r)
    else if isJoinById(path, n) then Rooms.joinRoute(r)
    else Left(MErr(404, M_UNRECOGNIZED(), "Unrecognized request"))

  // ---- path segments ----------------------------------------------------------

  /** segments of a leading-'/' path, Go `strings.Split(path[1:], "/")` style: a
   *  trailing slash yields a final empty segment (which is how the mux's
   *  `{stateKey...}` wildcard matches an empty remainder). */
  def segCount(path: String): scala.Int =
    var n = 1
    var i = 1
    while i < path.length do
      if path.charAt(i) == '/' then n = n + 1
      i = i + 1
    n

  /** the `want`-th segment; "" when out of range (callers gate on `segCount`). */
  def seg(path: String, want: scala.Int): String =
    var start = 1
    var k = 0
    while k < want && start < path.length do
      if path.charAt(start) == '/' then k = k + 1
      start = start + 1
    if k < want then "" else segFrom(path, start)

  def segFrom(path: String, start: scala.Int): String =
    var e = start
    while e < path.length && path.charAt(e) != '/' do e = e + 1
    path.substring(start, e)

  // ---- one predicate per registered pattern shape -----------------------------

  def isClientV(path: String, v: String): Boolean =
    seg(path, 0) == "_matrix" && seg(path, 1) == "client" && seg(path, 2) == v

  def isLoginPath(path: String): Boolean = path == "/_matrix/client/v3/login"

  /** `/_wata/v1/dm/{userId}` */
  def isDmPath(path: String, n: scala.Int): Boolean =
    n == 4 && seg(path, 0) == "_wata" && seg(path, 1) == "v1" && seg(path, 2) == "dm"

  /** `/_wata/v1/group` — group get-or-extend (group.scala). */
  def isGroupPath(path: String): Boolean = path == "/_wata/v1/group"

  /** `/_wata/v1/favorite/{roomId}/{eventId}` */
  def isFavoritePath(path: String, n: scala.Int): Boolean =
    n == 5 && seg(path, 0) == "_wata" && seg(path, 1) == "v1" && seg(path, 2) == "favorite"

  /** `/_wata/v1/admin/…` — the whole admin surface (adminapi.scala) behind one
   *  predicate; `Admin.dispatch` splits it by shape. Every route under it is
   *  admin-gated, so a new one cannot be added ungated by accident. */
  def isAdminPath(path: String, n: scala.Int): Boolean =
    n >= 4 && seg(path, 0) == "_wata" && seg(path, 1) == "v1" && seg(path, 2) == "admin"

  /** `/_wata/v1/enroll` — the device announce (enroll.scala), and
   *  `/_wata/v1/enroll/server` — the server's own public card (plan 0062).
   *  The TWO unauthenticated wata routes, for the same reason: they serve a
   *  device with nothing yet, and neither grants anything — the announce
   *  parks a public id, the card repeats identity the enrolment QRs already
   *  display. */
  def isEnrollPath(path: String): Boolean = path == "/_wata/v1/enroll"
  def isEnrollServerPath(path: String): Boolean = path == "/_wata/v1/enroll/server"

  /** `/_wata/v1/device-login` — token-less BY DESIGN (bindings.scala): the
   *  credential is the iroh connection's proven node id, read off the trusted
   *  header; where that is absent the handler answers 403 itself. */
  def isDeviceLoginPath(path: String): Boolean = path == "/_wata/v1/device-login"

  /** `/_wata/v1/test/fail` — dispatched only when `TestHooks.enabled`,
   *  mirroring its conditional registration (server.scala). */
  def isTestFailPath(path: String): Boolean = path == "/_wata/v1/test/fail"

  /** `/_matrix/client/{v3,v2}/profile/{userId}` (GET) and
   *  `/_matrix/client/v3/profile/{userId}/{displayname,avatar_url}` (PUT). */
  def isProfilePath(path: String, n: scala.Int): Boolean =
    if n == 5 then (isClientV(path, "v3") || isClientV(path, "v2")) && seg(path, 3) == "profile"
    else n == 6 && isClientV(path, "v3") && seg(path, 3) == "profile" && isProfileField(seg(path, 5))

  def isProfileField(s: String): Boolean = s == "displayname" || s == "avatar_url"

  /** `/_matrix/client/v3/user/{userId}/account_data/{type}` and
   *  `/_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type}`. */
  def isAcctPath(path: String, n: scala.Int): Boolean =
    if !(isClientV(path, "v3") && seg(path, 3) == "user") then false
    else if n == 7 then seg(path, 5) == "account_data"
    else n == 9 && seg(path, 5) == "rooms" && seg(path, 7) == "account_data"

  def isRoomAcct(path: String): Boolean = seg(path, 5) == "rooms"

  /** `/_matrix/client/v3/keys/{query,upload}` and
   *  `/_matrix/client/v3/keys/device_signing/upload`. */
  def isKeysPath(path: String, n: scala.Int): Boolean =
    if !(isClientV(path, "v3") && seg(path, 3) == "keys") then false
    else if n == 5 then seg(path, 4) == "query" || seg(path, 4) == "upload"
    else n == 6 && seg(path, 4) == "device_signing" && seg(path, 5) == "upload"

  /** `/_matrix/client/v1/directory/room/{roomAlias}` */
  def isAliasPath(path: String, n: scala.Int): Boolean =
    n == 6 && isClientV(path, "v1") && seg(path, 3) == "directory" && seg(path, 4) == "room"

  /** `/_matrix/client/v3/join/{roomIdOrAlias}` */
  def isJoinById(path: String, n: scala.Int): Boolean =
    n == 5 && isClientV(path, "v3") && seg(path, 3) == "join"

  /** `/_matrix/client/v3/rooms/{roomId}/<verb>/...` at exactly `want` segments:
   *  6 for the bare verbs (join/invite/leave/kick/ban/messages), 8 for the
   *  two-param ones (send/redact/receipt). */
  def isRoomVerb(path: String, n: scala.Int, want: scala.Int, verb: String): Boolean =
    n == want && isClientV(path, "v3") && seg(path, 3) == "rooms" && seg(path, 5) == verb

  /** `/_matrix/client/v3/rooms/{roomId}/state/{eventType}` plus the
   *  `{stateKey...}` wildcard form — 7 segments or more (the wildcard may be
   *  empty, one segment, or, per the mux, several). */
  def isStatePath(path: String, n: scala.Int): Boolean =
    n >= 7 && isClientV(path, "v3") && seg(path, 3) == "rooms" && seg(path, 5) == "state"

  // ---- access-token middleware -----------------------------------------------

  /** the transport-proven node id rides along (plan 0060): on the iroh path
   *  the bridge injected it, on TCP the edge stripped it to "" — so the
   *  store can backfill a pre-0058 session row's node id from the very
   *  request that authenticates it. */
  def requireAuth(r: go.net.http.Request): Either[MErr, Auth] =
    val h = r.header.get("Authorization")
    if !h.startsWith("Bearer ") then Left(MErr(401, M_MISSING_TOKEN(), "Missing access token"))
    else requireAuthTok(h, r.header.get(DeviceLogin.NODE_HEADER))

  def requireAuthTok(h: String, nodeId: String): Either[MErr, Auth] =
    val token = h.substring(7)
    Store.deviceByToken(token, nodeId) match
      case None => Left(MErr(401, M_UNKNOWN_TOKEN(), "Unknown access token"))
      case s: Some[Device] => Right(Auth(s.value.userId, s.value.deviceId))

  // ---- versions --------------------------------------------------------------

  def versions(): Json =
    var vs: List[Json] = Nil
    vs = JStr("v1.6") :: vs
    vs = JStr("v1.5") :: vs
    vs = JStr("v1.4") :: vs
    vs = JStr("v1.3") :: vs
    vs = JStr("v1.2") :: vs
    vs = JStr("v1.1") :: vs
    obj2("versions", JArr(vs), "unstable_features", emptyObj)

  // ---- auth ---------------------------------------------------------------

  def loginRoute(m: String, body: String): Either[MErr, Json] =
    if m == "GET" then Right(loginFlows()) else login(body)

  def loginFlows(): Json =
    obj1("flows", arr1(obj1("type", JStr("m.login.password"))))

  def login(body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => loginParsed(j)

  def loginParsed(j: Json): Either[MErr, Json] = loginLocalpart(j) match
    case s: Some[String] => loginUser(cleanLocalpart(s.value), j)
    case None => Left(MErr(403, M_FORBIDDEN(), "Missing user identifier"))

  /** identifier.user (m.id.user) or the deprecated top-level `user` field. */
  def loginLocalpart(j: Json): Option[String] = identUser(j) match
    case s: Some[String] => s
    case None => topUser(j)

  def identUser(j: Json): Option[String] = getField(j, "identifier") match
    case s: Some[Json] => identUserField(s.value)
    case None => None

  def identUserField(id: Json): Option[String] = getField(id, "user") match
    case s: Some[Json] => asStr(s.value)
    case None => None

  def topUser(j: Json): Option[String] = getField(j, "user") match
    case s: Some[Json] => asStr(s.value)
    case None => None

  /** accept full MXIDs ("@alice:localhost") as well as bare localparts. */
  def cleanLocalpart(lp: String): String = cutColon(stripAt(lp))
  def stripAt(s: String): String = if s.startsWith("@") then s.substring(1) else s
  def cutColon(s: String): String =
    val i = s.indexOf(":")
    if i < 0 then s else s.substring(0, i)

  def loginUser(lp: String, j: Json): Either[MErr, Json] = Store.userByLocalpart(lp) match
    case None => Left(MErr(403, M_FORBIDDEN(), "Invalid username or password"))
    case s: Some[UserCfg] => loginCheck(s.value, lp, j)

  /** the password check: derive with the stored hash's own parameters and
   *  compare constant-time (`Pwhash.verify`). An account with no usable hash
   *  (a malformed file entry) verifies against nothing. */
  def loginCheck(u: UserCfg, lp: String, j: Json): Either[MErr, Json] =
    if Pwhash.verify(u.hash, strField(j, "password", "")) then loginOk(lp)
    else Left(MErr(403, M_FORBIDDEN(), "Invalid username or password"))

  def loginOk(lp: String): Either[MErr, Json] = loginOkNode(lp, "")

  /** the shared success shape; `nodeId` is "" for a password login and the
   *  proven transport identity for device-login (plan 0058: the row records
   *  the node it came through, so a revocation can find it). */
  def loginOkNode(lp: String, nodeId: String): Either[MErr, Json] =
    val userId = Store.userIdOf(lp)
    val dev = Store.createDevice(userId, nodeId)
    Right(obj4(
      "user_id", JStr(userId),
      "access_token", JStr(dev.accessToken),
      "device_id", JStr(dev.deviceId),
      "home_server", JStr(Config.serverName)))

  def logoutRoute(r: go.net.http.Request): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => doLogout(rr.right)

  def doLogout(a: Auth): Either[MErr, Json] =
    Store.removeDevice(a.deviceId)
    Right(emptyObj)

  def whoami(r: go.net.http.Request): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => whoamiOk(rr.right)

  def whoamiOk(a: Auth): Either[MErr, Json] =
    Right(obj2("user_id", JStr(a.userId), "device_id", JStr(a.deviceId)))

  // ---- profile ------------------------------------------------------------

  def profileRoute(path: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    if segCount(path) == 5 then getProfile(r)
    else if seg(path, 5) == "displayname" then setDisplayName(r, body)
    else setAvatarUrl(r, body)

  def getProfile(r: go.net.http.Request): Either[MErr, Json] =
    Store.getProfile(r.pathValue("userId")) match
      case None => Left(MErr(404, M_NOT_FOUND(), "User not found"))
      case s: Some[Profile] => Right(profileJson(s.value))

  def profileJson(p: Profile): Json =
    if p.avatarUrl == "" then obj1("displayname", JStr(p.displayname))
    else obj2("displayname", JStr(p.displayname), "avatar_url", JStr(p.avatarUrl))

  def setDisplayName(r: go.net.http.Request, body: String): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => setDisplayName2(rr.right, r, body)

  def setDisplayName2(a: Auth, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val userId = r.pathValue("userId")
    if a.userId != userId then Left(MErr(403, M_FORBIDDEN(), "Cannot set displayname for other users"))
    else setDisplayName3(userId, body)

  def setDisplayName3(userId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => setDisplayName4(userId, j)

  def setDisplayName4(userId: String, j: Json): Either[MErr, Json] =
    Store.setDisplayName(userId, strField(j, "displayname", ""))
    Right(emptyObj)

  def setAvatarUrl(r: go.net.http.Request, body: String): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => setAvatarUrl2(rr.right, r, body)

  def setAvatarUrl2(a: Auth, r: go.net.http.Request, body: String): Either[MErr, Json] =
    val userId = r.pathValue("userId")
    if a.userId != userId then Left(MErr(403, M_FORBIDDEN(), "Cannot set avatar_url for other users"))
    else setAvatarUrl3(userId, body)

  def setAvatarUrl3(userId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => setAvatarUrl4(userId, j)

  def setAvatarUrl4(userId: String, j: Json): Either[MErr, Json] =
    Store.setAvatarUrl(userId, strField(j, "avatar_url", ""))
    Right(emptyObj)

  // ---- account data -------------------------------------------------------

  def acctRoute(m: String, path: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    if isRoomAcct(path) then roomAcctRoute(m, r, body)
    else globalAcctRoute(m, r, body)

  def globalAcctRoute(m: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    if m == "GET" then getAcct(r, false) else setAcct(r, body, false)

  def roomAcctRoute(m: String, r: go.net.http.Request, body: String): Either[MErr, Json] =
    if m == "GET" then getAcct(r, true) else setAcct(r, body, true)

  def getAcct(r: go.net.http.Request, hasRoom: Boolean): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => getAcct2(rr.right, r, hasRoom)

  def getAcct2(a: Auth, r: go.net.http.Request, hasRoom: Boolean): Either[MErr, Json] =
    val userId = r.pathValue("userId")
    if a.userId != userId then Left(MErr(403, M_FORBIDDEN(), "Cannot access account data for other users"))
    else getAcct3(userId, r, hasRoom)

  def getAcct3(userId: String, r: go.net.http.Request, hasRoom: Boolean): Either[MErr, Json] =
    Store.getAccountData(userId, hasRoom, r.pathValue("roomId"), r.pathValue("type")) match
      case None => Left(MErr(404, M_NOT_FOUND(), "Account data not found"))
      case s: Some[AcctData] => Right(s.value.content)

  def setAcct(r: go.net.http.Request, body: String, hasRoom: Boolean): Either[MErr, Json] = requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => setAcct2(rr.right, r, body, hasRoom)

  def setAcct2(a: Auth, r: go.net.http.Request, body: String, hasRoom: Boolean): Either[MErr, Json] =
    val userId = r.pathValue("userId")
    if a.userId != userId then Left(MErr(403, M_FORBIDDEN(), "Cannot set account data for other users"))
    else setAcct3(userId, r, body, hasRoom)

  /** spec (§Account Data): `m.fully_read` / `m.push_rules` are server-controlled
   *  -> 405. */
  def setAcct3(userId: String, r: go.net.http.Request, body: String, hasRoom: Boolean): Either[MErr, Json] =
    val dtype = r.pathValue("type")
    if serverControlled(dtype) then Left(MErr(405, M_BAD_JSON(), "Cannot set " + dtype + " through this API"))
    else setAcct4(userId, r, dtype, body, hasRoom)

  def serverControlled(t: String): Boolean = t == "m.fully_read" || t == "m.push_rules"

  def setAcct4(userId: String, r: go.net.http.Request, dtype: String, body: String, hasRoom: Boolean): Either[MErr, Json] =
    Json.tryParse(body) match
      case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Request body is not valid JSON"))
      case Right(j) => setAcct5(userId, r, dtype, j, hasRoom)

  def setAcct5(userId: String, r: go.net.http.Request, dtype: String, j: Json, hasRoom: Boolean): Either[MErr, Json] =
    if !isObj(j) then Left(MErr(400, M_BAD_JSON(), "Request body is not a JSON object"))
    else setAcct6(userId, r, dtype, j, hasRoom)

  def setAcct6(userId: String, r: go.net.http.Request, dtype: String, j: Json, hasRoom: Boolean): Either[MErr, Json] =
    Store.setAccountData(userId, hasRoom, r.pathValue("roomId"), dtype, j)
    Right(emptyObj)
