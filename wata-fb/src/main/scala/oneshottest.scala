import language.experimental.saferExceptions

/** the pending-one-shot oracle (plan 0046): a full action queue must not
 *  silently eat a delete or a favorite. Runs against a PARKED client — the
 *  record is built (`Runtime.make`) but neither loop is started, so the
 *  action queue fills and stays full until the test drains it by hand. The
 *  transcript is byte-diffed by tools/wataclient-tests.sh, check 7. */

/** transport that never answers — nothing here may reach a network. */
final class NullHttp() extends HttpDo:
  def send(req: HttpRequest): HttpResponse = HttpResponse(0, "")

/** frozen clock; the frame ticks pass their own dt. */
final class NullClock() extends Clock:
  def nowUnixMillis(): Long = 0L
  def sleepMs(ms: Long): Unit = ()

object OneshotTest:

  def run(): Unit =
    val cfg = ClientConfig("http://parked", "u", "p", 1000, Session("", "", "", "", ""))
    val c = Runtime.make(cfg, NullHttp(), NullClock())
    // no loops running: every trySend lands in the buffer until it is full.
    var filled = 0
    while c.actions.trySend(ActReceipt("!pad:hs", "pad")) do filled += 1
    println("queue filled with " + filled + " receipts")

    val ctx = parkedCtx(c)
    var st = WataLogic.enterConv(WataLogic.initial(), 0)

    // delete + favorite the selected message against the full queue, then a
    // second delete of the same message: it must coalesce, not stack.
    st = WataLogic.deleteSelected(st, ctx)
    st = WataLogic.favoriteSelected(st, ctx)
    st = WataLogic.deleteSelected(st, ctx)
    println("pending after delete+favorite+delete: " + WataLogic.lenActions(st.pendingOneshots))

    // a frame against the still-full queue moves nothing.
    st = WataLogic.update(st, 0.033, ctx)
    println("pending after full-queue frame: " + WataLogic.lenActions(st.pendingOneshots))

    // free one slot per frame: the head goes first, then the next.
    drainOne(c)
    st = WataLogic.update(st, 0.033, ctx)
    println("pending after one freed slot: " + WataLogic.lenActions(st.pendingOneshots))
    drainOne(c)
    st = WataLogic.update(st, 0.033, ctx)
    println("pending after two freed slots: " + WataLogic.lenActions(st.pendingOneshots))

    // drain the whole queue: the delete and the favorite each arrive exactly
    // once, in offer order, after the receipts that were ahead of them.
    println("one-shots in queue: " + arrivalOrder(c))

  /** a frame context over the parked client and a one-conversation snapshot
   *  (one message, "m1", which `enterConv` leaves selected at row 0). */
  def parkedCtx(c: MatrixClient): FrameCtx =
    val u = User("@bob:hs", "Bob")
    val msgs = VoiceMessage("m1", u, "mxc://hs/a", 1000L, 2000L, false, false, false) :: Nil
    val conv = Conversation("!r1:hs", DmConv(), true, Contact(u), msgs, 1, "")
    val snap = StateSnapshot(Disconnected(), false, User("", ""), Contact(u) :: Nil,
      conv :: Nil, false, Family("", "", Nil), true)
    val evts = sgo.makeChan[AudioEvt](16)
    FrameCtx(snap, Disconnected(), NetState(PipeUnknown(), NetDown(), false),
      c, c.audioCmds, evts, Nil, Nil, false)

  def drainOne(c: MatrixClient): Unit =
    val gone = c.actions.tryReceive()
    ()

  /** empty the queue; each m1 one-shot appends its letter (R = redact,
   *  F = favorite), so the string IS the count and the order. */
  def arrivalOrder(c: MatrixClient): String =
    var order = ""
    var going = true
    while going do
      c.actions.tryReceive() match
        case s: Some[Action] => s.value match
          case x: ActRedact => if x.eventId == "m1" then order = order + "R"
          case f: ActFavorite => if f.eventId == "m1" then order = order + "F"
          case _ => ()
        case None => going = false
    order
