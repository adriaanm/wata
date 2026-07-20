/** M8 chunk 4 — the MatrixClient RUNTIME, ported from fbclient `client.zig` +
 *  `sync_thread.zig`. PORTABLE — ZERO `go` facade use: transport/time ride the injected
 *  capabilities; concurrency is the sgo surface (CONC-2's first client-side
 *  outing: `supervised`/`fork` + `Chan`).
 *
 *  SHAPE (client.zig): an action queue IN (`Chan[Action]`, the Zig Mailbox), a
 *  UI-event queue OUT (`Chan[UiEvent]`, the Zig BoundedQueue), and an
 *  IMMUTABLE-SNAPSHOT cell — Zig's atomic-swap StateStore becomes a capacity-1
 *  `Chan[StateSnapshot]`: publish = drain-then-send (sole producer: the sync
 *  loop), acquire = non-blocking receive that TAKES the snapshot (exactly the
 *  Zig `swap(0)` semantics). Zig's OwnedSnapshot refcounting dies — GC; the
 *  snapshot stays an immutable value, the UI never sees shared mutable state.
 *
 *  SHUTDOWN (the M7 close-signalled pattern — no new cancellation mechanism;
 *  the pending cancellation-as-tracked-signal spec adopts this shape for free):
 *  `stop` is a channel that is only ever CLOSED; the sync loop polls it
 *  non-blockingly between rounds and exits via its ordinary while-condition.
 *  The action loop exits on the `ActQuit` POISON PILL (an ordinary value through
 *  the ordinary receive edge — closing the action channel would hand a nil
 *  interface to `match`). `stopClient` = close(stop) + send(ActQuit).
 *
 *  QUEUE-SEMANTICS DEPARTURE (recorded): Zig's BoundedQueue.push DROPS when
 *  full; a `Chan.send` BLOCKS (CONC-2 has no non-blocking send). The UI-event
 *  channel is sized 1024 (~500 sync rounds of slack at <=2 events/round); the
 *  app/oracle contract is to poll it. DOGFOOD'd for the designer.
 *
 *  SINGLE-CLIENT-PER-PROCESS (the accepted chunk-3 adjudication): the engine is
 *  a singleton and the poll/stash cells below are module vars — one runtime at
 *  a time, phases run sequentially. Each var is touched by exactly ONE
 *  goroutine (stash cells: the driver; txn counter: the action loop; stop probe:
 *  the sync loop); `lastAuth` crosses only over the supervised-join barrier. */

// ---- actions (types.zig Action; inline [128]u8 buffers become Strings) -------
import sgo.add  // the Atomic[Int] add extension (M9 ch.4a: txnCounter cell)

sealed trait Action
case class ActReceipt(roomId: String, eventId: String) extends Action
/** roomId "" -> resolve/create the DM room for contactId first (Zig). */
case class ActSendVoice(roomId: String, contactId: String, ogg: Bytes, durationMs: Long) extends Action
case class ActPlay(mxcUrl: String) extends Action
case class ActSetName(name: String) extends Action
case class ActRedact(roomId: String, eventId: String) extends Action
/** the shutdown poison pill (no Zig counterpart — Zig closes its Mailbox). */
case class ActQuit() extends Action

// ---- UI events (types.zig UiEvent — the subset these loops push) -------------
sealed trait UiEvent
case class EvConn(state: ConnectionState) extends UiEvent
case class EvSnapshot() extends UiEvent
case class EvSendComplete(txnId: Int) extends UiEvent
case class EvSendFailed(txnId: Int) extends UiEvent
/** audio is DECOUPLED in chunk 4 (client.zig `audio_cmd_queue = null`): a
 *  download-and-play still downloads, then surfaces this (the Zig behavior). */
case class EvPlaybackError() extends UiEvent

/** login-published credentials (sync_thread.zig AuthCredentials; the Zig
 *  AuthStore atomic pointer becomes a capacity-1 channel). A zero/empty
 *  accessToken = "login failed, stand down" (the channel-close zero value). */
case class AuthCreds(accessToken: String, userId: String)

/** client.zig Config + the stored session for login-or-resume (decision 8;
 *  where the session comes FROM — config.json — is the app layer's business). */
case class ClientConfig(homeserver: String, username: String, password: String,
                        syncTimeoutMs: Int, stored: Session)

