/** the SYNC ENGINE: a pure state machine, sync-response `Json` in -> state
 *  delta + `SyncEvent`s out. PORTABLE (zero `go` facade use); the `json`
 *  module is the wire currency.
 *
 *  Notes on the representation:
 *   - There are no plain mutable classes in this language subset (only case
 *     classes / objects), so the engine is a module `object` with private
 *     `var` state + `reset()`. One engine per process (the real client has
 *     exactly one; tests `reset()` between scenarios).
 *   - The rooms/members/receipts collections are insertion-ordered `List`s
 *     with replace-keeps-position upsert, matching insertion-ordered map
 *     semantics — `buildSnapshot`'s contact/conversation order depends on
 *     this. Lookups are linear scans; wata's room/member counts are
 *     family-sized, so this is not a scaling concern here.
 *   - DM CLASSIFICATION is structural: a room is a DM when its state carries
 *     the server's `net.wata.dm` stamp, whose `members` pair says who it is
 *     with. The engine holds no `m.direct` state and infers nothing from
 *     `is_direct` — DM identity is the server's (docs/plans/0007), and the
 *     `m.direct` the server still emits is a compat projection for stock
 *     clients that this engine simply ignores.
 *   - Optional wire fields (`next_batch`, `canonical_alias`, `prev_batch`,
 *     `redacts`, `state_key`) become "" -or- has-flag pairs. Where PRESENCE
 *     matters (canonical_alias clearing, state_key gating) the code tests
 *     field presence via `WJson.getField`, not ""-ness; `redacts`/`prev_batch`
 *     use "" as none ("" is not a valid event id / batch token).
 *   - The sync response is walked directly as a `Json` tree rather than
 *     through a typed pre-parsed struct — `WJson` field reads apply the same
 *     tolerant absent-field defaulting a typed decode with optional fields
 *     would.
 *   - The engine intentionally does not process `rooms.leave` (it never
 *     removes rooms) and does not act on `limited` inside `process()` itself —
 *     room removal wata's flows don't need, and backfill is triggered a level
 *     up, by `Runtime.queueIfLimited`.
 *
 *  Event emission ORDER is asserted by the fixture oracle: joined rooms in
 *  wire order (state membership events, timeline events, receipt events, then
 *  `room_updated` LAST per room), then invited rooms (invite_state membership
 *  events, `room_updated`).
 */
