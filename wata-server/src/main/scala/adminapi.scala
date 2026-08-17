import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** The admin surface (plan 0021): everything under `/_wata/v1/admin/`, plus
 *  the boot clock the status panel reports uptime from.
 *
 *  **The gate.** Every route here requires an authenticated session whose
 *  ACCOUNT carries `"admin": true` (config.scala): no token is 401, a
 *  non-admin token is 403. There is no second credential — login is the
 *  ordinary password login, so the browser page and a handset use the same
 *  endpoint.
 *
 *  **First-run setup.** Two routes are OUTSIDE the gate, and only those two:
 *  {{{
 *  GET    /_wata/v1/admin/mode                          {setup}
 *  POST   /_wata/v1/admin/setup                         {user, password, displayname}
 *  }}}
 *  `mode` is the whole surface the page needs to decide which screen to draw —
 *  one boolean, no accounts, nothing an anonymous caller could not infer from
 *  a login attempt anyway. `setup` answers only while the server is in setup
 *  mode (`Config.setupMode`, config.scala): it creates the first account with
 *  the admin flag and closes the window in ONE locked transaction, so on a LAN
 *  where two people race, exactly one wins and the other gets 409. Once an
 *  account exists it is 409 forever after; while setup is open, every OTHER
 *  admin route is 503 rather than 401, since nothing can be authenticated yet.
 *
 *  **The routes.**
 *  {{{
 *  GET    /_wata/v1/admin/status
 *  GET    /_wata/v1/admin/users
 *  POST   /_wata/v1/admin/users                        {user, password, displayname, admin}
 *  POST   /_wata/v1/admin/users/{user}/password        {password}
 *  POST   /_wata/v1/admin/users/{user}/displayname     {displayname}
 *  POST   /_wata/v1/admin/users/{user}/admin           {admin}
 *  DELETE /_wata/v1/admin/users/{user}
 *  GET    /_wata/v1/admin/enroll
 *  POST   /_wata/v1/admin/enroll/{nodeId}/approve
 *  POST   /_wata/v1/admin/enroll/{nodeId}/deny
 *  POST   /_wata/v1/admin/enroll/{nodeId}/revoke
 *  POST   /_wata/v1/admin/enroll/{nodeId}/bind
 *  POST   /_wata/v1/admin/enroll/{nodeId}/nickname
 *  }}}
 *
 *  The enrolment rows are enroll.scala's; only the routing lives here, since
 *  the gate is what they have in common with the account routes. Their
 *  unauthenticated counterpart, `POST /_wata/v1/enroll`, is deliberately NOT
 *  under this prefix.
 *
 *  Every mutation applies IN MEMORY and rewrites `users.json` in the same
 *  transaction (`Config`), so a created account can log in immediately and the
 *  next boot sees the same set. Removing an account also revokes its live
 *  sessions (`Store.dropUserDevices`), so the removed user's token is dead on
 *  its next request rather than at its next login.
 *
 *  What the admin API does NOT do: touch rooms, events, or media. A removed
 *  user's messages stay where they are — retention (retain.scala) is the only
 *  thing that deletes content.
 */
class BootState:
  var startedMs: scala.Long = 0L

