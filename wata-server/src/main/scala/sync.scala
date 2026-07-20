import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** M7 chunk 4 — `/sync` (sync.ts) + the long-poll (decision 4). THE CONC SHOWCASE.
 *
 *  Two halves:
 *
 *   (1) The PURE sync builder (`buildParts` → `partsToJson`): since-token
 *       sequencing, initial vs incremental, per-room join/invite blocks, the
 *       state-vs-timeline split, summary/heroes, unread counts, ephemeral
 *       receipts (m.receipt edu), and global account-data deltas. Assembled from
 *       the store's persistent snapshots via the JsonNav builders. Deterministic
 *       modulo the wall-clock `unsigned.age`.
 *
 *   (2) The LONG-POLL: a per-request waiter channel registered when an
 *       incremental sync finds no changes and `timeout > 0`; the handler selects
 *       over {waiter, timer} via `sgo.select2` + `go.time.After`. See store.scala
 *       for the waiter lifecycle and the no-lost-wake ordering.
 *
 *  SPEC-CORRECTED over dormant sync.ts (recorded in the chunk report):
 *   - sync.ts's incremental branch carries an `if (fullState)` sub-case that is
 *     DEAD: the function returns from the initial branch whenever `fullState` is
 *     true, so that arm is unreachable. We drop it; `fullState` always routes to
 *     the initial (full state + full timeline) shape, matching the reachable TS.
 *
 *  Subset idioms as elsewhere in wata-server: every list is built by prepending
 *  single conses onto a `var` and reversing (endObj / explicit reverse); every
 *  match arm is a single call to a Step function; object-field order follows the
 *  TS for readability (JSON order is not semantic; the oracle parses).
 */

/** The three deltas a sync response is assembled from, kept as lists so
 *  `hasChangesOf` can test emptiness WITHOUT re-navigating JSON (the sync.ts
 *  `hasChanges(response)` re-reads the built object; we split it). */
case class SyncParts(joins: List[(String, Json)], invites: List[(String, Json)], global: List[AcctData], upTo: scala.Long)

