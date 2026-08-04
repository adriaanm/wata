import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** Canonical DMs: the PAIR is the key.
 *
 *  Matrix has no first-class DMs — `m.direct` is client-written account data
 *  and `is_direct` is a hint on an invite, so nothing enforces uniqueness.
 *  Here the server owns DM identity instead: at most one room per unordered
 *  pair of users, keyed by the sorted pair in the store's `dmPairs` slice
 *  (store.scala), so "the DM with Bob" is a lookup.
 *
 *  Two things carry that identity:
 *
 *   - the `net.wata.dm` STATE EVENT (`{"members": [a, b]}`, sorted) stamped
 *     into every canonical DM, plus a canonical alias `#dm.<a>.<b>:server`
 *     with sorted localparts. A client classifies a room the moment its state
 *     arrives — no account data, no ordering race, no inference. There is
 *     deliberately no custom `m.room.create` `type`: Element hides rooms of an
 *     unknown type, which would kill the degraded stock-client mode.
 *   - the pair map itself, which is what makes get-or-create atomic.
 *
 *  The BOOT MIGRATION below derives the map from rooms that predate it. Rooms
 *  are scanned oldest-first and a claim never overwrites, so the oldest room
 *  wins a contested pair; the losers stay joined but unmapped (nothing deletes
 *  a room).
 */