/** the client handle (client.zig MatrixClient): config + capabilities + the
 *  channels. A plain immutable record — all state lives in the engine
 *  singleton and the channels. M8 chunk 6: `audioCmds` is the audio thread's
 *  command mailbox (client.zig `audio_cmd_queue: ?*CommandQueue`); a `Chan`
 *  cannot be null, so the Zig nullability becomes the `audioEnabled` flag —
 *  false = headless (actions needing audio surface `EvPlaybackError`, the
 *  chunk-4/`-Daudio=false` behavior the integ oracle pins). */
case class MatrixClient(
  cfg: ClientConfig,
  http: HttpDo,
  clock: Clock,
  actions: sgo.Chan[Action],
  events: sgo.Chan[UiEvent],
  snaps: sgo.Chan[StateSnapshot],
  stop: sgo.Chan[Boolean],
  auth: sgo.Chan[AuthCreds],
  audioEnabled: Boolean,
  audioCmds: sgo.Chan[AudioCmd]
) extends Shareable
// M9 ch.6: `MatrixClient` is the client handle that crosses into the sync +
// action goroutines (`fork(syncLoop(c))`), so it derives `Shareable` — CONC-7
// then verifies every field is crossable at THIS definition site: `cfg` pure
// config, `http`/`clock` the `Shareable` capability traits (their impls curate
// their facade-handle fields), the `sgo.Chan[…]` synchronizers, `audioEnabled`
// a primitive. No hatch — the record proves itself where it is written.

