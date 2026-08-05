import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** The server's user accounts.
 *
 *  wata PROVISIONS accounts; it does not let anyone register one. That is the
 *  trust model — the network is the boundary, and the set of people on it is
 *  decided out of band — so the accounts are configuration, read at boot:
 *
 *  {{{
 *  WATA_USERS=/etc/wata/users.json wata-server :8008
 *  }}}
 *
 *  {{{
 *  [ {"user": "alice", "hash": "pbkdf2-sha256$…", "displayname": "Alice", "admin": true},
 *    {"user": "bob",   "hash": "pbkdf2-sha256$…", "displayname": "Bob",   "admin": false} ]
 *  }}}
 *
 *  `displayname` is optional and defaults to the localpart; `admin` defaults
 *  to false and gates the `/_wata/v1/admin/…` surface; an entry with no `user`
 *  is skipped. An unset, unreadable, unparseable, or empty `WATA_USERS` falls
 *  back to the built-in alice/bob pair (alice admin), so every harness and
 *  script in this repo runs unchanged with no file present. A bad file is a
 *  fallback rather than a hard failure because the alternative — a homeserver
 *  that refuses to boot — is worse for a device that has to come up on its own.
 *
 *  **No plaintext at rest.** A password is stored as the
 *  `pbkdf2-sha256$<iter>$<salt>$<dk>` string `Pwhash` mints (pwhash.scala). A
 *  hand-written `"password"` field is still accepted as INPUT — provisioning
 *  by hand types a password once — but it is hashed as the file is read and
 *  the file is REWRITTEN, so plaintext never survives a boot. An entry
 *  carrying both fields keeps the `hash`.
 *
 *  **The server owns the file.** Admin mutations (adminapi.scala) change the
 *  in-memory list and rewrite the file in the same transaction (temp +
 *  rename, so a reader never sees a half-written file), which is what makes a
 *  created account usable with no restart while the file stays the source of
 *  truth for the next boot. The list lives behind its own small `Mutex`, kept
 *  separate from the store's cell (like the journal's): logins and admin
 *  requests reach it from per-request goroutines.
 */
class ConfigState:
  var users: List[UserCfg] = Nil
  /** the `WATA_USERS` path, "" when the built-in pair is in force (nothing is
   *  ever written then — there is no file to own). */
  var path: String = ""

/** what one parse of a users file yielded: the entries, and whether any of
 *  them carried a plaintext `password` (which makes the boot rewrite the
 *  file). */
case class ParsedUsers(users: List[UserCfg], plaintext: Boolean)