object Sync:

  // ---- handler entry (auth + query parse + long-poll orchestration) ----------

  def handle(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] => handleAuthed(rr.right.userId, r)

  def handleAuthed(userId: String, r: go.net.http.Request): Either[MErr, Json] =
    val q = r.uRL.query()
    val since = q.get("since")
    val hasSince = since != ""
    val sinceSeq = parseSince(since)
    val timeoutMs = parseTimeout(q.get("timeout"))
    val fullState = q.get("full_state") == "true"
    Right(syncResult(userId, hasSince, sinceSeq, fullState, timeoutMs))

  /** Build once; long-poll only when this is an incremental sync (`hasSince`)
   *  with no changes and a positive timeout — mirrors sync.ts exactly. */
  def syncResult(userId: String, hasSince: Boolean, sinceSeq: scala.Long, fullState: Boolean, timeoutMs: scala.Int): Json =
    val parts = buildParts(userId, hasSince, sinceSeq, fullState)
    if hasSince && timeoutMs > 0 && !hasChangesOf(parts) then longPoll(userId, sinceSeq, fullState, timeoutMs)
    else partsToJson(parts)

  /** The long-poll (decision 4). Register FIRST, then re-check: the registration
   *  is the store-commit that the no-lost-wake argument pivots on (store.scala).
   *  If the re-check already has data, drop the waiter and return it; else wait
   *  on {waiter-close, timer}, then remove the waiter and rebuild. Whether the
   *  wake was real, spurious, or the timer, we rebuild once and return (sync.ts
   *  does not re-loop). */
  def longPoll(userId: String, sinceSeq: scala.Long, fullState: Boolean, timeoutMs: scala.Int): Json =
    val w = Store.registerWaiter(userId)
    val parts2 = buildParts(userId, true, sinceSeq, fullState)
    if hasChangesOf(parts2) then finishNoWait(w, parts2)
    else waitThenBuild(w, userId, sinceSeq, fullState, timeoutMs)

  def finishNoWait(w: Waiter, parts: SyncParts): Json =
    Store.removeWaiter(w.id)
    partsToJson(parts)

  def waitThenBuild(w: Waiter, userId: String, sinceSeq: scala.Long, fullState: Boolean, timeoutMs: scala.Int): Json =
    Store.waitForEvents(w, timeoutMs)
    Store.removeWaiter(w.id)
    partsToJson(buildParts(userId, true, sinceSeq, fullState))

  // ---- query-param parsing (Go strconv, throws caught) -----------------------

  def parseSince(since: String): scala.Long =
    if since == "" then 0L else parseLong(stripS(since))

  def stripS(s: String): String = if s.startsWith("s") then s.substring(1) else s

  def parseLong(s: String): scala.Long =
    var out = 0L
    try
      val v = go.strconv.parseInt(s, 10, 64)
      out = v
      ()
    catch case e: sgo.GoError => out = 0L
    out

  def parseTimeout(s: String): scala.Int =
    var out = 0
    try
      val v = go.strconv.atoi(s)
      out = v.toInt
      ()
    catch case e: sgo.GoError => out = 0
    out

  // ---- the pure builder ------------------------------------------------------

  /** Capture the next_batch seq (`upTo`) BEFORE reading any timeline: the token
   *  must be <= the seq of every event NOT yet included, or an event committed
   *  between the timeline read and the token read would be skipped forever by the
   *  next incremental sync (a lost message under concurrent sends — the
   *  check-then-act-across-two-locks softness the chunk-3 report flagged). Reading
   *  `upTo` first means any event with seq <= upTo was committed before we read the
   *  timeline (so it is included), and any event with seq > upTo is caught next
   *  sync. Including an event with seq > upTo is harmless (the client dedupes). */
  def buildParts(userId: String, hasSince: Boolean, sinceSeq: scala.Long, fullState: Boolean): SyncParts =
    val upTo = Store.globalSeq()
    if !hasSince || fullState then initialParts(userId, upTo)
    else incrParts(userId, sinceSeq, upTo)

  def hasChangesOf(p: SyncParts): Boolean =
    notEmptyPairs(p.joins) || notEmptyPairs(p.invites) || notEmptyAcct(p.global)

  def partsToJson(p: SyncParts): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("next_batch", JStr("s" + JsonNav.longStr(p.upTo))) :: fs
    fs = ("rooms", roomsObj(p.joins, p.invites)) :: fs
    fs = ("account_data", obj1("events", acctEventsArr(p.global))) :: fs
    endObj(fs)

  def roomsObj(joins: List[(String, Json)], invites: List[(String, Json)]): Json =
    obj3("join", JObj(joins), "invite", JObj(invites), "leave", emptyObj)

  // ---- initial sync ----------------------------------------------------------

  def initialParts(userId: String, upTo: scala.Long): SyncParts =
    val joins = buildJoinsInitial(Store.roomsForUser(userId, "join"), userId, Nil)
    val invites = buildInvites(Store.roomsForUser(userId, "invite"), Nil)
    val global = Store.allAccountData(userId, false, "")
    SyncParts(joins, invites, global, upTo)

  def buildJoinsInitial(rooms: List[Room], userId: String, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildJoinsInitialStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def buildJoinsInitialStep(h: Room, t: List[Room], userId: String, acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    acc2 = (h.roomId, joinBlockInitial(h, userId)) :: acc2
    buildJoinsInitial(t, userId, acc2)

  def joinBlockInitial(room: Room, userId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("summary", summaryOf(room, userId)) :: fs
    fs = ("state", obj1("events", stateValuesArr(room.state))) :: fs
    fs = ("timeline", timelineObj(eventsArr(room.timeline), "s0")) :: fs
    fs = ("ephemeral", obj1("events", formatReceipts(Store.receiptsForRoom(room.roomId)))) :: fs
    fs = ("account_data", obj1("events", acctEventsArr(Store.allAccountData(userId, true, room.roomId)))) :: fs
    fs = ("unread_notifications", unreadZero) :: fs
    endObj(fs)

  // ---- incremental sync ------------------------------------------------------

  def incrParts(userId: String, sinceSeq: scala.Long, upTo: scala.Long): SyncParts =
    val joins = buildJoinsIncr(Store.roomsForUser(userId, "join"), userId, sinceSeq, Nil)
    val invites = buildInvitesIncr(Store.roomsForUser(userId, "invite"), userId, sinceSeq, Nil)
    val global = Store.acctSinceGlobal(userId, sinceSeq)
    SyncParts(joins, invites, global, upTo)

  def buildJoinsIncr(rooms: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildJoinsIncrStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  /** SPEC-CORRECTION over dormant sync.ts (recorded in the report): sync.ts
   *  includes an incremental room block whenever it has ANY receipts ("receipts
   *  should always be included ... even if unchanged"), which makes `hasChanges`
   *  permanently true for any member of a room that ever got a receipt — so
   *  long-poll NEVER engages for them (a wake that can't happen). We gate
   *  inclusion on NEW timeline events OR NEW receipts (`getReceiptsSince`, the
   *  store method sync.ts left unused), while still sending ALL current read
   *  markers in the ephemeral block of an included room (the spec intent). */
  def buildJoinsIncrStep(h: Room, t: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] =
    val newEvents = Store.timelineSince(h.roomId, sinceSeq)
    val allReceipts = Store.receiptsForRoom(h.roomId)
    val newReceipts = Store.receiptsSinceRoom(h.roomId, sinceSeq)
    var acc2: List[(String, Json)] = acc
    if isEmptyEv(newEvents) && isEmptyR(newReceipts) then ()
    else acc2 = (h.roomId, joinBlockIncr(h, userId, sinceSeq, newEvents, allReceipts)) :: acc2
    buildJoinsIncr(t, userId, sinceSeq, acc2)

  def joinBlockIncr(room: Room, userId: String, sinceSeq: scala.Long, newEvents: List[Event], receipts: List[Receipt]): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("summary", summaryOf(room, userId)) :: fs
    fs = ("state", obj1("events", stateEventsIncr(newEvents))) :: fs
    fs = ("timeline", timelineObj(eventsArr(newEvents), "s" + JsonNav.longStr(sinceSeq))) :: fs
    fs = ("ephemeral", obj1("events", formatReceipts(receipts))) :: fs
    fs = ("account_data", obj1("events", JArr(Nil))) :: fs
    fs = ("unread_notifications", unreadZero) :: fs
    endObj(fs)

  /** incremental state: only the NEW events that carry a state key. */
  def stateEventsIncr(evs: List[Event]): Json = JArr(ListOps.reverse(filterStateEv(evs, Nil)))

  def filterStateEv(evs: List[Event], acc: List[Json]): List[Json] = evs match
    case h :: t => filterStateEvStep(h, t, acc)
    case Nil  => acc

  def filterStateEvStep(h: Event, t: List[Event], acc: List[Json]): List[Json] =
    var acc2: List[Json] = acc
    if h.hasStateKey then acc2 = stripAndAge(h) :: acc2 else ()
    filterStateEv(t, acc2)

  // ---- invited-room blocks ---------------------------------------------------

  def buildInvites(rooms: List[Room], acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildInvitesStep(h, t, acc)
    case Nil  => ListOps.reverse(acc)

  def buildInvitesStep(h: Room, t: List[Room], acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    acc2 = (h.roomId, inviteBlock(h)) :: acc2
    buildInvites(t, acc2)

  /** incremental invites: only rooms whose member event for this user is NEWER
   *  than the since-token (sync.ts's `inviteEvent._seq <= sinceSeq` skip). */
  def buildInvitesIncr(rooms: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildInvitesIncrStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def buildInvitesIncrStep(h: Room, t: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    if isNewInvite(h, userId, sinceSeq) then acc2 = (h.roomId, inviteBlock(h)) :: acc2 else ()
    buildInvitesIncr(t, userId, sinceSeq, acc2)

  def isNewInvite(room: Room, userId: String, sinceSeq: scala.Long): Boolean = Store.memberEvent(room, userId) match
    case s: Some[Event] => s.value.seq > sinceSeq
    case None => false

  def inviteBlock(room: Room): Json =
    obj1("invite_state", obj1("events", strippedInviteArr(room.state)))

  // ---- summary / heroes / member counts --------------------------------------

  def summaryOf(room: Room, userId: String): Json =
    obj3("m.heroes", strArr(heroesOf(room.state, userId)),
      "m.joined_member_count", JInt(joinedCount(room.state)),
      "m.invited_member_count", JInt(invitedCount(room.state)))

  /** up to five join/invite members other than this user, in state order. */
  def heroesOf(state: List[(String, Event)], userId: String): List[String] =
    take5(heroesList(state, userId, Nil))

  def heroesList(state: List[(String, Event)], userId: String, acc: List[String]): List[String] = state match
    case p :: t => heroesListStep(p, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def heroesListStep(p: (String, Event), t: List[(String, Event)], userId: String, acc: List[String]): List[String] =
    val ev: Event = p._2
    var acc2: List[String] = acc
    if isHero(ev, userId) then acc2 = ev.stateKey :: acc2 else ()
    heroesList(t, userId, acc2)

  def isHero(ev: Event, userId: String): Boolean =
    ev.etype == "m.room.member" && ev.hasStateKey && ev.stateKey != userId &&
      joinOrInvite(strField(ev.content, "membership", ""))

  def joinOrInvite(m: String): Boolean = m == "join" || m == "invite"

  def joinedCount(state: List[(String, Event)]): scala.Long = countMembership(state, "join", 0L)
  def invitedCount(state: List[(String, Event)]): scala.Long = countMembership(state, "invite", 0L)

  def countMembership(state: List[(String, Event)], want: String, n: scala.Long): scala.Long = state match
    case p :: t => countMembershipStep(p, t, want, n)
    case Nil  => n

  def countMembershipStep(p: (String, Event), t: List[(String, Event)], want: String, n: scala.Long): scala.Long =
    val ev: Event = p._2
    var n2: scala.Long = n
    if ev.etype == "m.room.member" && strField(ev.content, "membership", "") == want then n2 = n + 1L else ()
    countMembership(t, want, n2)

  def unreadZero: Json = obj2("highlight_count", JInt(0L), "notification_count", JInt(0L))

  // ---- event / state serialization -------------------------------------------

  def timelineObj(events: Json, prevBatch: String): Json =
    obj3("events", events, "limited", JBool(false), "prev_batch", JStr(prevBatch))

  /** sync.ts `stripSeqAndAddAge`: the wire event, with `unsigned.age = now - ts`
   *  merged in (the internal `_seq`/`Event.seq` never crosses the wire). */
  def stripAndAge(ev: Event): Json =
    JsonNav.jsonSet(JsonNav.eventToJson(ev), "unsigned", ageMerge(ev.unsigned, ev.ts))

  def ageMerge(u: Json, ts: scala.Long): Json =
    JsonNav.jsonSet(unsignedBase(u), "age", JInt(Store.nowMs() - ts))

  def unsignedBase(u: Json): Json = u match
    case _: JNull => JsonNav.emptyObj
    case _        => u

  def eventsArr(evs: List[Event]): Json = JArr(ListOps.reverse(mapStrip(evs, Nil)))

  def mapStrip(evs: List[Event], acc: List[Json]): List[Json] = evs match
    case h :: t => mapStrip(t, stripAndAge(h) :: acc)
    case Nil  => acc

  def stateValuesArr(state: List[(String, Event)]): Json = JArr(ListOps.reverse(mapStripState(state, Nil)))

  def mapStripState(state: List[(String, Event)], acc: List[Json]): List[Json] = state match
    case p :: t => mapStripStateStep(p, t, acc)
    case Nil  => acc

  def mapStripStateStep(p: (String, Event), t: List[(String, Event)], acc: List[Json]): List[Json] =
    val ev: Event = p._2
    mapStripState(t, stripAndAge(ev) :: acc)

  /** stripped state for an invited room: {type, state_key, content, sender}. */
  def strippedInviteArr(state: List[(String, Event)]): Json = JArr(ListOps.reverse(mapStripped(state, Nil)))

  def mapStripped(state: List[(String, Event)], acc: List[Json]): List[Json] = state match
    case p :: t => mapStrippedStep(p, t, acc)
    case Nil  => acc

  def mapStrippedStep(p: (String, Event), t: List[(String, Event)], acc: List[Json]): List[Json] =
    val ev: Event = p._2
    mapStripped(t, strippedEvent(ev) :: acc)

  def strippedEvent(ev: Event): Json =
    obj4("type", JStr(ev.etype), "state_key", JStr(ev.stateKey), "content", ev.content, "sender", JStr(ev.sender))

  // ---- account-data events ---------------------------------------------------

  def acctEventsArr(items: List[AcctData]): Json = JArr(ListOps.reverse(acctEvents(items, Nil)))

  def acctEvents(items: List[AcctData], acc: List[Json]): List[Json] = items match
    case h :: t => acctEvents(t, acctEventObj(h) :: acc)
    case Nil  => acc

  def acctEventObj(a: AcctData): Json = obj2("type", JStr(a.dtype), "content", a.content)

  // ---- ephemeral receipts (m.receipt edu) ------------------------------------
  //
  // sync.ts `formatReceipts`: group by eventId → receiptType → userId → {ts},
  // wrapped as [{type:"m.receipt", content: grouped}], or [] when empty. Built by
  // folding the flat receipt list into a nested JObj via jsonSet.

  def formatReceipts(rs: List[Receipt]): Json =
    if isEmptyR(rs) then JArr(Nil)
    else arr1(obj2("type", JStr("m.receipt"), "content", groupReceipts(rs, emptyObj)))

  def groupReceipts(rs: List[Receipt], content: Json): Json = rs match
    case h :: t => groupReceipts(t, addReceipt(content, h))
    case Nil  => content

  def addReceipt(content: Json, r: Receipt): Json =
    val byEvent = objFieldOr(content, r.eventId)
    val byType = objFieldOr(byEvent, r.receiptType)
    val withUser = JsonNav.jsonSet(byType, r.userId, obj1("ts", JInt(r.ts)))
    val newByType = JsonNav.jsonSet(byEvent, r.receiptType, withUser)
    JsonNav.jsonSet(content, r.eventId, newByType)

  def objFieldOr(j: Json, key: String): Json = getField(j, key) match
    case s: Some[Json] => s.value
    case None => emptyObj

  // ---- list helpers ----------------------------------------------------------

  def strArr(xs: List[String]): Json = JArr(ListOps.reverse(strJsons(xs, Nil)))

  def strJsons(xs: List[String], acc: List[Json]): List[Json] = xs match
    case h :: t => strJsons(t, JStr(h) :: acc)
    case Nil  => acc

  def take5(xs: List[String]): List[String] = takeN(xs, 5, Nil)

  def takeN(xs: List[String], n: scala.Int, acc: List[String]): List[String] =
    if n <= 0 then ListOps.reverse(acc) else takeNStep(xs, n, acc)

  def takeNStep(xs: List[String], n: scala.Int, acc: List[String]): List[String] = xs match
    case h :: t => takeN(t, n - 1, h :: acc)
    case Nil  => ListOps.reverse(acc)

  def isEmptyEv(xs: List[Event]): Boolean = xs match
    case _ :: _ => false
    case Nil  => true

  def isEmptyR(xs: List[Receipt]): Boolean = xs match
    case _ :: _ => false
    case Nil  => true

  def notEmptyPairs(xs: List[(String, Json)]): Boolean = xs match
    case _ :: _ => true
    case Nil  => false

  def notEmptyAcct(xs: List[AcctData]): Boolean = xs match
    case _ :: _ => true
    case Nil  => false