object Runtime:

  // ---- construction / lifecycle ----------------------------------------------

  /** headless client (audio disabled — the chunk-4 shape; the oracle's). */
  def make(cfg: ClientConfig, http: HttpDo, clock: Clock): MatrixClient =
    mk(cfg, http, clock, false)

  /** audio-wired client (M8 chunk 6): `audioCmds` is consumed by the app-layer
   *  audio thread (`AudioThread.mainLoop` reads the SAME chan this handle
   *  carries — the client.zig `init(.., audio_cmd_queue)` seam). */
  def makeWithAudio(cfg: ClientConfig, http: HttpDo, clock: Clock): MatrixClient =
    mk(cfg, http, clock, true)

  def mk(cfg: ClientConfig, http: HttpDo, clock: Clock, audioEnabled: Boolean): MatrixClient =
    // chans BOUND TO LOCALS first: makeChan's element-type recording is the
    // local-val path (a makeChan in ARGUMENT position has no symbol to record).
    val actions = sgo.makeChan[Action](64)        // sync_thread.zig Mailbox(_, 64)
    val events = sgo.makeChan[UiEvent](1024)      // Zig BoundedQueue(_, 256) drops; we block — see header
    val snaps = sgo.makeChan[StateSnapshot](1)    // the snapshot cell (StateStore)
    val stop = sgo.makeChan[Boolean]()            // close-signalled stop
    val auth = sgo.makeChan[AuthCreds](1)         // AuthStore
    val audioCmds = sgo.makeChan[AudioCmd](16)    // audio_thread.zig Mailbox(Command, 16)
    MatrixClient(cfg, http, clock, actions, events, snaps, stop, auth, audioEnabled, audioCmds)

  /** spawn the sync + action loops in the CALLER's supervised scope (client.zig
   *  `start` spawns threads; structured concurrency makes the caller's scope own
   *  them — `supervised { start(c); …drive…; stopClient(c) }`). */
  def start(c: MatrixClient)(using sgo.Scope): Unit =
    // M9 ch.6: the bridge hatch RETIRED. `MatrixClient` carries the `HttpDo`/
    // `Clock` capability traits, which now `extends sgo.Shareable` — each impl
    // proves its facade-handle fields crossable at its own definition site
    // (CONC-7 + the curated-facade disjunct, §4). So the fork captures are
    // Shareable by construction; no hatch needed.
    sgo.fork(syncLoop(c))
    sgo.fork(actionLoop(c))
    ()

  /** signal both loops to wind down (client.zig `stop`, minus the joins — the
   *  enclosing `supervised` IS the join). Idempotence caveat: close twice
   *  panics, send-after-exit just buffers; call once, like the Zig. */
  def stopClient(c: MatrixClient): Unit =
    c.stop.close()
    c.actions.send(ActQuit())

  def sendAction(c: MatrixClient, a: Action): Unit = c.actions.send(a)

  // ---- the sync loop (sync_thread.zig syncThreadMain) -------------------------

  def syncLoop(c: MatrixClient): Unit =
    c.events.send(EvConn(Connecting()))
    val creds = loginOrResume(c)
    if creds.accessToken == "" then
      c.events.send(EvConn(ConnError()))
      c.auth.close()                     // unblock the action loop (zero creds)
    else
      lastAuthC.set(creds)
      c.events.send(EvConn(Connected()))
      c.auth.send(creds)
      SyncEngine.reset()
      SyncEngine.setSelfUser(creds.userId)
      syncRounds(c, Hs(c.http, c.clock, c.cfg.homeserver, creds.accessToken), creds.userId)

  /** login-or-resume (sync_thread.zig): a stored session's token is validated
   *  with a zero-timeout test sync; expired/foreign -> password login. Empty
   *  accessToken on total failure. */
  def loginOrResume(c: MatrixClient): AuthCreds =
    var token = ""
    var uid = ""
    val st = c.cfg.stored
    if Sessions.isValid(st) && st.homeserver == c.cfg.homeserver then
      val probe = MatrixHttp.sync(Hs(c.http, c.clock, c.cfg.homeserver, st.accessToken), "", 0)
      if probe.status == 200 then
        token = st.accessToken
        uid = st.userId
    if token == "" then
      val resp = MatrixHttp.login(Hs(c.http, c.clock, c.cfg.homeserver, ""),
        c.cfg.username, c.cfg.password)
      if resp.status == 200 then
        val lr = Matrix.parseLogin(MatrixHttp.parseOrNull(resp.body))
        token = lr.accessToken
        uid = lr.userId
    AuthCreds(token, uid)

  /** the long-poll loop: stop is checked FIRST each round and re-checked after
   *  the (blocking, <= syncTimeoutMs) sync call — the exit path is the ordinary
   *  while-condition, never a mid-loop abort. */
  def syncRounds(c: MatrixClient, hs: Hs, selfUid: String): Unit =
    var retryMs = 1000L
    var run = true
    while run do
      if isStopped(c) then run = false
      else
        val resp = MatrixHttp.sync(hs, SyncEngine.nextBatch, c.cfg.syncTimeoutMs)
        if isStopped(c) then run = false
        else if resp.status != 200 then
          c.events.send(EvConn(ConnError()))
          c.clock.sleepMs(retryMs)       // exponential backoff (Zig: 1s .. 60s)
          retryMs = retryMs * 2L
          if retryMs > 60000L then retryMs = 60000L
        else
          val j = MatrixHttp.parseOrNull(resp.body)
          if isNullJ(j) then c.events.send(EvConn(ConnError())) // parse fail: err + continue (Zig)
          else
            processRound(c, hs, selfUid, j)
            retryMs = 1000L

  def isNullJ(j: Json): Boolean = j match
    case _: JNull => true
    case _        => false

  /** one successful sync round: engine ingest -> invite auto-join -> backfill
   *  -> snapshot publish (the Zig order — auto-join after process() so the NEXT
   *  sync carries the joined room). Backfill fires on TWO triggers: a limited
   *  timeline (the Zig/Conduit gap trigger, via prev_batch) and OUR OWN join in
   *  the delta (a room that is new to us — wata-server never sets `limited`,
   *  and its /messages `from` is an EVENT id, so the new-room trigger fetches
   *  the recent TAIL instead; the engine's dedup absorbs any overlap). */
  def processRound(c: MatrixClient, hs: Hs, selfUid: String, j: Json): Unit =
    val evs = SyncEngine.process(j)
    autoJoin(hs, WJson.objField(WJson.objField(j, "rooms"), "invite"))
    backfillRooms(hs, WJson.objField(WJson.objField(j, "rooms"), "join"))
    backfillNewJoins(hs, evs, selfUid)
    publishSnapshot(c)

  /** the self-join trigger: process() emitted MembershipChanged(join) for US ->
   *  fetch that room's message tail (duplicate membership events just repeat a
   *  deduped fetch). */
  def backfillNewJoins(hs: Hs, evs: List[SyncEvent], selfUid: String): Unit =
    var cur = evs
    var going = true
    while going do
      cur match
        case e :: t =>
          backfillIfSelfJoin(hs, e, selfUid)
          cur = t
        case Nil => going = false

  def backfillIfSelfJoin(hs: Hs, e: SyncEvent, selfUid: String): Unit = e match
    case m: MembershipChanged =>
      if m.membership == "join" && m.userId == selfUid then backfillTail(hs, m.roomId)
    case _ => ()

  /** tail backfill for a newly joined room: /messages with NO from token
   *  (wata-server: the newest `limit` events), ingested OLDEST-FIRST (the chunk
   *  arrives newest-first; the engine appends in ingest order — normalizing to
   *  oldest-first keeps message order deterministic, the house rule; Zig
   *  ingests as-served and leans on dedup). */
  def backfillTail(hs: Hs, roomId: String): Unit =
    val resp = MatrixHttp.getMessagesTail(hs, roomId, 50)
    if resp.status == 200 then
      ingestChunk(roomId, ListOps.reverse(SyncEngine.arrItems(
        WJson.objField(MatrixHttp.parseOrNull(resp.body), "chunk"))))

  /** trusted family environment — accept ALL invites (Zig). */
  def autoJoin(hs: Hs, inviteMap: Json): Unit =
    var cur = MatrixHttp.objFields(inviteMap)
    var going = true
    while going do
      cur match
        case p :: t =>
          joinOne(hs, p)
          cur = t
        case Nil => going = false

  def joinOne(hs: Hs, p: (String, Json)): Unit =
    val roomId: String = p._1
    drop(MatrixHttp.joinRoom(hs, roomId))

  def backfillRooms(hs: Hs, joinMap: Json): Unit =
    var cur = MatrixHttp.objFields(joinMap)
    var going = true
    while going do
      cur match
        case p :: t =>
          backfillIfLimited(hs, p)
          cur = t
        case Nil => going = false

  def backfillIfLimited(hs: Hs, p: (String, Json)): Unit =
    val roomId: String = p._1
    if WJson.boolField(WJson.objField(p._2, "timeline"), "limited") then
      backfillRoom(hs, roomId)

  /** sync_thread.zig `backfillRoom`: GET /messages(prev_batch, dir=b, 50) and
   *  feed the chunk through the engine's dedup + voice extraction. */
  def backfillRoom(hs: Hs, roomId: String): Unit =
    val pb = SyncEngine.prevBatchOf(roomId)
    if pb != "" then
      val resp = MatrixHttp.getMessages(hs, roomId, pb, 50)
      if resp.status == 200 then
        ingestChunk(roomId, SyncEngine.arrItems(
          WJson.objField(MatrixHttp.parseOrNull(resp.body), "chunk")))

  def ingestChunk(roomId: String, evs: List[Json]): Unit =
    var cur = evs
    var going = true
    while going do
      cur match
        case e :: t =>
          SyncEngine.ingestBackfill(roomId, e)
          cur = t
        case Nil => going = false

  /** publish to the capacity-1 cell: drain (sole producer — never racing
   *  another send), then send; a reader between the two just sees "no snapshot
   *  yet", exactly the Zig swap window. */
  def publishSnapshot(c: MatrixClient): Unit =
    val snap = SyncEngine.buildSnapshot()
    sgo.selectOrDefault(c.snaps)((old: StateSnapshot) => ())(())
    c.snaps.send(snap)
    c.events.send(EvConn(Syncing()))
    c.events.send(EvSnapshot())

  // ---- the action loop (sync_thread.zig actionThreadMain) ---------------------

  def actionLoop(c: MatrixClient): Unit =
    val creds = c.auth.recv()            // blocks; login failure closes -> zero creds
    if creds.accessToken == "" then ()   // stand down (Zig: mailbox closed while waiting)
    else
      val hs = Hs(c.http, c.clock, c.cfg.homeserver, creds.accessToken)
      var run = true
      while run do
        run = execAction(c, hs, creds.userId, c.actions.recv())

  /** returns false only for the poison pill — every real action keeps looping. */
  def execAction(c: MatrixClient, hs: Hs, selfUid: String, a: Action): Boolean = a match
    case _: ActQuit     => false
    case r: ActReceipt  => execReceipt(hs, r)
    case m: ActSendVoice => execSendVoice(c, hs, selfUid, m)
    case d: ActPlay     => execPlay(c, hs, d)
    case n: ActSetName  => execSetName(hs, selfUid, n)
    case x: ActRedact   => execRedact(hs, x)

  def execReceipt(hs: Hs, r: ActReceipt): Boolean =
    drop(MatrixHttp.sendReadReceipt(hs, r.roomId, r.eventId)) // best-effort (Zig catch {})
    true

  def execSendVoice(c: MatrixClient, hs: Hs, selfUid: String, m: ActSendVoice): Boolean =
    val txn = txnCounterC.add(1)
    var roomId = m.roomId
    if roomId == "" then roomId = resolveDmRoom(hs, selfUid, m.contactId)
    if roomId == "" then c.events.send(EvSendFailed(txn))
    else
      val up = MatrixHttp.uploadMedia(hs, m.ogg)
      val mxc = MatrixHttp.parseMxcUrl(up.body)
      if up.status != 200 then c.events.send(EvSendFailed(txn))
      else if mxc == "" then c.events.send(EvSendFailed(txn))
      else
        val sv = MatrixHttp.sendVoiceMessage(hs, roomId, mxc, m.durationMs, m.ogg.size, txn)
        if sv.status == 200 then c.events.send(EvSendComplete(txn))
        else c.events.send(EvSendFailed(txn))
    true

  /** DM-room resolution (sync_thread.zig / TS getOrCreateDmRoom parity): reuse
   *  the first m.direct room for the contact, else create + tag m.direct. */
  def resolveDmRoom(hs: Hs, selfUid: String, contactId: String): String =
    if contactId == "" then ""
    else
      var roomId = ""
      val got = MatrixHttp.getAccountData(hs, selfUid, "m.direct")
      if got.status == 200 then roomId = MatrixHttp.findRoomForContact(got.body, contactId)
      if roomId == "" then
        val cr = MatrixHttp.createRoom(hs, contactId)
        if cr.status == 200 then roomId = MatrixHttp.parseRoomId(cr.body)
        if roomId != "" then updateMDirect(hs, selfUid, contactId, roomId)
      roomId

  /** GET current m.direct ("{}"-when-absent) -> upsert -> PUT (best-effort). */
  def updateMDirect(hs: Hs, selfUid: String, contactId: String, roomId: String): Unit =
    val got = MatrixHttp.getAccountData(hs, selfUid, "m.direct")
    var existing = "{}"
    if got.status == 200 then existing = got.body
    drop(MatrixHttp.setAccountData(hs, selfUid, "m.direct",
      MatrixHttp.mdirectWithRoom(existing, contactId, roomId)))

  /** the download-play action (sync_thread.zig `.dl_play`): download; on failure
   *  -> `EvPlaybackError`; on success -> route the Ogg bytes to the audio thread
   *  (drop-on-full `trySend`, the Zig `_ = acq.send(.play)` bool-discard) when
   *  audio is wired, else surface `EvPlaybackError` (the Zig `-Daudio=false` /
   *  null-queue behavior — chunk 4's shape, still what the integ oracle pins). */
  def execPlay(c: MatrixClient, hs: Hs, d: ActPlay): Boolean =
    val resp = MatrixHttp.downloadMedia(hs, d.mxcUrl)
    if resp.status != 200 then c.events.send(EvPlaybackError())
    else if c.audioEnabled then
      c.audioCmds.trySend(AcPlay(Bytes.fromRawString(resp.body)))
      ()
    else c.events.send(EvPlaybackError())
    true

  def execSetName(hs: Hs, selfUid: String, n: ActSetName): Boolean =
    drop(MatrixHttp.setDisplayName(hs, selfUid, n.name))
    true

  def execRedact(hs: Hs, x: ActRedact): Boolean =
    drop(MatrixHttp.redactEvent(hs, x.roomId, x.eventId, txnCounterC.add(1)))
    true

  /** discard a response in statement position (a bare non-call value statement
   *  is not legal Go; a call statement is). */
  def drop(r: HttpResponse): Unit = ()

  // ---- module state (single-client-per-process — see header) ------------------

  // M9 ch.4a (CONC-10 migration): `val`-held Atomic cells (CONCURRENCY.md
  // §4.3) — the single-client-per-process protocol is unchanged; the cells
  // make the by-naming reachability DRF instead of conventional. `txnCounter`
  // rides the native atomic word (`add` returns the new value — the old
  // increment-then-read, now one RMW); `lastAuthV`/`snapVal` swap immutable
  // case-class snapshots (boxed cells, one alloc per store).
  private val txnCounterC: sgo.Atomic[Int] = sgo.atomic(0)
  private val lastAuthC: sgo.Atomic[AuthCreds] = sgo.atomic(AuthCreds("", ""))
  /** the credentials the last login/resume produced (the app persists them as a
   *  Session — config.json is the app/chunk-7 layer; the resume oracle reads
   *  this AFTER the supervised scope joined). */
  def lastAuth: AuthCreds = lastAuthC.get()

  // ---- non-blocking polls (M8 chunk 6: rewritten onto the pinned CONC-2
  //      surface — `tryReceive`/`selectValue` RETURN values, killing the
  //      chunk-4 stash-into-module-cells idiom, which only held under
  //      one-goroutine-per-cell discipline and would rot) ----------------------

  /** take one UI event if available (client.zig pollEvent). */
  def pollEvent(c: MatrixClient): Option[UiEvent] = c.events.tryReceive()

  /** TAKE the latest snapshot if one is published (client.zig acquireSnapshot —
   *  the cell empties, Zig's `swap(0)`). Also refreshes `lastSnap` (the
   *  oracle's read-after-wait window). */
  def pollSnap(c: MatrixClient): Option[StateSnapshot] =
    val o = c.snaps.tryReceive()
    o match
      case s: Some[StateSnapshot] => snapC.set(s.value)
      case None => ()
    o

  /** the most recent snapshot `pollSnap`/`waitForSnapshot` saw (client.zig
   *  keeps the acquired snapshot; the integ oracle reads it after a wait). */
  def lastSnap: StateSnapshot = snapC.get()
  private val snapC: sgo.Atomic[StateSnapshot] = sgo.atomic(emptySnapshot())

  def emptySnapshot(): StateSnapshot =
    StateSnapshot(Disconnected(), false, User("", ""), Nil,
      Nil, false, Family("", "", Nil))

  /** non-blocking stop probe (a CLOSED channel's receive is always ready —
   *  the M7 close-signal persistence). select-as-EXPRESSION: the taken arm's
   *  value IS the answer, no cell. Sync-loop-only. */
  def isStopped(c: MatrixClient): Boolean =
    sgo.selectValue[Boolean, Boolean](c.stop)((b: Boolean) => true)(false)

  // ---- wait helpers (client.zig waitForConnection / waitForSnapshot) ----------

  /** drain events until one is `EvConn(want)` or the deadline passes. */
  def waitForConnection(c: MatrixClient, want: ConnectionState, timeoutMs: Long): Boolean =
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var found = false
    var run = true
    while run do
      if found then run = false
      else if c.clock.nowUnixMillis() >= deadline then run = false
      else
        pollEvent(c) match
          case s: Some[UiEvent] => found = isConnEvent(s.value, want)
          case None => c.clock.sleepMs(20L)
    found

  def isConnEvent(e: UiEvent, want: ConnectionState): Boolean = e match
    case cs: EvConn => sameConn(cs.state, want)
    case _          => false

  /** ConnectionState equality via tags (the subset compares primitives). */
  def connTag(s: ConnectionState): Int = s match
    case _: Disconnected => 0
    case _: Connecting   => 1
    case _: Connected    => 2
    case _: Syncing      => 3
    case _: ConnError    => 4

  def sameConn(a: ConnectionState, b: ConnectionState): Boolean = connTag(a) == connTag(b)

  /** poll snapshots until `pred` matches (the match stays in `lastSnap`) or the
   *  deadline passes; non-matching snapshots are discarded (Zig releases them). */
  def waitForSnapshot(c: MatrixClient, pred: StateSnapshot => Boolean, timeoutMs: Long): Boolean =
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var found = false
    var run = true
    while run do
      if found then run = false
      else if c.clock.nowUnixMillis() >= deadline then run = false
      else
        pollSnap(c) match
          case s: Some[StateSnapshot] => found = pred(s.value)
          case None => c.clock.sleepMs(20L)
    found