object Admin:
  private val cell: Mutex[BootState] = mutex(new BootState())

  /** the advertised build version. One constant, reported by
   *  `/_wata/v1/admin/status` and shown on the page. */
  def version: String = "0.1.0"

  /** called once from `Server.serve`, before listening. */
  def bootClock(): Unit =
    cell.withLock(st => st.startedMs = Store.nowMs())

  def uptimeMs(): scala.Long =
    val started = cell.withLock(st => st.startedMs)
    if started == 0L then 0L else Store.nowMs() - started

  // ---- routing ---------------------------------------------------------------

  /** `/_wata/v1/admin/…` — mirrors `Server.registerRoutes` one for one; an
   *  unmatched shape here can only be a pattern the mux does not serve, which
   *  the catch-all already 404s. */
  def route(r: go.net.http.Request, m: String, body: String): Either[MErr, Json] =
    val path = r.uRL.path
    val n = Router.segCount(path)
    if n == 4 && Router.seg(path, 3) == "mode" then Right(mode())
    else if n == 4 && Router.seg(path, 3) == "setup" then setup(body)
    else if Config.setupMode() then Left(MErr(503, M_NOT_READY(), "Server setup is not complete"))
    else gated(r, m, body)

  def gated(r: go.net.http.Request, m: String, body: String): Either[MErr, Json] =
    requireAdmin(r) match
      case l: Left[MErr, Auth]   => Left(l.left)
      case rr: Right[MErr, Auth] => dispatch(rr.right, r, m, body)

  // ---- first-run setup ---------------------------------------------------------

  /** `GET /_wata/v1/admin/mode` — unauthenticated, so the page can pick its
   *  screen before anyone can log in. */
  def mode(): Json = obj1("setup", JBool(Config.setupMode()))

  /** `POST /_wata/v1/admin/setup` — unauthenticated, and refused with 409 the
   *  moment an account exists. Validation is the create path's, so a name the
   *  admin surface would refuse cannot arrive through the front door either. */
  def setup(body: String): Either[MErr, Json] =
    if !Config.setupMode() then Left(MErr(409, M_FORBIDDEN(), "Setup is already complete"))
    else setupBody(body)

  def setupBody(body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => setup2(j)

  def setup2(j: Json): Either[MErr, Json] =
    val lp = strField(j, "user", "")
    val pw = strField(j, "password", "")
    if !validLocalpart(lp) then Left(MErr(400, M_BAD_JSON(), "Invalid user name"))
    else if pw == "" then Left(MErr(400, M_BAD_JSON(), "A password is required"))
    else setup3(lp, pw, displayOr(strField(j, "displayname", ""), lp))

  /** the claim itself: `Config.claimSetup` is the atomic step — whoever loses
   *  the race gets the same 409 a late caller gets. */
  def setup3(lp: String, pw: String, display: String): Either[MErr, Json] =
    if !Config.claimSetup(lp, pw, display) then Left(MErr(409, M_FORBIDDEN(), "Setup is already complete"))
    else created(lp, display)

  /** authenticated (401) AND an admin account (403). */
  def requireAdmin(r: go.net.http.Request): Either[MErr, Auth] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] => adminOnly(rr.right)

  def adminOnly(a: Auth): Either[MErr, Auth] =
    if Config.isAdmin(Store.localpartOf(a.userId)) then Right(a)
    else Left(MErr(403, M_FORBIDDEN(), "Admin account required"))

  def dispatch(a: Auth, r: go.net.http.Request, m: String, body: String): Either[MErr, Json] =
    val path = r.uRL.path
    val n = Router.segCount(path)
    if n == 4 && Router.seg(path, 3) == "status" then Right(status())
    else if n == 4 && Router.seg(path, 3) == "users" then usersRoute(m, body)
    else if n == 5 && Router.seg(path, 3) == "users" then removeUser(a, r.pathValue("user"))
    else if n == 6 && Router.seg(path, 3) == "users" then userField(r.pathValue("user"), Router.seg(path, 5), body)
    else if n == 4 && Router.seg(path, 3) == "enroll" then Right(Enroll.list())
    else if n == 6 && Router.seg(path, 3) == "enroll" then enrollVerb(r.pathValue("nodeId"), Router.seg(path, 5), body)
    else Left(MErr(404, M_UNRECOGNIZED(), "Unrecognized request"))

  /** approve carries the optional account binding in its body (plan 0027);
   *  revoke and bind are plan 0058's lifecycle verbs; nickname is plan
   *  0060's display label. */
  def enrollVerb(nodeId: String, verb: String, body: String): Either[MErr, Json] =
    if verb == "approve" then Enroll.approve(nodeId, body)
    else if verb == "deny" then Enroll.deny(nodeId)
    else if verb == "revoke" then Enroll.revoke(nodeId)
    else if verb == "bind" then Enroll.bindRoute(nodeId, body)
    else if verb == "nickname" then Enroll.nicknameRoute(nodeId, body)
    else Left(MErr(404, M_UNRECOGNIZED(), "Unrecognized request"))

  def usersRoute(m: String, body: String): Either[MErr, Json] =
    if m == "GET" then Right(userList()) else createUser(body)

  def userField(lp: String, field: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => userField2(lp, field, j)

  def userField2(lp: String, field: String, j: Json): Either[MErr, Json] =
    if field == "password" then setPassword(lp, strField(j, "password", ""))
    else if field == "displayname" then setDisplay(lp, strField(j, "displayname", ""))
    else if field == "admin" then setAdmin(lp, boolField(j, "admin"))
    else Left(MErr(404, M_UNRECOGNIZED(), "Unrecognized request"))

  // ---- users -----------------------------------------------------------------

  def userList(): Json = obj1("users", JArr(userRows(Config.allUsers(), Nil)))

  def userRows(us: List[UserCfg], acc: List[Json]): List[Json] = us match
    case h :: t => userRows(t, userRow(h) :: acc)
    case Nil  => ListOps.reverse(acc)

  /** one account as the page renders it. Never the hash: the admin surface has
   *  no reason to hand a password digest to a browser. */
  def userRow(u: UserCfg): Json =
    val userId = Store.userIdOf(u.localpart)
    var fs: List[(String, Json)] = startObj
    fs = ("user", JStr(u.localpart)) :: fs
    fs = ("user_id", JStr(userId)) :: fs
    fs = ("displayname", JStr(u.displayName)) :: fs
    fs = ("admin", JBool(u.admin)) :: fs
    fs = ("devices", JInt(Store.deviceCount(userId))) :: fs
    fs = ("last_sync_age_ms", JInt(ageOf(Store.lastSyncMs(userId)))) :: fs
    fs = ("sessions", JArr(sessionRows(Store.sessionsOf(userId), Nil))) :: fs
    endObj(fs)

  /** how long ago, in ms; `-1` for "not since this server booted". */
  def ageOf(atMs: scala.Long): scala.Long =
    if atMs == 0L then -1L else Store.nowMs() - atMs

  /** the account's live sessions (plan 0059): the durable facts off the
   *  device row plus the in-memory last-seen, both as ages the page's
   *  relative-time helper renders — `-1` for "not since restart" (last
   *  seen) and for "unknown" (a row journaled before mint times existed). */
  def sessionRows(ss: List[SessionSnap], acc: List[Json]): List[Json] = ss match
    case h :: t => sessionRows(t, sessionRow(h) :: acc)
    case Nil  => ListOps.reverse(acc)

  def sessionRow(s: SessionSnap): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("device_id", JStr(s.deviceId)) :: fs
    fs = ("node_id", JStr(s.nodeId)) :: fs
    fs = ("created_ms", JInt(s.createdMs)) :: fs
    fs = ("created_age_ms", JInt(ageOf(s.createdMs))) :: fs
    fs = ("last_seen_age_ms", JInt(ageOf(s.lastSeenMs))) :: fs
    endObj(fs)

  def createUser(body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => createUser2(j)

  def createUser2(j: Json): Either[MErr, Json] =
    val lp = strField(j, "user", "")
    val pw = strField(j, "password", "")
    if !validLocalpart(lp) then Left(MErr(400, M_BAD_JSON(), "Invalid user name"))
    else if pw == "" then Left(MErr(400, M_BAD_JSON(), "A password is required"))
    else createUser3(lp, pw, displayOr(strField(j, "displayname", ""), lp), boolField(j, "admin"))

  def displayOr(d: String, lp: String): String = if d == "" then lp else d

  def createUser3(lp: String, pw: String, display: String, admin: Boolean): Either[MErr, Json] =
    if !Config.createUser(lp, pw, display, admin) then Left(MErr(400, M_USER_IN_USE(), "User already exists"))
    else created(lp, display)

  /** seed the new account's profile the way `Store.init` seeds a booted one,
   *  so `/profile` answers for it without a restart — then `Family.ensure()`:
   *  provisioning an account IS joining the family, and the very first
   *  account is what mints the family room (family.scala). */
  def created(lp: String, display: String): Either[MErr, Json] =
    Store.setDisplayName(Store.userIdOf(lp), display)
    Family.ensure()
    Right(obj1("user_id", JStr(Store.userIdOf(lp))))

  /** Matrix localparts, minus the slash: lowercase alphanumerics plus
   *  `.`, `_`, `-`, `=`. Enforced here because a name carrying a `/` or a `:`
   *  would collide with path and MXID parsing everywhere else. */
  def validLocalpart(lp: String): Boolean =
    lp != "" && lp.length <= 64 && allLpChars(lp, 0)

  def allLpChars(lp: String, i: scala.Int): Boolean =
    if i >= lp.length then true
    else if lpChar(lp.charAt(i)) then allLpChars(lp, i + 1)
    else false

  def lpChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '='

  def setPassword(lp: String, pw: String): Either[MErr, Json] =
    if pw == "" then Left(MErr(400, M_BAD_JSON(), "A password is required"))
    else okIf(Config.setPassword(lp, pw))

  /** a display-name change is BOTH config (the file) and store state: the
   *  profile fan-out rewrites the user's `m.room.member` event in every joined
   *  room, so clients see the new name without a restart. */
  def setDisplay(lp: String, name: String): Either[MErr, Json] =
    if name == "" then Left(MErr(400, M_BAD_JSON(), "A display name is required"))
    else setDisplay2(lp, name)

  def setDisplay2(lp: String, name: String): Either[MErr, Json] =
    if !Config.setDisplay(lp, name) then Left(MErr(404, M_NOT_FOUND(), "Unknown user"))
    else displayed(lp, name)

  def displayed(lp: String, name: String): Either[MErr, Json] =
    Store.setDisplayName(Store.userIdOf(lp), name)
    Right(emptyObj)

  def setAdmin(lp: String, admin: Boolean): Either[MErr, Json] = okIf(Config.setAdmin(lp, admin))

  /** removing an account revokes its live sessions in the same breath. The
   *  CALLER's own account is refused: an admin cannot lock themselves out of
   *  the interface mid-session. */
  def removeUser(a: Auth, lp: String): Either[MErr, Json] =
    if lp == Store.localpartOf(a.userId) then Left(MErr(403, M_FORBIDDEN(), "Cannot remove your own account"))
    else removeUser2(lp)

  def removeUser2(lp: String): Either[MErr, Json] =
    if !Config.removeUser(lp) then Left(MErr(404, M_NOT_FOUND(), "Unknown user"))
    else removed(lp)

  def removed(lp: String): Either[MErr, Json] =
    Store.dropUserDevices(Store.userIdOf(lp))
    Right(emptyObj)

  def okIf(done: Boolean): Either[MErr, Json] =
    if done then Right(emptyObj) else Left(MErr(404, M_NOT_FOUND(), "Unknown user"))

  // ---- status ----------------------------------------------------------------

  /** everything the panel shows, all of it read off state the server already
   *  keeps: the accounts (config.scala), device counts and last-sync stamps
   *  (store.scala), the journal and blob files (their sizes on disk), and the
   *  read-only deployment settings. */
  def status(): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("version", JStr(version)) :: fs
    fs = ("uptime_ms", JInt(uptimeMs())) :: fs
    fs = ("transport", JStr(transport())) :: fs
    fs = ("server_name", JStr(Config.serverName)) :: fs
    fs = ("users_file", JStr(Config.usersPath())) :: fs
    fs = ("journal_file", JStr(Journal.path())) :: fs
    fs = ("journal_bytes", JInt(fileSize(Journal.path()))) :: fs
    fs = ("retention_days", JInt(Retain.retainDays().toLong)) :: fs
    fs = ("rooms", JInt(Store.roomCount())) :: fs
    fs = ("media_count", JInt(mediaCount())) :: fs
    fs = ("media_bytes", JInt(mediaBytes())) :: fs
    fs = ("users", JArr(userRows(Config.allUsers(), Nil))) :: fs
    endObj(fs)

  /** which listener the mux is being served over (`WATA_IROH_CONFIG`, plan
   *  0013). In iroh mode a plain HTTP listener runs alongside it — that is how
   *  a browser reaches this page at all — so the panel reports the transport
   *  the CLIENTS use. */
  def transport(): String =
    if go.sys.getenv("WATA_IROH_CONFIG") != "" then "iroh" else "tcp"

  def mediaCount(): scala.Long = countMedia(Store.allMedia(), 0L)

  def countMedia(xs: List[MediaItem], n: scala.Long): scala.Long = xs match
    case _ :: t => countMedia(t, n + 1L)
    case Nil  => n

  def mediaBytes(): scala.Long = sumMedia(Store.allMedia(), 0L)

  def sumMedia(xs: List[MediaItem], n: scala.Long): scala.Long = xs match
    case h :: t => sumMedia(t, n + mediaSize(h))
    case Nil  => n

  /** stateless runs hold the bytes in the item; file-backed runs hold only
   *  metadata, so the blob file is what is measured. */
  def mediaSize(m: MediaItem): scala.Long =
    if m.data != "" then m.data.length.toLong
    else if MediaFiles.enabled then fileSize(MediaFiles.blobPath(m.mediaId))
    else 0L

  def fileSize(p: String): scala.Long =
    if p == "" then 0L else statSize(p)

  def statSize(p: String): scala.Long =
    var n = 0L
    try
      val fi = go.osfile.stat(p)
      n = fi.size()
      ()
    catch case e: sgo.GoError => n = 0L
    n
