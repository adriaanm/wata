/** M8 chunk 3 — the SYNC ENGINE, ported from fbclient `sync_engine.zig`
 *  (SyncProcessor), cross-read against `sync-engine.ts`. A pure state machine:
 *  sync-response `Json` in -> state delta + `SyncEvent`s out. PORTABLE (ZERO
 *  `go` facade use); the `json` module is the wire currency.
 *
 *  DEPARTURES (dormancy caveat — spec + taste win; the adjudication list):
 *   - Zig's SyncProcessor is an allocator-carrying struct; the subset has no
 *     plain mutable classes (only case classes / objects), so the engine is a
 *     module `object` with private `var` state + `reset()` — wata-server
 *     `Store`'s exact idiom. One engine per process (the real client has
 *     exactly one; tests `reset()` between scenarios).
 *   - Zig's `StringArrayHashMap`s (rooms/members/receipts/m_direct) become
 *     insertion-ordered `List`s with replace-keeps-position upsert — the SAME
 *     iteration semantics (ArrayHashMap preserves insertion order, and
 *     buildSnapshot's contact/conversation order depends on it). Linear scans;
 *     wata sizes are family-sized.
 *   - `?[]const u8` options (`next_batch`, `canonical_alias`, `prev_batch`,
 *     `redacts`, `state_key`) become "" -or- has-flag pairs. Where PRESENCE
 *     matters (canonical_alias clearing, state_key gating) the port tests
 *     field presence via `WJson.getField`, not ""-ness; `redacts`/`prev_batch`
 *     use "" as none ("" is not a valid event id / batch token).
 *   - Zig takes a TYPED, pre-parsed `SyncResponse` (std.json struct decode);
 *     we walk the `Json` tree directly (json_types.zig's shapes inlined as
 *     `WJson` field reads — same tolerant absent-field defaults as Zig's
 *     `= null` struct fields with `ignore_unknown_fields`).
 *   - Zig test-only accessors (`dupe`, arena plumbing) die with the GC.
 *   - TS-vs-Zig: the TS engine also processes `rooms.leave` (removes rooms) and
 *     timeline `limited`/backfill; the Zig client ignores both, and the spec
 *     needs neither for wata's flows — ZIG scope kept (recorded for designer).
 *
 *  Event emission ORDER is Zig's exactly (asserted by the fixture oracle):
 *  account_data first, then joined rooms in wire order (state membership events,
 *  timeline events, receipt events, then `room_updated` LAST per room), then
 *  invited rooms (invite_state membership events, `room_updated`).
 */
