/** The LIVE ORACLE: integration-test scenarios run against a real
 *  wata-server, driven by `wata-fb integ <scenario> <baseUrl>`
 *  (tools/wataclient-integ.sh boots a FRESH server per scenario and reads
 *  the scoreboard).
 *
 *  AUDIO IS DECOUPLED: no audio thread exists in this driver; download-and-play
 *  surfaces `EvPlaybackError` since there's nothing to actually play through.
 *
 *  TOPOLOGY: rather than running both test users concurrently, this engine is
 *  a one-client-per-process singleton, so a scenario with two users
 *  (typically alice + bob) is broken into SEQUENTIAL PHASES —
 *  `phase(user)(driver)` = supervised { start; drive; stop }. Every assertion
 *  a concurrent test would make still holds (cross-client visibility flows
 *  through the server; a fresh server per scenario gives isolation); what
 *  changes is only WHEN the second client syncs (its initial sync picks up
 *  what the first client already did, instead of receiving a live push). The
 *  live long-poll path IS exercised wherever a phase waits for its OWN
 *  action's echo (send -> own-snapshot wait, receipt -> is_played
 *  round-trip, family send -> family wait).
 *
 *  CLOSURE RULE: a phase lambda may READ captured vals but must not ASSIGN
 *  enclosing vars (no captured-ref lowering for closures) — outputs cross
 *  phases through the `out*` module cells, set by defs. */
