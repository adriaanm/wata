/** the MatrixClient RUNTIME. PORTABLE — zero `go` facade use directly:
 *  transport/time ride the injected capabilities; concurrency is the sgo
 *  concurrency surface (`supervised`/`fork` + `Chan`).
 *
 *  SHAPE: an action queue IN (`Chan[Action]`), a UI-event queue OUT
 *  (`Chan[UiEvent]`), and an IMMUTABLE-SNAPSHOT cell — a capacity-1
 *  `Chan[StateSnapshot]`: publish = drain-then-send (sole producer: the sync
 *  loop), acquire = non-blocking receive that TAKES the snapshot (a swap-out
 *  semantics). The snapshot stays an immutable value, so the UI never sees
 *  shared mutable state.
 *
 *  CONNECT LIFECYCLE (plan 0022): the sync loop NEVER terminates on failure.
 *  Login-or-resume is itself wrapped in the 1s..60s backoff the sync rounds
 *  use, so a device that boots before its network, or against a server that
 *  is down, keeps trying until it is stopped. A login the server REJECTS
 *  (401/403) is distinguished from a transport failure: it also retries, but
 *  jumps straight to the 60s ceiling and publishes `ConnAuthRejected`, which
 *  is a different sentence on screen ("check the account", not "waiting for
 *  network"). A mid-session 401/403 on `/sync` ends the session and relogs
 *  in rather than backing off against a token that will never work again.
 *
 *  SHUTDOWN (a close-signalled pattern — no separate cancellation mechanism):
 *  `stop` is a channel that is only ever CLOSED; the sync loop polls it
 *  non-blockingly between rounds AND inside every backoff sleep (which runs in
 *  short slices), and exits via its ordinary while-condition. The action loop
 *  exits on the `ActQuit` POISON PILL (an ordinary value through the ordinary
 *  receive edge — closing the action channel would hand a nil interface to
 *  `match`). `stopClient` = close(stop) + poison pill, ONCE: it is idempotent
 *  (a `closed` cell), because the disconnect path (Settings -> Network OFF)
 *  and the quit path both call it and closing a channel twice panics.
 *
 *  QUEUE-SEMANTICS NOTE: NOTHING the UI goroutine calls may block on a
 *  channel. `sendAction` is a `trySend` — on a full action queue the
 *  best-effort actions (receipts, favorites, name, redact) drop silently and
 *  send/play surface their ordinary failure event, which is the flash the UI
 *  already draws. The UI-event channel is sized 1024 (~500 sync rounds of
 *  slack at <=2 events/round); the app/oracle contract is to poll it.
 *
 *  RETRY-NOW: `retry` is a capacity-1 poke channel. The boot screen's OK key
 *  calls `retryNow`, which arms it; every backoff sleep polls it between
 *  slices and returns early, and the caller then resets its backoff to 1s. So
 *  a user staring at "can't reach server" can force the next attempt instead
 *  of waiting out a 60s ceiling.
 *
 *  SINGLE-CLIENT-PER-PROCESS: the engine is a singleton and the poll/stash
 *  cells below are module vars — one runtime at a time, phases run
 *  sequentially. Each var is touched by exactly ONE goroutine (stash cells:
 *  the driver; txn counter: the action loop; stop/retry probes + backfill
 *  queue: the sync loop). `lastAuthC` is the one cell genuinely shared — the
 *  sync loop writes it at each login, the action loop reads it per action,
 *  the app reads it after the join — which is what an `Atomic` cell is for. */

// ---- actions -------------------------------------------------------------------
import sgo.add  // the Atomic[Int] add extension (the txnCounter cell)

sealed trait Action
case class ActReceipt(roomId: String, eventId: String) extends Action
/** roomId "" -> resolve/create the DM room for contactId first. */
case class ActSendVoice(roomId: String, contactId: String, ogg: Bytes, durationMs: Long) extends Action
case class ActPlay(mxcUrl: String) extends Action
case class ActSetName(name: String) extends Action
case class ActRedact(roomId: String, eventId: String) extends Action
/** toggle the server's favorite marker on one message (plan 0019): keeps it
 *  past the media-retention window until it is toggled back. */
case class ActFavorite(roomId: String, eventId: String) extends Action
/** the shutdown poison pill. */
case class ActQuit() extends Action