object Dm:

  // ---- classification ---------------------------------------------------------

  /** is this room a canonical DM — does it carry the `net.wata.dm` stamp. */
  def isDmRoom(roomId: String): Boolean = Store.getRoom(roomId) match
    case s: Some[Room] => hasDmState(s.value)
    case None => false

  def hasDmState(room: Room): Boolean = Store.stateContent(room, "net.wata.dm", "") match
    case _: Some[Json] => true
    case None => false

  /** the stamped member pair, or `Nil` when the room carries no stamp. */
  def dmMembersOf(room: Room): List[String] = Store.stateContent(room, "net.wata.dm", "") match
    case s: Some[Json] => Rooms.arrStrings(membersField(s.value))
    case None => Nil

  def membersField(content: Json): Json = getField(content, "members") match
    case s: Some[Json] => s.value
    case None => JArr(Nil)

  /** the canonical alias, localparts SORTED: `#dm.<a>.<b>:server`. Same room,
   *  same alias, whichever side asks. */
  def aliasOf(a: String, b: String): String =
    val la = Store.localpartOf(a)
    val lb = Store.localpartOf(b)
    if Store.strLess(la, lb) then aliasStr(la, lb) else aliasStr(lb, la)

  def aliasStr(lo: String, hi: String): String =
    "#dm." + lo + "." + hi + ":" + Config.serverName

  /** the structural identity stamped into a DM's state: `{"members":[a,b]}`,
   *  sorted, so both sides read the same value. */
  def dmContent(a: String, b: String): Json =
    if Store.strLess(a, b) then membersObj(a, b) else membersObj(b, a)

  def membersObj(lo: String, hi: String): Json =
    var xs: List[Json] = Nil
    xs = JStr(hi) :: xs
    xs = JStr(lo) :: xs
    obj1("members", JArr(xs))

  // ---- the dialect endpoint ----------------------------------------------------
  //
  // POST /_wata/v1/dm/{userId} -> {"room_id": …}. Idempotent get-or-create of
  // THE room for (caller, userId): the server joins the caller, invites the
  // peer, and stamps the identity. Repeat calls and concurrent calls from both
  // sides return the same room, because the whole thing is one transaction over
  // the pair map. This is what replaces the client's resolve/create/update
  // dance entirely.

  def route(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case rr: Right[MErr, Auth] => resolve(rr.right.userId, r.pathValue("userId"))

  def resolve(caller: String, peerRaw: String): Either[MErr, Json] =
    val peer = normalize(peerRaw)
    if peer == "" then Left(MErr(404, M_NOT_FOUND(), "Unknown user"))
    else if peer == caller then Left(MErr(403, M_FORBIDDEN(), "Cannot open a DM with yourself"))
    else Right(obj1("room_id", JStr(getOrCreate(caller, peer))))

  /** a bare localpart or a full MXID -> the full user id, or "" when the id
   *  names nobody with an account here (unknown, or another server — there is
   *  no federation). */
  def normalize(raw: String): String =
    val lp = Router.cleanLocalpart(raw)
    if lp == "" then "" else knownUser(lp, raw)

  def knownUser(lp: String, raw: String): String = Config.userByLocalpart(lp) match
    case _: Some[UserCfg] => onThisServer(lp, raw)
    case None => ""

  def onThisServer(lp: String, raw: String): String =
    if raw.startsWith("@") && !raw.endsWith(":" + Config.serverName) then ""
    else Store.userIdOf(lp)

  /** the canonical room for the pair, minting it on first ask. The
   *  after-effects (the compat `m.direct` write for both users, and the two
   *  long-poll wakes) run ONLY for the call that actually created the room, and
   *  OUTSIDE the store transaction. */
  def getOrCreate(caller: String, peer: String): String =
    val res = Store.dmGetOrCreate(caller, peer, caller, aliasOf(caller, peer), seeds(caller, peer))
    if res.created then afterCreate(caller, peer, res.roomId) else ensureJoined(caller, res.roomId)
    res.roomId

  /** the endpoint always leaves the CALLER joined. Whoever minted the room is
   *  joined by construction; the other side holds an invite until they ask for
   *  the room, and asking IS the accept — so a first send needs no separate
   *  join round-trip. A ban is the one membership this does not walk over. */
  def ensureJoined(caller: String, roomId: String): Unit = Store.getMembership(roomId, caller) match
    case _: MJoin => ()
    case _: MBan  => ()
    case _        => joinCaller(caller, roomId)

  def joinCaller(caller: String, roomId: String): Unit =
    Rooms.addStateEvent(roomId, caller, "m.room.member", caller, Rooms.memberJoinContent(caller, true))
    Store.notifyRoomMembers(roomId)

  def afterCreate(caller: String, peer: String, roomId: String): Unit =
    assertBoth(caller, peer, roomId)
    Store.notifyUser(caller)
    Store.notifyUser(peer)

  /** the canonical room for (caller, invitee) when `invitee` names a user here,
   *  else "" — the DM-shaped `createRoom` path (rooms.scala) falls back to an
   *  ordinary room on "". */
  def canonicalRoom(caller: String, inviteeRaw: String): String =
    val peer = normalize(inviteeRaw)
    if peer == "" then ""
    else if peer == caller then ""
    else getOrCreate(caller, peer)

  /** a canonical DM's state, in the order it is written: the ordinary
   *  private-room preamble, both members (the caller joined, the peer invited,
   *  `is_direct` on BOTH), then the two identity carriers. */
  def seeds(caller: String, peer: String): List[StateSeed] =
    var xs: List[StateSeed] = Nil
    xs = StateSeed("m.room.canonical_alias", "", obj1("alias", JStr(aliasOf(caller, peer)))) :: xs
    xs = StateSeed("net.wata.dm", "", dmContent(caller, peer)) :: xs
    xs = StateSeed("m.room.member", peer, Rooms.memberInviteContent(peer, true)) :: xs
    xs = StateSeed("m.room.member", caller, Rooms.memberJoinContent(caller, true)) :: xs
    xs = StateSeed("m.room.power_levels", "", Rooms.powerLevels(caller, "trusted_private_chat", oneInvite(peer))) :: xs
    xs = StateSeed("m.room.guest_access", "", obj1("guest_access", JStr("can_join"))) :: xs
    xs = StateSeed("m.room.history_visibility", "", obj1("history_visibility", JStr("shared"))) :: xs
    xs = StateSeed("m.room.join_rules", "", obj1("join_rule", JStr("invite"))) :: xs
    xs = StateSeed("m.room.create", "", Rooms.createContent(caller)) :: xs
    xs

  def oneInvite(peer: String): List[String] =
    var xs: List[String] = Nil
    xs = peer :: xs
    xs

  // ---- boot migration ----------------------------------------------------------

  /** derive the pair map from the rooms already in the store. Runs at boot,
   *  AFTER journal replay (so pairs the journal already carries are in place
   *  and win), single-threaded, before serving. */
  def migrate(): Unit = migrateRooms(Store.allRooms())

  def migrateRooms(rs: List[Room]): Unit = rs match
    case h :: t => migrateStep(h, t)
    case Nil  => ()

  def migrateStep(h: Room, t: List[Room]): Unit =
    migrateRoom(h.roomId, pairOf(h))
    migrateRooms(t)

  def migrateRoom(roomId: String, pair: List[String]): Unit = pair match
    case a :: rest => migrateWith(roomId, a, rest)
    case Nil  => ()

  def migrateWith(roomId: String, a: String, rest: List[String]): Unit = rest match
    case b :: _ => claim(roomId, a, b)
    case Nil  => ()

  def claim(roomId: String, a: String, b: String): Unit =
    if Store.registerDmPair(a, b, roomId) then assertBoth(a, b, roomId) else ()

  /** a room's DM pair: the `net.wata.dm` members when it is stamped, else the
   *  LEGACY shape a stock client leaves behind — exactly two join/invite
   *  members with `is_direct` on one of their member events. */
  def pairOf(room: Room): List[String] =
    val stamped = dmMembersOf(room)
    if notEmptyS(stamped) then stamped else legacyPair(room)

  def legacyPair(room: Room): List[String] =
    if legacyDirect(room.state) then twoMembers(memberIds(room.state, Nil)) else Nil

  def legacyDirect(state: List[(String, Event)]): Boolean = state match
    case p :: t => legacyDirectStep(p, t)
    case Nil  => false

  def legacyDirectStep(p: (String, Event), t: List[(String, Event)]): Boolean =
    val ev: Event = p._2
    if ev.etype == "m.room.member" && boolField(ev.content, "is_direct") then true
    else legacyDirect(t)

  def memberIds(state: List[(String, Event)], acc: List[String]): List[String] = state match
    case p :: t => memberIdsStep(p, t, acc)
    case Nil  => ListOps.reverse(acc)

  def memberIdsStep(p: (String, Event), t: List[(String, Event)], acc: List[String]): List[String] =
    val ev: Event = p._2
    var acc2: List[String] = acc
    if ev.etype == "m.room.member" && joinOrInvite(strField(ev.content, "membership", "")) then
      acc2 = ev.stateKey :: acc2
    else ()
    memberIds(t, acc2)

  def joinOrInvite(m: String): Boolean = m == "join" || m == "invite"

  /** EXACTLY two — a bigger or smaller member set is not a pair. */
  def twoMembers(ids: List[String]): List[String] = ids match
    case a :: rest => twoStep(a, rest)
    case Nil  => Nil

  def twoStep(a: String, rest: List[String]): List[String] = rest match
    case b :: more => twoDone(a, b, more)
    case Nil  => Nil

  def twoDone(a: String, b: String, more: List[String]): List[String] = more match
    case _ :: _ => Nil
    case Nil  => pairList(a, b)

  def pairList(a: String, b: String): List[String] =
    var xs: List[String] = Nil
    xs = b :: xs
    xs = a :: xs
    xs

  def notEmptyS(xs: List[String]): Boolean = xs match
    case _ :: _ => true
    case Nil  => false

  // ---- the compat projection (one-way, disposable) -----------------------------
  //
  // Stock clients (a parent's phone running Element) know only `m.direct`, so
  // the server DERIVES it from its pairs. Client writes to `m.direct` are
  // accepted and stored, but never load-bearing: the server re-asserts its own
  // pairs on top, both when a pair is claimed (a real account-data write, so
  // the change rides the ordinary incremental /sync delta) and again at
  // EMISSION (so a client that overwrites the entry is corrected on its next
  // sync). Deleting this projection later touches nothing in the core.

  def assertBoth(a: String, b: String, roomId: String): Unit =
    assertDirect(a, b, roomId)
    assertDirect(b, a, roomId)

  def assertDirect(user: String, peer: String, roomId: String): Unit =
    Store.setAccountData(user, false, "", "m.direct", withPeerRoom(currentDirect(user), peer, roomId))

  def currentDirect(user: String): Json = Store.getAccountData(user, false, "", "m.direct") match
    case s: Some[AcctData] => s.value.content
    case None => emptyObj

  /** the canonical room FIRST in the peer's list, any other rooms the client
   *  wrote kept after it. */
  def withPeerRoom(c: Json, peer: String, roomId: String): Json =
    // cons BOUND first: a `::` in argument position does not lower correctly.
    val rest = othersOf(c, peer, roomId)
    val xs = JStr(roomId) :: rest
    jsonSet(c, peer, JArr(xs))

  def othersOf(c: Json, peer: String, roomId: String): List[Json] =
    dropRoom(Rooms.arrStrings(fieldOr(c, peer)), roomId, Nil)

  def fieldOr(c: Json, key: String): Json = getField(c, key) match
    case s: Some[Json] => s.value
    case None => JArr(Nil)

  def dropRoom(xs: List[String], roomId: String, acc: List[Json]): List[Json] = xs match
    case h :: t => dropRoomStep(h, t, roomId, acc)
    case Nil  => ListOps.reverse(acc)

  def dropRoomStep(h: String, t: List[String], roomId: String, acc: List[Json]): List[Json] =
    var acc2: List[Json] = acc
    if h != roomId then acc2 = JStr(h) :: acc2 else ()
    dropRoom(t, roomId, acc2)

  /** /sync's account-data projection: every `m.direct` entry in the delta with
   *  this user's pairs re-asserted on top. */
  def project(userId: String, items: List[AcctData]): List[AcctData] =
    projectGo(items, userId, Store.dmPeersOf(userId), Nil)

  def projectGo(xs: List[AcctData], userId: String, ps: List[DmPeer], acc: List[AcctData]): List[AcctData] = xs match
    case h :: t => projectStep(h, t, userId, ps, acc)
    case Nil  => ListOps.reverse(acc)

  def projectStep(h: AcctData, t: List[AcctData], userId: String, ps: List[DmPeer], acc: List[AcctData]): List[AcctData] =
    var acc2: List[AcctData] = acc
    if h.dtype == "m.direct" then acc2 = projected(h, ps) :: acc2 else acc2 = h :: acc2
    projectGo(t, userId, ps, acc2)

  def projected(a: AcctData, ps: List[DmPeer]): AcctData =
    AcctData(a.userId, a.hasRoom, a.roomId, a.dtype, assertPairs(a.content, ps), a.seq)

  def assertPairs(c: Json, ps: List[DmPeer]): Json = ps match
    case h :: t => assertPairs(withPeerRoom(c, h.peer, h.roomId), t)
    case Nil  => c
