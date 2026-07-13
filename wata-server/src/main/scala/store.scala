import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** M7 chunk 2 — the in-memory store (decision 3): ONE store, ONE coarse lock.
 *  The TS server is a single-threaded event loop; the coarse lock reproduces
 *  that serialization under net/http's per-request goroutines (chunk-0's
 *  `HttpCounter` in miniature). An owning-goroutine/actor refactor waits on
 *  post-gate evidence.
 *
 *  M9 chunk 4b (decision 7, the store-shape decision): the `sync.RWMutex` + 14
 *  module `var`s migrate to ONE `Mutex[StoreState]` guarded cell — `StoreState`
 *  is a plain class whose `var` fields hold the EXISTING immutable HashMaps /
 *  cons lists (in-place field reseats under the lock; same ops, no facade types).
 *  Exclusive-only (`Mutex` has no RWMutex variant, §4.6): today's RWMutex was
 *  already one lock over all slices, the only concession being read-read on a
 *  near-idle server — not worth a reader-parallel cell's soundness cost. The
 *  cell is a PRIVATE `val`; the public surface stays `Store.addDevice(...)`-shaped
 *  with `withLock` inside, so callers never see the lock and the leak-guard
 *  obligation (CONC-12 / separationChecking) is discharged in one module. Each
 *  former `mu.lock()`…`mu.unlock()` span is ONE `withLock` = ONE transaction;
 *  the side-effect-after-unlock discipline (updateMemberProfile, notifyUser)
 *  stays OUTSIDE the block (its snapshot crosses out as a pure value).
 *
 *  State slices (auth + profile + account-data + rooms/events/aliases/media/
 *  receipts/txns + long-poll waiters), see `StoreState` below.
 */

/** The guarded store state (M9 ch.4b): the 14 former `Store` module `var`s,
 *  now `var` fields of a plain class held behind ONE `Mutex`. Reseats are
 *  in-place under the lock (same HashMap/cons ops); NO facade types, NO
 *  write-back API (the mutable-fields class answers the whole-swap gap, ch.5). */
class StoreState:
  var devices: HashMap[String, Device] =
    HashMap.empty[String, Device](k => sgo.hash(k), (a, b) => a == b)
  var tokens: HashMap[String, Device] =
    HashMap.empty[String, Device](k => sgo.hash(k), (a, b) => a == b)
  var profiles: HashMap[String, Profile] =
    HashMap.empty[String, Profile](k => sgo.hash(k), (a, b) => a == b)
  var acct: List[AcctData] = Nil[AcctData]()
  var rooms: HashMap[String, Room] =
    HashMap.empty[String, Room](k => sgo.hash(k), (a, b) => a == b)
  var aliases: HashMap[String, String] =
    HashMap.empty[String, String](k => sgo.hash(k), (a, b) => a == b)
  var media: HashMap[String, MediaItem] =
    HashMap.empty[String, MediaItem](k => sgo.hash(k), (a, b) => a == b)
  var receiptList: List[Receipt] = Nil[Receipt]()
  var txns: HashMap[String, String] =
    HashMap.empty[String, String](k => sgo.hash(k), (a, b) => a == b)
  var roomIds: List[String] = Nil[String]()
  var seq: scala.Long = 0L
  var waiters: List[Waiter] = Nil[Waiter]()
  var waiterSeq: scala.Long = 0L

/** A pure snapshot crossing out of `updateMemberProfile`'s `withLock` (the
 *  guarded room-id list + the user's profile, both immutable) — a named pure
 *  case class rather than a tuple (crossable, CONC-12). */
case class RoomsProfileSnap(ids: List[String], prof: Profile)