object SyncEngine:

  // ---- the processor state (SyncProcessor's fields) --------------------------
  // M9 ch.4a (CONC-10 migration): module-level mutable state lives in
  // `val`-held Atomic cells (CONCURRENCY.md §4.3) — the engine stays
  // single-goroutine by protocol (one sync loop per process), but the cells
  // make the by-naming reachability DRF instead of conventional. Reads keep
  // their old names via private accessor defs (zero churn at ~50 read sites);
  // writes go through `.set`. Whole-record swaps of immutable snapshots — the
  // engine's existing discipline, now checker-visible.
  private val roomsC: sgo.Atomic[List[RoomState]] = sgo.atomic(Nil)
  private val selfUserIdC: sgo.Atomic[String] = sgo.atomic("")
  private val batchC: sgo.Atomic[String] = sgo.atomic("")
  private val mDirectC: sgo.Atomic[List[DirectEntry]] = sgo.atomic(Nil)
  private def rooms: List[RoomState] = roomsC.get()
  private def selfUserId: String = selfUserIdC.get()
  private def batch: String = batchC.get()
  private def mDirect: List[DirectEntry] = mDirectC.get()

  def reset(): Unit =
    roomsC.set(Nil)
    selfUserIdC.set("")
    batchC.set("")
    mDirectC.set(Nil)

  /** set after login/whoami (client.zig sets `proc.self_user_id`). */
  def setSelfUser(uid: String): Unit = selfUserIdC.set(uid)

  def nextBatch: String = batch
  def selfUser: String = selfUserId

  /** read-only views for oracles/drivers (insertion order preserved). */
  def allRooms: List[RoomState] = rooms
  def allDirect: List[DirectEntry] = mDirect

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
      Nil, Nil, "", false)

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

  /** joinedMemberCount (RoomState accessor in Zig). */
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

  // ---- backfill (M8 chunk 4, sync_thread.zig `backfillRoom`) -------------------

  /** the room's stored `prev_batch` pagination token, "" when the room is
   *  unknown / none stored (the loop's backfill gate). */
  def prevBatchOf(roomId: String): String = findRoom(roomId) match
    case s: Some[RoomState] => s.value.prevBatch
    case None => ""

  /** ingest ONE backfilled event from a GET /messages chunk — exactly the Zig
   *  per-event body: timeline DEDUP + `m.room.message` voice extraction, and
   *  NOTHING else (no state processing, no SyncEvent emission — backfill repairs
   *  message history, it does not replay the room's life). Requires the room to
   *  already exist (Zig `rooms.getPtr orelse return`). */
  def ingestBackfill(roomId: String, ev: Json): Unit =
    if hasRoom(roomId) then ingestBackfill1(roomId, ev)

  def ingestBackfill1(roomId: String, ev: Json): Unit =
    val hasEid = WJson.hasStr(ev, "event_id")
    val eid = WJson.strField(ev, "event_id", "")
    val r0 = roomOr(roomId, emptyRoom(roomId))
    if hasEid && !strListContains(r0.timelineEventIds, eid) then
      updateRoom(RoomState(r0.roomId, r0.name, r0.hasAlias, r0.alias, r0.members,
        appendStr(r0.timelineEventIds, eid), r0.voiceMessages, r0.receipts, r0.prevBatch, r0.isDm))
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
    evs = accountDataLoop(eventsOf(WJson.objField(resp, "account_data")), evs)
    val roomsJ = WJson.objField(resp, "rooms")
    evs = joinMapLoop(WJson.objField(roomsJ, "join"), evs)
    evs = inviteMapLoop(WJson.objField(roomsJ, "invite"), evs)
    ListOps.reverse(evs)

  // ---- account data (m.direct) ------------------------------------------------

  def accountDataLoop(events: List[Json], evs0: List[SyncEvent]): List[SyncEvent] =
    var evs = evs0
    var cur = events
    var going = true
    while going do
      cur match
        case c: ::[Json] =>
          evs = accountDataEvent(c.head, evs)
          cur = c.tail
        case Nil => going = false
    evs

  def accountDataEvent(ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val etype = WJson.strField(ev, "type", "")
    if etype != "m.direct" then evs0
    else
      rebuildDirect(WJson.getField(ev, "content"))
      AccountDataUpdated("m.direct") :: evs0             // emitted even w/o content (Zig)

  /** clear-and-rebuild m_direct from `{userId: [roomIds]}` (non-array values
   *  SKIP the user entirely — Zig's `.array` arm is the only one that puts). */
  def rebuildDirect(content: Option[Json]): Unit = content match
    case s: Some[Json] => rebuildDirectFrom(s.value)
    case None => ()

  def rebuildDirectFrom(c: Json): Unit = c match
    case o: JObj =>
      mDirectC.set(Nil)
      directFieldsLoop(o.fields)
    case _ => ()

  def directFieldsLoop(fs: List[(String, Json)]): Unit =
    var cur = fs
    var going = true
    while going do
      cur match
        case c: ::[(String, Json)] =>
          directField(c.head)
          cur = c.tail
        case Nil => going = false

  def directField(p: (String, Json)): Unit =
    val uid: String = p._1
    p._2 match
      case a: JArr => mDirectC.set(appendDirect(mDirect, DirectEntry(uid, stringItems(a.items))))
      case _       => ()

  def appendDirect(ds: List[DirectEntry], d: DirectEntry): List[DirectEntry] =
    ListOps.reverse(d :: ListOps.reverse(ds))

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

  def findDirect(userId: String): Option[DirectEntry] = findDirectIn(mDirect, userId)

  def findDirectIn(ds: List[DirectEntry], userId: String): Option[DirectEntry] = ds match
    case h :: t => findDirectStep(h, t, userId)
    case Nil  => None

  def findDirectStep(h: DirectEntry, t: List[DirectEntry], userId: String): Option[DirectEntry] =
    val k: String = h.userId
    if k == userId then Some(h) else findDirectIn(t, userId)

  def directCount: Int =
    var n = 0
    var cur = mDirect
    var going = true
    while going do
      cur match
        case c: ::[DirectEntry] => n += 1; cur = c.tail
        case Nil              => going = false
    n

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
   *  BEFORE auto-join — parity with TS processInvitedRoom). */
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
    else if etype == "m.room.member" then stateMember(roomId, ev, evs0)
    else evs0

  def stateName(roomId: String, ev: Json): Unit =
    val content = WJson.objField(ev, "content")
    if WJson.hasStr(content, "name") then
      val r = roomOr(roomId, emptyRoom(roomId))
      updateRoom(RoomState(r.roomId, WJson.strField(content, "name", ""), r.hasAlias, r.alias,
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.isDm))

  /** canonical_alias: an alias string sets it; a PRESENT content without one
   *  CLEARS it (Zig's `else` arm); an absent content changes nothing. */
  def stateAlias(roomId: String, ev: Json): Unit = WJson.getField(ev, "content") match
    case s: Some[Json] => stateAliasSet(roomId, s.value)
    case None => ()

  def stateAliasSet(roomId: String, content: Json): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    if WJson.hasStr(content, "alias") then
      updateRoom(RoomState(r.roomId, r.name, true, WJson.strField(content, "alias", ""),
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.isDm))
    else
      updateRoom(RoomState(r.roomId, r.name, false, "",
        r.members, r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, r.isDm))

  def stateMember(roomId: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    if !hasField(ev, "state_key") then evs0                 // Zig: state_key orelse return
    else stateMemberKeyed(roomId, WJson.strField(ev, "state_key", ""), ev, evs0)

  def stateMemberKeyed(roomId: String, uid: String, ev: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    WJson.getField(ev, "content") match                     // Zig: whole block inside if(content)
      case s: Some[Json] => stateMemberContent(roomId, uid, s.value, evs0)
      case None => evs0

  def stateMemberContent(roomId: String, uid: String, content: Json, evs0: List[SyncEvent]): List[SyncEvent] =
    val membership = WJson.strField(content, "membership", "leave")
    val display = WJson.strField(content, "displayname", uid)
    val isDirect = WJson.boolField(content, "is_direct")
    val r = roomOr(roomId, emptyRoom(roomId))
    val newMembers = upsertMember(r.members, MemberInfo(uid, display, membership, isDirect))
    val newDm = if isDirect then true else r.isDm           // sticky (TS hasIsDirectFlag)
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, newMembers,
      r.timelineEventIds, r.voiceMessages, r.receipts, r.prevBatch, newDm))
    MembershipChanged(roomId, uid, membership) :: evs0

  // ---- timeline -------------------------------------------------------------------

  def setPrevBatch(roomId: String, pb: String): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, r.voiceMessages, r.receipts, pb, r.isDm))

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
          appendStr(r0.timelineEventIds, eid), r0.voiceMessages, r0.receipts, r0.prevBatch, r0.isDm))
      var evs = evs0
      if hasField(ev, "state_key") then evs = stateEvent(roomId, ev, evs)
      val etype = WJson.strField(ev, "type", "")
      if etype == "m.room.message" then extractVoice(roomId, ev)
      else if etype == "m.room.redaction" then applyRedaction(roomId, ev)
      if hasEid then evs = TimelineEventE(roomId, eid) :: evs
      evs

  /** extractVoiceMessageOwned: msgtype m.audio + url + event_id + sender all
   *  required; ts defaults 0; duration from content.info.duration (>0 else 0). */
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
        r.timelineEventIds, appendVoice(r.voiceMessages, vm), r.receipts, r.prevBatch, r.isDm))

  def appendVoice(vs: List[VoiceMessageRaw], v: VoiceMessageRaw): List[VoiceMessageRaw] =
    ListOps.reverse(v :: ListOps.reverse(vs))

  /** redaction target: top-level `redacts` (v1.10+) else content.redacts. */
  def applyRedaction(roomId: String, ev: Json): Unit =
    var target = WJson.strField(ev, "redacts", "")
    if target == "" then target = WJson.strField(WJson.objField(ev, "content"), "redacts", "")
    if target != "" then removeVoice(roomId, target)

  /** drop ALL voice messages with the redacted event id (Zig removeVoiceMessage). */
  def removeVoice(roomId: String, targetId: String): Unit =
    val r = roomOr(roomId, emptyRoom(roomId))
    updateRoom(RoomState(r.roomId, r.name, r.hasAlias, r.alias, r.members,
      r.timelineEventIds, dropVoice(r.voiceMessages, targetId, Nil),
      r.receipts, r.prevBatch, r.isDm))

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
      r.timelineEventIds, r.voiceMessages, upsertReceipt(r.receipts, updated), r.prevBatch, r.isDm))
    ReceiptUpdated(roomId, eventId) :: evs0

  /** append every user key (no dedup — Zig appends unconditionally). */
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

  def buildSnapshot(): StateSnapshot =
    val contacts0 = contactsFromDirect()
    var conversations: List[Conversation] = Nil
    var contacts = contacts0
    // conversations from m.direct (insertion order; first JOINED room wins)
    conversations = convsFromDirect(contacts, conversations)
    // sticky is_dm inference for rooms not covered by m.direct
    val inferred = inferDmConvs(contacts, conversations)
    contacts = inferred._1
    conversations = inferred._2
    // family room by "#family:" canonical alias (first match wins; conv PREPENDED)
    val fam = findFamily()
    val hasFamily = fam._1
    val family = fam._2
    if hasFamily then
      conversations = familyConv(family.id) :: conversations
      conversations = roomlessFamilyConvs(family, conversations)
    StateSnapshot(Syncing(), selfUserId != "", User(selfUserId, resolveSelfDisplay()),
      contacts, conversations, hasFamily, family)

  // ---- contacts from m.direct ----------------------------------------------------

  def contactsFromDirect(): List[Contact] =
    var acc: List[Contact] = Nil
    var cur = mDirect
    var going = true
    while going do
      cur match
        case c: ::[DirectEntry] =>
          acc = contactFromEntry(c.head, acc)
          cur = c.tail
        case Nil => going = false
    ListOps.reverse(acc)

  def contactFromEntry(d: DirectEntry, acc: List[Contact]): List[Contact] =
    if selfUserId != "" && d.userId == selfUserId then acc  // skip self
    else Contact(User(d.userId, resolveDisplay(d.userId, d.roomIds))) :: acc

  /** display name: first room in the entry's list that exists and knows the user. */
  def resolveDisplay(userId: String, roomIds: List[String]): String =
    var result = userId
    var found = false
    var cur = roomIds
    var going = true
    while going do
      cur match
        case c: ::[String] =>
          if !found then
            val dn = displayIn(c.head, userId)
            if dn != "" then { result = dn; found = true }
          cur = c.tail
        case Nil => going = false
    result

  /** the member's display name in a room, or "" (room missing / member missing). */
  def displayIn(roomId: String, userId: String): String = findRoom(roomId) match
    case s: Some[RoomState] => displayInRoom(s.value, userId)
    case None => ""

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

  // ---- conversations from m.direct -----------------------------------------------

  def convsFromDirect(contacts: List[Contact], convs0: List[Conversation]): List[Conversation] =
    var convs = convs0
    var cur = mDirect
    var going = true
    while going do
      cur match
        case c: ::[DirectEntry] =>
          convs = convFromEntry(c.head, contacts, convs)
          cur = c.tail
        case Nil => going = false
    convs

  def convFromEntry(d: DirectEntry, contacts: List[Contact], convs: List[Conversation]): List[Conversation] =
    if selfUserId != "" && d.userId == selfUserId then convs
    else
      val rid = firstJoinedRoom(d.roomIds)
      if rid == "" then convs                              // stale-only entry: excluded
      else
        val r = roomOr(rid, emptyRoom(rid))
        val messages = buildMessages(r)
        val found = findContact(contacts, d.userId)
        val hasContact = contactExists(contacts, d.userId)
        val contact = found match
          case s: Some[Contact] => s.value
          case None => Contact(User(d.userId, d.userId))
        appendConv(convs, Conversation(rid, DmConv(), hasContact, contact, messages, unplayedOf(messages)))

  /** the FIRST room id in the m.direct list that we have joined ("" if none) —
   *  m.direct preserves insertion order (oldest first); stale entries skipped. */
  def firstJoinedRoom(roomIds: List[String]): String =
    var result = ""
    var cur = roomIds
    var going = true
    while going do
      cur match
        case c: ::[String] =>
          if result == "" && hasRoom(c.head) then result = c.head
          cur = c.tail
        case Nil => going = false
    result

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

  // ---- sticky is_dm inference (rooms not covered by m.direct) ----------------------

  /** returns (contacts', conversations') — Tuple2, the blessed value composite. */
  def inferDmConvs(contacts0: List[Contact], convs0: List[Conversation]): (List[Contact], List[Conversation]) =
    var contacts = contacts0
    var convs = convs0
    var cur = rooms
    var going = true
    while going do
      cur match
        case c: ::[RoomState] =>
          val step = inferDmRoom(c.head, contacts, convs)
          contacts = step._1
          convs = step._2
          cur = c.tail
        case Nil => going = false
    (contacts, convs)

  def inferDmRoom(r: RoomState, contacts: List[Contact], convs: List[Conversation]): (List[Contact], List[Conversation]) =
    if !r.isDm then (contacts, convs)
    else if convHasRoom(convs, r.roomId) then (contacts, convs)
    else
      val peer = findPeer(r)
      peer match
        case s: Some[MemberInfo] => inferDmPeer(r, s.value, contacts, convs)
        case None => (contacts, convs)

  def inferDmPeer(r: RoomState, other: MemberInfo, contacts0: List[Contact], convs: List[Conversation]): (List[Contact], List[Conversation]) =
    if peerHasConv(convs, other.userId) then (contacts0, convs)  // m.direct is the authority
    else
      val contact = Contact(User(other.userId, other.displayName))
      var contacts = contacts0
      if !contactExists(contacts, other.userId) then contacts = appendContact(contacts, contact)
      val messages = buildMessages(r)
      (contacts, appendConv(convs, Conversation(r.roomId, DmConv(), true, contact, messages, unplayedOf(messages))))

  def appendContact(cs: List[Contact], c: Contact): List[Contact] =
    ListOps.reverse(c :: ListOps.reverse(cs))

  def convHasRoom(convs: List[Conversation], roomId: String): Boolean = convs match
    case h :: t => convHasRoomStep(h, t, roomId)
    case Nil  => false

  def convHasRoomStep(h: Conversation, t: List[Conversation], roomId: String): Boolean =
    val k: String = h.roomId
    if k == roomId then true else convHasRoom(t, roomId)

  def peerHasConv(convs: List[Conversation], userId: String): Boolean = convs match
    case h :: t => peerHasConvStep(h, t, userId)
    case Nil  => false

  def peerHasConvStep(h: Conversation, t: List[Conversation], userId: String): Boolean =
    val k: String = h.contact.user.id
    if h.hasContact && k == userId then true else peerHasConv(t, userId)

  /** the "other" joined/invited member — the DM peer (first in member order). */
  def findPeer(r: RoomState): Option[MemberInfo] =
    var result: Option[MemberInfo] = None
    var found = false
    var cur = r.members
    var going = true
    while going do
      cur match
        case c: ::[MemberInfo] =>
          if !found && isPeer(c.head) then { result = Some(c.head); found = true }
          cur = c.tail
        case Nil => going = false
    result

  def isPeer(m: MemberInfo): Boolean =
    if selfUserId != "" && m.userId == selfUserId then false
    else m.membership == "join" || m.membership == "invite"

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

