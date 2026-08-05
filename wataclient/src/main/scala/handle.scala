/** THE CLIENT HANDLE (plan 0025): wataclient under a shell that owns the loop.
 *
 *  `Runtime.start` forks the sync and action loops into the CALLER's
 *  `sgo.supervised` scope, and that scope's exit IS the join — so running the
 *  client is one call that does not return while the session lives. That fits
 *  an app whose frame loop and client share a process lifetime (`wata-fb`); it
 *  does not fit a toolkit that owns the main loop and calls in from callbacks
 *  (a phone shell). The handle is the other view of the same runtime: the
 *  supervised scope moves onto a goroutine the handle owns, and the outside
 *  sees a non-blocking `start`, an action sink, a snapshot reader, a health
 *  reader, an event pump, and an idempotent `stop`.
 *
 *  NOTHING about the runtime's internals changes. Every method here is a
 *  packaging of a surface `Runtime` already has, so the two shapes coexist:
 *  `wata-fb` keeps polling the cells inside its own scope, a shell drives the
 *  handle, and both are the same loops over the same state.
 *
 *  THE EVENT PUMP. A frame loop polls; a shell must not poll at 30 Hz to
 *  notice a message. `events()` is a BOUNDED channel of dirty flags: an
 *  `Event` names a TOPIC (`EvSnapshotDirty`, `EvConnDirty`, `EvOutboxDirty`)
 *  and carries no payload, and the runtime publishes with `trySend` only — it
 *  must never block on a slow or absent consumer. A dropped event is
 *  therefore harmless: the consumer reacts by READING the current
 *  snapshot/cell, so what matters is that one later event of that topic
 *  arrives, which a bounded channel with a live consumer always gives. A
 *  consumer that sleeps through ten rounds converges on the next read.
 *
 *  A shell's pump is an ordinary blocking `events().recv()` loop; it wakes for
 *  the last time on `EvStopped`, which `runScope` delivers after the loops are
 *  gone (making room in the queue if it has to — see `signalStopped`). Tests
 *  and other pollers use `waitEvent`, which is the same thing with a deadline
 *  served by the `Clock` capability.
 *
 *  SINGLE CLIENT PER PROCESS, still: the runtime's module cells are the
 *  engine's, so one handle at a time. `stop()` + `join` is the edge a second
 *  `start` may cross. */

// ---- the dirty-topic vocabulary ------------------------------------------------

/** what changed — a flag, never data. The consumer reads the current state. */
sealed trait Event
/** a sync round published a new snapshot. */
case class EvSnapshotDirty() extends Event
/** the connection state moved (read it with `connection()`). */
case class EvConnDirty() extends Event
/** the outbox changed (unsent/undelivered markers). */
case class EvOutboxDirty() extends Event
/** the client's goroutine is gone: the last event any pump receives. */
case class EvStopped() extends Event

// ---- the handle -------------------------------------------------------------------

/** the outside view of a running client: the client record its goroutine
 *  drives, plus the `done` channel that goroutine CLOSES on its way out (the
 *  join edge a caller needs before starting another client). */
case class Handle(client: MatrixClient, done: sgo.Chan[Boolean]) extends Shareable:

  /** enqueue an action — non-blocking (`trySend`), as `Runtime.sendAction`.
   *  Returns whether the queue took it; a refused send/play has already
   *  surfaced its ordinary failure event. */
  def sendAction(a: Action): Boolean = ClientHandle.sendAction(client, a)

  /** the client's current view of the world. Takes a freshly published
   *  snapshot if one is waiting, else repeats the last one — so it is always
   *  answerable, and a consumer that missed an event still converges. */
  def snapshot(): StateSnapshot = ClientHandle.snapshot(client)

  /** the connection state the sync loop last published. */
  def connection(): ConnectionState = Runtime.connection()

  /** the bounded dirty-topic channel (see the header). Receive it blocking
   *  from a pump goroutine, or `waitEvent`/`pollEvent` with a deadline. */
  def events(): sgo.Chan[Event] = client.topics

  /** take one topic if the queue has one. */
  def pollEvent(): Option[Event] = ClientHandle.pollEvent(client)

  /** the next topic, or `None` when `timeoutMs` passes with none. */
  def waitEvent(timeoutMs: Long): Option[Event] = ClientHandle.waitEvent(client, timeoutMs)

  /** wind the loops down. IDEMPOTENT (`Runtime.stopClient`'s `closed` cell),
   *  and non-blocking: the goroutine ends when both loops have left their
   *  current step. `join` is the wait. */
  def stop(): Unit = Runtime.stopClient(client)

  /** wait for the client's goroutine to exit, up to `timeoutMs`. True = it is
   *  gone, so its module cells are free for the next `start`. */
  def join(timeoutMs: Long): Boolean = ClientHandle.join(this, timeoutMs)

  /** has the goroutine exited? (a closed channel is always ready). The
   *  lambda sits inline in the class method as the standing proof of the
   *  selectValue-adapter half of the class-method lambda fix. */
  def stopped(): Boolean =
    sgo.selectValue[Boolean, Boolean](done)((b: Boolean) => true)(false)