object Config:
  private val cell: Mutex[ConfigState] = mutex(new ConfigState())

  def serverName: String = "localhost"

  /** boot: called from `Store.init`, so every entry point that brings the store
   *  up (the server and `SelfCheck` alike) gets the same accounts. */
  def load(): Unit =
    val p = go.sys.getenv("WATA_USERS")
    if p == "" then useDefaults() else loadFrom(p)

  def loadFrom(p: String): Unit =
    val parsed = parseUsers(readAll(p))
    if isEmptyU(parsed.users) then useDefaults() else installFile(p, parsed)

  /** a parsed file becomes the live accounts; a plaintext `password` anywhere
   *  in it triggers the one-time rewrite that replaces it with a hash. A file
   *  that did NOT parse is left alone (`loadFrom` fell back to the built-in
   *  pair): rewriting it would destroy whatever the human meant to write. */
  def installFile(p: String, parsed: ParsedUsers): Unit =
    cell.withLock { st =>
      st.path = p
      st.users = parsed.users
      if parsed.plaintext then writeFile(p, parsed.users) else ()
    }

  def readAll(p: String): String =
    var out = ""
    try
      val v = go.sys.readFile(p)
      out = go.string(v)
      ()
    catch case e: sgo.GoError => out = ""
    out

  def parseUsers(raw: String): ParsedUsers = Json.tryParse(raw) match
    case Right(j) => userList(j)
    case Left(_)  => ParsedUsers(Nil, false)

  def userList(j: Json): ParsedUsers = j match
    case a: JArr => collectUsers(a.items, Nil, false)
    case _       => ParsedUsers(Nil, false)

  def collectUsers(xs: List[Json], acc: List[UserCfg], plain: Boolean): ParsedUsers = xs match
    case h :: t => collectUsersStep(h, t, acc, plain)
    case Nil  => ParsedUsers(ListOps.reverse(acc), plain)

  def collectUsersStep(h: Json, t: List[Json], acc: List[UserCfg], plain: Boolean): ParsedUsers =
    val u = strField(h, "user", "")
    if u == "" then collectUsers(t, acc, plain)
    else collectUsers(t, consUser(u, h, acc), plain || hasPlaintext(h))

  /** an entry provisioned by hand: a `password` field and no usable `hash`. */
  def hasPlaintext(j: Json): Boolean =
    !Pwhash.isHashed(strField(j, "hash", "")) && strField(j, "password", "") != ""

  def consUser(u: String, j: Json, acc: List[UserCfg]): List[UserCfg] =
    var xs: List[UserCfg] = acc
    xs = UserCfg(u, hashOf(j), displayOr(j, u), boolField(j, "admin")) :: xs
    xs

  /** the stored hash, or one minted from a hand-written plaintext password.
   *  Neither present means an account nothing can log in as, which is a
   *  deliberate outcome for a malformed entry rather than an open door. */
  def hashOf(j: Json): String =
    val h = strField(j, "hash", "")
    if Pwhash.isHashed(h) then h else hashPlain(strField(j, "password", ""))

  def hashPlain(pw: String): String = if pw == "" then "" else Pwhash.hash(pw)

  def displayOr(j: Json, u: String): String =
    val d = strField(j, "displayname", "")
    if d == "" then u else d

  /** the compiled-in pair every harness in this repo logs in as; alice is the
   *  admin, so a fresh server has a usable admin with no file. No path is
   *  set, so nothing is written. */
  def useDefaults(): Unit =
    var us: List[UserCfg] = Nil
    us = UserCfg("bob", Pwhash.hash("testpass123"), "Bob", false) :: us
    us = UserCfg("alice", Pwhash.hash("testpass123"), "Alice", true) :: us
    install(us)

  def install(us: List[UserCfg]): Unit =
    cell.withLock(st => st.users = us)

  // ---- reads ----------------------------------------------------------------

  def userByLocalpart(localpart: String): Option[UserCfg] =
    cell.withLock(st => findUser(st.users, localpart))

  def findUser(us: List[UserCfg], localpart: String): Option[UserCfg] = us match
    case h :: t => findUserStep(h, t, localpart)
    case Nil  => None

  def findUserStep(h: UserCfg, t: List[UserCfg], localpart: String): Option[UserCfg] =
    if h.localpart == localpart then Some(h) else findUser(t, localpart)

  /** every configured user, in file order — `Store.init` seeds their profiles
   *  and the admin user list renders them. */
  def allUsers(): List[UserCfg] = cell.withLock(st => st.users)

  /** is this user an admin? Unknown users are not. */
  def isAdmin(localpart: String): Boolean = userByLocalpart(localpart) match
    case s: Some[UserCfg] => s.value.admin
    case None => false

  /** the file the accounts came from, "" for the built-in pair. */
  def usersPath(): String = cell.withLock(st => st.path)

  def isEmptyU(us: List[UserCfg]): Boolean = us match
    case _ :: _ => false
    case Nil  => true

  // ---- mutations (the server owns the file) ----------------------------------
  //
  // Each is ONE transaction under the config lock: reseat the list, then
  // rewrite the file from the reseated list. Writing inside the lock is what
  // makes the file and memory agree — two concurrent admin requests cannot
  // interleave a write with the other's list.

  /** create an account. False when the localpart is already taken. */
  def createUser(lp: String, password: String, display: String, admin: Boolean): Boolean =
    val u = UserCfg(lp, Pwhash.hash(password), display, admin)
    cell.withLock(st => createLocked(st, u))

  def createLocked(st: ConfigState, u: UserCfg): Boolean = findUser(st.users, u.localpart) match
    case _: Some[UserCfg] => false
    case None => commit(st, appendUser(st.users, u))

  def appendUser(us: List[UserCfg], u: UserCfg): List[UserCfg] =
    var r: List[UserCfg] = ListOps.reverse(us)
    r = u :: r
    ListOps.reverse(r)

  /** replace a password. False when the localpart names nobody. */
  def setPassword(lp: String, password: String): Boolean =
    val h = Pwhash.hash(password)
    cell.withLock(st => updateLocked(st, lp, (u: UserCfg) => UserCfg(u.localpart, h, u.displayName, u.admin)))

  /** rename the display name (the store's profile fan-out is the caller's
   *  job — this owns the file, not the room state). */
  def setDisplay(lp: String, display: String): Boolean =
    cell.withLock(st => updateLocked(st, lp, (u: UserCfg) => UserCfg(u.localpart, u.hash, display, u.admin)))

  /** grant or revoke the admin flag. */
  def setAdmin(lp: String, admin: Boolean): Boolean =
    cell.withLock(st => updateLocked(st, lp, (u: UserCfg) => UserCfg(u.localpart, u.hash, u.displayName, admin)))

  def updateLocked(st: ConfigState, lp: String, f: UserCfg => UserCfg): Boolean =
    if !hasUser(st.users, lp) then false
    else commit(st, mapUser(st.users, lp, f, Nil))

  def hasUser(us: List[UserCfg], lp: String): Boolean = findUser(us, lp) match
    case _: Some[UserCfg] => true
    case None => false

  def mapUser(us: List[UserCfg], lp: String, f: UserCfg => UserCfg, acc: List[UserCfg]): List[UserCfg] = us match
    case h :: t => mapUserStep(h, t, lp, f, acc)
    case Nil  => ListOps.reverse(acc)

  def mapUserStep(h: UserCfg, t: List[UserCfg], lp: String, f: UserCfg => UserCfg, acc: List[UserCfg]): List[UserCfg] =
    var acc2: List[UserCfg] = acc
    if h.localpart == lp then acc2 = f(h) :: acc2 else acc2 = h :: acc2
    mapUser(t, lp, f, acc2)

  /** delete an account. False when the localpart names nobody. Revoking the
   *  removed user's live sessions is the caller's job (`Store.dropUserDevices`,
   *  adminapi.scala) — this module owns the file, not the store. */
  def removeUser(lp: String): Boolean =
    cell.withLock(st => removeLocked(st, lp))

  def removeLocked(st: ConfigState, lp: String): Boolean =
    if !hasUser(st.users, lp) then false
    else commit(st, dropUser(st.users, lp, Nil))

  def dropUser(us: List[UserCfg], lp: String, acc: List[UserCfg]): List[UserCfg] = us match
    case h :: t => dropUserStep(h, t, lp, acc)
    case Nil  => ListOps.reverse(acc)

  def dropUserStep(h: UserCfg, t: List[UserCfg], lp: String, acc: List[UserCfg]): List[UserCfg] =
    var acc2: List[UserCfg] = acc
    if h.localpart == lp then acc2 = acc else acc2 = h :: acc2
    dropUser(t, lp, acc2)

  /** reseat the list and persist it (a no-op write when there is no file). */
  def commit(st: ConfigState, us: List[UserCfg]): Boolean =
    st.users = us
    if st.path != "" then writeFile(st.path, us) else ()
    true

  // ---- the file ---------------------------------------------------------------

  /** write the accounts ATOMICALLY: a temp file in the SAME directory (so the
   *  rename cannot cross a filesystem) then `os.Rename` over the target, which
   *  is atomic on every filesystem wata runs on. A reader — the next boot, or a
   *  human — sees either the old file or the new one, never a partial write.
   *  Mode 0600: the file holds password hashes. */
  def writeFile(p: String, us: List[UserCfg]): Unit =
    val tmp = p + ".tmp"
    go.osfile.writeFile(tmp, go.bytes(render(us)), 384)
    go.osfile.rename(tmp, p)

  /** one entry per line — the file stays something a human opens and edits.
   *  Built by string concatenation rather than a `StringBuilder`, which this
   *  dialect keeps function-local (DATA-10). */
  def render(us: List[UserCfg]): String = "[\n" + renderRows(us, true) + "\n]\n"

  def renderRows(us: List[UserCfg], first: Boolean): String = us match
    case h :: t => renderRowsStep(h, t, first)
    case Nil  => ""

  def renderRowsStep(h: UserCfg, t: List[UserCfg], first: Boolean): String =
    rowSep(first) + Json.write(userJson(h)) + renderRows(t, false)

  def rowSep(first: Boolean): String = if first then "  " else ",\n  "

  def userJson(u: UserCfg): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("user", JStr(u.localpart)) :: fs
    fs = ("hash", JStr(u.hash)) :: fs
    fs = ("displayname", JStr(u.displayName)) :: fs
    fs = ("admin", JBool(u.admin)) :: fs
    endObj(fs)