object SyncEngine:

  // ---- the processor state ------------------------------------------------------
  // Module-level mutable state lives in `val`-held Atomic cells — the engine
  // stays single-goroutine by protocol (one sync loop per process), but the
  // cells make the by-naming reachability data-race-free rather than merely
  // conventional. Reads go through private accessor defs; writes go through
  // `.set`. Whole-record swaps of immutable snapshots is the engine's
  // discipline throughout.
  private val roomsC: sgo.Atomic[List[RoomState]] = sgo.atomic(Nil)
  private val selfUserIdC: sgo.Atomic[String] = sgo.atomic("")
  private val batchC: sgo.Atomic[String] = sgo.atomic("")
  private def rooms: List[RoomState] = roomsC.get()
  private def selfUserId: String = selfUserIdC.get()
  private def batch: String = batchC.get()

  def reset(): Unit =
    roomsC.set(Nil)
    selfUserIdC.set("")
    batchC.set("")

  /** set after login/whoami. */
  def setSelfUser(uid: String): Unit = selfUserIdC.set(uid)

  def nextBatch: String = batch
  def selfUser: String = selfUserId

  /** read-only view for oracles/drivers (insertion order preserved). */
  def allRooms: List[RoomState] = rooms

  // ---- room-list plumbing (insertion-ordered, replace keeps position) --------

  def roomCount: Int =
    var n = 0
    var cur = rooms
    var going = true
    while going do
      cur match
        case c: ::[RoomState] => n += 1; cur = c.tail
        case Nil            => going = false
    n

  def findRoom(roomId: String): Option[RoomState] = findRoomIn(rooms, roomId)

  def findRoomIn(rs: List[RoomState], roomId: String): Option[RoomState] = rs match
    case h :: t => findRoomStep(h, t, roomId)
    case Nil  => None

  def findRoomStep(h: RoomState, t: List[RoomState], roomId: String): Option[RoomState] =
    val k: String = h.roomId
    if k == roomId then Some(h) else findRoomIn(t, roomId)

  def hasRoom(roomId: String): Boolean = findRoom(roomId) match
    case _: Some[RoomState] => true
    case None => false

  def roomOr(roomId: String, dflt: RoomState): RoomState = findRoom(roomId) match
    case s: Some[RoomState] => s.value
    case None => dflt

  def emptyRoom(roomId: String): RoomState =
    RoomState(roomId, "", false, "", Nil, Nil,
      Nil, Nil, "", Nil)

  /** getOrCreateRoom: append a fresh RoomState if absent (insertion order). */
  def ensureRoom(roomId: String): Unit =
    if !hasRoom(roomId) then roomsC.set(appendRoom(rooms, emptyRoom(roomId)))

  def appendRoom(rs: List[RoomState], r: RoomState): List[RoomState] =
    ListOps.reverse(r :: ListOps.reverse(rs))

  /** swap the room record with the same roomId (position preserved). */
  def updateRoom(r: RoomState): Unit =
    roomsC.set(replaceRoom(rooms, r, Nil))

  def replaceRoom(rs: List[RoomState], r: RoomState, acc: List[RoomState]): List[RoomState] = rs match
    case h :: t => replaceRoomStep(h, t, r, acc)
    case Nil  => ListOps.reverse(acc)

  def replaceRoomStep(h: RoomState, t: List[RoomState], r: RoomState, acc: List[RoomState]): List[RoomState] =
    val k: String = h.roomId
    var acc2: List[RoomState] = acc
    if k == r.roomId then acc2 = r :: acc2 else acc2 = h :: acc2
    replaceRoom(t, r, acc2)

  // ---- member-list plumbing ---------------------------------------------------

  def findMember(ms: List[MemberInfo], userId: String): Option[MemberInfo] = ms match
    case h :: t => findMemberStep(h, t, userId)
    case Nil  => None

  def findMemberStep(h: MemberInfo, t: List[MemberInfo], userId: String): Option[MemberInfo] =
    val k: String = h.userId
    if k == userId then Some(h) else findMember(t, userId)

  /** ArrayHashMap getOrPut + overwrite: replace keeps position, else append. */
  def upsertMember(ms: List[MemberInfo], m: MemberInfo): List[MemberInfo] =
    if memberExists(ms, m.userId) then replaceMember(ms, m, Nil)
    else ListOps.reverse(m :: ListOps.reverse(ms))

  def memberExists(ms: List[MemberInfo], userId: String): Boolean = findMember(ms, userId) match
    case _: Some[MemberInfo] => true
    case None => false

  def replaceMember(ms: List[MemberInfo], m: MemberInfo, acc: List[MemberInfo]): List[MemberInfo] = ms match
    case h :: t => replaceMemberStep(h, t, m, acc)
    case Nil  => ListOps.reverse(acc)

  def replaceMemberStep(h: MemberInfo, t: List[MemberInfo], m: MemberInfo, acc: List[MemberInfo]): List[MemberInfo] =
    val k: String = h.userId
    var acc2: List[MemberInfo] = acc
    if k == m.userId then acc2 = m :: acc2 else acc2 = h :: acc2
    replaceMember(t, m, acc2)

  /** the count of joined members in a room. */
  def joinedMemberCount(r: RoomState): Int =
    var n = 0
    var cur = r.members
    var going = true
    while going do
      cur match
        case c: ::[MemberInfo] =>
          if c.head.membership == "join" then n += 1
          cur = c.tail
        case Nil => going = false
    n

  // ---- string-list / receipt plumbing ----------------------------------------

  def strListContains(xs: List[String], s: String): Boolean = xs match
    case h :: t => strListContainsStep(h, t, s)
    case Nil  => false

  def strListContainsStep(h: String, t: List[String], s: String): Boolean =
    if h == s then true else strListContains(t, s)

  def appendStr(xs: List[String], s: String): List[String] =
    ListOps.reverse(s :: ListOps.reverse(xs))

  def findReceipt(res: List[ReceiptEntry], eventId: String): Option[ReceiptEntry] = res match
    case h :: t => findReceiptStep(h, t, eventId)
    case Nil  => None

  def findReceiptStep(h: ReceiptEntry, t: List[ReceiptEntry], eventId: String): Option[ReceiptEntry] =
    val k: String = h.eventId
    if k == eventId then Some(h) else findReceipt(t, eventId)

  def replaceReceipt(res: List[ReceiptEntry], e: ReceiptEntry, acc: List[ReceiptEntry]): List[ReceiptEntry] = res match
    case h :: t => replaceReceiptStep(h, t, e, acc)
    case Nil  => ListOps.reverse(acc)

  def replaceReceiptStep(h: ReceiptEntry, t: List[ReceiptEntry], e: ReceiptEntry, acc: List[ReceiptEntry]): List[ReceiptEntry] =
    val k: String = h.eventId
    var acc2: List[ReceiptEntry] = acc
    if k == e.eventId then acc2 = e :: acc2 else acc2 = h :: acc2
    replaceReceipt(t, e, acc2)

  def upsertReceipt(res: List[ReceiptEntry], e: ReceiptEntry): List[ReceiptEntry] = findReceipt(res, e.eventId) match
    case _: Some[ReceiptEntry] => replaceReceipt(res, e, Nil)
    case None => ListOps.reverse(e :: ListOps.reverse(res))

  // ---- backfill ---------------------------------------------------------------

  /** the room's stored `prev_batch` pagination token, "" when the room is
   *  unknown / none stored (the loop's backfill gate). */
  def prevBatchOf(roomId: String): String = findRoom(roomId) match
    case s: Some[RoomState] => s.value.prevBatch
    case None => ""

  /** ingest ONE backfilled event from a GET /messages chunk: timeline DEDUP +
   *  `m.room.message` voice extraction, and NOTHING else (no state
   *  processing, no SyncEvent emission — backfill repairs message history, it
   *  does not replay the room's life). Requires the room to already exist. */
  def ingestBackfill(roomId: String, ev: Json): Unit =
    if hasRoom(roomId) then ingestBackfill1(roomId, ev)

  def ingestBackfill1(roomId: String, ev: Json): Unit =
    val hasEid = WJson.hasStr(ev, "event_id")
    val eid = WJson.strField(ev, "event_id", "")
    val r0 = roomOr(roomId, emptyRoom(roomId))
    if hasEid && !strListContains(r0.timelineEventIds, eid) then
      updateRoom(RoomState(r0.roomId, r0.name, r0.hasAlias, r0.alias, r0.members,
        appendStr(r0.timelineEventIds, eid), r0.voiceMessages, r0.receipts, r0.prevBatch, r0.dmMembers))
      if WJson.strField(ev, "type", "") == "m.room.message" then extractVoice(roomId, ev)

  // ---- json array helpers -----------------------------------------------------

  def arrItems(j: Json): List[Json] = j match
    case a: JArr => a.items
    case _       => Nil

  /** `<obj>.events` as a list (EventList shape: absent -> empty). */
  def eventsOf(j: Json): List[Json] = arrItems(WJson.objField(j, "events"))

  def hasField(j: Json, key: String): Boolean = WJson.getField(j, key) match
    case _: Some[Json] => true
    case None => false

  // ============================ process ========================================

  /** process a parsed sync response; returns the emitted events IN ORDER. */
  def process(resp: Json): List[SyncEvent] =
    batchC.set(WJson.strField(resp, "next_batch", ""))
    var evs: List[SyncEvent] = Nil          // built REVERSED
    val roomsJ = WJson.objField(resp, "rooms")
    evs = joinMapLoop(WJson.objField(roomsJ, "join"), evs)
    evs = inviteMapLoop(WJson.objField(roomsJ, "invite"), evs)
    ListOps.reverse(evs)

  // ---- json string arrays -----------------------------------------------------

  def stringItems(items: List[Json]): List[String] =
    var acc: List[String] = Nil
    var cur = items
    var going = true
    while going do
      cur match
        case c: ::[Json] =>
          acc = consIfStr(c.head, acc)
          cur = c.tail
        case Nil => going = false
    ListOps.reverse(acc)

  def consIfStr(j: Json, acc: List[String]): List[String] = j match
    case s: JStr => s.s :: acc
    case _       => acc

  def notEmptyStr(xs: List[String]): Boolean = xs match
    case _ :: _ => true
    case Nil  => false

  // ---- joined / invited room maps ----------------------------------------------

  def joinMapLoop(join: Json, evs0: List[SyncEvent]): List[SyncEvent] = join match
    case o: JObj => joinFields(o.fields, evs0)
    case _       => evs0

  def joinFields(fs: List[(String, Json)], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = fs
    var going = true
    while going do
      cur match
        case c: ::[(String, Json)] =>
          evs = joinedRoom(c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def joinedRoom(p: (String, Json), evs0: List[SyncEvent]): List[SyncEvent] =
    val roomId: String = p._1
    val data = p._2
    ensureRoom(roomId)
    var evs = evs0
    // state events
    evs = stateEventsLoop(roomId, eventsOf(WJson.objField(data, "state")), evs)
    // timeline
    val timeline = WJson.objField(data, "timeline")
    if WJson.hasStr(timeline, "prev_batch") then
      setPrevBatch(roomId, WJson.strField(timeline, "prev_batch", ""))
    evs = timelineLoop(roomId, eventsOf(timeline), evs)
    // ephemeral (receipts)
    evs = ephemeralLoop(roomId, eventsOf(WJson.objField(data, "ephemeral")), evs)
    RoomUpdated(roomId) :: evs

  def inviteMapLoop(invite: Json, evs0: List[SyncEvent]): List[SyncEvent] = invite match
    case o: JObj => inviteFields(o.fields, evs0)
    case _       => evs0

  def inviteFields(fs: List[(String, Json)], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = fs
    var going = true
    while going do
      cur match
        case c: ::[(String, Json)] =>
          evs = invitedRoom(c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  /** invited rooms: process stripped invite_state (DM detection via is_direct
   *  BEFORE auto-join). */
  def invitedRoom(p: (String, Json), evs0: List[SyncEvent]): List[SyncEvent] =
    val roomId: String = p._1
    ensureRoom(roomId)
    var evs = evs0
    evs = stateEventsLoop(roomId, eventsOf(WJson.objField(p._2, "invite_state")), evs)
    RoomUpdated(roomId) :: evs

  // ---- state events -------------------------------------------------------------

  def stateEventsLoop(roomId: String, events: List[Json], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = events
    var going = true
    while going do
      cur match
        case c: ::[Json] =>
          evs = stateEvent(roomId, c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def stateEvent(roomId: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val etype = WJson.strField(ev, "type", "")
    if etype == "m.room.name" then { stateName(roomId, ev); evs0 }
    else if etype == "m.room.canonical_alias" then { stateAlias(roomId, ev); evs0 }
    else if etype == "net.wata.dm" then { stateDm(roomId, ev); evs0 }
    else if etype == "m.room.member" then stateMember(roomId, ev, evs0)
    else evs0

  /** the server's structural DM stamp, `{"members": [a, b]}`. This IS the
   *  classification: a room is a DM the moment this state event arrives, and
   *  the pair says who with. No account data, no ordering race, no inference
   *  from `is_direct` — which is why nothing here has to be sticky. */
  def stateDm(roomId: String, ev: Json): Unit =
    val members = stringItems(arrItems(WJson.objField(WJson.objField(ev, "content"), "members")))
    if notEmptyStr(members) then setDmMembers(roomId, members)

  def setDmMembers(roomId: String, members: List[String]): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, members))

  def stateName(roomId: String, ev: Json): Unit =
    val content = WJson.objField(ev, "content")
    if WJson.hasStr(content, "name") then
      val r = roomOr(roomId, emptyRoom(roomId))
      updateRoom(RoomState(r.roomId, WJson.strField(content, "name", ""), r.hasAlias, r.alias,
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.dmMembers))

  /** canonical_alias: an alias string sets it; a PRESENT content without one
   *  CLEARS it; an absent content changes nothing. */
  def stateAlias(roomId: String, ev: Json): Unit = WJson.getField(ev, "content") match
    case s: Some[Json] => stateAliasSet(roomId, s.value)
    case None => ()

  def stateAliasSet(roomId: String, content: Json): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    if WJson.hasStr(content, "alias") then
      updateRoom(RoomState(r.roomId, r.name, true, WJson.strField(content, "alias", ""),
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.dmMembers))
    else
      updateRoom(RoomState(r.roomId, r.name, false, "",
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.dmMembers))

  def stateMember(roomId: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    if !hasField(ev, "state_key") then evs0                 // no state_key: not a state event
    else stateMemberKeyed(roomId, WJson.strField(ev, "state_key", ""), ev, evs0)

  def stateMemberKeyed(roomId: String, uid: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    WJson.getField(ev, "content") match                     // no content: nothing to apply
      case s: Some[Json] => stateMemberContent(roomId, uid, s.value, evs0)
      case None => evs0

  def stateMemberContent(roomId: String, uid: String, content: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val membership = WJson.strField(content, "membership", "leave")
    val display = WJson.strField(content, "displayname", uid)
    val r = roomOr(roomId, emptyRoom(roomId))
    val newMembers = upsertMember(r.members, MemberInfo(uid, display, membership))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, newMembers,
      r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.dmMembers))
    MembershipChanged(roomId, uid, membership) :: evs0

  // ---- timeline -------------------------------------------------------------------

  def setPrevBatch(roomId: String, pb: String): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, r.voiceMessages, r.receipts, pb, r.dmMembers))

  def timelineLoop(roomId: String, events: List[Json], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = events
    var going = true
    while going do
      cur match
        case c: ::[Json] =>
          evs = timelineEvent(roomId, c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def timelineEvent(roomId: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val hasEid = WJson.hasStr(ev, "event_id")
    val eid = WJson.strField(ev, "event_id", "")
    val r0 = roomOr(roomId, emptyRoom(roomId))
    if hasEid && strListContains(r0.timelineEventIds, eid) then evs0   // dedup: skip entirely
    else
      if hasEid then
        updateRoom(RoomState(r0.roomId, r0.name, r0.hasAlias, r0.alias, r0.members,
          appendStr(r0.timelineEventIds, eid), r0.voiceMessages, r0.receipts, r0.prevBatch, r0.dmMembers))
      var evs = evs0
      if hasField(ev, "state_key") then evs = stateEvent(roomId, ev, evs)
      val etype = WJson.strField(ev, "type", "")
      if etype == "m.room.message" then extractVoice(roomId, ev)
      else if etype == "m.room.redaction" then applyRedaction(roomId, ev)
      if hasEid then evs = TimelineEventE(roomId, eid) :: evs
      evs

  /** extract a voice message: msgtype m.audio + url + event_id + sender all
   *  required; timestamp defaults to 0; duration from content.info.duration
   *  (>0 else 0). */
  def extractVoice(roomId: String, ev: Json): Unit =
    val content = WJson.objField(ev, "content")
    val msgtype = WJson.strField(content, "msgtype", "")
    if msgtype == "m.audio" && WJson.hasStr(content, "url") &&
        WJson.hasStr(ev, "event_id") && WJson.hasStr(ev, "sender") then
      val d = WJson.longField(WJson.objField(content, "info"), "duration", 0L)
      val dur = if d > 0L then d else 0L
      val vm = VoiceMessageRaw(
        WJson.strField(ev, "event_id", ""),
        WJson.strField(ev, "sender", ""),
        WJson.strField(content, "url", ""),
        dur,
        WJson.longField(ev, "origin_server_ts", 0L))
      val r = roomOr(roomId, emptyRoom(roomId))
      updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
        r.timelineEventIds, appendVoice(r.voiceMessages, vm), r.receipts, r.prevBatch, r.dmMembers))

  def appendVoice(vs: List[VoiceMessageRaw], v: VoiceMessageRaw): List[VoiceMessageRaw] =
    ListOps.reverse(v :: ListOps.reverse(vs))

  /** redaction target: top-level `redacts` (v1.10+) else content.redacts. */
  def applyRedaction(roomId: String, ev: Json): Unit =
    var target = WJson.strField(ev, "redacts", "")
    if target == "" then target = WJson.strField(WJson.objField(ev, "content"), "redacts", "")
    if target != "" then removeVoice(roomId, target)

  /** drop ALL voice messages with the redacted event id. */
  def removeVoice(roomId: String, targetId: String): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, dropVoice(r.voiceMessages, targetId, Nil),
      r.receipts, r.prevBatch, r.dmMembers))

  def dropVoice(vs: List[VoiceMessageRaw], targetId: String, acc: List[VoiceMessageRaw]): List[VoiceMessageRaw] = vs match
    case h :: t => dropVoiceStep(h, t, targetId, acc)
    case Nil  => ListOps.reverse(acc)

  def dropVoiceStep(h: VoiceMessageRaw, t: List[VoiceMessageRaw], targetId: String, acc: List[VoiceMessageRaw]): List[VoiceMessageRaw] =
    val k: String = h.eventId
    if k == targetId then dropVoice(t, targetId, acc)
    else dropVoice(t, targetId, h :: acc)

  // ---- ephemeral (receipts) -----------------------------------------------------

  def ephemeralLoop(roomId: String, events: List[Json], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = events
    var going = true
    while going do
      cur match
        case c: ::[Json] =>
          evs = ephemeralEvent(roomId, c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def ephemeralEvent(roomId: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val etype = WJson.strField(ev, "type", "")
    if etype != "m.receipt" then evs0
    else receiptContent(roomId, WJson.objField(ev, "content"), evs0)

  /** receipt content: `{ "$eid": { "m.read": { "@uid": {ts} } } }`. */
  def receiptContent(roomId: String, content: Json, evs0: List[SyncEvent]): List[SyncEvent] = content match
    case o: JObj => receiptFields(roomId, o.fields, evs0)
    case _       => evs0

  def receiptFields(roomId: String, fs: List[(String, Json)], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = fs
    var going = true
    while going do
      cur match
        case c: ::[(String, Json)] =>
          evs = receiptField(roomId, c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def receiptField(roomId: String, p: (String, Json), evs0: List[SyncEvent]): List[SyncEvent] =
    val eventId: String = p._1
    val mread = WJson.objField(p._2, "m.read")
    mread match
      case users: JObj => receiptUsers(roomId, eventId, users.fields, evs0)
      case _           => evs0

  def receiptUsers(roomId: String, eventId: String, users: List[(String, Json)], evs0: List[SyncEvent]): List[SyncEvent] =
    val r = roomOr(roomId, emptyRoom(roomId))
    val existing = findReceipt(r.receipts, eventId) match
      case s: Some[ReceiptEntry] => s.value
      case None => ReceiptEntry(eventId, Nil)
    val updated = ReceiptEntry(eventId, appendUserKeys(existing.userIds, users))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, r.voiceMessages, upsertReceipt(r.receipts, updated), r.prevBatch, r.dmMembers))
    ReceiptUpdated(roomId, eventId) :: evs0

  /** append every user key (no dedup — duplicates are appended as-is). */
  def appendUserKeys(uids: List[String], users: List[(String, Json)]): List[String] =
    var acc = uids
    var cur = users
    var going = true
    while going do
      cur match
        case c: ::[(String, Json)] =>
          acc = appendUserKey(acc, c.head)
          cur = c.tail
        case Nil => going = false
    acc

  def appendUserKey(acc: List[String], p: (String, Json)): List[String] =
    val uid: String = p._1
    appendStr(acc, uid)

  // ============================ buildSnapshot ==================================

  /** the UI view, derived from TWO things and nothing else: the rooms the
   *  server classified as DMs (`net.wata.dm`), and the family roster. A DM room
   *  IS the conversation with its peer; a roster member with no DM room yet
   *  gets the roomless sentinel, and the first send resolves it. */
  def buildSnapshot(): StateSnapshot =
    var contacts: List[Contact] = Nil
    var conversations: List[Conversation] = Nil
    val dm = dmConvs(contacts, conversations)
    contacts = dm._1
    conversations = dm._2
    // family room by "#family:" canonical alias (first match wins; conv PREPENDED)
    val fam = findFamily()
    val hasFamily = fam._1
    val family = fam._2
    if hasFamily then
      contacts = rosterContacts(family.members, contacts)
      conversations = familyConv(family.id) :: conversations
      conversations = roomlessFamilyConvs(family, conversations)
    StateSnapshot(Syncing(), selfUserId != "", User(selfUserId, resolveSelfDisplay()),
      contacts, conversations, hasFamily, family)

  // ---- conversations from the classified DM rooms ---------------------------------

  /** every classified DM room, in room order. Returns (contacts', convs') —
   *  Tuple2, the blessed value composite. */
  def dmConvs(contacts0: List[Contact], convs0: List[Conversation]): (List[Contact], List[Conversation]) =
    var contacts = contacts0
    var convs = convs0
    var cur = rooms
    var going = true
    while going do
      cur match
        case c: ::[RoomState] =>
          val step = dmConvOf(c.head, contacts, convs)
          contacts = step._1
          convs = step._2
          cur = c.tail
        case Nil => going = false
    (contacts, convs)

  def dmConvOf(r: RoomState, contacts: List[Contact], convs: List[Conversation]): (List[Contact], List[Conversation]) =
    val peerId = dmPeerOf(r)
    if peerId == "" then (contacts, convs)
    else if peerHasConv(convs, peerId) then (contacts, convs)   // the first room for a peer wins
    else dmConvFor(r, peerId, contacts, convs)

  def dmConvFor(r: RoomState, peerId: String, contacts0: List[Contact],
                convs: List[Conversation]): (List[Contact], List[Conversation]) =
    val contact = Contact(User(peerId, dmDisplay(r, peerId)))
    var contacts = contacts0
    if !contactExists(contacts, peerId) then contacts = appendContact(contacts, contact)
    val messages = buildMessages(r)
    (contacts, appendConv(convs, Conversation(r.roomId, DmConv(), true, contact, messages, unplayedOf(messages))))

  /** the OTHER member of the room's stamped pair — "" when the room carries no
   *  stamp, or the pair does not name us (someone else's DM). */
  def dmPeerOf(r: RoomState): String =
    if selfUserId == "" then "" else peerIn(r.dmMembers, selfUserId, false, "")

  def peerIn(members: List[String], selfId: String, sawSelf: Boolean, other: String): String = members match
    case h :: t => peerInStep(h, t, selfId, sawSelf, other)
    case Nil  => peerDone(sawSelf, other)

  def peerInStep(h: String, t: List[String], selfId: String, sawSelf: Boolean, other: String): String =
    if h == selfId then peerIn(t, selfId, true, other) else peerIn(t, selfId, sawSelf, h)

  def peerDone(sawSelf: Boolean, other: String): String =
    if sawSelf then other else ""

  def dmDisplay(r: RoomState, peerId: String): String =
    val dn = displayInRoom(r, peerId)
    if dn != "" then dn else peerId

  /** everyone on the family roster is a contact, whether or not a DM room for
   *  them exists yet. */
  def rosterContacts(ms: List[Contact], contacts0: List[Contact]): List[Contact] =
    var contacts = contacts0
    var cur = ms
    var going = true
    while going do
      cur match
        case c: ::[Contact] =>
          if !contactExists(contacts, c.head.user.id) then contacts = appendContact(contacts, c.head)
          cur = c.tail
        case Nil => going = false
    contacts

  /** the member's display name in a room, or "" (member missing). */
  def displayInRoom(r: RoomState, userId: String): String = findMember(r.members, userId) match
    case s: Some[MemberInfo] => s.value.displayName
    case None => ""

  def findContact(cs: List[Contact], userId: String): Option[Contact] = cs match
    case h :: t => findContactStep(h, t, userId)
    case Nil  => None

  def findContactStep(h: Contact, t: List[Contact], userId: String): Option[Contact] =
    val k: String = h.user.id
    if k == userId then Some(h) else findContact(t, userId)

  def contactExists(cs: List[Contact], userId: String): Boolean = findContact(cs, userId) match
    case _: Some[Contact] => true
    case None => false

  def appendConv(cs: List[Conversation], c: Conversation): List[Conversation] =
    ListOps.reverse(c :: ListOps.reverse(cs))

  // ---- messages / played state ----------------------------------------------------

  def buildMessages(r: RoomState): List[VoiceMessage] =
    var acc: List[VoiceMessage] = Nil
    var cur = r.voiceMessages
    var going = true
    while going do
      cur match
        case c: ::[VoiceMessageRaw] =>
          acc = buildMessage(r, c.head) :: acc
          cur = c.tail
        case Nil => going = false
    ListOps.reverse(acc)

  def buildMessage(r: RoomState, vm: VoiceMessageRaw): VoiceMessage =
    val dn = displayInRoom(r, vm.sender)
    val senderName = if dn != "" then dn else vm.sender
    VoiceMessage(vm.eventId, User(vm.sender, senderName), vm.mxcUrl,
      vm.durationMs, vm.timestamp, isPlayed(r, vm.eventId))

  /** is_played: the SELF user's id appears in the event's receipt list. */
  def isPlayed(r: RoomState, eventId: String): Boolean =
    if selfUserId == "" then false
    else findReceipt(r.receipts, eventId) match
      case s: Some[ReceiptEntry] => strListContains(s.value.userIds, selfUserId)
      case None => false

  def unplayedOf(messages: List[VoiceMessage]): Int =
    var n = 0
    var cur = messages
    var going = true
    while going do
      cur match
        case c: ::[VoiceMessage] =>
          if !c.head.isPlayed then n += 1
          cur = c.tail
        case Nil => going = false
    n

  // ---- contact / conversation lookups ---------------------------------------------

  def appendContact(cs: List[Contact], c: Contact): List[Contact] =
    ListOps.reverse(c :: ListOps.reverse(cs))

  def peerHasConv(convs: List[Conversation], userId: String): Boolean = convs match
    case h :: t => peerHasConvStep(h, t, userId)
    case Nil  => false

  def peerHasConvStep(h: Conversation, t: List[Conversation], userId: String): Boolean =
    val k: String = h.contact.user.id
    if h.hasContact && k == userId then true else peerHasConv(t, userId)

  // ---- family ---------------------------------------------------------------------

  /** the FIRST room whose canonical alias starts with "#family:" -> (true, Family). */
  def findFamily(): (Boolean, Family) =
    var has = false
    var fam = Family("", "", Nil)
    var cur = rooms
    var going = true
    while going do
      cur match
        case c: ::[RoomState] =>
          if !has && isFamilyRoom(c.head) then
            has = true
            fam = familyOf(c.head)
          cur = c.tail
        case Nil => going = false
    (has, fam)

  def isFamilyRoom(r: RoomState): Boolean = r.hasAlias && r.alias.startsWith("#family:")

  def familyOf(r: RoomState): Family =
    var nm = r.name                       // value-`if` in arg position is unsupported
    if nm == "" then nm = "Family"
    Family(r.roomId, nm, familyMembers(r))

  /** joined members, self excluded (member order). */
  def familyMembers(r: RoomState): List[Contact] =
    var acc: List[Contact] = Nil
    var cur = r.members
    var going = true
    while going do
      cur match
        case c: ::[MemberInfo] =>
          acc = familyMemberStep(c.head, acc)
          cur = c.tail
        case Nil => going = false
    ListOps.reverse(acc)

  def familyMemberStep(m: MemberInfo, acc: List[Contact]): List[Contact] =
    if m.membership != "join" then acc
    else if selfUserId != "" && m.userId == selfUserId then acc
    else Contact(User(m.userId, m.displayName)) :: acc

  /** the family conversation (prepended at index 0 by the caller). */
  def familyConv(famRoomId: String): Conversation =
    val r = roomOr(famRoomId, emptyRoom(famRoomId))
    val messages = buildMessages(r)
    Conversation(r.roomId, FamilyConv(), false, Contact(User("", "")), messages, unplayedOf(messages))

  /** family members with no conversation yet get an empty-roomId DM (created on
   *  first send). */
  def roomlessFamilyConvs(fam: Family, convs0: List[Conversation]): List[Conversation] =
    var convs = convs0
    var cur = fam.members
    var going = true
    while going do
      cur match
        case c: ::[Contact] =>
          convs = roomlessFamilyStep(c.head, convs)
          cur = c.tail
        case Nil => going = false
    convs

  def roomlessFamilyStep(member: Contact, convs: List[Conversation]): List[Conversation] =
    if peerHasConv(convs, member.user.id) then convs
    else appendConv(convs, Conversation("", DmConv(), true, member, Nil, 0))

  // ---- self display name ------------------------------------------------------------

  /** first room where self has a non-empty display name != the raw id. */
  def resolveSelfDisplay(): String =
    var result = selfUserId
    if selfUserId != "" then
      var found = false
      var cur = rooms
      var going = true
      while going do
        cur match
          case c: ::[RoomState] =>
            if !found then
              val dn = displayInRoom(c.head, selfUserId)
              if dn != "" && dn != selfUserId then { result = dn; found = true }
            cur = c.tail
          case Nil => going = false
    result