object Integ:

  // `val`-held Atomic[String] cells.
  private val baseC: sgo.Atomic[String] = sgo.atomic("")
  private def base: String = baseC.get()
  private val PASS = "testpass123"

  def run(args: Array[String]): Unit =
    if args.length < 3 then println("integ: want  wata-fb integ <scenario> <baseUrl>")
    else
      baseC.set(args(2))
      val name = args(1)
      var ok = false
      if name == "login-syncing" then ok = s01()
      else if name == "both-sync" then ok = s02()
      else if name == "voice-to-bob" then ok = s03()
      else if name == "receipt-accepted" then ok = s04()
      else if name == "receipt-roundtrip" then ok = s05()
      else if name == "multiturn-order" then ok = s06()
      else if name == "redaction" then ok = s07()
      else if name == "download-bytes" then ok = s08()
      else if name == "family-room" then ok = s09()
      else if name == "session-resume" then ok = s10()
      else if name == "dm-idempotent" then ok = s11()
      else if name == "dm-stock-create" then ok = s12()
      else if name == "backfill-paged" then ok = s13()
      else if name == "backfill-cap" then ok = s14()
      else println("integ: unknown scenario " + name)
      if ok then println("INTEG PASS " + name)
      else println("INTEG FAIL " + name)

  // ---- plumbing ---------------------------------------------------------------

  def cfg(user: String): ClientConfig =
    ClientConfig(base, user, PASS, 1000, Session("", "", "", "", ""))

  /** one client session as a supervised scope: start the loops, drive, wind
   *  down. The scope join is equivalent to joining the client's threads. */
  def phase(user: String)(body: MatrixClient => Boolean): Boolean =
    phaseCfg(cfg(user))(body)

  def phaseCfg(config: ClientConfig)(body: MatrixClient => Boolean): Boolean =
    val c = Runtime.make(config, FbCaps.httpDo(), FbCaps.clock())
    sgo.supervised {
      Runtime.start(c)
      var ok = body(c)
      Runtime.stopClient(c)
      ok
    }

  /** a fake Ogg payload ("OggS\x00\x02" + 8 zero bytes) — the media repo
   *  accepts arbitrary bytes; upload/send is what's exercised. */
  def fakeOgg(): Bytes =
    val bb = new BytesBuilder
    bb.addAscii("OggS")
    bb.addByte(0)
    bb.addByte(2)
    var i = 0
    while i < 8 do
      bb.addByte(0)
      i += 1
    bb.result()

  // ---- module out-cells (phase lambdas may not assign enclosing vars) ---------
  // `val`-held Atomic[String] cells.
  private val outIdC: sgo.Atomic[String] = sgo.atomic("")     // a user id
  private val outRoomC: sgo.Atomic[String] = sgo.atomic("")   // a room id
  private val outEventC: sgo.Atomic[String] = sgo.atomic("")  // an event id
  private val outMxcC: sgo.Atomic[String] = sgo.atomic("")    // an mxc url
  private def outId: String = outIdC.get()
  private def outRoom: String = outRoomC.get()
  private def outEvent: String = outEventC.get()
  private def outMxc: String = outMxcC.get()

  // ---- shared waits -------------------------------------------------------------

  def waitSyncing(c: MatrixClient): Boolean =
    Runtime.waitForConnection(c, Syncing(), 15000L)

  def waitSelf(c: MatrixClient): Boolean =
    Runtime.waitForSnapshot(c, s => s.hasSelfUser, 15000L)

  /** syncing + self snapshot; stashes the self user id in `outId`. */
  def grabSelf(c: MatrixClient): Boolean =
    if waitSyncing(c) && waitSelf(c) then
      outIdC.set(Runtime.lastSnap.selfUser.id)
      true
    else false

  /** enqueue a voice send and wait for its completion event. */
  def sendVoice(c: MatrixClient, roomId: String, contactId: String, durMs: Long): Boolean =
    Runtime.sendAction(c, ActSendVoice(roomId, contactId, fakeOgg(), durMs))
    waitSendResult(c, 20000L)

  def waitSendResult(c: MatrixClient, timeoutMs: Long): Boolean =
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var res = false
    var run = true
    while run do
      if c.clock.nowUnixMillis() >= deadline then run = false
      else
        // pollEvent returns Option (tryReceive) — no stash cell needed.
        Runtime.pollEvent(c) match
          case s: Some[UiEvent] =>
            val v = sendResultOf(s.value)
            if v == 1 then
              res = true
              run = false
            else if v == 2 then run = false
          case None => c.clock.sleepMs(20L)
    res

  def sendResultOf(e: UiEvent): Int = e match
    case _: EvSendComplete => 1
    case _: EvSendFailed   => 2
    case _                 => 0

  // ---- snapshot inspection (scenario assertion predicates) ---------------------

  def findConv(cs: List[Conversation], contactId: String): Option[Conversation] = cs match
    case h :: t => findConvStep(h, t, contactId)
    case Nil  => None

  def findConvStep(h: Conversation, t: List[Conversation], contactId: String): Option[Conversation] =
    if h.hasContact && h.contact.user.id == contactId then Some(h)
    else findConv(t, contactId)

  def findFamilyConv(cs: List[Conversation]): Option[Conversation] = cs match
    case h :: t => findFamilyStep(h, t)
    case Nil  => None

  def findFamilyStep(h: Conversation, t: List[Conversation]): Option[Conversation] =
    if isFamilyType(h.convType) then Some(h) else findFamilyConv(t)

  def isFamilyType(t: ConversationType): Boolean = t match
    case _: FamilyConv => true
    case _: DmConv     => false

  def msgCount(ms: List[VoiceMessage]): Int =
    var n = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case h :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  def dropMsgs(ms: List[VoiceMessage], n: Int): List[VoiceMessage] =
    var cur = ms
    var i = 0
    while i < n do
      cur match
        case h :: t =>
          cur = t
          i += 1
        case Nil => i = n
    cur

  def dursMatch(ms: List[VoiceMessage], ds: List[Long]): Boolean =
    var cur = ms
    var want = ds
    var ok = true
    var going = true
    while going do
      want match
        case d :: dt => going = dursMatchStep(cur, d)
        case Nil   => going = false
      if going then
        want = tailDurs(want)
        cur = tailMsgs(cur)
      else ok = doneMatch(want)
    ok

  /** current message's duration equals `d`? false ends the walk (mismatch or
   *  ran out of messages). */
  def dursMatchStep(cur: List[VoiceMessage], d: Long): Boolean = cur match
    case m :: t => m.durationMs == d
    case Nil  => false

  def doneMatch(want: List[Long]): Boolean = want match
    case _ :: _ => false // walk ended early -> mismatch
    case Nil  => true

  def tailDurs(ds: List[Long]): List[Long] = ds match
    case _ :: t => t
    case Nil  => Nil

  def tailMsgs(ms: List[VoiceMessage]): List[VoiceMessage] = ms match
    case _ :: t => t
    case Nil  => Nil

  /** does the conversation with `contactId` have at least `min` messages? */
  def hasMsgFrom(s: StateSnapshot, contactId: String, min: Int): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => msgCount(cv.value.messages) >= min
      case None  => false

  /** the conversation's LAST k messages have exactly these durations, in
   *  order. */
  def lastDursMatch(s: StateSnapshot, contactId: String, ds: List[Long]): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => lastDursIn(cv.value.messages, ds)
      case None  => false

  def lastDursIn(ms: List[VoiceMessage], ds: List[Long]): Boolean =
    val n = msgCount(ms)
    val k = lenDurs(ds)
    if n < k then false
    else dursMatch(dropMsgs(ms, n - k), ds)

  def lenDurs(ds: List[Long]): Int =
    var n = 0
    var cur = ds
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  /** the message with `eventId` is marked played. */
  def msgPlayed(s: StateSnapshot, contactId: String, eventId: String): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => playedIn(cv.value.messages, eventId)
      case None  => false

  def playedIn(ms: List[VoiceMessage], eventId: String): Boolean = ms match
    case m :: t => playedStep(m, t, eventId)
    case Nil  => false

  def playedStep(m: VoiceMessage, t: List[VoiceMessage], eventId: String): Boolean =
    if m.id == eventId && m.isPlayed then true else playedIn(t, eventId)

  /** NO message with this duration remains (a missing conversation counts as
   *  gone). */
  def durGone(s: StateSnapshot, contactId: String, d: Long): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => !hasDurIn(cv.value.messages, d)
      case None  => true

  def hasDurIn(ms: List[VoiceMessage], d: Long): Boolean = ms match
    case m :: t => hasDurStep(m, t, d)
    case Nil  => false

  def hasDurStep(m: VoiceMessage, t: List[VoiceMessage], d: Long): Boolean =
    if m.durationMs == d then true else hasDurIn(t, d)

  /** family populated + a family-typed conversation. */
  def familyReady(s: StateSnapshot): Boolean =
    if !s.hasFamily then false
    else hasFamilyConv(s.conversations)

  def hasFamilyConv(cs: List[Conversation]): Boolean = findFamilyConv(cs) match
    case _: Some[Conversation] => true
    case None => false

  /** does the family conversation have a message with this duration? */
  def familyHasDur(s: StateSnapshot, d: Long): Boolean =
    if !s.hasFamily then false
    else familyDurIn(s.conversations, d)

  def familyDurIn(cs: List[Conversation], d: Long): Boolean = findFamilyConv(cs) match
    case cv: Some[Conversation] => hasDurIn(cv.value.messages, d)
    case None  => false

  /** stash the DM conversation's room id + LAST message's event id + mxc url. */
  def stashLastMsg(s: StateSnapshot, contactId: String): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => stashFromConv(cv.value)
      case None  => false

  def stashFromConv(conv: Conversation): Boolean =
    val n = msgCount(conv.messages)
    if n == 0 then false
    else stashMsgFields(conv.roomId, dropMsgs(conv.messages, n - 1))

  def stashMsgFields(roomId: String, lastOne: List[VoiceMessage]): Boolean = lastOne match
    case m :: t => stashMsg1(roomId, m)
    case Nil  => false

  def stashMsg1(roomId: String, m: VoiceMessage): Boolean =
    outRoomC.set(roomId)
    outEventC.set(m.id)
    outMxcC.set(m.mxcUrl)
    true

  /** stash the room id + event id + mxc of the message with duration `d`. */
  def stashMsgWithDur(s: StateSnapshot, contactId: String, d: Long): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => stashDurConv(cv.value, d)
      case None  => false

  def stashDurConv(conv: Conversation, d: Long): Boolean =
    stashDurWalk(conv.roomId, conv.messages, d)

  def stashDurWalk(roomId: String, ms: List[VoiceMessage], d: Long): Boolean = ms match
    case m :: t => stashDurStep(roomId, m, t, d)
    case Nil  => false

  def stashDurStep(roomId: String, m: VoiceMessage, t: List[VoiceMessage], d: Long): Boolean =
    if m.durationMs == d then stashMsg1(roomId, m)
    else stashDurWalk(roomId, t, d)

  /** stash the FAMILY conversation's room id. */
  def stashFamilyRoom(s: StateSnapshot): Boolean =
    findFamilyConv(s.conversations) match
      case cv: Some[Conversation] => stashRoomOf(cv.value)
      case None  => false

  def stashRoomOf(conv: Conversation): Boolean =
    outRoomC.set(conv.roomId)
    true

  // ---- direct-HTTP helpers (out-of-band Matrix HTTP calls, bypassing the
  // client runtime) --------------------------------------------------------------

  /** login outside any client runtime; "" on failure. */
  def directToken(user: String): String =
    val hs0 = Hs(FbCaps.httpDo(), FbCaps.clock(), base, "")
    val resp = MatrixHttp.login(hs0, user, PASS)
    if resp.status != 200 then ""
    else Matrix.parseLogin(MatrixHttp.parseOrNull(resp.body)).accessToken

  def directHs(token: String): Hs = Hs(FbCaps.httpDo(), FbCaps.clock(), base, token)

  // ============================ the scenarios ==================================

  /** login and reach syncing state. */
  def s01(): Boolean =
    phase("alice")(c => grabSelf(c))

  /** alice and bob both reach syncing (sequential phases — see header). */
  def s02(): Boolean =
    if !phase("alice")(c => grabSelf(c)) then false
    else phase("bob")(c => grabSelf(c))

  /** alice sends voice to bob, bob sees the message. */
  def s03(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => grabSelf(c) && sendVoice(c, "", bobId, 1000L)) then false
      else
        val aliceId = outId
        phase("bob")(c => grabSelf(c) &&
          Runtime.waitForSnapshot(c, s => hasMsgFrom(s, aliceId, 1), 20000L))

  /** bob acks alice's message with a read receipt (enqueue accepted). */
  def s04(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => grabSelf(c) && sendVoice(c, "", bobId, 1000L)) then false
      else
        val aliceId = outId
        phase("bob")(c => bobReceipts(c, aliceId))

  def bobReceipts(c: MatrixClient, aliceId: String): Boolean =
    if !grabSelf(c) then false
    else if !Runtime.waitForSnapshot(c, s => hasMsgFrom(s, aliceId, 1), 20000L) then false
    else if !stashLastMsg(Runtime.lastSnap, aliceId) then false
    else
      Runtime.sendAction(c, ActReceipt(outRoom, outEvent))
      true

  /** bob's own snapshot reflects the receipt he sent — the m.receipt
   *  round-trip over bob's own LIVE long-poll. */
  def s05(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => grabSelf(c) && sendVoice(c, "", bobId, 1000L)) then false
      else
        val aliceId = outId
        phase("bob")(c => bobReceiptRoundtrip(c, aliceId))

  def bobReceiptRoundtrip(c: MatrixClient, aliceId: String): Boolean =
    if !bobReceipts(c, aliceId) then false
    else
      val eid = outEvent
      Runtime.waitForSnapshot(c, s => msgPlayed(s, aliceId, eid), 20000L)

  /** multi-turn conversation preserves order and dedupes. */
  def s06(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => aliceSendsThree(c, bobId)) then false
      else
        val aliceId = outId
        if !phase("bob")(c => bobRepliesThree(c, aliceId)) then false
        else phase("alice")(c => grabSelf(c) &&
          Runtime.waitForSnapshot(c, s => lastDursMatch(s, bobId, durs6()), 30000L))

  def aliceSendsThree(c: MatrixClient, bobId: String): Boolean =
    if !grabSelf(c) then false
    else if !sendVoice(c, "", bobId, 100L) then false
    else if !sendVoice(c, "", bobId, 200L) then false
    else if !sendVoice(c, "", bobId, 300L) then false
    else Runtime.waitForSnapshot(c, s => lastDursMatch(s, bobId, durs3()), 30000L)

  def bobRepliesThree(c: MatrixClient, aliceId: String): Boolean =
    if !grabSelf(c) then false
    else if !Runtime.waitForSnapshot(c, s => lastDursMatch(s, aliceId, durs3()), 30000L) then false
    else if !stashLastMsg(Runtime.lastSnap, aliceId) then false
    else bobRepliesInto(c, aliceId, outRoom)

  /** bob replies into the SAME room (roomId set -> no DM resolution). */
  def bobRepliesInto(c: MatrixClient, aliceId: String, roomId: String): Boolean =
    if !sendVoice(c, roomId, aliceId, 400L) then false
    else if !sendVoice(c, roomId, aliceId, 500L) then false
    else if !sendVoice(c, roomId, aliceId, 600L) then false
    else Runtime.waitForSnapshot(c, s => lastDursMatch(s, aliceId, durs6()), 30000L)

  def durs3(): List[Long] =
    var ds: List[Long] = Nil
    ds = 300L :: ds
    ds = 200L :: ds
    ds = 100L :: ds
    ds

  def durs6(): List[Long] =
    var ds: List[Long] = Nil
    ds = 600L :: ds
    ds = 500L :: ds
    ds = 400L :: ds
    ds = 300L :: ds
    ds = 200L :: ds
    ds = 100L :: ds
    ds

  /** alice deletes (redacts) a message and bob sees the redaction. Marker
   *  777; bob must see it BEFORE the redaction. */
  def s07(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => aliceSendsMarker(c, bobId, 777L)) then false
      else
        val aliceId = outId
        if !phase("bob")(c => grabSelf(c) &&
            Runtime.waitForSnapshot(c, s => lastDursMatch(s, aliceId, one(777L)), 20000L)) then false
        else
          val room = outRoom
          val event = outEvent
          if !phase("alice")(c => aliceRedacts(c, room, event)) then false
          else phase("bob")(c => grabSelf(c) &&
            Runtime.waitForSnapshot(c, s => durGone(s, aliceId, 777L), 20000L))

  /** send a marker to `contactId`, wait for it in the OWN snapshot, stash its
   *  room/event/mxc. */
  def aliceSendsMarker(c: MatrixClient, contactId: String, d: Long): Boolean =
    if !grabSelf(c) then false
    else if !sendVoice(c, "", contactId, d) then false
    else if !Runtime.waitForSnapshot(c, s => lastDursMatch(s, contactId, one(d)), 20000L) then false
    else stashMsgWithDur(Runtime.lastSnap, contactId, d)

  def aliceRedacts(c: MatrixClient, roomId: String, eventId: String): Boolean =
    if !grabSelf(c) then false
    else
      // FIFO: the redact executes before the ActQuit pill ends the phase.
      Runtime.sendAction(c, ActRedact(roomId, eventId))
      true

  def one(d: Long): List[Long] =
    var ds: List[Long] = Nil
    ds = d :: ds
    ds

  /** bob downloads alice's audio and bytes match — direct HTTP as bob,
   *  outside the client runtime (the audio queue is stubbed). */
  def s08(): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => aliceSendsMarker(c, bobId, 555L)) then false
      else
        val aliceId = outId
        if !phase("bob")(c => grabSelf(c) &&
            Runtime.waitForSnapshot(c, s => lastDursMatch(s, aliceId, one(555L)), 20000L) &&
            stashMsgWithDur(Runtime.lastSnap, aliceId, 555L)) then false
        else downloadMatches(outMxc)

  def downloadMatches(mxc: String): Boolean =
    val tok = directToken("bob")
    if tok == "" then false
    else
      val dl = MatrixHttp.downloadMedia(directHs(tok), mxc)
      dl.status == 200 && Bytes.fromRawString(dl.body) == fakeOgg()

  /** family room — both members see a voice message: alias'd public room
   *  created out-of-band, bob auto-joins his invite, marker 888 visible to
   *  both. */
  def s09(): Boolean =
    val tok = directToken("alice")
    if tok == "" then false
    else
      val cr = MatrixHttp.createRoomWithAlias(directHs(tok), "family", "@bob:localhost")
      if cr.status != 200 then false
      else if !phase("alice")(c => aliceFamilySend(c, 888L)) then false
      else phase("bob")(c => grabSelf(c) &&
        Runtime.waitForSnapshot(c, s => familyReady(s), 30000L) &&
        Runtime.waitForSnapshot(c, s => familyHasDur(s, 888L), 20000L))

  def aliceFamilySend(c: MatrixClient, d: Long): Boolean =
    if !grabSelf(c) then false
    else if !Runtime.waitForSnapshot(c, s => familyReady(s), 30000L) then false
    else if !stashFamilyRoom(Runtime.lastSnap) then false
    else if !sendVoice(c, outRoom, "", d) then false
    else Runtime.waitForSnapshot(c, s => familyHasDur(s, d), 20000L)

  /** login-or-resume via a stored session (not part of the original nine
   *  scenarios above): resume with a stored token — the second client
   *  carries a WRONG password, so only the resume path can reach Syncing;
   *  the published creds must be the STORED token (no fresh login). */
  def s10(): Boolean =
    if !phase("alice")(c => grabSelf(c)) then false
    else
      val creds = Runtime.lastAuth
      if creds.accessToken == "" then false
      else
        val stored = Session(base, "alice", creds.accessToken, creds.userId, "dev0")
        val config = ClientConfig(base, "alice", "WRONG-PASSWORD", 1000, stored)
        if !phaseCfg(config)(c => grabSelf(c)) then false
        else Runtime.lastAuth.accessToken == creds.accessToken

  // ---- canonical DMs -----------------------------------------------------------

  /** BOTH sides resolve the DM, each unaware of the other, and converge on ONE
   *  room — the property the old client's dedup/convergence dance existed to
   *  approximate. Asking twice from the same side is the same answer again, and
   *  the room the client actually sends into is that same room. */
  def s11(): Boolean =
    val atok = directToken("alice")
    val btok = directToken("bob")
    if atok == "" || btok == "" then false
    else
      val a1 = dmRoomId(atok, "@bob:localhost")
      val b1 = dmRoomId(btok, "@alice:localhost")
      val a2 = dmRoomId(atok, "bob")
      if a1 == "" then false
      else if a1 != b1 then false
      else if a1 != a2 then false
      else aliceSendsInto(a1)

  /** alice's first send resolves the DM through the endpoint (roomId "") and
   *  lands in exactly the room both sides already agreed on. */
  def aliceSendsInto(want: String): Boolean =
    if !phase("bob")(c => grabSelf(c)) then false
    else
      val bobId = outId
      if !phase("alice")(c => aliceSendsMarker(c, bobId, 111L)) then false
      else outRoom == want

  /** a stock Matrix client's DM-shaped createRoom is answered with the SAME
   *  canonical room — twice over, and from the other side too, so no duplicate
   *  DM can appear even from a client that never heard of the endpoint. */
  def s12(): Boolean =
    val atok = directToken("alice")
    val btok = directToken("bob")
    if atok == "" || btok == "" then false
    else
      val canonical = dmRoomId(atok, "@bob:localhost")
      val c1 = stockDmRoomId(atok, "@bob:localhost")
      val c2 = stockDmRoomId(atok, "@bob:localhost")
      val c3 = stockDmRoomId(btok, "@alice:localhost")
      if canonical == "" then false
      else canonical == c1 && canonical == c2 && canonical == c3

  // ---- limited-timeline backfill (the paged /messages walk + queue drain) ------
  //
  // Both scenarios force a DEEP GAP while alice's client is away: the DM room
  // is minted out-of-band (the dm endpoint joins both sides), bob pumps voice
  // events into it by direct HTTP, and only THEN does alice's client start.
  // Her initial sync's room block carries the newest `timelineWindow` (20)
  // events with `limited: true`, so everything older arrives only through the
  // deferred GET /messages walk (50-event pages, 2 per round, 10-page cap).
  // The pump gives message i duration i, so ONE walk of the final snapshot
  // (`dursRun`) asserts completeness, exact count, and chronological order at
  // once — and the counts are chosen against the server's window (20) and the
  // client's page size (50) so that the assertion can only pass if the paged
  // walk really ran.

  /** alice syncs into a room holding 130 pumped messages: 20 arrive in the
   *  limited window, the other 110 need THREE backward /messages pages (50 >
   *  110/3 > 2×50 − 110), so a single-page backfill cannot satisfy the exact
   *  1..130 run. */
  def s13(): Boolean =
    val atok = directToken("alice")
    val btok = directToken("bob")
    if atok == "" || btok == "" then false
    else
      val room = dmRoomId(atok, "@bob:localhost")
      // the dm endpoint joins only its CALLER (the peer holds an invite), so
      // bob asks too before pumping — and must get the same canonical room.
      if room == "" || dmRoomId(btok, "@alice:localhost") != room then false
      else if !pumpVoices(btok, room, 130) then false
      else phase("alice")(c => grabSelf(c) &&
        Runtime.waitForSnapshot(c, s => dursRun(s, "@bob:localhost", 1L, 130), 30000L))

  /** a gap deeper than the 10-page cap STAYS a gap: 531 pumped messages =
   *  20 in the window + 10 pages × 50 recovered = exactly 520 present,
   *  starting at duration 12 (messages 1..11 are the abandoned deep end),
   *  and the count never grows past 520 afterwards. */
  def s14(): Boolean =
    val atok = directToken("alice")
    val btok = directToken("bob")
    if atok == "" || btok == "" then false
    else
      val room = dmRoomId(atok, "@bob:localhost")
      if room == "" || dmRoomId(btok, "@alice:localhost") != room then false
      else if !pumpVoices(btok, room, 531) then false
      else phase("alice")(c => capChecks(c))

  def capChecks(c: MatrixClient): Boolean =
    if !grabSelf(c) then false
    else if !Runtime.waitForSnapshot(c, s => dursRun(s, "@bob:localhost", 12L, 520), 60000L) then false
    // no later snapshot may exceed the cap's yield — the walk must NOT resume.
    else if Runtime.waitForSnapshot(c, s => hasMsgFrom(s, "@bob:localhost", 521), 2500L) then false
    else true

  /** pump `n` voice events by direct HTTP (each ~1ms on loopback — a client
   *  phase would long-poll between sends). Message i gets duration i; the mxc
   *  is a shared fake (nothing downloads it here). */
  def pumpVoices(token: String, roomId: String, n: Int): Boolean =
    val hs = directHs(token)
    var i = 1
    var ok = true
    while ok && i <= n do
      val resp = MatrixHttp.sendVoiceMessage(hs, roomId, "mxc://localhost/pumped", i.toLong, 12, i)
      ok = resp.status == 200
      i += 1
    ok

  /** the conversation with `contactId` holds EXACTLY `n` messages with
   *  durations `firstDur, firstDur+1, …` in order — completeness, count, and
   *  chronological order in one walk. */
  def dursRun(s: StateSnapshot, contactId: String, firstDur: Long, n: Int): Boolean =
    findConv(s.conversations, contactId) match
      case cv: Some[Conversation] => runMatches(cv.value.messages, firstDur, n)
      case None => false

  def runMatches(ms: List[VoiceMessage], firstDur: Long, n: Int): Boolean =
    var cur = ms
    var want = firstDur
    var left = n
    var ok = true
    var going = true
    while going do
      cur match
        case m :: t =>
          if left == 0 then
            ok = false
            going = false
          else if m.durationMs != want then
            ok = false
            going = false
          else
            want += 1L
            left -= 1
            cur = t
        case Nil =>
          if left != 0 then ok = false
          going = false
    ok

  /** the dialect endpoint, out of band; "" on failure. */
  def dmRoomId(token: String, peer: String): String =
    val resp = MatrixHttp.dmRoom(directHs(token), peer)
    if resp.status != 200 then "" else MatrixHttp.parseRoomId(resp.body)

  /** what a stock client sends: createRoom with is_direct + one invitee. */
  def stockDmRoomId(token: String, peer: String): String =
    val resp = MatrixHttp.createRoomStockDm(directHs(token), peer)
    if resp.status != 200 then "" else MatrixHttp.parseRoomId(resp.body)
