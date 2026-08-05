import language.experimental.saferExceptions

/** The BIND SURFACE of the phone spike (plan 0023 M1, rewired on plan 0025's
 *  client handle): the whole client core behind a handful of calls that take
 *  and return strings, plus the handle value itself.
 *
 *  ASYNCHRONOUS, because the handle is. `start` returns as soon as the client
 *  has its own goroutine; the HOST then owns the loop — it pumps
 *  `events()` (a bounded channel of dirty flags), reads `connection()` and
 *  `snapshot()` when a flag says something moved, and calls `stop` when it is
 *  done. That is the shape a UIKit app needs and the shape `watamobile`'s Go
 *  shim implements: a goroutine draining `events()` into an `EventSink` the
 *  host implements. Nothing here parks a thread or sleeps to fake a lifetime.
 *
 *  The rendered report is line-oriented and deterministic apart from the ids
 *  the server mints, which is what makes it checkable from a shell. */
object Bind:

  /** start the client on its own goroutine — returns immediately. */
  def start(homeserver: String, user: String, pass: String): Handle =
    val cfg = ClientConfig(homeserver, user, pass, 1000, Session("", "", "", "", ""))
    ClientHandle.start(cfg, SpikeCaps.httpDo(), SpikeCaps.clock(), SpikeCaps.spawner())

  /** the dirty-topic channel the host's pump goroutine receives on. */
  def events(h: Handle): sgo.Chan[Event] = h.events()

  /** the next topic's NAME within the deadline, "" if none came. Reads the
   *  same channel `events` hands the host, which is also what keeps `events`
   *  in the emitted package: the app-mode link prunes to what `main` reaches,
   *  so a bind-surface function no Sgola code calls is not emitted at all. */
  def nextTopic(h: Handle, timeoutMs: Long): String =
    val ch = events(h)
    val deadline = SpikeCaps.clock().nowUnixMillis() + timeoutMs
    var out = ""
    var run = true
    while run do
      ch.tryReceive() match
        case e: Some[Event] =>
          out = ClientHandle.topicName(e.value)
          run = false
        case None =>
          if SpikeCaps.clock().nowUnixMillis() >= deadline then run = false
          else SpikeCaps.sleepMs(20L)
    out

  /** is the client connected/syncing right now? */
  def live(h: Handle): Boolean = isLive(h.connection())

  def isLive(s: ConnectionState): Boolean = s match
    case _: Connected => true
    case _: Syncing   => true
    case _            => false

  /** does the current snapshot know who we are? (the first round that carries
   *  the account is the one worth reporting). */
  def hasSelf(h: Handle): Boolean = h.snapshot().hasSelfUser

  /** the current snapshot, rendered. */
  def reportOf(h: Handle): String = report(h.snapshot())

  /** wind the client down and wait for its goroutine (the host calls this from
   *  wherever its lifecycle ends). */
  def stop(h: Handle): Unit =
    val gone = ClientHandle.stopAndJoin(h, 10000L)
    ()

  /** the same session, driven from Sgola instead of from the host — what
   *  `watabind <hs> <user> <pass>` runs, so a pure-Go run of identical code
   *  can be diffed against the Swift shell's output. */
  def probe(homeserver: String, user: String, pass: String, timeoutMs: Long): String =
    val h = start(homeserver, user, pass)
    var out = "error unreachable-or-rejected"
    if waitLive(h, timeoutMs) then
      if waitSelf(h, timeoutMs) then out = reportOf(h)
      else out = "error no-snapshot"
    stop(h)
    out

  /** pump events until the client reads live, or the deadline passes. The
   *  flags say "look again"; the state is the answer. */
  def waitLive(h: Handle, timeoutMs: Long): Boolean =
    val deadline = SpikeCaps.clock().nowUnixMillis() + timeoutMs
    var ok = false
    var run = true
    while run do
      if live(h) then
        ok = true
        run = false
      else if SpikeCaps.clock().nowUnixMillis() >= deadline then run = false
      else drop1(nextTopic(h, 200L))
    ok

  def waitSelf(h: Handle, timeoutMs: Long): Boolean =
    val deadline = SpikeCaps.clock().nowUnixMillis() + timeoutMs
    var ok = false
    var run = true
    while run do
      if hasSelf(h) then
        ok = true
        run = false
      else if SpikeCaps.clock().nowUnixMillis() >= deadline then run = false
      else drop1(nextTopic(h, 200L))
    ok

  def drop1(topic: String): Unit = ()

  /** a build-identity line, so a bound framework can be shown to be OURS
   *  without a server anywhere near it. */
  def hello(): String = "watabind sgola-emitted go, wataclient linked"

  def report(s: StateSnapshot): String =
    val b = new StringBuilder
    b.append("self ")
    b.append(s.selfUser.id)
    b.append(" ")
    b.append(Names.displayOr(s.selfUser.displayName, s.selfUser.id))
    b.append('\n')
    b.append("contacts ")
    b.append(len(s.contacts, 0))
    b.append('\n')
    var cur = s.contacts
    var going = true
    while going do
      cur match
        case h :: t =>
          b.append("contact ")
          b.append(h.user.id)
          b.append('\n')
          cur = t
        case Nil => going = false
    b.append("conversations ")
    b.append(convLen(s.conversations, 0))
    b.append('\n')
    b.append(convLines(s.conversations))
    b.append("family ")
    b.append(if s.hasFamily then s.family.name else "-")
    b.append('\n')
    b.toString

  def len(cs: List[Contact], acc: Int): Int = cs match
    case _ :: t => len(t, acc + 1)
    case Nil    => acc

  def convLen(cs: List[Conversation], acc: Int): Int = cs match
    case _ :: t => convLen(t, acc + 1)
    case Nil    => acc

  def msgLen(ms: List[VoiceMessage], acc: Int): Int = ms match
    case _ :: t => msgLen(t, acc + 1)
    case Nil    => acc

  def convLines(cs: List[Conversation]): String =
    val b = new StringBuilder
    var cur = cs
    var going = true
    while going do
      cur match
        case h :: t =>
          b.append(convLine(h))
          cur = t
        case Nil => going = false
    b.toString

  def convLine(c: Conversation): String =
    val b = new StringBuilder
    b.append("conv ")
    b.append(kindOf(c.convType))
    b.append(" with=")
    b.append(if c.hasContact then c.contact.user.id else "-")
    b.append(" messages=")
    b.append(msgLen(c.messages, 0))
    b.append(" unplayed=")
    b.append(c.unplayedCount)
    b.append('\n')
    b.toString

  def kindOf(t: ConversationType): String = t match
    case _: FamilyConv => "family"
    case _: DmConv     => "dm"