object ClientHandle:

  /** START the client on its own goroutine — never blocks. Headless (no audio
   *  thread), queueing failed sends in memory: the shape a shell that has not
   *  wired audio yet wants. The goroutine is `sgo.spawn` — the portable
   *  spelling of a detached spawn, so the core owns its own scope and no
   *  app-side capability is involved. */
  def start(cfg: ClientConfig, http: HttpDo, clock: Clock): Handle =
    startClient(Runtime.make(cfg, http, clock))

  /** the same, over a client the caller built itself (`makeWithAudio`,
   *  `makeStored`, …) — the runtime's four constructors stay the way an app
   *  chooses audio and outbox storage. */
  def startClient(c: MatrixClient): Handle =
    // bound to a local first: `makeChan`'s element-type recording is the
    // local-val path (see `Runtime.mk`).
    val done = sgo.makeChan[Boolean]()
    val h = Handle(c, done)
    sgo.spawn(() => ClientHandle.runScope(h))
    h

  /** WHAT THE GOROUTINE RUNS. The supervised scope is unchanged — it is just
   *  no longer the caller's: `Runtime.start` forks the two loops into it and
   *  the scope exit joins them, which happens once `stop()` has signalled
   *  both. Then the pump gets its last event and `done` closes. */
  def runScope(h: Handle): Unit =
    sgo.supervised {
      Runtime.start(h.client)
    }
    signalStopped(h, 0)
    h.done.close()

  /** deliver `EvStopped` even to a pump that has fallen behind: on a full
   *  queue, drop the oldest topic and retry. Bounded by the queue's capacity,
   *  and the dropped topics are dirty flags a stopped client will never
   *  contradict. */
  def signalStopped(h: Handle, tries: Int): Unit =
    if !h.client.topics.trySend(EvStopped()) && tries < Runtime.TOPIC_QUEUE then
      dropTopic(h)
      signalStopped(h, tries + 1)

  def dropTopic(h: Handle): Unit =
    val gone = h.client.topics.tryReceive()
    ()

  /** enqueue without blocking; a refused action takes its ordinary drop path
   *  (`Runtime.dropped`: a send/play surfaces its failure event, the
   *  best-effort actions drop silently). */
  def sendAction(c: MatrixClient, a: Action): Boolean =
    val ok = c.actions.trySend(a)
    if !ok then Runtime.dropped(c, a)
    ok

  /** take one topic if the queue has one. (The object-side bodies here are
   *  house style — class methods may carry lambdas again since the
   *  class-method lambda fix; `Handle.stopped` keeps one inline as proof.) */
  def pollEvent(c: MatrixClient): Option[Event] = c.topics.tryReceive()

  /** the current snapshot: take a newly published one if the cell holds it
   *  (that also refreshes `Runtime.lastSnap`), else the last one seen. */
  def snapshot(c: MatrixClient): StateSnapshot =
    val fresh = Runtime.pollSnap(c)
    Runtime.lastSnap

  /** the next topic within `timeoutMs`, polled on the clock capability (a
   *  blocking `events().recv()` is what a shell's pump goroutine does
   *  instead — this shape exists for callers that need a deadline). */
  def waitEvent(c: MatrixClient, timeoutMs: Long): Option[Event] =
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var out: Option[Event] = None
    var run = true
    while run do
      c.topics.tryReceive() match
        case s: Some[Event] =>
          out = s
          run = false
        case None =>
          if c.clock.nowUnixMillis() >= deadline then run = false
          else c.clock.sleepMs(POLL_MS)
    out

  /** the event-poll slice: short enough that a deadline is honoured closely,
   *  long enough that a waiting consumer is not a spin loop. */
  val POLL_MS: Long = 20L

  /** wait for the goroutine to exit. */
  def join(h: Handle, timeoutMs: Long): Boolean =
    val deadline = h.client.clock.nowUnixMillis() + timeoutMs
    var out = false
    var run = true
    while run do
      if h.stopped() then
        out = true
        run = false
      else if h.client.clock.nowUnixMillis() >= deadline then run = false
      else h.client.clock.sleepMs(POLL_MS)
    out

  /** stop and wait, the ordinary teardown of a shell that is about to build
   *  another client (the module cells are the engine's — one at a time). */
  def stopAndJoin(h: Handle, timeoutMs: Long): Boolean =
    h.stop()
    join(h, timeoutMs)

  /** the topic's name, for a shell whose boundary is stringly (gobind carries
   *  strings, not sealed traits). */
  def topicName(e: Event): String = e match
    case _: EvSnapshotDirty => "snapshot"
    case _: EvConnDirty     => "conn"
    case _: EvOutboxDirty   => "outbox"
    case _: EvStopped       => "stopped"
