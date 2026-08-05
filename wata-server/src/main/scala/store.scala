import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** The in-memory store: ONE store, ONE coarse lock. An earlier reference
 *  implementation this was ported from was a single-threaded event loop; the
 *  coarse lock reproduces that serialization under net/http's per-request
 *  goroutines. An owning-goroutine/actor refactor is a possible future
 *  direction but isn't justified by anything observed so far.
 *
 *  All server state lives behind ONE `Mutex[StoreState]` guarded cell —
 *  `StoreState` is a plain class whose `var` fields hold immutable HashMaps /
 *  cons lists (in-place field reseats under the lock; no facade types).
 *  Exclusive-only (this dialect's `Mutex` has no RWMutex variant): a single
 *  lock over all slices is not a regression from a prior reader/writer split,
 *  since that split only bought read-read concurrency on an otherwise
 *  near-idle server — not worth a reader-parallel cell's soundness cost. The
 *  cell is a PRIVATE `val`; the public surface stays `Store.addDevice(...)`-shaped
 *  with `withLock` inside, so callers never see the lock, and every
 *  lock/unlock span lives in one module. Each mutation is ONE `withLock` = ONE
 *  transaction; the side-effect-after-unlock discipline (updateMemberProfile,
 *  notifyUser) stays OUTSIDE the block (its snapshot crosses out as a pure
 *  value).
 *
 *  State slices (auth + profile + account-data + rooms/events/aliases/media/
 *  receipts/txns + long-poll waiters), see `StoreState` below.
 */

/** The guarded store state: 14 `var` fields of a plain class held behind ONE
 *  `Mutex`. Reseats are in-place under the lock (same HashMap/cons ops); no
 *  facade types, no separate write-back API. */
class StoreState:
  var devices: HashMap[String, Device] =
    HashMap.empty[String, Device](k => sgo.hash(k), (a, b) => a == b)
  var tokens: HashMap[String, Device] =
    HashMap.empty[String, Device](k => sgo.hash(k), (a, b) => a == b)
  var profiles: HashMap[String, Profile] =
    HashMap.empty[String, Profile](k => sgo.hash(k), (a, b) => a == b)
  var acct: List[AcctData] = Nil
  var rooms: HashMap[String, Room] =
    HashMap.empty[String, Room](k => sgo.hash(k), (a, b) => a == b)
  var aliases: HashMap[String, String] =
    HashMap.empty[String, String](k => sgo.hash(k), (a, b) => a == b)
  var media: HashMap[String, MediaItem] =
    HashMap.empty[String, MediaItem](k => sgo.hash(k), (a, b) => a == b)
  var receiptList: List[Receipt] = Nil
  var txns: HashMap[String, String] =
    HashMap.empty[String, String](k => sgo.hash(k), (a, b) => a == b)
  var roomIds: List[String] = Nil
  var dmPairs: List[DmPair] = Nil
  var seq: scala.Long = 0L
  var waiters: List[Waiter] = Nil
  var waiterSeq: scala.Long = 0L
  /** when each user last called `/sync`, epoch ms — what the admin status
   *  panel reports as "last seen". Transient (never journaled): it describes
   *  this process's uptime, not the account. */
  var lastSync: HashMap[String, scala.Long] =
    HashMap.empty[String, scala.Long](k => sgo.hash(k), (a, b) => a == b)

/** A pure snapshot crossing out of `updateMemberProfile`'s `withLock` (the
 *  guarded room-id list + the user's profile, both immutable) — a named case
 *  class rather than a tuple. */
case class RoomsProfileSnap(ids: List[String], prof: Profile)

object Store:
  // the ONE guarded cell (a private val, so nothing outside this module can
  // touch the store state without going through `withLock`).
  private val cell: Mutex[StoreState] = mutex(new StoreState())

  /** load the accounts, then seed each one's default profile. Every entry point
   *  that brings the store up calls this, so the server and `SelfCheck` see the
   *  same users. A journal replay runs after and overrides where it set. */
  def init(): Unit =
    Config.load()
    val users = Config.allUsers()
    cell.withLock(st => st.profiles = seedProfiles(st.profiles, users))

  def seedProfiles(acc: HashMap[String, Profile], us: List[UserCfg]): HashMap[String, Profile] = us match
    case h :: t => seedProfiles(seedProfile(acc, h.localpart, h.displayName), t)
    case Nil  => acc

  def seedProfile(acc: HashMap[String, Profile], localpart: String, displayName: String): HashMap[String, Profile] =
    HashMap.put(acc, userIdOf(localpart), Profile(displayName, ""))

  // ---- ID helpers ------------------------------------------------------------

  def userIdOf(localpart: String): String = "@" + localpart + ":" + Config.serverName

  def localpartOf(userId: String): String =
    val c = userId.indexOf(":")
    if c < 0 then userId.substring(1) else userId.substring(1, c)

  /** crypto/rand + base64url. No UUID dependency; access tokens are formatted
   *  `syt_<localpart>_<rand>`, other IDs are base64url random. */
  def randId(n: scala.Int): String =
    var out = ""
    val buf = go.makeSlice[Byte](n)
    try
      go.crypto.rand.read(buf)
      out = go.encoding.base64.URLEncoding.encodeToString(buf)
      ()
    catch case e: sgo.GoError => ()
    out

  // ---- users -----------------------------------------------------------------

  def userByLocalpart(lp: String): Option[UserCfg] = Config.userByLocalpart(lp)

  // ---- devices / auth --------------------------------------------------------

  def createDevice(userId: String): Device =
    val localpart = localpartOf(userId)
    val token = "syt_" + localpart + "_" + randId(18)
    val deviceId = randId(6)
    val d = Device(deviceId, userId, token)
    cell.withLock { st =>
      st.devices = HashMap.put(st.devices, deviceId, d)
      st.tokens = HashMap.put(st.tokens, token, d)
      if Journal.enabled then Journal.rec(Journal.deviceOp(d)) else ()
    }
    d

  /** constant-time string equality (crypto/subtle via the app-owned
   *  `go.subtle` facade): every secret comparison — the login password check
   *  and the access-token lookup — goes through this, so timing cannot leak
   *  how much of a guess matched. */
  def ctEq(a: String, b: String): Boolean =
    go.subtle.constantTimeCompare(go.bytes(a), go.bytes(b)) == 1

  /** the token lookup is NOT a straight HashMap.get on the guess: a hash
   *  lookup's early exit is itself a timing channel, so this folds over EVERY
   *  stored token comparing each in constant time, keeping the matched KEY,
   *  and only then resolves it — the final get runs on the already-matched
   *  stored key, never on the guess. Work is constant per request in the
   *  guess and linear in the device count — family-sized here, so a handful
   *  of compares. */
  def deviceByToken(token: String): Option[Device] =
    cell.withLock(st => resolveToken(st.tokens, foldTokens(st.tokens, token)))

  /** "" when no stored token constant-time-matches the guess. */
  def foldTokens(tokens: HashMap[String, Device], token: String): String =
    HashMap.foldLeft[String, Device, String](tokens, "",
      (acc: String, k: String, d: Device) => tokenFold(acc, k, token))

  def tokenFold(acc: String, k: String, token: String): String =
    if ctEq(k, token) then k else acc

  def resolveToken(tokens: HashMap[String, Device], matched: String): Option[Device] =
    if matched == "" then None else HashMap.get(tokens, matched)

  def removeDevice(deviceId: String): Unit =
    cell.withLock(st => dropDevice(st, HashMap.get(st.devices, deviceId), deviceId))

  def dropDevice(st: StoreState, d: Option[Device], deviceId: String): Unit = d match
    case s: Some[Device] => dropDeviceGo(st, s.value, deviceId)
    case None => ()

  def dropDeviceGo(st: StoreState, d: Device, deviceId: String): Unit =
    st.tokens = HashMap.remove(st.tokens, d.accessToken)
    st.devices = HashMap.remove(st.devices, deviceId)
    if Journal.enabled then Journal.rec(Journal.rmDeviceOp(deviceId, d.accessToken)) else ()

  /** Revoke EVERY live session of one user — the store half of an admin
   *  account removal (adminapi.scala). One transaction: the devices and their
   *  tokens go (so the removed user's token is dead on the very next request,
   *  mid-session), and the user's long-poll waiters are woken in the same
   *  block, exactly as `notifyUser` does, so a client parked in `/sync` finds
   *  out now rather than at its timeout. */
  def dropUserDevices(userId: String): Unit =
    cell.withLock { st =>
      dropEach(st, userDeviceIds(st.devices, userId))
      val old = st.waiters
      st.waiters = dropUser(old, userId, Nil)
      closeUser(old, userId)
    }

  // the fold returned directly from result position: the standing proof of
  // the WATA-FOLD-RETURN-POS fix (pin 5663647).
  def userDeviceIds(devices: HashMap[String, Device], userId: String): List[String] =
    HashMap.foldLeft[String, Device, List[String]](devices, Nil,
      (acc: List[String], k: String, d: Device) => consIfUser(acc, k, d, userId))

  def consIfUser(acc: List[String], k: String, d: Device, userId: String): List[String] =
    if d.userId == userId then k :: acc else acc

  def dropEach(st: StoreState, ids: List[String]): Unit = ids match
    case h :: t => dropEachStep(st, h, t)
    case Nil  => ()

  def dropEachStep(st: StoreState, h: String, t: List[String]): Unit =
    dropDevice(st, HashMap.get(st.devices, h), h)
    dropEach(st, t)

  /** how many live devices (sessions) this user has — the admin status panel. */
  def deviceCount(userId: String): scala.Long =
    cell.withLock(st => HashMap.foldLeft[String, Device, scala.Long](st.devices, 0L,
      (acc: scala.Long, k: String, d: Device) => addIfUser(acc, d, userId)))

  def addIfUser(acc: scala.Long, d: Device, userId: String): scala.Long =
    if d.userId == userId then acc + 1L else acc

  // ---- last-seen (the admin status panel) ------------------------------------

  /** stamp "this user just synced". Called from the `/sync` handler; a plain
   *  map write, never journaled — it describes the running process. */
  def touchSync(userId: String): Unit =
    cell.withLock(st => st.lastSync = HashMap.put(st.lastSync, userId, nowMs()))

  /** when this user last synced, epoch ms; `0` if never (since boot). */
  def lastSyncMs(userId: String): scala.Long =
    cell.withLock(st => longOrZero(HashMap.get(st.lastSync, userId)))

  def longOrZero(o: Option[scala.Long]): scala.Long = o match
    case s: Some[scala.Long] => s.value
    case None => 0L

  /** every stored media item (metadata; `data` is "" in file-backed mode) —
   *  the admin status panel's count and byte total. */
  /** the fold as a withLock lambda tail: the other WATA-FOLD-RETURN-POS
   *  proof shape (pin 5663647). */
  def allMedia(): List[MediaItem] =
    cell.withLock(st => HashMap.foldLeft[String, MediaItem, List[MediaItem]](st.media, Nil,
      (acc: List[MediaItem], k: String, m: MediaItem) => consMedia(acc, m)))

  def consMedia(acc: List[MediaItem], m: MediaItem): List[MediaItem] = m :: acc

  /** how many rooms exist — the admin status panel. */
  def roomCount(): scala.Long =
    cell.withLock(st => lenOf(st.roomIds, 0L))

  def lenOf(xs: List[String], n: scala.Long): scala.Long = xs match
    case _ :: t => lenOf(t, n + 1L)
    case Nil  => n

  // ---- profiles --------------------------------------------------------------

  def getProfile(userId: String): Option[Profile] =
    cell.withLock(st => HashMap.get(st.profiles, userId))

  def setDisplayName(userId: String, name: String): Unit =
    cell.withLock { st =>
      val cur = profileOr(HashMap.get(st.profiles, userId))
      val np = Profile(name, cur.avatarUrl)
      st.profiles = HashMap.put(st.profiles, userId, np)
      if Journal.enabled then Journal.rec(Journal.profileOp(userId, np)) else ()
    }
    updateMemberProfile(userId)

  def setAvatarUrl(userId: String, url: String): Unit =
    cell.withLock { st =>
      val cur = profileOr(HashMap.get(st.profiles, userId))
      val np = Profile(cur.displayname, url)
      st.profiles = HashMap.put(st.profiles, userId, np)
      if Journal.enabled then Journal.rec(Journal.profileOp(userId, np)) else ()
    }
    updateMemberProfile(userId)

  def profileOr(p: Option[Profile]): Profile = p match
    case s: Some[Profile] => s.value
    case None => Profile("", "")

  /** rewrite the user's `m.room.member` state event in every room they've
   *  joined with the freshly-stored profile, and notify each room's members.
   *  Snapshots the room-id list + profile under the lock (both PURE values —
   *  they cross out of the block), then does the per-room reads/appends via
   *  their own transactions, outside the lock. */
  def updateMemberProfile(userId: String): Unit =
    val snap = cell.withLock(st => RoomsProfileSnap(st.roomIds, profileOr(HashMap.get(st.profiles, userId))))
    updateRooms(snap.ids, userId, snap.prof)

  def updateRooms(ids: List[String], userId: String, prof: Profile): Unit = ids match
    case h :: t => updateRoomsStep(h, t, userId, prof)
    case Nil  => ()

  def updateRoomsStep(h: String, t: List[String], userId: String, prof: Profile): Unit =
    updateOneRoom(h, userId, prof)
    updateRooms(t, userId, prof)

  def updateOneRoom(roomId: String, userId: String, prof: Profile): Unit = getMembership(roomId, userId) match
    case _: MJoin => rewriteMember(roomId, userId, prof)
    case _        => ()

  def rewriteMember(roomId: String, userId: String, prof: Profile): Unit = getRoom(roomId) match
    case s: Some[Room] => rewriteMember2(s.value, roomId, userId, prof)
    case None => ()

  def rewriteMember2(room: Room, roomId: String, userId: String, prof: Profile): Unit =
    lookupState(room.state, stateKeyOf("m.room.member", userId)) match
      case s: Some[Event] => rewriteMember3(roomId, userId, prof, s.value.content)
      case None => ()

  def rewriteMember3(roomId: String, userId: String, prof: Profile, content: Json): Unit =
    addEvent(roomId, "m.room.member", userId, mergeProfile(content, prof), true, userId, false, "", JNull())
    notifyRoomMembers(roomId)

  // ---- account data ----------------------------------------------------------

  def setAccountData(userId: String, hasRoom: Boolean, roomId: String, dtype: String, content: Json): Unit =
    cell.withLock { st =>
      st.seq = st.seq + 1L
      val item = AcctData(userId, hasRoom, roomId, dtype, content, st.seq)
      st.acct = replaceAcct(st.acct, item)
      if Journal.enabled then Journal.rec(Journal.acctOp(item)) else ()
    }
    notifyUser(userId)

  def replaceAcct(xs: List[AcctData], item: AcctData): List[AcctData] =
    if acctExists(xs, item) then acctMap(xs, item)
    else appendAcct(xs, item)

  def acctExists(xs: List[AcctData], item: AcctData): Boolean = xs match
    case h :: t => acctExistsStep(h, t, item)
    case Nil  => false

  def acctExistsStep(h: AcctData, t: List[AcctData], item: AcctData): Boolean =
    if acctSameKey(h, item) then true else acctExists(t, item)

  def acctMap(xs: List[AcctData], item: AcctData): List[AcctData] =
    acctMapGo(xs, item, Nil)

  def acctMapGo(xs: List[AcctData], item: AcctData, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => acctMapStep(h, t, item, acc)
    case Nil  => ListOps.reverse(acc)

  def acctMapStep(h: AcctData, t: List[AcctData], item: AcctData, acc: List[AcctData]): List[AcctData] =
    if acctSameKey(h, item) then acctMapGo(t, item, item :: acc)
    else acctMapGo(t, item, h :: acc)

  def appendAcct(xs: List[AcctData], item: AcctData): List[AcctData] =
    ListOps.reverse(item :: ListOps.reverse(xs))

  def getAccountData(userId: String, hasRoom: Boolean, roomId: String, dtype: String): Option[AcctData] =
    val probe = AcctData(userId, hasRoom, roomId, dtype, JNull(), 0L)
    cell.withLock(st => findAcct(st.acct, probe))

  def findAcct(xs: List[AcctData], probe: AcctData): Option[AcctData] = xs match
    case h :: t => findAcctStep(h, t, probe)
    case Nil  => None

  def findAcctStep(h: AcctData, t: List[AcctData], probe: AcctData): Option[AcctData] =
    if acctSameKey(h, probe) then Some(h) else findAcct(t, probe)

  def acctSameKey(a: AcctData, b: AcctData): Boolean =
    a.userId == b.userId && a.dtype == b.dtype && sameRoom(a, b)

  def sameRoom(a: AcctData, b: AcctData): Boolean =
    if a.hasRoom then bothRoom(a, b) else !b.hasRoom

  def bothRoom(a: AcctData, b: AcctData): Boolean =
    if b.hasRoom then a.roomId == b.roomId else false

  // ---- long-poll waiters ------------------------------------------------------
  //
  // The wake channel is CLOSE-SIGNALLED, never sent to: this dialect's channel
  // surface has a non-blocking receive but no non-blocking send, and a
  // blocking send would couple the notifying goroutine to the waiter's drain
  // (and deadlock a second notify on a size-1 buffer). `close` never blocks and
  // its effect is persistent (a later `recv`/`select` still observes it), so it
  // gives the same effect as a buffered(1) non-blocking send without needing
  // one. A channel is closed AT MOST ONCE because the waiter is REMOVED from
  // the shared list under the write lock before closing: whoever removes a
  // waiter under the lock OWNS it (notify removes+closes; `removeWaiter`, the
  // timer path, removes+discards — a waiter's own channel needs no close from
  // that path). This is what makes the no-lost-wake argument below hold.
  //
  // The waiter list holds `Chan`s, which cannot leave the `withLock` block, so
  // `notifyUser` CLOSES the dropped channels INSIDE the block (closing a
  // channel cannot block, so this is safe under the lock).

  /** Register a waiter for `userId`, returning a handle whose `ch` the caller
   *  selects on. The channel is unbuffered; the registration is the store-commit
   *  that the no-lost-wake argument pivots on (it precedes the caller's second
   *  emptiness check). The returned `Waiter` (which holds a `Chan` and so can't
   *  leave the lock) is rebuilt OUTSIDE the block from the guarded seq — the
   *  block itself returns only the pure `id`. */
  def registerWaiter(userId: String): Waiter =
    val ch = sgo.makeChan[Boolean]()
    val id = cell.withLock { st =>
      st.waiterSeq = st.waiterSeq + 1L
      st.waiters = Waiter(st.waiterSeq, userId, ch) :: st.waiters
      st.waiterSeq
    }
    Waiter(id, userId, ch)

  /** Remove a waiter by id (the timer-expiry path). Idempotent: if `notifyUser`
   *  already removed+closed it, this finds nothing. Never closes — the caller
   *  discards its own channel. */
  def removeWaiter(id: scala.Long): Unit =
    cell.withLock(st => st.waiters = dropId(st.waiters, id, Nil))

  def dropId(xs: List[Waiter], id: scala.Long, acc: List[Waiter]): List[Waiter] = xs match
    case h :: t => dropIdStep(h, t, id, acc)
    case Nil  => ListOps.reverse(acc)

  def dropIdStep(h: Waiter, t: List[Waiter], id: scala.Long, acc: List[Waiter]): List[Waiter] =
    var acc2: List[Waiter] = acc
    if h.id == id then acc2 = acc else acc2 = h :: acc2
    dropId(t, id, acc2)

  /** Wake every waiter for `userId`. Under the lock: snapshot the current waiter
   *  list, drop this user's waiters from the shared list (so a concurrent
   *  notify/timer never touches the same waiter), and CLOSE each dropped channel
   *  — all in the one transaction (the waiter list can't leave the block, and
   *  `close` cannot block, so closing under the lock is safe). */
  def notifyUser(userId: String): Unit =
    cell.withLock { st =>
      val old = st.waiters
      st.waiters = dropUser(old, userId, Nil)
      closeUser(old, userId)
    }

  def dropUser(xs: List[Waiter], userId: String, acc: List[Waiter]): List[Waiter] = xs match
    case h :: t => dropUserStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def dropUserStep(h: Waiter, t: List[Waiter], userId: String, acc: List[Waiter]): List[Waiter] =
    var acc2: List[Waiter] = acc
    if h.userId == userId then acc2 = acc else acc2 = h :: acc2
    dropUser(t, userId, acc2)

  def closeUser(xs: List[Waiter], userId: String): Unit = xs match
    case h :: t => closeUserStep(h, t, userId)
    case Nil  => ()

  def closeUserStep(h: Waiter, t: List[Waiter], userId: String): Unit =
    if h.userId == userId then h.ch.close() else ()
    closeUser(t, userId)

  /** Block the calling (request) goroutine until the waiter's channel is closed
   *  (a wake) OR the timer fires — a real Go `select` over {waiter, timer}.
   *  Neither arm's value is used; the caller rebuilds the sync response
   *  afterwards. NO lock is held here — the select can block, and channels must
   *  never be waited on while holding the store lock. */
  def waitForEvents(w: Waiter, timeoutMs: scala.Int): Unit =
    sgo.select2(w.ch, go.time.After(go.time.milliseconds(timeoutMs)))(
      (b: Boolean) => (),
      (tm: go.time.Time) => ())

  // ---- ID generation ----------------------------------------------------------

  def genRoomId(): String = "!" + randId(9) + ":" + Config.serverName
  def genEventId(): String = "$" + randId(9) + ":" + Config.serverName
  def genMediaId(): String = randId(18)

  // ---- state-map key ---------------------------------------------------------

  /** the `${type}\0${stateKey}` composite key, `|SK|`-separated (no NUL — avoids
   *  a control char in an emitted Go literal; neither operand contains `|SK|`). */
  def stateKeyOf(etype: String, sk: String): String = etype + "|SK|" + sk

  // ---- rooms -----------------------------------------------------------------

  def createRoom(): String =
    val id = genRoomId()
    cell.withLock { st =>
      st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil, Nil))
      st.roomIds = id :: st.roomIds
      if Journal.enabled then Journal.rec(Journal.roomOp(id)) else ()
    }
    id

  def getRoom(roomId: String): Option[Room] =
    cell.withLock(st => HashMap.get(st.rooms, roomId))

  /** every room, OLDEST FIRST (`roomIds` is newest-first). The boot migration
   *  (dm.scala) scans in this order, which is what makes "oldest wins" fall
   *  out of a never-overwrite register. */
  def allRooms(): List[Room] =
    cell.withLock(st => collectAll(st, ListOps.reverse(st.roomIds), Nil))

  def collectAll(st: StoreState, ids: List[String], acc: List[Room]): List[Room] = ids match
    case h :: t => collectAllStep(st, h, t, acc)
    case Nil  => ListOps.reverse(acc)

  def collectAllStep(st: StoreState, h: String, t: List[String], acc: List[Room]): List[Room] =
    var acc2: List[Room] = acc
    acc2 = consRoom(HashMap.get(st.rooms, h), acc2)
    collectAll(st, t, acc2)

  def consRoom(ro: Option[Room], acc: List[Room]): List[Room] = ro match
    case s: Some[Room] => s.value :: acc
    case None => acc

  // ---- canonical DM pairs -----------------------------------------------------
  //
  // A DM is identified by its unordered user pair, so the pair is the KEY and
  // uniqueness is a lookup rather than a distributed protocol: the whole
  // get-or-create — lookup, room mint, alias, every seed state event, and the
  // pair registration — happens in ONE `withLock` transaction, so two
  // concurrent first-sends from the two sides cannot mint two rooms. Pairs are
  // stored SORTED (`a < b`), so the two orderings are one entry.

  /** lexicographic `<` over the byte-wise chars. The subset has no string
   *  ordering operator, and only the ORDER matters here (it decides which of
   *  the two spellings of a pair is canonical), not the collation. */
  def strLess(a: String, b: String): Boolean = strLessAt(a, b, 0)

  def strLessAt(a: String, b: String, i: scala.Int): Boolean =
    if i >= a.length then i < b.length
    else if i >= b.length then false
    else strLessChar(a, b, i)

  def strLessChar(a: String, b: String, i: scala.Int): Boolean =
    val ca = a.charAt(i)
    val cb = b.charAt(i)
    if ca < cb then true
    else if ca > cb then false
    else strLessAt(a, b, i + 1)

  def pairLo(a: String, b: String): String = if strLess(a, b) then a else b
  def pairHi(a: String, b: String): String = if strLess(a, b) then b else a

  /** the canonical room for this pair, if one has been claimed. */
  def dmRoomFor(a: String, b: String): Option[String] =
    cell.withLock(st => roomOfPair(findPair(st.dmPairs, pairLo(a, b), pairHi(a, b))))

  def roomOfPair(p: Option[DmPair]): Option[String] = p match
    case s: Some[DmPair] => Some(s.value.roomId)
    case None => None

  def findPair(xs: List[DmPair], lo: String, hi: String): Option[DmPair] = xs match
    case h :: t => findPairStep(h, t, lo, hi)
    case Nil  => None

  def findPairStep(h: DmPair, t: List[DmPair], lo: String, hi: String): Option[DmPair] =
    if h.a == lo && h.b == hi then Some(h) else findPair(t, lo, hi)

  /** every canonical DM this user is in, from their side. The compat
   *  projection (dm.scala) re-asserts these over the stored `m.direct`. */
  def dmPeersOf(userId: String): List[DmPeer] =
    cell.withLock(st => peersOf(st.dmPairs, userId, Nil))

  def peersOf(xs: List[DmPair], userId: String, acc: List[DmPeer]): List[DmPeer] = xs match
    case h :: t => peersOfStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def peersOfStep(h: DmPair, t: List[DmPair], userId: String, acc: List[DmPeer]): List[DmPeer] =
    var acc2: List[DmPeer] = acc
    if h.a == userId then acc2 = DmPeer(h.b, h.roomId) :: acc2
    else if h.b == userId then acc2 = DmPeer(h.a, h.roomId) :: acc2
    else ()
    peersOf(t, userId, acc2)

  /** THE get-or-create: one transaction over the pair map. When the pair is
   *  already claimed the existing room comes straight back; otherwise the room
   *  is minted, its alias registered, every seed state event written, and the
   *  pair recorded — all before the lock is released, so nothing observes a
   *  half-built DM and no second room can appear for the same pair. */
  def dmGetOrCreate(a: String, b: String, sender: String, alias: String, seeds: List[StateSeed]): DmRoom =
    cell.withLock(st => dmGetOrCreateLocked(st, pairLo(a, b), pairHi(a, b), sender, alias, seeds))

  def dmGetOrCreateLocked(st: StoreState, lo: String, hi: String, sender: String,
                          alias: String, seeds: List[StateSeed]): DmRoom =
    findPair(st.dmPairs, lo, hi) match
      case s: Some[DmPair] => DmRoom(s.value.roomId, false)
      case None => dmCreateLocked(st, lo, hi, sender, alias, seeds)

  def dmCreateLocked(st: StoreState, lo: String, hi: String, sender: String,
                     alias: String, seeds: List[StateSeed]): DmRoom =
    val id = genRoomId()
    st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil, Nil))
    st.roomIds = id :: st.roomIds
    if Journal.enabled then Journal.rec(Journal.roomOp(id)) else ()
    st.aliases = HashMap.put(st.aliases, alias, id)
    if Journal.enabled then Journal.rec(Journal.aliasOp(alias, id)) else ()
    seedEvents(st, id, sender, seeds)
    putPairLocked(st, DmPair(lo, hi, id))
    DmRoom(id, true)

  def seedEvents(st: StoreState, roomId: String, sender: String, seeds: List[StateSeed]): Unit = seeds match
    case h :: t => seedStep(st, roomId, sender, h, t)
    case Nil  => ()

  def seedStep(st: StoreState, roomId: String, sender: String, h: StateSeed, t: List[StateSeed]): Unit =
    addEventLocked(st, roomId, h.etype, sender, h.content, true, h.sk, false, "", JNull())
    seedEvents(st, roomId, sender, t)

  def putPairLocked(st: StoreState, p: DmPair): Unit =
    st.dmPairs = appendPair(st.dmPairs, p)
    if Journal.enabled then Journal.rec(Journal.dmPairOp(p)) else ()

  def appendPair(xs: List[DmPair], p: DmPair): List[DmPair] =
    ListOps.reverse(p :: ListOps.reverse(xs))

  /** claim a pair for a room minted elsewhere (the boot migration). Never
   *  overwrites: the FIRST room to claim a pair is the canonical one, and the
   *  migration scans oldest-first, so the oldest room wins and the losers stay
   *  joined but unmapped. Returns whether this call did the claiming. */
  def registerDmPair(a: String, b: String, roomId: String): Boolean =
    cell.withLock(st => registerLocked(st, pairLo(a, b), pairHi(a, b), roomId))

  def registerLocked(st: StoreState, lo: String, hi: String, roomId: String): Boolean =
    findPair(st.dmPairs, lo, hi) match
      case _: Some[DmPair] => false
      case None => claimPair(st, lo, hi, roomId)

  def claimPair(st: StoreState, lo: String, hi: String, roomId: String): Boolean =
    putPairLocked(st, DmPair(lo, hi, roomId))
    true

  // ---- the canonical family room / groups (plan 0018) -------------------------
  //
  // The same one-transaction shape as the DM pair map, keyed differently: the
  // family room by its `net.wata.family` stamp (falling back to the
  // `#family:<server>` alias for a room that predates the stamp), a group by
  // the `name` in its `net.wata.group` stamp. No dedicated slice and no new
  // journal op: the stamps are ordinary state events, so a replayed journal
  // reconstructs the keys by itself and these lookups just scan the rooms —
  // family-sized, so a scan is not a scaling concern.

  /** THE family-room get-or-create, one transaction: a room already stamped
   *  `net.wata.family` wins (oldest first, the `Dm.migrate` rule); else the
   *  room the alias names is STAMPED IN PLACE rather than duplicated; else a
   *  room is minted from `seeds` and the alias registered. */
  def familyGetOrCreate(alias: String, sender: String, stamp: StateSeed, seeds: List[StateSeed]): DmRoom =
    cell.withLock(st => familyLocked(st, alias, sender, stamp, seeds))

  def familyLocked(st: StoreState, alias: String, sender: String, stamp: StateSeed, seeds: List[StateSeed]): DmRoom =
    findStamped(st, ListOps.reverse(st.roomIds), "net.wata.family") match
      case s: Some[String] => DmRoom(s.value, false)
      case None => familyByAlias(st, alias, sender, stamp, seeds)

  def familyByAlias(st: StoreState, alias: String, sender: String, stamp: StateSeed, seeds: List[StateSeed]): DmRoom =
    HashMap.get(st.aliases, alias) match
      case s: Some[String] => stampInPlace(st, s.value, sender, stamp)
      case None => mintFamily(st, alias, sender, seeds)

  def stampInPlace(st: StoreState, roomId: String, sender: String, stamp: StateSeed): DmRoom =
    addEventLocked(st, roomId, stamp.etype, sender, stamp.content, true, stamp.sk, false, "", JNull())
    DmRoom(roomId, false)

  def mintFamily(st: StoreState, alias: String, sender: String, seeds: List[StateSeed]): DmRoom =
    val id = mintSeeded(st, sender, seeds)
    st.aliases = HashMap.put(st.aliases, alias, id)
    if Journal.enabled then Journal.rec(Journal.aliasOp(alias, id)) else ()
    DmRoom(id, true)

  /** THE group get-or-create, one transaction: the oldest room whose
   *  `net.wata.group` stamp carries this `name` wins; else a room is minted
   *  from `seeds` (no alias — groups are reached through the stamp alone). */
  def groupGetOrCreate(name: String, sender: String, seeds: List[StateSeed]): DmRoom =
    cell.withLock(st => groupLocked(st, name, sender, seeds))

  def groupLocked(st: StoreState, name: String, sender: String, seeds: List[StateSeed]): DmRoom =
    findGroupNamed(st, ListOps.reverse(st.roomIds), name) match
      case s: Some[String] => DmRoom(s.value, false)
      case None => DmRoom(mintSeeded(st, sender, seeds), true)

  /** mint a room and write its seed state, all under the caller's lock. */
  def mintSeeded(st: StoreState, sender: String, seeds: List[StateSeed]): String =
    val id = genRoomId()
    st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil, Nil))
    st.roomIds = id :: st.roomIds
    if Journal.enabled then Journal.rec(Journal.roomOp(id)) else ()
    seedEvents(st, id, sender, seeds)
    id

  /** the OLDEST room carrying a `etype` stamp (`ids` arrives oldest-first). */
  def findStamped(st: StoreState, ids: List[String], etype: String): Option[String] = ids match
    case h :: t => findStampedStep(st, h, t, etype)
    case Nil  => None

  def findStampedStep(st: StoreState, h: String, t: List[String], etype: String): Option[String] =
    if roomHasState(st, h, etype) then Some(h) else findStamped(st, t, etype)

  def roomHasState(st: StoreState, roomId: String, etype: String): Boolean =
    HashMap.get(st.rooms, roomId) match
      case s: Some[Room] => stateHasKey(s.value.state, stateKeyOf(etype, ""))
      case None => false

  def stateHasKey(state: List[(String, Event)], key: String): Boolean = lookupState(state, key) match
    case _: Some[Event] => true
    case None => false

  /** the OLDEST room whose `net.wata.group` stamp names `name`. */
  def findGroupNamed(st: StoreState, ids: List[String], name: String): Option[String] = ids match
    case h :: t => findGroupStep(st, h, t, name)
    case Nil  => None

  def findGroupStep(st: StoreState, h: String, t: List[String], name: String): Option[String] =
    if groupNameLocked(st, h) == name then Some(h) else findGroupNamed(st, t, name)

  def groupNameLocked(st: StoreState, roomId: String): String =
    HashMap.get(st.rooms, roomId) match
      case s: Some[Room] => groupNameIn(s.value.state)
      case None => ""

  def groupNameIn(state: List[(String, Event)]): String =
    lookupState(state, stateKeyOf("net.wata.group", "")) match
      case s: Some[Event] => strField(s.value.content, "name", "")
      case None => ""

  /** a room's `net.wata.group` stamp name, or "" (not a group). */
  def groupNameOf(roomId: String): String =
    cell.withLock(st => groupNameLocked(st, roomId))

  def stateContent(room: Room, etype: String, sk: String): Option[Json] =
    lookupState(room.state, stateKeyOf(etype, sk)) match
      case s: Some[Event] => Some(s.value.content)
      case None => None

  def lookupState(state: List[(String, Event)], key: String): Option[Event] = state match
    case p :: t => lookupStateStep(p, t, key)
    case Nil  => None

  def lookupStateStep(p: (String, Event), t: List[(String, Event)], key: String): Option[Event] =
    val k: String = p._1
    val ev: Event = p._2
    if k == key then Some(ev) else lookupState(t, key)

  // ---- membership ------------------------------------------------------------

  def getMembership(roomId: String, userId: String): Membership =
    cell.withLock(st => membershipLocked(st, roomId, userId))

  def membershipLocked(st: StoreState, roomId: String, userId: String): Membership = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => memInState(s.value.state, stateKeyOf("m.room.member", userId))
    case None => MNone()

  def memInState(state: List[(String, Event)], key: String): Membership = lookupState(state, key) match
    case s: Some[Event] => Mem.parse(strField(s.value.content, "membership", ""))
    case None => MNone()

  // ---- events ----------------------------------------------------------------

  /** append an event; if it carries a state key, update the room's state map.
   *  Returns `None` if the room is gone. Under the write lock. */
  def addEvent(roomId: String, etype: String, sender: String, content: Json,
               hasSK: Boolean, sk: String, hasRedacts: Boolean, redacts: String, unsigned: Json): Option[Event] =
    cell.withLock(st => addEventLocked(st, roomId, etype, sender, content, hasSK, sk, hasRedacts, redacts, unsigned))

  def addEventLocked(st: StoreState, roomId: String, etype: String, sender: String, content: Json,
                     hasSK: Boolean, sk: String, hasRedacts: Boolean, redacts: String, unsigned: Json): Option[Event] =
    HashMap.get(st.rooms, roomId) match
      case s: Some[Room] => addEventTo(st, s.value, roomId, etype, sender, content, hasSK, sk, hasRedacts, redacts, unsigned)
      case None => None

  def addEventTo(st: StoreState, room: Room, roomId: String, etype: String, sender: String, content: Json,
                 hasSK: Boolean, sk: String, hasRedacts: Boolean, redacts: String, unsigned: Json): Option[Event] =
    st.seq = st.seq + 1L
    val ev = Event(genEventId(), etype, sender, roomId, nowMs(), content, hasSK, sk, hasRedacts, redacts, unsigned, st.seq)
    st.rooms = HashMap.put(st.rooms, roomId,
      Room(room.roomId, room.version, addToState(room.state, hasSK, stateKeyOf(etype, sk), ev), appendEnd(room.timeline, ev)))
    if Journal.enabled then Journal.rec(Journal.eventOp(ev)) else ()
    Some(ev)

  def addToState(state: List[(String, Event)], hasSK: Boolean, key: String, ev: Event): List[(String, Event)] =
    if hasSK then putState(state, key, ev) else state

  def putState(state: List[(String, Event)], key: String, ev: Event): List[(String, Event)] =
    if stateHas(state, key) then stateReplace(state, key, ev, Nil)
    else appendEndS(state, key, ev)

  def stateHas(state: List[(String, Event)], key: String): Boolean = state match
    case p :: t => stateHasStep(p, t, key)
    case Nil  => false

  def stateHasStep(p: (String, Event), t: List[(String, Event)], key: String): Boolean =
    val k: String = p._1
    if k == key then true else stateHas(t, key)

  def stateReplace(state: List[(String, Event)], key: String, ev: Event, acc: List[(String, Event)]): List[(String, Event)] = state match
    case p :: t => stateReplaceStep(p, t, key, ev, acc)
    case Nil  => ListOps.reverse(acc)

  def stateReplaceStep(p: (String, Event), t: List[(String, Event)], key: String, ev: Event, acc: List[(String, Event)]): List[(String, Event)] =
    val k: String = p._1
    var acc2: List[(String, Event)] = acc
    if k == key then acc2 = (key, ev) :: acc2 else acc2 = p :: acc2
    stateReplace(t, key, ev, acc2)

  def appendEndS(state: List[(String, Event)], key: String, ev: Event): List[(String, Event)] =
    var r: List[(String, Event)] = ListOps.reverse(state)
    r = (key, ev) :: r
    ListOps.reverse(r)

  def appendEnd(xs: List[Event], ev: Event): List[Event] =
    ListOps.reverse(ev :: ListOps.reverse(xs))

  def getEventById(roomId: String, eventId: String): Option[Event] =
    cell.withLock(st => eventByIdLocked(st, roomId, eventId))

  def eventByIdLocked(st: StoreState, roomId: String, eventId: String): Option[Event] = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => findEv(s.value.timeline, eventId)
    case None => None

  def findEv(xs: List[Event], eventId: String): Option[Event] = xs match
    case h :: t => findEvStep(h, t, eventId)
    case Nil  => None

  def findEvStep(h: Event, t: List[Event], eventId: String): Option[Event] =
    if h.eventId == eventId then Some(h) else findEv(t, eventId)

  /** redact the target event: content -> `{}`, `unsigned.redacted_because` -> the
   *  redaction event. Rebuilds both the timeline and the state map (these are
   *  immutable persistent structures, so this is a replace, not an in-place
   *  mutation). Under the write lock. */
  def redactTarget(roomId: String, eventId: String, red: Event): Unit =
    cell.withLock(st => redactLocked(st, roomId, eventId, red))

  def redactLocked(st: StoreState, roomId: String, eventId: String, red: Event): Unit = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => redactRoom(st, s.value, roomId, eventId, red)
    case None => ()

  def redactRoom(st: StoreState, room: Room, roomId: String, eventId: String, red: Event): Unit =
    reclaimMedia(st, findEv(room.timeline, eventId))
    st.rooms = HashMap.put(st.rooms, roomId,
      Room(room.roomId, room.version, redactState(room.state, eventId, red, Nil),
        redactTL(room.timeline, eventId, red, Nil)))
    if Journal.enabled then Journal.rec(Journal.redactOp(roomId, eventId, red.eventId)) else ()

  /** redacting a media message reclaims its blob: drop the metadata entry and
   *  delete the file (the event is the only referrer — media ids are not
   *  shared). Runs inside the redact transaction, before the content is
   *  emptied; keyed on the target's `url` field, so non-media redactions and
   *  already-redacted targets (content `{}`) fall through. Idempotent across
   *  a journal replay (file delete of a missing file is a no-op). */
  def reclaimMedia(st: StoreState, target: Option[Event]): Unit = target match
    case s: Some[Event] => reclaimMediaOf(st, mediaIdOfMxc(strField(s.value.content, "url", "")))
    case None => ()

  def reclaimMediaOf(st: StoreState, mediaId: String): Unit =
    if mediaId != "" && mediaKnown(HashMap.get(st.media, mediaId)) then reclaimMediaGo(st, mediaId)
    else ()

  def reclaimMediaGo(st: StoreState, mediaId: String): Unit =
    st.media = HashMap.remove(st.media, mediaId)
    if MediaFiles.enabled then MediaFiles.delete(mediaId) else ()

  def redactTL(xs: List[Event], eventId: String, red: Event, acc: List[Event]): List[Event] = xs match
    case h :: t => redactTLStep(h, t, eventId, red, acc)
    case Nil  => ListOps.reverse(acc)

  def redactTLStep(h: Event, t: List[Event], eventId: String, red: Event, acc: List[Event]): List[Event] =
    if h.eventId == eventId then redactTL(t, eventId, red, redactedCopy(h, red) :: acc)
    else redactTL(t, eventId, red, h :: acc)

  def redactState(state: List[(String, Event)], eventId: String, red: Event, acc: List[(String, Event)]): List[(String, Event)] = state match
    case p :: t => redactStateStep(p, t, eventId, red, acc)
    case Nil  => ListOps.reverse(acc)

  def redactStateStep(p: (String, Event), t: List[(String, Event)], eventId: String, red: Event, acc: List[(String, Event)]): List[(String, Event)] =
    val k: String = p._1
    val ev: Event = p._2
    var acc2: List[(String, Event)] = acc
    if ev.eventId == eventId then acc2 = (k, redactedCopy(ev, red)) :: acc2 else acc2 = p :: acc2
    redactState(t, eventId, red, acc2)

  def redactedCopy(ev: Event, red: Event): Event =
    Event(ev.eventId, ev.etype, ev.sender, ev.roomId, ev.ts, emptyObj,
      ev.hasStateKey, ev.stateKey, ev.hasRedacts, ev.redacts, jsonSet(ev.unsigned, "redacted_because", eventToJson(red)), ev.seq)

  // ---- aliases ---------------------------------------------------------------

  def setAlias(alias: String, roomId: String): Unit =
    cell.withLock { st =>
      st.aliases = HashMap.put(st.aliases, alias, roomId)
      if Journal.enabled then Journal.rec(Journal.aliasOp(alias, roomId)) else ()
    }

  def getRoomIdByAlias(alias: String): Option[String] =
    cell.withLock(st => HashMap.get(st.aliases, alias))

  // ---- media --------------------------------------------------------------
  //
  // File-backed when persistence is on (MediaFiles, mediafiles.scala): the
  // blob file is written BEFORE the store/journal transaction, so a crash
  // between the two leaves an orphan file, never a journal ref to a missing
  // blob. The store then holds METADATA only (`data` = ""), and the journal op
  // carries `{media_id, content_type, size}`. Stateless runs (no WATA_LOG /
  // WATA_DATA) keep the bytes in memory exactly as before.

  def storeMedia(data: String, contentType: String): String =
    val id = genMediaId()
    if MediaFiles.enabled then MediaFiles.write(id, data) else ()
    cell.withLock { st =>
      st.media = HashMap.put(st.media, id, mediaMeta(id, data, contentType))
      if Journal.enabled then Journal.rec(Journal.mediaOp(id, contentType, data.length.toLong)) else ()
    }
    id

  /** metadata-only in file-backed mode; the full item otherwise. */
  def mediaMeta(id: String, data: String, contentType: String): MediaItem =
    if MediaFiles.enabled then MediaItem(id, "", contentType)
    else MediaItem(id, data, contentType)

  def getMedia(mediaId: String): Option[MediaItem] =
    loadMedia(cell.withLock(st => HashMap.get(st.media, mediaId)))

  /** in file-backed mode, read the blob on demand (voice blobs are ~15 KB; no
   *  cache until profiling says so). A metadata entry whose file is gone
   *  serves a 404, same as an unknown id. */
  def loadMedia(m: Option[MediaItem]): Option[MediaItem] = m match
    case s: Some[MediaItem] => loadMediaItem(s.value)
    case None => None

  def loadMediaItem(mi: MediaItem): Option[MediaItem] =
    if MediaFiles.enabled then loadMediaFile(mi)
    else Some(mi)

  def loadMediaFile(mi: MediaItem): Option[MediaItem] = MediaFiles.load(mi.mediaId) match
    case s: Some[String] => Some(MediaItem(mi.mediaId, s.value, mi.contentType))
    case None => None

  def hasMedia(mediaId: String): Boolean =
    cell.withLock(st => mediaKnown(HashMap.get(st.media, mediaId)))

  def mediaKnown(m: Option[MediaItem]): Boolean = m match
    case _: Some[MediaItem] => true
    case None => false

  /** the media id of an `mxc://<server>/<mediaId>` url, "" when it isn't one.
   *  Only the LAST path segment matters — wata's own uploads mint the url, so
   *  the server part is ours by construction. */
  def mediaIdOfMxc(url: String): String =
    if url.startsWith("mxc://") then afterLastSlash(url)
    else ""

  def afterLastSlash(s: String): String =
    val i = MediaFiles.lastSlash(s, 0, -1)
    if i < 0 || i + 1 >= s.length then "" else s.substring(i + 1)

  // ---- receipts --------------------------------------------------------------

  def setReceipt(roomId: String, receiptType: String, userId: String, eventId: String): Unit =
    cell.withLock { st =>
      st.seq = st.seq + 1L
      val rc = Receipt(roomId, userId, eventId, nowMs(), receiptType, st.seq)
      st.receiptList = replaceReceipt(st.receiptList, rc)
      if Journal.enabled then Journal.rec(Journal.receiptOp(rc)) else ()
    }

  def replaceReceipt(xs: List[Receipt], rc: Receipt): List[Receipt] =
    if receiptHas(xs, rc) then receiptMap(xs, rc, Nil)
    else appendReceipt(xs, rc)

  def appendReceipt(xs: List[Receipt], rc: Receipt): List[Receipt] =
    var r: List[Receipt] = ListOps.reverse(xs)
    r = rc :: r
    ListOps.reverse(r)

  def receiptHas(xs: List[Receipt], rc: Receipt): Boolean = xs match
    case h :: t => receiptHasStep(h, t, rc)
    case Nil  => false

  def receiptHasStep(h: Receipt, t: List[Receipt], rc: Receipt): Boolean =
    if sameReceiptKey(h, rc) then true else receiptHas(t, rc)

  def receiptMap(xs: List[Receipt], rc: Receipt, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => receiptMapStep(h, t, rc, acc)
    case Nil  => ListOps.reverse(acc)

  def receiptMapStep(h: Receipt, t: List[Receipt], rc: Receipt, acc: List[Receipt]): List[Receipt] =
    if sameReceiptKey(h, rc) then receiptMap(t, rc, rc :: acc)
    else receiptMap(t, rc, h :: acc)

  def sameReceiptKey(a: Receipt, b: Receipt): Boolean =
    a.roomId == b.roomId && a.userId == b.userId && a.receiptType == b.receiptType

  // ---- per-device txnId idempotency ------------------------------------------

  def txnKey(deviceId: String, txnId: String): String = deviceId + "|SK|" + txnId

  def getTxn(deviceId: String, txnId: String): Option[String] =
    cell.withLock(st => HashMap.get(st.txns, txnKey(deviceId, txnId)))

  def setTxn(deviceId: String, txnId: String, eventId: String): Unit =
    val key = txnKey(deviceId, txnId)
    cell.withLock { st =>
      st.txns = HashMap.put(st.txns, key, eventId)
      if Journal.enabled then Journal.rec(Journal.txnOp(key, eventId)) else ()
    }

  // ---- notify fan-out ---------------------------------------------------------

  /** notify every join/invite member of a room by waking their long-poll
   *  waiters via `notifyUser`. */
  def notifyRoomMembers(roomId: String): Unit = getRoom(roomId) match
    case s: Some[Room] => notifyMembers(s.value.state)
    case None => ()

  def notifyMembers(state: List[(String, Event)]): Unit = state match
    case p :: t => notifyMembersStep(p, t)
    case Nil  => ()

  def notifyMembersStep(p: (String, Event), t: List[(String, Event)]): Unit =
    val ev: Event = p._2
    notifyIfMember(ev)
    notifyMembers(t)

  def notifyIfMember(ev: Event): Unit =
    if ev.etype == "m.room.member" && isJoinOrInvite(strField(ev.content, "membership", "")) then notifyUser(ev.stateKey)
    else ()

  def isJoinOrInvite(m: String): Boolean = m == "join" || m == "invite"

  // ---- wall clock (origin_server_ts) -----------------------------------------

  /** int64 epoch ms. Always inline (never val-bound to a temp), so the
   *  compiler emits `time.Now().UnixMilli()` directly. */
  def nowMs(): scala.Long = go.time.nowUnixMilli()

  // ---- /sync read accessors ----------------------------------------------------
  //
  // Each takes ONE lock and snapshots an immutable value (the store's data is
  // persistent case classes / cons lists), so /sync assembles from a
  // consistent per-call snapshot per accessor. A /sync that reads across
  // several such accessors can see a torn view under concurrent writes; this
  // is invisible to sequential testing and self-corrects (the client re-syncs
  // from next_batch), so an actor-based refactor to close that gap isn't
  // currently justified.

  def globalSeq(): scala.Long =
    cell.withLock(st => st.seq)

  /** rooms whose membership for `userId` equals `want` ("join"/"invite"), in
   *  stable creation order (roomIds is newest-first, so reverse gives
   *  oldest-first). */
  def roomsForUser(userId: String, want: String): List[Room] =
    cell.withLock(st => collectRooms(st, ListOps.reverse(st.roomIds), userId, want, Nil))

  def collectRooms(st: StoreState, ids: List[String], userId: String, want: String, acc: List[Room]): List[Room] = ids match
    case h :: t => collectRoomsStep(st, h, t, userId, want, acc)
    case Nil  => ListOps.reverse(acc)

  def collectRoomsStep(st: StoreState, h: String, t: List[String], userId: String, want: String, acc: List[Room]): List[Room] =
    var acc2: List[Room] = acc
    acc2 = keepIfMember(HashMap.get(st.rooms, h), userId, want, acc2)
    collectRooms(st, t, userId, want, acc2)

  def keepIfMember(ro: Option[Room], userId: String, want: String, acc: List[Room]): List[Room] = ro match
    case s: Some[Room] => keepIfMember2(s.value, userId, want, acc)
    case None => acc

  def keepIfMember2(room: Room, userId: String, want: String, acc: List[Room]): List[Room] =
    if membershipStrOf(room, userId) == want then room :: acc else acc

  def membershipStrOf(room: Room, userId: String): String =
    Mem.str(memInState(room.state, stateKeyOf("m.room.member", userId)))

  /** rooms this user has left or been banned from — /sync's `leave` block.
   *  One pass over the same id list, since "left" is two membership values. */
  def roomsLeftBy(userId: String): List[Room] =
    cell.withLock(st => collectLeft(st, ListOps.reverse(st.roomIds), userId, Nil))

  def collectLeft(st: StoreState, ids: List[String], userId: String, acc: List[Room]): List[Room] = ids match
    case h :: t => collectLeftStep(st, h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def collectLeftStep(st: StoreState, h: String, t: List[String], userId: String, acc: List[Room]): List[Room] =
    var acc2: List[Room] = acc
    acc2 = keepIfLeft(HashMap.get(st.rooms, h), userId, acc2)
    collectLeft(st, t, userId, acc2)

  def keepIfLeft(ro: Option[Room], userId: String, acc: List[Room]): List[Room] = ro match
    case s: Some[Room] => keepIfLeft2(s.value, userId, acc)
    case None => acc

  def keepIfLeft2(room: Room, userId: String, acc: List[Room]): List[Room] =
    if hasLeft(membershipStrOf(room, userId)) then room :: acc else acc

  def hasLeft(m: String): Boolean = m == "leave" || m == "ban"

  /** the member event for `userId` in a room's state (used by /sync's new-invite
   *  test: is this invite newer than the since-token). */
  def memberEvent(room: Room, userId: String): Option[Event] =
    lookupState(room.state, stateKeyOf("m.room.member", userId))

  /** all receipts for a room (the flat list, filtered). */
  def receiptsForRoom(roomId: String): List[Receipt] =
    cell.withLock(st => filterReceipts(st.receiptList, roomId, Nil))

  def filterReceipts(xs: List[Receipt], roomId: String, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => filterReceiptsStep(h, t, roomId, acc)
    case Nil  => ListOps.reverse(acc)

  def filterReceiptsStep(h: Receipt, t: List[Receipt], roomId: String, acc: List[Receipt]): List[Receipt] =
    var acc2: List[Receipt] = acc
    if h.roomId == roomId then acc2 = h :: acc2 else ()
    filterReceipts(t, roomId, acc2)

  /** receipts in a room NEWER than the since-token. /sync uses this to decide
   *  whether an incremental room block is warranted (see sync.scala). */
  def receiptsSinceRoom(roomId: String, sinceSeq: scala.Long): List[Receipt] =
    cell.withLock(st => filterReceiptsSince(st.receiptList, roomId, sinceSeq, Nil))

  def filterReceiptsSince(xs: List[Receipt], roomId: String, sinceSeq: scala.Long, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => filterReceiptsSinceStep(h, t, roomId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def filterReceiptsSinceStep(h: Receipt, t: List[Receipt], roomId: String, sinceSeq: scala.Long, acc: List[Receipt]): List[Receipt] =
    var acc2: List[Receipt] = acc
    if h.roomId == roomId && h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterReceiptsSince(t, roomId, sinceSeq, acc2)

  /** timeline events with `seq > sinceSeq` (the incremental delta). */
  def timelineSince(roomId: String, sinceSeq: scala.Long): List[Event] =
    cell.withLock(st => timelineSinceLocked(st, roomId, sinceSeq))

  def timelineSinceLocked(st: StoreState, roomId: String, sinceSeq: scala.Long): List[Event] = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => filterSince(s.value.timeline, sinceSeq, Nil)
    case None => Nil

  def filterSince(xs: List[Event], sinceSeq: scala.Long, acc: List[Event]): List[Event] = xs match
    case h :: t => filterSinceStep(h, t, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def filterSinceStep(h: Event, t: List[Event], sinceSeq: scala.Long, acc: List[Event]): List[Event] =
    var acc2: List[Event] = acc
    if h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterSince(t, sinceSeq, acc2)

  /** all account-data entries for a user in a scope (global when `hasRoom` is
   *  false, per-room otherwise). */
  def allAccountData(userId: String, hasRoom: Boolean, roomId: String): List[AcctData] =
    cell.withLock(st => filterAcct(st.acct, userId, hasRoom, roomId, Nil))

  def filterAcct(xs: List[AcctData], userId: String, hasRoom: Boolean, roomId: String, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => filterAcctStep(h, t, userId, hasRoom, roomId, acc)
    case Nil  => ListOps.reverse(acc)

  def filterAcctStep(h: AcctData, t: List[AcctData], userId: String, hasRoom: Boolean, roomId: String, acc: List[AcctData]): List[AcctData] =
    var acc2: List[AcctData] = acc
    if acctInScope(h, userId, hasRoom, roomId) then acc2 = h :: acc2 else ()
    filterAcct(t, userId, hasRoom, roomId, acc2)

  def acctInScope(a: AcctData, userId: String, hasRoom: Boolean, roomId: String): Boolean =
    if a.userId != userId then false
    else if a.hasRoom != hasRoom then false
    else if hasRoom then a.roomId == roomId
    else true

  /** account data for a user filtered to GLOBAL entries (not scoped to a room)
   *  set after `sinceSeq` — /sync's incremental account-data delta. */
  def acctSinceGlobal(userId: String, sinceSeq: scala.Long): List[AcctData] =
    cell.withLock(st => filterAcctSince(st.acct, userId, sinceSeq, Nil))

  def filterAcctSince(xs: List[AcctData], userId: String, sinceSeq: scala.Long, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => filterAcctSinceStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def filterAcctSinceStep(h: AcctData, t: List[AcctData], userId: String, sinceSeq: scala.Long, acc: List[AcctData]): List[AcctData] =
    var acc2: List[AcctData] = acc
    if h.userId == userId && !h.hasRoom && h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterAcctSince(t, userId, sinceSeq, acc2)

  // ---- persistence replay -----------------------------------------------------
  //
  // Boot-only, single-threaded, applied in log == commit order. Each reinserts a
  // CONCRETE record verbatim (no id/token/ts/seq regeneration) and bumps `seq`
  // past any replayed seq so post-reboot mutations stay monotonic. `Journal.on`
  // is still false here, so the mutation helpers these reuse (redactRoom,
  // addToState) do NOT re-log. Replay runs BEFORE serving, but still takes the
  // store lock (one transaction per op — cheap and uncontended at boot).

  def bumpSeq(st: StoreState, s: scala.Long): Unit = if s > st.seq then st.seq = s else ()

  def replayDevice(d: Device): Unit =
    cell.withLock { st =>
      st.devices = HashMap.put(st.devices, d.deviceId, d)
      st.tokens = HashMap.put(st.tokens, d.accessToken, d)
    }

  def replayRmDevice(deviceId: String, token: String): Unit =
    cell.withLock { st =>
      st.tokens = HashMap.remove(st.tokens, token)
      st.devices = HashMap.remove(st.devices, deviceId)
    }

  def replayProfile(userId: String, p: Profile): Unit =
    cell.withLock(st => st.profiles = HashMap.put(st.profiles, userId, p))

  def replayAcct(item: AcctData): Unit =
    cell.withLock { st =>
      st.acct = replaceAcct(st.acct, item)
      bumpSeq(st, item.seq)
    }

  def replayRoom(id: String): Unit =
    cell.withLock { st =>
      st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil, Nil))
      st.roomIds = id :: st.roomIds
    }

  def replayEvent(ev: Event): Unit =
    cell.withLock { st =>
      HashMap.get(st.rooms, ev.roomId) match
        case s: Some[Room] => replayEventTo(st, s.value, ev)
        case None => ()
    }

  def replayEventTo(st: StoreState, room: Room, ev: Event): Unit =
    st.rooms = HashMap.put(st.rooms, ev.roomId,
      Room(room.roomId, room.version, addToState(room.state, ev.hasStateKey, stateKeyOf(ev.etype, ev.stateKey), ev), appendEnd(room.timeline, ev)))
    bumpSeq(st, ev.seq)

  def replayRedact(roomId: String, targetId: String, redId: String): Unit =
    cell.withLock { st =>
      HashMap.get(st.rooms, roomId) match
        case s: Some[Room] => replayRedact2(st, s.value, roomId, targetId, redId)
        case None => ()
    }

  def replayRedact2(st: StoreState, room: Room, roomId: String, targetId: String, redId: String): Unit = findEv(room.timeline, redId) match
    case s: Some[Event] => redactRoom(st, room, roomId, targetId, s.value)
    case None => ()

  def replayAlias(alias: String, roomId: String): Unit =
    cell.withLock(st => st.aliases = HashMap.put(st.aliases, alias, roomId))

  /** metadata-only reinsert (file-backed mode: the bytes live in the blob
   *  file; persist.scala verified — or just wrote — the file first). */
  def replayMediaMeta(id: String, contentType: String): Unit =
    cell.withLock(st => st.media = HashMap.put(st.media, id, MediaItem(id, "", contentType)))

  /** full reinsert with bytes — the in-memory fallback for a replay that runs
   *  without a media dir (not reachable from `Server.serve`, which boots
   *  MediaFiles before the journal; kept so `Journal.bootWith` alone stays
   *  correct). */
  def replayMedia(id: String, data: String, contentType: String): Unit =
    cell.withLock(st => st.media = HashMap.put(st.media, id, MediaItem(id, data, contentType)))

  def replayReceipt(rc: Receipt): Unit =
    cell.withLock { st =>
      st.receiptList = replaceReceipt(st.receiptList, rc)
      bumpSeq(st, rc.seq)
    }

  def replayTxn(key: String, eventId: String): Unit =
    cell.withLock(st => st.txns = HashMap.put(st.txns, key, eventId))

  def replayDmPair(p: DmPair): Unit =
    cell.withLock(st => st.dmPairs = appendPair(st.dmPairs, p))