// ---- UI events -----------------------------------------------------------------
sealed trait UiEvent
case class EvConn(state: ConnectionState) extends UiEvent
case class EvSnapshot() extends UiEvent
case class EvSendComplete(txnId: Int) extends UiEvent
case class EvSendFailed(txnId: Int) extends UiEvent
/** audio can be DECOUPLED (no audio thread wired up): a download-and-play
 *  still downloads, then surfaces this instead of playing. */
case class EvPlaybackError() extends UiEvent

/** one room's deferred backfill walk: the token to page from next and how
 *  many pages this trigger has already fetched (`Runtime.maxBackfillPages`
 *  caps the walk). Lives only in the sync loop's queue cell. */
case class BackfillJob(roomId: String, from: String, pages: Int)

/** the credentials one session runs on. An empty accessToken = "not logged in
 *  right now" — the action loop then builds a token-less `Hs`, whose requests
 *  the server answers 401, which is the ordinary failure path every action
 *  already has. */
case class AuthCreds(accessToken: String, userId: String)

/** what one login-or-resume attempt produced: the credentials (empty token =
 *  it failed) and whether the failure was a REJECTION (401/403 from /login)
 *  rather than a transport failure. */
case class LoginOutcome(creds: AuthCreds, rejected: Boolean)

/** the client's login config plus the stored session for login-or-resume
 *  (where the session comes FROM — config.json — is the app layer's
 *  business). */
case class ClientConfig(homeserver: String, username: String, password: String,
                        syncTimeoutMs: Int, stored: Session)

/** the client handle: config + capabilities + the channels. A plain immutable
 *  record — all state lives in the engine singleton and the channels.
 *  `audioCmds` is the audio thread's command mailbox; a `Chan` cannot be
 *  null, so "no audio thread wired up" is instead represented by the
 *  `audioEnabled` flag — false = headless (actions needing audio surface
 *  `EvPlaybackError`). */
case class MatrixClient(
  cfg: ClientConfig,
  http: HttpDo,
  clock: Clock,
  actions: sgo.Chan[Action],
  events: sgo.Chan[UiEvent],
  snaps: sgo.Chan[StateSnapshot],
  stop: sgo.Chan[Boolean],
  retry: sgo.Chan[Boolean],
  audioEnabled: Boolean,
  audioCmds: sgo.Chan[AudioCmd]
) extends Shareable
// `MatrixClient` is the client handle that crosses into the sync + action
// goroutines (`fork(syncLoop(c))`), so it derives `Shareable` — that then
// requires every field to be crossable at THIS definition site: `cfg` pure
// config, `http`/`clock` the `Shareable` capability traits (their impls curate
// their facade-handle fields), the `sgo.Chan[…]` synchronizers, `audioEnabled`
// a primitive. No escape hatch — the record proves itself where it is written.

