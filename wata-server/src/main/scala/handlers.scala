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
 *  `route` re-derives which handler from method + path, reading params back with
 *  `Request.PathValue`.
 */
object Router:

  def route(r: go.net.http.Request, body: String): Either[MErr, Json] =
    val m = r.method
    val path = r.uRL.path
    if path == "/_matrix/client/versions" then Right(versions())
    else if isLoginPath(path) then loginRoute(m, body)
    else if path == "/_matrix/client/v3/logout" then logoutRoute(r)
    else if path == "/_matrix/client/v3/account/whoami" then whoami(r)
    else if path.endsWith("/sync") then Sync.handle(r)
    else if path.startsWith("/_wata/v1/dm/") then Dm.route(r)
    else if path == "/_matrix/client/v3/createRoom" then Rooms.createRoom(r, body)
    else if path.endsWith("/messages") then Rooms.messages(r)
    else if isAcctPath(path) then acctRoute(m, path, r, body)
    else if isProfilePath(path) then profileRoute(path, r, body)
    else if path.contains("/keys/") then Keys.route(path, r, body)
    else if path.contains("/directory/room/") then Rooms.resolveAlias(r)
    else if path.endsWith("/upload") then Rooms.upload(r, body)
    else if path.contains("/send/") then Rooms.sendEvent(r, body)
    else if path.contains("/redact/") then Rooms.redact(r, body)
    else if path.contains("/receipt/") then Rooms.receipt(r)
    else if path.contains("/state/") then Rooms.setState(r, body)
    else if path.endsWith("/invite") then Rooms.inviteRoute(r, body)
    else if path.endsWith("/leave") then Rooms.leaveRoute(r, body)
    else if path.endsWith("/kick") then Rooms.kickRoute(r, body)
    else if path.endsWith("/ban") then Rooms.banRoute(r, body)
    else if path.endsWith("/join") then Rooms.joinRoute(r)
    else if path.contains("/join/") then Rooms.joinRoute(r)
    else Left(MErr(404, M_UNRECOGNIZED(), "Unrecognized request"))

  def isLoginPath(path: String): Boolean = path == "/_matrix/client/v3/login"
  def isProfilePath(path: String): Boolean = path.contains("/profile/")
  def isAcctPath(path: String): Boolean = path.contains("/account_data/")
  def isRoomAcct(path: String): Boolean = path.contains("/rooms/")

  // ---- access-token middleware -----------------------------------------------

  def requireAuth(r: go.net.http.Request): Either[MErr, Auth] =
    val h = r.header.get("Authorization")
    if !h.startsWith("Bearer ") then Left(MErr(401, M_MISSING_TOKEN(), "Missing access token"))
    else requireAuthTok(h)

  def requireAuthTok(h: String): Either[MErr, Auth] =
    val token = h.substring(7)
    Store.deviceByToken(token) match
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

  def loginCheck(u: UserCfg, lp: String, j: Json): Either[MErr, Json] =
    if u.password == strField(j, "password", "") then loginOk(lp)
    else Left(MErr(403, M_FORBIDDEN(), "Invalid username or password"))

  def loginOk(lp: String): Either[MErr, Json] =
    val userId = Store.userIdOf(lp)
    val dev = Store.createDevice(userId)
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
    if path.endsWith("/displayname") then setDisplayName(r, body)
    else if path.endsWith("/avatar_url") then setAvatarUrl(r, body)
    else getProfile(r)

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