object Store:
  // the ONE guarded cell (a private val — the leak-guard is discharged here).
  private val cell: Mutex[StoreState] = mutex(new StoreState())

  /** seed the config users' default profiles (profile.ts `initProfiles`). The
   *  two config users are seeded explicitly (see Config's List departure). */
  def init(): Unit =
    cell.withLock { st =>
      st.profiles = seedProfile(st.profiles, "alice", "Alice")
      st.profiles = seedProfile(st.profiles, "bob", "Bob")
    }

  def seedProfile(acc: HashMap[String, Profile], localpart: String, displayName: String): HashMap[String, Profile] =
    HashMap.put(acc, userIdOf(localpart), Profile(displayName, ""))

  // ---- ID helpers ------------------------------------------------------------

  def userIdOf(localpart: String): String = "@" + localpart + ":" + Config.serverName

  def localpartOf(userId: String): String =
    val c = userId.indexOf(":")
    if c < 0 then userId.substring(1) else userId.substring(1, c)

  /** crypto/rand + base64url (decision 5). No UUID dependency; token format is
   *  `syt_<localpart>_<rand>` per the TS server, IDs are base64url random. */
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

  def deviceByToken(token: String): Option[Device] =
    cell.withLock(st => HashMap.get(st.tokens, token))

  def removeDevice(deviceId: String): Unit =
    cell.withLock(st => dropDevice(st, HashMap.get(st.devices, deviceId), deviceId))

  def dropDevice(st: StoreState, d: Option[Device], deviceId: String): Unit = d match
    case s: Some[Device] => dropDeviceGo(st, s.v, deviceId)
    case _: None[Device] => ()

  def dropDeviceGo(st: StoreState, d: Device, deviceId: String): Unit =
    st.tokens = HashMap.remove(st.tokens, d.accessToken)
    st.devices = HashMap.remove(st.devices, deviceId)
    if Journal.enabled then Journal.rec(Journal.rmDeviceOp(deviceId, d.accessToken)) else ()

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
    case s: Some[Profile] => s.v
    case _: None[Profile] => Profile("", "")

  /** profile.ts `updateMemberProfile`: rewrite the user's `m.room.member` state
   *  event in every room they've joined with the freshly-stored profile, and
   *  notify each room's members. Snapshots the room-id list + profile under the
   *  lock (both PURE values — they cross out of the block), then does the
   *  per-room reads/appends via their own transactions (the
   *  side-effect-after-unlock discipline). */
  def updateMemberProfile(userId: String): Unit =
    val snap = cell.withLock(st => RoomsProfileSnap(st.roomIds, profileOr(HashMap.get(st.profiles, userId))))
    updateRooms(snap.ids, userId, snap.prof)

  def updateRooms(ids: List[String], userId: String, prof: Profile): Unit = ids match
    case h :: t => updateRoomsStep(h, t, userId, prof)
    case Nil()  => ()

  def updateRoomsStep(h: String, t: List[String], userId: String, prof: Profile): Unit =
    updateOneRoom(h, userId, prof)
    updateRooms(t, userId, prof)

  def updateOneRoom(roomId: String, userId: String, prof: Profile): Unit = getMembership(roomId, userId) match
    case _: MJoin => rewriteMember(roomId, userId, prof)
    case _        => ()

  def rewriteMember(roomId: String, userId: String, prof: Profile): Unit = getRoom(roomId) match
    case s: Some[Room] => rewriteMember2(s.v, roomId, userId, prof)
    case _: None[Room] => ()

  def rewriteMember2(room: Room, roomId: String, userId: String, prof: Profile): Unit =
    lookupState(room.state, stateKeyOf("m.room.member", userId)) match
      case s: Some[Event] => rewriteMember3(roomId, userId, prof, s.v.content)
      case _: None[Event] => ()

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
    case Nil()  => false

  def acctExistsStep(h: AcctData, t: List[AcctData], item: AcctData): Boolean =
    if acctSameKey(h, item) then true else acctExists(t, item)

  def acctMap(xs: List[AcctData], item: AcctData): List[AcctData] =
    acctMapGo(xs, item, Nil[AcctData]())

  def acctMapGo(xs: List[AcctData], item: AcctData, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => acctMapStep(h, t, item, acc)
    case Nil()  => ListOps.reverse(acc)

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
    case Nil()  => None[AcctData]()

  def findAcctStep(h: AcctData, t: List[AcctData], probe: AcctData): Option[AcctData] =
    if acctSameKey(h, probe) then Some(h) else findAcct(t, probe)

  def acctSameKey(a: AcctData, b: AcctData): Boolean =
    a.userId == b.userId && a.dtype == b.dtype && sameRoom(a, b)

  def sameRoom(a: AcctData, b: AcctData): Boolean =
    if a.hasRoom then bothRoom(a, b) else !b.hasRoom

  def bothRoom(a: AcctData, b: AcctData): Boolean =
    if b.hasRoom then a.roomId == b.roomId else false

  // ---- long-poll waiters (M7 chunk 4, decision 4) ----------------------------
  //
  // The wake channel is CLOSE-SIGNALLED, never sent to: the sgo surface has a
  // non-blocking receive (`selectOrDefault`) but no non-blocking SEND, and a
  // blocking send would couple the notifying goroutine to the waiter's drain
  // (and deadlock a second notify on a size-1 buffer). `close` never blocks and
  // its effect is persistent (a later `recv`/`select` still observes it), so it
  // is the exact analogue of the pinned "buffered(1) + non-blocking send" with
  // ZERO new emitter surface. A channel is closed AT MOST ONCE because the
  // waiter is REMOVED from the shared list under the write lock before closing:
  // whoever removes a waiter under the lock OWNS it (notify removes+closes;
  // `removeWaiter`, the timer path, removes+discards — H's own channel needs no
  // close). See the report's no-lost-wake argument.
  //
  // M9 ch.4b: the waiter list holds `Chan`s (a non-crossable synchronizer
  // field), so it must never leave the `withLock` block. `notifyUser` therefore
  // CLOSES the dropped channels INSIDE the block (close cannot block — the
  // "lock-vs-channel discipline" was a stylistic preference, not a correctness
  // requirement; moving it in confines the non-crossable list, CONC-12).

  /** Register a waiter for `userId`, returning a handle whose `ch` the caller
   *  selects on. The channel is unbuffered; the registration is the store-commit
   *  that the no-lost-wake argument pivots on (it precedes the caller's second
   *  emptiness check). The Waiter (which holds a `Chan`, non-crossable) is
   *  rebuilt OUTSIDE the block from the guarded seq — the block returns only the
   *  pure `id`, so the guarded waiter list never crosses the lock (CONC-12). */
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
    cell.withLock(st => st.waiters = dropId(st.waiters, id, Nil[Waiter]()))

  def dropId(xs: List[Waiter], id: scala.Long, acc: List[Waiter]): List[Waiter] = xs match
    case h :: t => dropIdStep(h, t, id, acc)
    case Nil()  => ListOps.reverse(acc)

  def dropIdStep(h: Waiter, t: List[Waiter], id: scala.Long, acc: List[Waiter]): List[Waiter] =
    var acc2: List[Waiter] = acc
    if h.id == id then acc2 = acc else acc2 = h :: acc2
    dropId(t, id, acc2)

  /** Wake every waiter for `userId`. Under the lock: snapshot the current waiter
   *  list, drop this user's waiters from the shared list (so a concurrent
   *  notify/timer never touches the same waiter), and CLOSE each dropped channel
   *  — all in the one transaction (M9 ch.4b: the waiter list is non-crossable, so
   *  it stays inside the block; `close` cannot block, so closing under the lock
   *  is safe). */
  def notifyUser(userId: String): Unit =
    cell.withLock { st =>
      val old = st.waiters
      st.waiters = dropUser(old, userId, Nil[Waiter]())
      closeUser(old, userId)
    }

  def dropUser(xs: List[Waiter], userId: String, acc: List[Waiter]): List[Waiter] = xs match
    case h :: t => dropUserStep(h, t, userId, acc)
    case Nil()  => ListOps.reverse(acc)

  def dropUserStep(h: Waiter, t: List[Waiter], userId: String, acc: List[Waiter]): List[Waiter] =
    var acc2: List[Waiter] = acc
    if h.userId == userId then acc2 = acc else acc2 = h :: acc2
    dropUser(t, userId, acc2)

  def closeUser(xs: List[Waiter], userId: String): Unit = xs match
    case h :: t => closeUserStep(h, t, userId)
    case Nil()  => ()

  def closeUserStep(h: Waiter, t: List[Waiter], userId: String): Unit =
    if h.userId == userId then h.ch.close() else ()
    closeUser(t, userId)

  /** Block the calling (request) goroutine until the waiter's channel is closed
   *  (a wake) OR the timer fires — a real Go `select` over {waiter, timer}
   *  (decision 4's "selects over {waiter, timer}"). Neither arm's value is used;
   *  the caller rebuilds the sync response afterwards. NO lock is held here — the
   *  select can block, and the lock-vs-channel discipline forbids blocking under
   *  the lock. */
  def waitForEvents(w: Waiter, timeoutMs: scala.Int): Unit =
    sgo.select2(w.ch, go.time.After(go.time.milliseconds(timeoutMs)))(
      (b: Boolean) => (),
      (tm: go.time.Time) => ())

  // ---- ID generation (decision 5) --------------------------------------------

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
      st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil[(String, Event)](), Nil[Event]()))
      st.roomIds = id :: st.roomIds
      if Journal.enabled then Journal.rec(Journal.roomOp(id)) else ()
    }
    id

  def getRoom(roomId: String): Option[Room] =
    cell.withLock(st => HashMap.get(st.rooms, roomId))

  def stateContent(room: Room, etype: String, sk: String): Option[Json] =
    lookupState(room.state, stateKeyOf(etype, sk)) match
      case s: Some[Event] => Some(s.v.content)
      case _: None[Event] => None[Json]()

  def lookupState(state: List[(String, Event)], key: String): Option[Event] = state match
    case p :: t => lookupStateStep(p, t, key)
    case Nil()  => None[Event]()

  def lookupStateStep(p: (String, Event), t: List[(String, Event)], key: String): Option[Event] =
    val k: String = p._1
    val ev: Event = p._2
    if k == key then Some(ev) else lookupState(t, key)

  // ---- membership ------------------------------------------------------------

  def getMembership(roomId: String, userId: String): Membership =
    cell.withLock(st => membershipLocked(st, roomId, userId))

  def membershipLocked(st: StoreState, roomId: String, userId: String): Membership = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => memInState(s.v.state, stateKeyOf("m.room.member", userId))
    case _: None[Room] => MNone()

  def memInState(state: List[(String, Event)], key: String): Membership = lookupState(state, key) match
    case s: Some[Event] => Mem.parse(strField(s.v.content, "membership", ""))
    case _: None[Event] => MNone()

  // ---- events ----------------------------------------------------------------

  /** append an event; if it carries a state key, update the room's state map.
   *  Returns `None` if the room is gone. Under the write lock. */
  def addEvent(roomId: String, etype: String, sender: String, content: Json,
               hasSK: Boolean, sk: String, hasRedacts: Boolean, redacts: String, unsigned: Json): Option[Event] =
    cell.withLock(st => addEventLocked(st, roomId, etype, sender, content, hasSK, sk, hasRedacts, redacts, unsigned))

  def addEventLocked(st: StoreState, roomId: String, etype: String, sender: String, content: Json,
                     hasSK: Boolean, sk: String, hasRedacts: Boolean, redacts: String, unsigned: Json): Option[Event] =
    HashMap.get(st.rooms, roomId) match
      case s: Some[Room] => addEventTo(st, s.v, roomId, etype, sender, content, hasSK, sk, hasRedacts, redacts, unsigned)
      case _: None[Room] => None[Event]()

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
    if stateHas(state, key) then stateReplace(state, key, ev, Nil[(String, Event)]())
    else appendEndS(state, key, ev)

  def stateHas(state: List[(String, Event)], key: String): Boolean = state match
    case p :: t => stateHasStep(p, t, key)
    case Nil()  => false

  def stateHasStep(p: (String, Event), t: List[(String, Event)], key: String): Boolean =
    val k: String = p._1
    if k == key then true else stateHas(t, key)

  def stateReplace(state: List[(String, Event)], key: String, ev: Event, acc: List[(String, Event)]): List[(String, Event)] = state match
    case p :: t => stateReplaceStep(p, t, key, ev, acc)
    case Nil()  => ListOps.reverse(acc)

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
    case s: Some[Room] => findEv(s.v.timeline, eventId)
    case _: None[Room] => None[Event]()

  def findEv(xs: List[Event], eventId: String): Option[Event] = xs match
    case h :: t => findEvStep(h, t, eventId)
    case Nil()  => None[Event]()

  def findEvStep(h: Event, t: List[Event], eventId: String): Option[Event] =
    if h.eventId == eventId then Some(h) else findEv(t, eventId)

  /** redact the target event: content -> `{}`, `unsigned.redacted_because` -> the
   *  redaction event (store.ts in-place mutation, here a rebuild of both the
   *  timeline and the state map). Under the write lock. */
  def redactTarget(roomId: String, eventId: String, red: Event): Unit =
    cell.withLock(st => redactLocked(st, roomId, eventId, red))

  def redactLocked(st: StoreState, roomId: String, eventId: String, red: Event): Unit = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => redactRoom(st, s.v, roomId, eventId, red)
    case _: None[Room] => ()

  def redactRoom(st: StoreState, room: Room, roomId: String, eventId: String, red: Event): Unit =
    st.rooms = HashMap.put(st.rooms, roomId,
      Room(room.roomId, room.version, redactState(room.state, eventId, red, Nil[(String, Event)]()),
        redactTL(room.timeline, eventId, red, Nil[Event]())))
    if Journal.enabled then Journal.rec(Journal.redactOp(roomId, eventId, red.eventId)) else ()

  def redactTL(xs: List[Event], eventId: String, red: Event, acc: List[Event]): List[Event] = xs match
    case h :: t => redactTLStep(h, t, eventId, red, acc)
    case Nil()  => ListOps.reverse(acc)

  def redactTLStep(h: Event, t: List[Event], eventId: String, red: Event, acc: List[Event]): List[Event] =
    if h.eventId == eventId then redactTL(t, eventId, red, redactedCopy(h, red) :: acc)
    else redactTL(t, eventId, red, h :: acc)

  def redactState(state: List[(String, Event)], eventId: String, red: Event, acc: List[(String, Event)]): List[(String, Event)] = state match
    case p :: t => redactStateStep(p, t, eventId, red, acc)
    case Nil()  => ListOps.reverse(acc)

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

  // ---- media (decision 6) ----------------------------------------------------

  def storeMedia(data: String, contentType: String): String =
    val id = genMediaId()
    cell.withLock { st =>
      st.media = HashMap.put(st.media, id, MediaItem(id, data, contentType))
      if Journal.enabled then Journal.rec(Journal.mediaOp(id, data, contentType)) else ()
    }
    id

  def getMedia(mediaId: String): Option[MediaItem] =
    cell.withLock(st => HashMap.get(st.media, mediaId))

  // ---- receipts --------------------------------------------------------------

  def setReceipt(roomId: String, receiptType: String, userId: String, eventId: String): Unit =
    cell.withLock { st =>
      st.seq = st.seq + 1L
      val rc = Receipt(roomId, userId, eventId, nowMs(), receiptType, st.seq)
      st.receiptList = replaceReceipt(st.receiptList, rc)
      if Journal.enabled then Journal.rec(Journal.receiptOp(rc)) else ()
    }

  def replaceReceipt(xs: List[Receipt], rc: Receipt): List[Receipt] =
    if receiptHas(xs, rc) then receiptMap(xs, rc, Nil[Receipt]())
    else appendReceipt(xs, rc)

  def appendReceipt(xs: List[Receipt], rc: Receipt): List[Receipt] =
    var r: List[Receipt] = ListOps.reverse(xs)
    r = rc :: r
    ListOps.reverse(r)

  def receiptHas(xs: List[Receipt], rc: Receipt): Boolean = xs match
    case h :: t => receiptHasStep(h, t, rc)
    case Nil()  => false

  def receiptHasStep(h: Receipt, t: List[Receipt], rc: Receipt): Boolean =
    if sameReceiptKey(h, rc) then true else receiptHas(t, rc)

  def receiptMap(xs: List[Receipt], rc: Receipt, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => receiptMapStep(h, t, rc, acc)
    case Nil()  => ListOps.reverse(acc)

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

  // ---- notify fan-out (store.ts notifyRoomMembers) ---------------------------

  /** notify every join/invite member of a room (chunk-4 wake; a no-op body via
   *  notifyUser today, but the scan is live so chunk 4 only fills notifyUser). */
  def notifyRoomMembers(roomId: String): Unit = getRoom(roomId) match
    case s: Some[Room] => notifyMembers(s.v.state)
    case _: None[Room] => ()

  def notifyMembers(state: List[(String, Event)]): Unit = state match
    case p :: t => notifyMembersStep(p, t)
    case Nil()  => ()

  def notifyMembersStep(p: (String, Event), t: List[(String, Event)]): Unit =
    val ev: Event = p._2
    notifyIfMember(ev)
    notifyMembers(t)

  def notifyIfMember(ev: Event): Unit =
    if ev.etype == "m.room.member" && isJoinOrInvite(strField(ev.content, "membership", "")) then notifyUser(ev.stateKey)
    else ()

  def isJoinOrInvite(m: String): Boolean = m == "join" || m == "invite"

  // ---- wall clock (origin_server_ts) -----------------------------------------

  /** int64 epoch ms. Always inline (never val-bound to a facade temp), so the
   *  emitter renders `time.Now().UnixMilli()` — see the time-facade note. */
  def nowMs(): scala.Long = go.time.nowUnixMilli()

  // ---- /sync read accessors (M7 chunk 4) -------------------------------------
  //
  // Each takes ONE lock and snapshots an immutable value (the store's data is
  // persistent case classes / cons lists, DATA-1), so /sync assembles from a
  // consistent per-call snapshot per accessor. As noted in chunk 3, a /sync that
  // reads across several such accessors can see a torn view under concurrent
  // writes; this is invisible to the sequential oracle and self-corrects (the
  // client re-syncs from next_batch), and the actor refactor stays post-gate.

  def globalSeq(): scala.Long =
    cell.withLock(st => st.seq)

  /** store.ts `getRoomsForUser` — rooms whose membership for `userId` equals
   *  `want` ("join"/"invite"), in stable creation order (roomIds is newest-first,
   *  so reverse gives oldest-first). */
  def roomsForUser(userId: String, want: String): List[Room] =
    cell.withLock(st => collectRooms(st, ListOps.reverse(st.roomIds), userId, want, Nil[Room]()))

  def collectRooms(st: StoreState, ids: List[String], userId: String, want: String, acc: List[Room]): List[Room] = ids match
    case h :: t => collectRoomsStep(st, h, t, userId, want, acc)
    case Nil()  => ListOps.reverse(acc)

  def collectRoomsStep(st: StoreState, h: String, t: List[String], userId: String, want: String, acc: List[Room]): List[Room] =
    var acc2: List[Room] = acc
    acc2 = keepIfMember(HashMap.get(st.rooms, h), userId, want, acc2)
    collectRooms(st, t, userId, want, acc2)

  def keepIfMember(ro: Option[Room], userId: String, want: String, acc: List[Room]): List[Room] = ro match
    case s: Some[Room] => keepIfMember2(s.v, userId, want, acc)
    case _: None[Room] => acc

  def keepIfMember2(room: Room, userId: String, want: String, acc: List[Room]): List[Room] =
    if membershipStrOf(room, userId) == want then room :: acc else acc

  def membershipStrOf(room: Room, userId: String): String =
    Mem.str(memInState(room.state, stateKeyOf("m.room.member", userId)))

  /** the member event for `userId` in a room's state (used by /sync's new-invite
   *  test: is this invite newer than the since-token). */
  def memberEvent(room: Room, userId: String): Option[Event] =
    lookupState(room.state, stateKeyOf("m.room.member", userId))

  /** store.ts `getReceipts`: all receipts for a room (the flat list, filtered). */
  def receiptsForRoom(roomId: String): List[Receipt] =
    cell.withLock(st => filterReceipts(st.receiptList, roomId, Nil[Receipt]()))

  def filterReceipts(xs: List[Receipt], roomId: String, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => filterReceiptsStep(h, t, roomId, acc)
    case Nil()  => ListOps.reverse(acc)

  def filterReceiptsStep(h: Receipt, t: List[Receipt], roomId: String, acc: List[Receipt]): List[Receipt] =
    var acc2: List[Receipt] = acc
    if h.roomId == roomId then acc2 = h :: acc2 else ()
    filterReceipts(t, roomId, acc2)

  /** store.ts `getReceiptsSince(roomId, sinceSeq)` (present but UNUSED in the TS —
   *  its existence reveals the original intent that /sync's rot dropped): receipts
   *  in a room NEWER than the since-token. /sync uses this to decide whether an
   *  incremental room block is warranted (spec-correction, see sync.scala). */
  def receiptsSinceRoom(roomId: String, sinceSeq: scala.Long): List[Receipt] =
    cell.withLock(st => filterReceiptsSince(st.receiptList, roomId, sinceSeq, Nil[Receipt]()))

  def filterReceiptsSince(xs: List[Receipt], roomId: String, sinceSeq: scala.Long, acc: List[Receipt]): List[Receipt] = xs match
    case h :: t => filterReceiptsSinceStep(h, t, roomId, sinceSeq, acc)
    case Nil()  => ListOps.reverse(acc)

  def filterReceiptsSinceStep(h: Receipt, t: List[Receipt], roomId: String, sinceSeq: scala.Long, acc: List[Receipt]): List[Receipt] =
    var acc2: List[Receipt] = acc
    if h.roomId == roomId && h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterReceiptsSince(t, roomId, sinceSeq, acc2)

  /** store.ts `getTimelineEvents(roomId, sinceSeq)`: timeline events with
   *  `seq > sinceSeq` (the incremental delta). */
  def timelineSince(roomId: String, sinceSeq: scala.Long): List[Event] =
    cell.withLock(st => timelineSinceLocked(st, roomId, sinceSeq))

  def timelineSinceLocked(st: StoreState, roomId: String, sinceSeq: scala.Long): List[Event] = HashMap.get(st.rooms, roomId) match
    case s: Some[Room] => filterSince(s.v.timeline, sinceSeq, Nil[Event]())
    case _: None[Room] => Nil[Event]()

  def filterSince(xs: List[Event], sinceSeq: scala.Long, acc: List[Event]): List[Event] = xs match
    case h :: t => filterSinceStep(h, t, sinceSeq, acc)
    case Nil()  => ListOps.reverse(acc)

  def filterSinceStep(h: Event, t: List[Event], sinceSeq: scala.Long, acc: List[Event]): List[Event] =
    var acc2: List[Event] = acc
    if h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterSince(t, sinceSeq, acc2)

  /** store.ts `getAllAccountData(userId, roomId?)`: all entries for a user in a
   *  scope (global when `hasRoom` is false, per-room otherwise). */
  def allAccountData(userId: String, hasRoom: Boolean, roomId: String): List[AcctData] =
    cell.withLock(st => filterAcct(st.acct, userId, hasRoom, roomId, Nil[AcctData]()))

  def filterAcct(xs: List[AcctData], userId: String, hasRoom: Boolean, roomId: String, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => filterAcctStep(h, t, userId, hasRoom, roomId, acc)
    case Nil()  => ListOps.reverse(acc)

  def filterAcctStep(h: AcctData, t: List[AcctData], userId: String, hasRoom: Boolean, roomId: String, acc: List[AcctData]): List[AcctData] =
    var acc2: List[AcctData] = acc
    if acctInScope(h, userId, hasRoom, roomId) then acc2 = h :: acc2 else ()
    filterAcct(t, userId, hasRoom, roomId, acc2)

  def acctInScope(a: AcctData, userId: String, hasRoom: Boolean, roomId: String): Boolean =
    if a.userId != userId then false
    else if a.hasRoom != hasRoom then false
    else if hasRoom then a.roomId == roomId
    else true

  /** store.ts `getAccountDataSince(userId, sinceSeq)` filtered to GLOBAL entries
   *  (roomId === null) — /sync's incremental account-data delta. */
  def acctSinceGlobal(userId: String, sinceSeq: scala.Long): List[AcctData] =
    cell.withLock(st => filterAcctSince(st.acct, userId, sinceSeq, Nil[AcctData]()))

  def filterAcctSince(xs: List[AcctData], userId: String, sinceSeq: scala.Long, acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => filterAcctSinceStep(h, t, userId, sinceSeq, acc)
    case Nil()  => ListOps.reverse(acc)

  def filterAcctSinceStep(h: AcctData, t: List[AcctData], userId: String, sinceSeq: scala.Long, acc: List[AcctData]): List[AcctData] =
    var acc2: List[AcctData] = acc
    if h.userId == userId && !h.hasRoom && h.seq > sinceSeq then acc2 = h :: acc2 else ()
    filterAcctSince(t, userId, sinceSeq, acc2)

  // ---- persistence replay (M7 chunk 6, decision 8) ---------------------------
  //
  // Boot-only, single-threaded, applied in log == commit order. Each reinserts a
  // CONCRETE record verbatim (no id/token/ts/seq regeneration) and bumps `seq`
  // past any replayed seq so post-reboot mutations stay monotonic. `Journal.on`
  // is still false here, so the mutation helpers these reuse (redactRoom,
  // addToState) do NOT re-log. Replay runs BEFORE serving, but still takes the
  // lock (M9 ch.4b: the state lives behind the cell now — one transaction per
  // op, cheap and uncontended at boot).

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
      st.rooms = HashMap.put(st.rooms, id, Room(id, "10", Nil[(String, Event)](), Nil[Event]()))
      st.roomIds = id :: st.roomIds
    }

  def replayEvent(ev: Event): Unit =
    cell.withLock { st =>
      HashMap.get(st.rooms, ev.roomId) match
        case s: Some[Room] => replayEventTo(st, s.v, ev)
        case _: None[Room] => ()
    }

  def replayEventTo(st: StoreState, room: Room, ev: Event): Unit =
    st.rooms = HashMap.put(st.rooms, ev.roomId,
      Room(room.roomId, room.version, addToState(room.state, ev.hasStateKey, stateKeyOf(ev.etype, ev.stateKey), ev), appendEnd(room.timeline, ev)))
    bumpSeq(st, ev.seq)

  def replayRedact(roomId: String, targetId: String, redId: String): Unit =
    cell.withLock { st =>
      HashMap.get(st.rooms, roomId) match
        case s: Some[Room] => replayRedact2(st, s.v, roomId, targetId, redId)
        case _: None[Room] => ()
    }

  def replayRedact2(st: StoreState, room: Room, roomId: String, targetId: String, redId: String): Unit = findEv(room.timeline, redId) match
    case s: Some[Event] => redactRoom(st, room, roomId, targetId, s.v)
    case _: None[Event] => ()

  def replayAlias(alias: String, roomId: String): Unit =
    cell.withLock(st => st.aliases = HashMap.put(st.aliases, alias, roomId))

  def replayMedia(id: String, data: String, contentType: String): Unit =
    cell.withLock(st => st.media = HashMap.put(st.media, id, MediaItem(id, data, contentType)))

  def replayReceipt(rc: Receipt): Unit =
    cell.withLock { st =>
      st.receiptList = replaceReceipt(st.receiptList, rc)
      bumpSeq(st, rc.seq)
    }

  def replayTxn(key: String, eventId: String): Unit =
    cell.withLock(st => st.txns = HashMap.put(st.txns, key, eventId))