object Runtime:

  // ---- construction / lifecycle ----------------------------------------------

  /** headless client (audio disabled). */
  def make(cfg: ClientConfig, http: HttpDo, clock: Clock): MatrixClient =
    mk(cfg, http, clock, false)

  /** audio-wired client: `audioCmds` is consumed by the app-layer audio thread
   *  (`AudioThread.mainLoop` reads the SAME chan this handle carries). */
  def makeWithAudio(cfg: ClientConfig, http: HttpDo, clock: Clock): MatrixClient =
    mk(cfg, http, clock, true)

  def mk(cfg: ClientConfig, http: HttpDo, clock: Clock, audioEnabled: Boolean): MatrixClient =
    // chans BOUND TO LOCALS first: makeChan's element-type recording is the
    // local-val path (a makeChan in ARGUMENT position has no symbol to record).
    val actions = sgo.makeChan[Action](ACTION_QUEUE)
    val events = sgo.makeChan[UiEvent](1024)      // sized for polling, not draining eagerly — see header
    val snaps = sgo.makeChan[StateSnapshot](1)    // the snapshot cell
    val stop = sgo.makeChan[Boolean]()            // close-signalled stop
    val retry = sgo.makeChan[Boolean](1)          // the retry-now poke
    val audioCmds = sgo.makeChan[AudioCmd](16)
    resetSession()
    MatrixClient(cfg, http, clock, actions, events, snaps, stop, retry, audioEnabled, audioCmds)

  /** the action queue's capacity — also the bound on how many queued actions
   *  the shutdown poke may discard to make room for the poison pill. */
  val ACTION_QUEUE: Int = 64

  /** the module cells a fresh client starts from (single-client-per-process:
   *  the host drivers run several sequential sessions in one process). */
  def resetSession(): Unit =
    closedC.set(false)
    lastAuthC.set(AuthCreds("", ""))
    loginsC.set(0)
    backfillQC.set(Nil)

  /** spawn the sync + action loops in the CALLER's supervised scope: structured
   *  concurrency makes the caller's scope own them —
   *  `supervised { start(c); …drive…; stopClient(c) }`. */
  def start(c: MatrixClient)(using sgo.Scope): Unit =
    // `MatrixClient` carries the `HttpDo`/`Clock` capability traits, which
    // extend `sgo.Shareable` — each impl proves its facade-handle fields
    // crossable at its own definition site. So the fork captures are
    // Shareable by construction; no hatch needed.
    sgo.fork(syncLoop(c))
    sgo.fork(actionLoop(c))
    ()

  /** signal both loops to wind down (minus the joins — the enclosing
   *  `supervised` IS the join). IDEMPOTENT: the second call is a no-op, so the
   *  disconnect path (Settings -> Network OFF) and the quit path may both run
   *  — closing `stop` twice would panic. */
  def stopClient(c: MatrixClient): Unit =
    if !closedC.getAndSet(true) then
      c.stop.close()
      pokeQuit(c, 0)

  /** hand the action loop its poison pill WITHOUT blocking. The queue is
   *  bounded and may be full of work the loop is still grinding through, so a
   *  full queue makes room by discarding a queued action — we are shutting
   *  down, and the alternative is the caller (the UI goroutine) blocking on a
   *  send. Bounded by the queue's capacity. */
  def pokeQuit(c: MatrixClient, tries: Int): Unit =
    if !c.actions.trySend(ActQuit()) && tries < ACTION_QUEUE then
      dropOne(c)
      pokeQuit(c, tries + 1)

  def dropOne(c: MatrixClient): Unit =
    val gone = c.actions.tryReceive()
    ()

  /** ENQUEUE an action — non-blocking, always. The frame goroutine calls this,
   *  and a full queue (the action loop stuck on a request against a server
   *  that is not answering) must never freeze the UI. What a drop costs
   *  depends on the action: receipts, favorites, name pushes and redactions
   *  are best-effort and drop silently (a later one supersedes them); a voice
   *  send or a playback surfaces its ordinary failure event, i.e. the flash
   *  the UI already draws for a failed send. */
  def sendAction(c: MatrixClient, a: Action): Unit =
    if !c.actions.trySend(a) then dropped(c, a)

  def dropped(c: MatrixClient, a: Action): Unit = a match
    case _: ActSendVoice => sendFailEvent(c)
    case _: ActPlay      => playFailEvent(c)
    case _               => ()

  def sendFailEvent(c: MatrixClient): Unit =
    val ok = c.events.trySend(EvSendFailed(txnCounterC.add(1)))
    ()

  def playFailEvent(c: MatrixClient): Unit =
    val ok = c.events.trySend(EvPlaybackError())
    ()

  /** poke the login/backoff sleep: it ends its current sleep slice, the caller
   *  resets its backoff to 1s, and the next attempt happens now. The boot
   *  screen's OK key. Non-blocking — the cell holds one poke and a second one
   *  while it is armed is redundant. */
  def retryNow(c: MatrixClient): Unit =
    val ok = c.retry.trySend(true)
    ()

  /** login/resume attempts made this session — what a test watches to see that
   *  the loop is still trying, and that a retry poke landed. */
  def loginAttempts: Int = loginsC.get()

  // ---- the sync loop -----------------------------------------------------------

  /** THE session loop: log in (or resume), run sync rounds until the session
   *  ends, log in again. It exits only on `stop` — a failed login is a state
   *  to report and a sleep to serve, never a reason to stand down. */
  def syncLoop(c: MatrixClient): Unit =
    var retryMs = 1000L
    var run = true
    while run do
      if isStopped(c) then run = false
      else
        c.events.send(EvConn(Connecting()))
        val o = loginOrResume(c)
        if o.creds.accessToken != "" then
          runSession(c, o.creds)
          retryMs = 1000L                // a session that ended relogs in promptly
        else
          c.events.send(EvConn(loginFailState(o)))
          if o.rejected then retryMs = 60000L   // rejection: straight to the ceiling
          if backoffSleep(c, retryMs) then retryMs = 1000L
          else retryMs = nextBackoff(retryMs)

  /** a rejected login is its own state; anything else (status 0, 5xx, a
   *  timeout, a malformed answer) is a transport failure. */
  def loginFailState(o: LoginOutcome): ConnectionState =
    if o.rejected then ConnAuthRejected() else ConnError()

  /** one logged-in session: publish the credentials, reset the engine, run
   *  rounds until stop or an auth failure ends it. */
  def runSession(c: MatrixClient, creds: AuthCreds): Unit =
    lastAuthC.set(creds)
    c.events.send(EvConn(Connected()))
    SyncEngine.reset()
    backfillQC.set(Nil)
    SyncEngine.setSelfUser(creds.userId)
    syncRounds(c, Hs(c.http, c.clock, c.cfg.homeserver, creds.accessToken), creds.userId)

  /** login-or-resume: a stored session's token is validated with a
   *  zero-timeout test sync; expired/foreign -> password login. Empty
   *  accessToken on failure, with `rejected` telling the two failure kinds
   *  apart (the server said no, vs. we could not ask). */
  def loginOrResume(c: MatrixClient): LoginOutcome =
    tallyLogin()
    var token = ""
    var uid = ""
    var rejected = false
    val st = c.cfg.stored
    if Sessions.isValid(st) && st.homeserver == c.cfg.homeserver then
      val probe = MatrixHttp.sync(Hs(c.http, c.clock, c.cfg.homeserver, st.accessToken), "", 0)
      if probe.status == 200 then
        token = st.accessToken
        uid = st.userId
    if token == "" then
      val resp = MatrixHttp.login(Hs(c.http, c.clock, c.cfg.homeserver, ""),
        c.cfg.username, c.cfg.password)
      if isAuthFail(resp.status) then rejected = true
      if resp.status == 200 then
        val lr = Matrix.parseLogin(MatrixHttp.parseOrNull(resp.body))
        token = lr.accessToken
        uid = lr.userId
    LoginOutcome(AuthCreds(token, uid), rejected)

  /** the server refusing the credentials themselves (as opposed to failing to
   *  answer at all). */
  def isAuthFail(status: Int): Boolean = status == 401 || status == 403

  def tallyLogin(): Unit =
    val n = loginsC.add(1)
    ()

  // ---- backoff -------------------------------------------------------------------

  /** one backoff sleep, in slices so a quit does not freeze the last frame for
   *  up to a minute and a retry poke is felt within a slice. Returns true when
   *  a POKE cut it short (the caller then resets its backoff), false when it
   *  ran out or the client was stopped. */
  def backoffSleep(c: MatrixClient, ms: Long): Boolean =
    var left = ms
    var poked = false
    var run = true
    while run do
      if left <= 0L || poked || isStopped(c) then run = false
      else
        c.clock.sleepMs(sleepSlice(left))
        left = left - sleepSlice(left)
        poked = retryPoked(c)
    poked

  /** backoff sleeps advance in <= `SLEEP_SLICE_MS` steps. */
  val SLEEP_SLICE_MS: Long = 200L

  def sleepSlice(left: Long): Long =
    if left < SLEEP_SLICE_MS then left else SLEEP_SLICE_MS

  /** take the retry poke if one is armed (sync-loop-only, like the stop probe). */
  def retryPoked(c: MatrixClient): Boolean =
    sgo.selectValue[Boolean, Boolean](c.retry)((b: Boolean) => true)(false)

  /** 1s, doubling, ceiling 60s. */
  def nextBackoff(ms: Long): Long =
    var out = ms * 2L
    if out > 60000L then out = 60000L
    out

  /** the long-poll loop: stop is checked FIRST each round and re-checked after
   *  the (blocking, <= syncTimeoutMs) sync call — the exit path is the ordinary
   *  while-condition, never a mid-loop abort. While deferred backfill is
   *  pending the sync call uses timeout 0 (an immediate poll), so the queue
   *  drains at `backfillPagesPerRound` per round instead of one bounded slice
   *  per long-poll expiry.
   *
   *  A 401/403 mid-session ENDS the session (the token died — an expired or
   *  server-side-invalidated one will never come back), dropping the loop out
   *  to `syncLoop`, which logs in again. Backing off against a dead token
   *  instead would leave the device permanently "reconnecting". */
  def syncRounds(c: MatrixClient, hs: Hs, selfUid: String): Unit =
    var retryMs = 1000L
    var run = true
    while run do
      if isStopped(c) then run = false
      else
        val resp = MatrixHttp.sync(hs, SyncEngine.nextBatch, roundTimeoutMs(c))
        if isStopped(c) then run = false
        else if isAuthFail(resp.status) then
          lastAuthC.set(AuthCreds("", ""))   // actions fail fast until the relogin lands
          run = false
        else if resp.status != 200 then
          c.events.send(EvConn(ConnError()))
          if backoffSleep(c, retryMs) then retryMs = 1000L
          else retryMs = nextBackoff(retryMs)
        else
          val j = MatrixHttp.parseOrNull(resp.body)
          if isNullJ(j) then c.events.send(EvConn(ConnError())) // parse fail: report error, keep looping
          else
            processRound(c, hs, selfUid, j)
            retryMs = 1000L

  def isNullJ(j: Json): Boolean = j match
    case _: JNull => true
    case _        => false

  /** one successful sync round: engine ingest -> invite auto-join -> backfill
   *  enqueue + bounded drain -> snapshot publish. Auto-join runs after
   *  process() so the NEXT sync carries the joined room. Backfill has ONE
   *  trigger, the standard Matrix one: a `limited` timeline, paged via
   *  `prev_batch` — the server sets `limited` whenever it withholds history
   *  and its `/messages` `from` takes the same `s<seq>` position tokens, so
   *  the gap is always recoverable. The paging itself is DEFERRED work: a
   *  limited room becomes a queue entry, and each round drains at most
   *  `backfillPagesPerRound` pages before publishing — so neither the
   *  snapshot publish nor the next sync call ever waits behind an unbounded
   *  serial page walk. */
  def processRound(c: MatrixClient, hs: Hs, selfUid: String, j: Json): Unit =
    SyncEngine.process(j)
    ()
    autoJoin(hs, WJson.objField(WJson.objField(j, "rooms"), "invite"))
    queueBackfills(WJson.objField(WJson.objField(j, "rooms"), "join"))
    drainBackfill(hs)
    publishSnapshot(c)

  /** trusted family environment — accept ALL invites. */
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

  // ---- deferred backfill -------------------------------------------------------

  /** cap on chained GET /messages pages per backfill trigger: 10 pages of 50
   *  = 500 events per `limited` room. A gap deeper than that stays
   *  unrecovered — deliberately: the oldest history of a very long absence is
   *  not worth unbounded serial paging, and no later trigger reopens it (a
   *  later `limited` sync starts from a NEWER `prev_batch`). */
  val maxBackfillPages: Int = 10

  /** deferred-backfill pages drained per sync round: 2 pages = 100 events of
   *  bounded work between engine ingest and snapshot publish. */
  val backfillPagesPerRound: Int = 2

  /** enqueue a backfill job for every `limited` room in the round's
   *  `rooms.join` map. */
  def queueBackfills(joinMap: Json): Unit =
    var cur = MatrixHttp.objFields(joinMap)
    var going = true
    while going do
      cur match
        case p :: t =>
          queueIfLimited(p)
          cur = t
        case Nil => going = false

  /** a limited room becomes (or REPLACES) that room's queue entry, restarting
   *  from the fresh `prev_batch`: a newer trigger supersedes any older walk in
   *  progress — it holds the newer position, and the engine's dedup absorbs
   *  the overlap. */
  def queueIfLimited(p: (String, Json)): Unit =
    val roomId: String = p._1
    if WJson.boolField(WJson.objField(p._2, "timeline"), "limited") then
      val from = SyncEngine.prevBatchOf(roomId)
      if from != "" then
        backfillQC.set(BackfillJob(roomId, from, 0) :: dropJob(backfillQC.get(), roomId, Nil))

  def dropJob(q: List[BackfillJob], roomId: String, acc: List[BackfillJob]): List[BackfillJob] = q match
    case h :: t => dropJobStep(h, t, roomId, acc)
    case Nil    => ListOps.reverse(acc)

  def dropJobStep(h: BackfillJob, t: List[BackfillJob], roomId: String, acc: List[BackfillJob]): List[BackfillJob] =
    if h.roomId == roomId then dropJob(t, roomId, acc) else dropJob(t, roomId, h :: acc)

  /** drain up to `backfillPagesPerRound` pages of deferred backfill. A page
   *  that leaves more to fetch re-queues its job at the TAIL, so several
   *  limited rooms page round-robin. */
  def drainBackfill(hs: Hs): Unit =
    var budget = backfillPagesPerRound
    var going = true
    while going do
      backfillQC.get() match
        case job :: t =>
          backfillQC.set(t)
          stepJob(hs, job)
          budget = budget - 1
          if budget == 0 then going = false
        case Nil => going = false

  /** fetch the job's page; a continuation within the per-trigger page cap
   *  goes back to the queue tail. */
  def stepJob(hs: Hs, job: BackfillJob): Unit =
    val next = backfillPage(hs, job.roomId, job.from)
    if next != "" && job.pages + 1 < maxBackfillPages then
      backfillQC.set(ListOps.reverse(
        BackfillJob(job.roomId, next, job.pages + 1) :: ListOps.reverse(backfillQC.get())))

  /** timeout for the next sync call: an immediate poll while deferred
   *  backfill is pending (so the queue keeps draining), the configured long
   *  poll otherwise. */
  def roundTimeoutMs(c: MatrixClient): Int =
    if backfillPending() then 0 else c.cfg.syncTimeoutMs

  def backfillPending(): Boolean = backfillQC.get() match
    case Nil => false
    case _   => true

  /** ONE /messages page: ingest the chunk, return the token to continue from
   *  ("" = done — the server's `end` is a real continuation position, and a
   *  missing/unmoved one or an empty chunk means the walk is over). */
  def backfillPage(hs: Hs, roomId: String, from: String): String =
    val resp = MatrixHttp.getMessages(hs, roomId, from, 50)
    if resp.status != 200 then ""
    else
      val j = MatrixHttp.parseOrNull(resp.body)
      val chunk = SyncEngine.arrItems(WJson.objField(j, "chunk"))
      ingestChunk(roomId, chunk)
      continueToken(chunk, from, WJson.strField(j, "end", ""))

  def continueToken(chunk: List[Json], from: String, end: String): String = chunk match
    case Nil => ""
    case _   => if end == "" || end == from then "" else end

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
   *  yet". */
  def publishSnapshot(c: MatrixClient): Unit =
    val snap = SyncEngine.buildSnapshot()
    sgo.selectOrDefault(c.snaps)((old: StateSnapshot) => ())(())
    c.snaps.send(snap)
    c.events.send(EvConn(Syncing()))
    c.events.send(EvSnapshot())

  // ---- the action loop -----------------------------------------------------------

  /** the action loop runs from process start and NEVER stands down: it takes
   *  the live credentials (`lastAuthC`) at the moment each action is dequeued,
   *  so it serves whatever session is current — and while there is none, it
   *  builds a token-less `Hs` whose requests the server answers 401, which is
   *  each action's ordinary failure path. Standing down instead is what used
   *  to let the action queue fill and freeze the UI behind it. */
  def actionLoop(c: MatrixClient): Unit =
    var run = true
    while run do
      val a = c.actions.recv()
      val creds = lastAuthC.get()
      run = execAction(c, Hs(c.http, c.clock, c.cfg.homeserver, creds.accessToken),
        creds.userId, a)

  /** returns false only for the poison pill — every real action keeps looping. */
  def execAction(c: MatrixClient, hs: Hs, selfUid: String, a: Action): Boolean = a match
    case _: ActQuit     => false
    case r: ActReceipt  => execReceipt(hs, r)
    case m: ActSendVoice => execSendVoice(c, hs, m)
    case d: ActPlay     => execPlay(c, hs, d)
    case n: ActSetName  => execSetName(hs, selfUid, n)
    case x: ActRedact   => execRedact(hs, x)
    case f: ActFavorite => execFavorite(hs, f)

  def execReceipt(hs: Hs, r: ActReceipt): Boolean =
    drop(MatrixHttp.sendReadReceipt(hs, r.roomId, r.eventId)) // best-effort, failure ignored
    true

  def execSendVoice(c: MatrixClient, hs: Hs, m: ActSendVoice): Boolean =
    val txn = txnCounterC.add(1)
    var roomId = m.roomId
    if roomId == "" then roomId = resolveDmRoom(hs, m.contactId)
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

  /** DM-room resolution: ONE call to the server's DM endpoint, which owns DM
   *  identity. There is nothing to reconcile here — the server answers with THE
   *  room for the pair, idempotently, having joined us to it. */
  def resolveDmRoom(hs: Hs, contactId: String): String =
    if contactId == "" then ""
    else
      val resp = MatrixHttp.dmRoom(hs, contactId)
      if resp.status == 200 then MatrixHttp.parseRoomId(resp.body) else ""

  /** the download-play action: download; on failure -> `EvPlaybackError`; on
   *  success -> route the Ogg bytes to the audio thread (drop-on-full
   *  `trySend`) when audio is wired, else surface `EvPlaybackError` (the
   *  headless-client behavior). */
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

  /** the favorite toggle: fire-and-forget, like the receipt. The resulting
   *  state comes back through `/sync` as room state (the server writes a
   *  `net.wata.favorite` event), so nothing here has to thread the answer into
   *  the snapshot — the next round carries it. */
  def execFavorite(hs: Hs, f: ActFavorite): Boolean =
    drop(MatrixHttp.setFavorite(hs, f.roomId, f.eventId))
    true

  /** discard a response in statement position (a bare non-call value statement
   *  is not legal Go; a call statement is). */
  def drop(r: HttpResponse): Unit = ()

  // ---- module state (single-client-per-process — see header) ------------------

  // `val`-held Atomic cells: the single-client-per-process protocol makes
  // reachability data-race-free by naming (each cell touched from exactly one
  // goroutine except where noted). `txnCounter` rides the native atomic word
  // (`add` returns the new value, one read-modify-write); `lastAuthC`/`snapC`
  // swap immutable case-class snapshots (boxed cells, one alloc per store).
  private val txnCounterC: sgo.Atomic[Int] = sgo.atomic(0)
  /** the deferred-backfill queue (sync-loop-only; reset per session). */
  private val backfillQC: sgo.Atomic[List[BackfillJob]] = sgo.atomic(Nil)
  /** the CURRENT session's credentials: written by the sync loop at each login
   *  (and cleared when a session's token dies), read by the action loop per
   *  action and by the app after the scope joins. */
  private val lastAuthC: sgo.Atomic[AuthCreds] = sgo.atomic(AuthCreds("", ""))
  /** login/resume attempts this session (a test's window on the retry loop). */
  private val loginsC: sgo.Atomic[Int] = sgo.atomic(0)
  /** has `stopClient` run? The teardown is reachable twice (disconnect + quit)
   *  and `close` on an already-closed channel panics. */
  private val closedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the credentials the last login/resume produced (the app persists them as
   *  a `Session`; a caller reads this AFTER the supervised scope has joined). */
  def lastAuth: AuthCreds = lastAuthC.get()

  // ---- non-blocking polls -------------------------------------------------------

  /** take one UI event if available. */
  def pollEvent(c: MatrixClient): Option[UiEvent] = c.events.tryReceive()

  /** TAKE the latest snapshot if one is published (the cell empties on
   *  read). Also refreshes `lastSnap`. */
  def pollSnap(c: MatrixClient): Option[StateSnapshot] =
    val o = c.snaps.tryReceive()
    o match
      case s: Some[StateSnapshot] => snapC.set(s.value)
      case None => ()
    o

  /** the most recent snapshot `pollSnap`/`waitForSnapshot` saw. */
  def lastSnap: StateSnapshot = snapC.get()
  private val snapC: sgo.Atomic[StateSnapshot] = sgo.atomic(emptySnapshot())

  def emptySnapshot(): StateSnapshot =
    StateSnapshot(Disconnected(), false, User("", ""), Nil,
      Nil, false, Family("", "", Nil))

  /** non-blocking stop probe (a CLOSED channel's receive is always ready).
   *  select-as-EXPRESSION: the taken arm's value IS the answer, no cell.
   *  Sync-loop-only. */
  def isStopped(c: MatrixClient): Boolean =
    sgo.selectValue[Boolean, Boolean](c.stop)((b: Boolean) => true)(false)

  // ---- wait helpers ---------------------------------------------------------------

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
    case _: ConnAuthRejected => 5

  def sameConn(a: ConnectionState, b: ConnectionState): Boolean = connTag(a) == connTag(b)

  /** poll snapshots until `pred` matches (the match stays in `lastSnap`) or the
   *  deadline passes; non-matching snapshots are simply discarded. */
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

