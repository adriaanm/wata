import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** The server's user accounts.
 *
 *  wata PROVISIONS accounts; it does not let anyone register one. That is the
 *  trust model — the network is the boundary, and the set of people on it is
 *  decided out of band — so the accounts are configuration, read once at boot:
 *
 *  {{{
 *  WATA_USERS=/etc/wata/users.json wata-server :8008
 *  }}}
 *
 *  {{{
 *  [ {"user": "alice", "password": "…", "displayname": "Alice"},
 *    {"user": "bob",   "password": "…", "displayname": "Bob"} ]
 *  }}}
 *
 *  `displayname` is optional and defaults to the localpart; an entry with no
 *  `user` is skipped. An unset, unreadable, unparseable, or empty `WATA_USERS`
 *  falls back to the built-in alice/bob pair, so every harness and script in
 *  this repo runs unchanged with no file present. A bad file is a fallback
 *  rather than a hard failure because the alternative — a homeserver that
 *  refuses to boot — is worse for a device that has to come up on its own.
 *
 *  The loaded list lives behind its own small `Mutex`, kept separate from the
 *  store's cell (like the journal's), rather than a bare module var: logins
 *  read it from per-request goroutines, and this is what makes that read a
 *  defined one. It is written exactly once, at boot, before serving.
 */
class ConfigState:
  var users: List[UserCfg] = Nil

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
    if isEmptyU(parsed) then useDefaults() else install(parsed)

  def readAll(p: String): String =
    var out = ""
    try
      val v = go.sys.readFile(p)
      out = go.string(v)
      ()
    catch case e: sgo.GoError => out = ""
    out

  def parseUsers(raw: String): List[UserCfg] = Json.tryParse(raw) match
    case Right(j) => userList(j)
    case Left(_)  => Nil

  def userList(j: Json): List[UserCfg] = j match
    case a: JArr => collectUsers(a.items, Nil)
    case _       => Nil

  def collectUsers(xs: List[Json], acc: List[UserCfg]): List[UserCfg] = xs match
    case h :: t => collectUsers(t, prependUser(h, acc))
    case Nil  => ListOps.reverse(acc)

  def prependUser(j: Json, acc: List[UserCfg]): List[UserCfg] =
    val u = strField(j, "user", "")
    if u == "" then acc else consUser(u, j, acc)

  def consUser(u: String, j: Json, acc: List[UserCfg]): List[UserCfg] =
    var xs: List[UserCfg] = acc
    xs = UserCfg(u, strField(j, "password", ""), displayOr(j, u)) :: xs
    xs

  def displayOr(j: Json, u: String): String =
    val d = strField(j, "displayname", "")
    if d == "" then u else d

  /** the compiled-in pair every harness in this repo logs in as. */
  def useDefaults(): Unit =
    var us: List[UserCfg] = Nil
    us = UserCfg("bob", "testpass123", "Bob") :: us
    us = UserCfg("alice", "testpass123", "Alice") :: us
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

  /** every configured user, in file order — `Store.init` seeds their profiles. */
  def allUsers(): List[UserCfg] = cell.withLock(st => st.users)

  def isEmptyU(us: List[UserCfg]): Boolean = us match
    case _ :: _ => false
    case Nil  => true
