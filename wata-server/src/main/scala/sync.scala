import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** `/sync` and the long-poll wait. This is the most concurrency-sensitive file
 *  in the server.
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
 *  `fullState` always routes to the initial (full state + full timeline)
 *  shape, whether or not a `since` token is present.
 *
 *  Subset idioms as elsewhere in wata-server: every list is built by prepending
 *  single conses onto a `var` and reversing (endObj / explicit reverse); every
 *  match arm is a single call to a Step function; JSON field order is not
 *  semantic, so it is chosen for readability.
 */

/** The four deltas a sync response is assembled from, kept as lists so
 *  `hasChangesOf` can test emptiness WITHOUT re-navigating the built JSON. */
case class SyncParts(joins: List[(String, Json)], invites: List[(String, Json)],
                     leaves: List[(String, Json)], global: List[AcctData], upTo: scala.Long)

/** One room block's timeline window: the events actually sent, whether older
 *  ones were withheld (`limited`), and the `prev_batch` position the client
 *  pages back from. */
case class TimelineWin(events: List[Event], limited: Boolean, prevBatch: String)

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
   *  with no changes and a positive timeout. */
  def syncResult(userId: String, hasSince: Boolean, sinceSeq: scala.Long, fullState: Boolean, timeoutMs: scala.Int): Json =
    val parts = buildParts(userId, hasSince, sinceSeq, fullState)
    if hasSince && timeoutMs > 0 && !hasChangesOf(parts) then longPoll(userId, sinceSeq, fullState, timeoutMs)
    else partsToJson(parts)

  /** The long-poll. Register FIRST, then re-check: the registration is the
   *  store-commit that the no-lost-wake argument pivots on (store.scala).
   *  If the re-check already has data, drop the waiter and return it; else wait
   *  on {waiter-close, timer}, then remove the waiter and rebuild. Whether the
   *  wake was real, spurious, or the timer, we rebuild once and return — there
   *  is no re-loop. */
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
   *  next incremental sync (a lost message under concurrent sends, since each
   *  store read below takes its own lock rather than one lock across the whole
   *  builder). Reading `upTo` first means any event with seq <= upTo was
   *  committed before we read the timeline (so it is included), and any event
   *  with seq > upTo is caught next sync. Including an event with seq > upTo is
   *  harmless (the client dedupes). */
  def buildParts(userId: String, hasSince: Boolean, sinceSeq: scala.Long, fullState: Boolean): SyncParts =
    val upTo = Store.globalSeq()
    if !hasSince || fullState then initialParts(userId, upTo)
    else incrParts(userId, sinceSeq, upTo)

  def hasChangesOf(p: SyncParts): Boolean =
    notEmptyPairs(p.joins) || notEmptyPairs(p.invites) || notEmptyPairs(p.leaves) || notEmptyAcct(p.global)

  def partsToJson(p: SyncParts): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("next_batch", JStr("s" + JsonNav.longStr(p.upTo))) :: fs
    fs = ("rooms", roomsObj(p.joins, p.invites, p.leaves)) :: fs
    fs = ("account_data", obj1("events", acctEventsArr(p.global))) :: fs
    endObj(fs)

  def roomsObj(joins: List[(String, Json)], invites: List[(String, Json)], leaves: List[(String, Json)]): Json =
    obj3("join", JObj(joins), "invite", JObj(invites), "leave", JObj(leaves))

  // ---- initial sync ----------------------------------------------------------

  /** An initial sync reports no `leave` rooms: the client is being told what it
   *  IS in, and a room it left has no place in a from-scratch view. Departures
   *  are a DELTA fact, so they are reported incrementally (`buildLeaves`). */
  def initialParts(userId: String, upTo: scala.Long): SyncParts =
    val joins = buildJoinsInitial(Store.roomsForUser(userId, "join"), userId, Nil)
    val invites = buildInvites(Store.roomsForUser(userId, "invite"), Nil)
    val global = Store.allAccountData(userId, false, "")
    SyncParts(joins, invites, Nil, global, upTo)

  def buildJoinsInitial(rooms: List[Room], userId: String, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildJoinsInitialStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def buildJoinsInitialStep(h: Room, t: List[Room], userId: String, acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    acc2 = (h.roomId, joinBlockInitial(h, userId)) :: acc2
    buildJoinsInitial(t, userId, acc2)

  def joinBlockInitial(room: Room, userId: String): Json =
    val win = windowOf(room.timeline, 0L)
    var fs: List[(String, Json)] = startObj
    fs = ("summary", summaryOf(room, userId)) :: fs
    fs = ("state", obj1("events", stateValuesArr(room.state))) :: fs
    fs = ("timeline", timelineObj(eventsArr(win.events), win.limited, win.prevBatch)) :: fs
    fs = ("ephemeral", obj1("events", formatReceipts(Store.receiptsForRoom(room.roomId)))) :: fs
    fs = ("account_data", obj1("events", acctEventsArr(Store.allAccountData(userId, true, room.roomId)))) :: fs
    fs = ("unread_notifications", unreadZero) :: fs
    endObj(fs)

  // ---- incremental sync ------------------------------------------------------

  def incrParts(userId: String, sinceSeq: scala.Long, upTo: scala.Long): SyncParts =
    val joins = buildJoinsIncr(Store.roomsForUser(userId, "join"), userId, sinceSeq, Nil)
    val invites = buildInvitesIncr(Store.roomsForUser(userId, "invite"), userId, sinceSeq, Nil)
    val leaves = buildLeaves(Store.roomsLeftBy(userId), userId, sinceSeq, Nil)
    val global = Store.acctSinceGlobal(userId, sinceSeq)
    SyncParts(joins, invites, leaves, global, upTo)

  def buildJoinsIncr(rooms: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildJoinsIncrStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  /** Gate inclusion of an incremental room block on NEW timeline events OR NEW
   *  receipts (`Store.receiptsSinceRoom`), rather than on having ANY receipts at
   *  all: the latter would make `hasChanges` permanently true for any member of
   *  a room that ever got a receipt, so long-poll would never engage for them
   *  (a wake that can never happen). An included room still sends ALL current
   *  read markers in its ephemeral block, matching the spec's intent that
   *  receipts always be included once a block is sent. */
  def buildJoinsIncrStep(h: Room, t: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] =
    val newEvents = Store.timelineSince(h.roomId, sinceSeq)
    val allReceipts = Store.receiptsForRoom(h.roomId)
    val newReceipts = Store.receiptsSinceRoom(h.roomId, sinceSeq)
    var acc2: List[(String, Json)] = acc
    acc2 = incrBlock(h, userId, sinceSeq, newEvents, allReceipts, newReceipts, acc)
    buildJoinsIncr(t, userId, sinceSeq, acc2)

  /** A room whose member event for this user is NEWER than the since-token is
   *  new to them in this delta: the room's history predates the token, so a
   *  timeline DELTA would silently omit everything said before they joined.
   *  Such a room is sent in the INITIAL shape instead — full state and a
   *  windowed timeline that reports `limited` when it withholds anything, so
   *  the client can page the rest back through /messages.
   *
   *  A display-name change rewrites the member event too and so re-sends the
   *  block; the client dedups it, and the alternative is tracking per-user
   *  membership history in the store for no behavioural gain. */
  def incrBlock(room: Room, userId: String, sinceSeq: scala.Long, newEvents: List[Event],
                receipts: List[Receipt], newReceipts: List[Receipt], acc: List[(String, Json)]): List[(String, Json)] =
    if memberEventIsNew(room, userId, sinceSeq) then (room.roomId, joinBlockInitial(room, userId)) :: acc
    else incrBlockDelta(room, userId, sinceSeq, newEvents, receipts, newReceipts, acc)

  def incrBlockDelta(room: Room, userId: String, sinceSeq: scala.Long, newEvents: List[Event],
                     receipts: List[Receipt], newReceipts: List[Receipt], acc: List[(String, Json)]): List[(String, Json)] =
    if isEmptyEv(newEvents) && isEmptyR(newReceipts) then acc
    else (room.roomId, joinBlockIncr(room, userId, sinceSeq, newEvents, receipts)) :: acc

  /** `state` carries the state deltas of EVERY new event, including any the
   *  timeline window withheld — the spec's "state between `since` and the start
   *  of the timeline" plus the window's own, so current state stays complete
   *  even when the timeline is `limited`. */
  def joinBlockIncr(room: Room, userId: String, sinceSeq: scala.Long, newEvents: List[Event], receipts: List[Receipt]): Json =
    val win = windowOf(newEvents, sinceSeq)
    var fs: List[(String, Json)] = startObj
    fs = ("summary", summaryOf(room, userId)) :: fs
    fs = ("state", obj1("events", stateEventsIncr(newEvents))) :: fs
    fs = ("timeline", timelineObj(eventsArr(win.events), win.limited, win.prevBatch)) :: fs
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
   *  than the since-token. */
  def buildInvitesIncr(rooms: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildInvitesIncrStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def buildInvitesIncrStep(h: Room, t: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    if memberEventIsNew(h, userId, sinceSeq) then acc2 = (h.roomId, inviteBlock(h)) :: acc2 else ()
    buildInvitesIncr(t, userId, sinceSeq, acc2)

  /** is this user's `m.room.member` event in the room newer than the
   *  since-token — i.e. did their membership change within this delta. */
  def memberEventIsNew(room: Room, userId: String, sinceSeq: scala.Long): Boolean = Store.memberEvent(room, userId) match
    case s: Some[Event] => s.value.seq > sinceSeq
    case None => false

  def inviteBlock(room: Room): Json =
    obj1("invite_state", obj1("events", strippedInviteArr(room.state)))

  // ---- left / banned rooms ---------------------------------------------------
  //
  // A room the user left (or was kicked or banned from) within this delta. The
  // block carries the departing `m.room.member` event itself and nothing else:
  // it exists so the client learns the room is gone, and nothing downstream of
  // a departure needs the room's history.

  def buildLeaves(rooms: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] = rooms match
    case h :: t => buildLeavesStep(h, t, userId, sinceSeq, acc)
    case Nil  => ListOps.reverse(acc)

  def buildLeavesStep(h: Room, t: List[Room], userId: String, sinceSeq: scala.Long, acc: List[(String, Json)]): List[(String, Json)] =
    var acc2: List[(String, Json)] = acc
    if memberEventIsNew(h, userId, sinceSeq) then acc2 = (h.roomId, leaveBlock(h, userId, sinceSeq)) :: acc2 else ()
    buildLeaves(t, userId, sinceSeq, acc2)

  def leaveBlock(room: Room, userId: String, sinceSeq: scala.Long): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("state", obj1("events", JArr(Nil))) :: fs
    fs = ("timeline", timelineObj(eventsArr(departure(room, userId)), false, tok(sinceSeq))) :: fs
    fs = ("account_data", obj1("events", JArr(Nil))) :: fs
    endObj(fs)

  def departure(room: Room, userId: String): List[Event] = Store.memberEvent(room, userId) match
    case s: Some[Event] => oneEvent(s.value)
    case None => Nil

  def oneEvent(ev: Event): List[Event] =
    var xs: List[Event] = Nil
    xs = ev :: xs
    xs

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

  def timelineObj(events: Json, limited: Boolean, prevBatch: String): Json =
    obj3("events", events, "limited", JBool(limited), "prev_batch", JStr(prevBatch))

  // ---- the timeline window (`limited` + `prev_batch`) ------------------------
  //
  // A room block carries at most `timelineWindow` events. A longer run is a GAP:
  // `limited` says so and `prev_batch` names the position immediately BEFORE the
  // oldest event sent, so GET /messages(from = prev_batch, dir = b) returns
  // exactly the withheld run — the tokens are the same `s<seq>` positions /sync
  // hands out as `next_batch`.

  def timelineWindow: scala.Int = 20

  /** an `s<seq>` position token. */
  def tok(seq: scala.Long): String = "s" + JsonNav.longStr(seq)

  /** the newest `timelineWindow` events of `evs`. `floor` is the position the
   *  caller measured `evs` from (0 for an initial sync, the since-token for an
   *  incremental one) — it is the `prev_batch` when nothing was withheld. */
  def windowOf(evs: List[Event], floor: scala.Long): TimelineWin =
    val n = countEv(evs, 0)
    if n <= timelineWindow then TimelineWin(evs, false, tok(floor))
    else windowCut(evs, n - timelineWindow)

  def windowCut(evs: List[Event], cut: scala.Int): TimelineWin =
    TimelineWin(dropFirst(evs, cut), true, tok(seqAt(evs, cut - 1)))

  def countEv(xs: List[Event], n: scala.Int): scala.Int = xs match
    case _ :: t => countEv(t, n + 1)
    case Nil  => n

  def seqAt(xs: List[Event], i: scala.Int): scala.Long = xs match
    case h :: t => seqAtStep(h, t, i)
    case Nil  => 0L

  def seqAtStep(h: Event, t: List[Event], i: scala.Int): scala.Long =
    if i <= 0 then h.seq else seqAt(t, i - 1)

  def dropFirst(xs: List[Event], n: scala.Int): List[Event] =
    if n <= 0 then xs else dropFirstStep(xs, n)

  def dropFirstStep(xs: List[Event], n: scala.Int): List[Event] = xs match
    case _ :: t => dropFirst(t, n - 1)
    case Nil  => Nil

  /** the wire event, with `unsigned.age = now - ts` merged in (the internal
   *  `Event.seq` never crosses the wire). */
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
  // Group receipts by eventId → receiptType → userId → {ts}, wrapped as
  // [{type:"m.receipt", content: grouped}], or [] when empty. Built by folding
  // the flat receipt list into a nested JObj via jsonSet.

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
