import language.experimental.saferExceptions

/** The CONNECTIVITY STATUS element's computed state — one record per frame,
 *  from which BOTH the wata applet's header indicator and the 1px status line
 *  are drawn, so the two can never make disagreeing claims about the
 *  connection (plan 0013, milestone 4).
 *
 *  TWO INPUTS. The *pipe* is which interface carries traffic, read from the
 *  same diagnostics sources the settings applet's info rows use
 *  (`Diag.wlanIp` for wlan0, `Diag.cellData` for ppp0) on the same ~5s
 *  cadence — an interface lookup per frame would be needless. The *health* is
 *  `wataclient`'s `ConnectionState`, the only honest source: an interface
 *  holding an IP whose sync is erroring is "reconnecting", not "on wifi".
 *
 *  OFF-DEVICE the diag sources answer `n/a` (there is no wlan0/ppp0 to ask),
 *  which is its own pipe — `PipeUnknown`, drawn as a plain `NET` label with
 *  the same health states. Host builds, the sim and the uitest goldens are
 *  therefore deterministic without faking interfaces. */

/** which interface is carrying traffic. `PipeUnknown` is the honest host
 *  answer (no such interfaces exist), `PipeNone` the honest device answer
 *  (they exist and none has an address). */
sealed trait NetPipe derives CanEqual
case class PipeWifi() extends NetPipe
case class PipeCell() extends NetPipe
case class PipeNone() extends NetPipe
case class PipeUnknown() extends NetPipe

/** what the client's sync loop says about the link it has. */
sealed trait NetHealth derives CanEqual
case class NetLive() extends NetHealth          // Connected / Syncing
case class NetReconnecting() extends NetHealth  // Connecting / ConnError
case class NetDown() extends NetHealth          // Disconnected

/** the frame's computed connectivity: pipe, health, and the reconnecting
 *  animation's current phase (`blink` = the `..` are showing this frame). */
case class NetState(pipe: NetPipe, health: NetHealth, blink: Boolean)

object NetStatus:
  /** frames between interface re-reads (~5s at 30fps — the settings
   *  diagnostics cadence, and system-menu's before it). */
  val REFRESH_FRAMES = 150

  /** frames between re-reads while there is NO interface (~1s). A device
   *  waiting for its network re-asks five times as often, because that read is
   *  what arms the retry poke (`notePipe`): at the 5s cadence an interface
   *  that appeared could sit unnoticed while the client's backoff ran on. The
   *  cost is one extra pair of reads a second, and only in the state where
   *  nothing else is happening. */
  val REFRESH_FRAMES_NO_PIPE = 30

  /** frames per phase of the reconnecting `..` (~0.5s at 30fps). The phase
   *  counter is reset whenever the health CHANGES, so the animation starts at
   *  a known phase on the frame the state turns bad — which is what makes a
   *  scripted reconnecting frame reproducible. */
  val BLINK_FRAMES = 15

  // pipe tags for the atomic cells (the cells hold primitives; the sum type
  // is rebuilt at the edge — same tag discipline as `Runtime.connTag`).
  val P_WIFI = 0
  val P_CELL = 1
  val P_NONE = 2
  val P_UNKNOWN = 3

  private val pipeC: sgo.Atomic[scala.Int] = sgo.atomic(P_UNKNOWN)
  private val leftC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val phaseC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val lastHealthC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  // the scripted driver's pipe override: -1 = read the interfaces (every
  // real run), else a pipe tag. The uitest goldens pin the wifi/cell/OFF
  // renderings this way rather than by faking interfaces underneath `Diag`,
  // which would make the goldens depend on the host's network.
  private val forcedC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  // has the health been `NetLive` at any point THIS SESSION? Latched by `poll`
  // and never cleared except by `reset`: it is what separates a device that has
  // not connected yet (the wata applet's calm boot presentation) from one that
  // connected and dropped (the ordinary reconnect presentation). Health alone
  // cannot tell those apart — both are `NetDown`/`NetReconnecting`.
  private val everLiveC: sgo.Atomic[Boolean] = sgo.atomic(false)
  // the pipe tag the previous frame saw (-1 before the first poll), and the
  // one-shot the ARRIVAL edge arms — see `notePipe`.
  private val lastPipeC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  private val arrivedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  // has the system clock been plausible at any point this session? Latched:
  // a clock only ever gets SET (see `clockOk`).
  private val clockOkC: sgo.Atomic[Boolean] = sgo.atomic(false)
  // `logTransition`'s last printed key, and the ms stamp of its first call
  // (the log's zero — process start, near enough).
  private val lastLogC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  private val logZeroC: sgo.Atomic[Long] = sgo.atomic(0L)

  /** a fresh session: re-read the interfaces on the next frame and start the
   *  reconnecting animation from its first phase (the host drivers run several
   *  sequential sessions in one process). */
  def reset(): Unit =
    pipeC.set(P_UNKNOWN)
    leftC.set(0)
    phaseC.set(0)
    lastHealthC.set(-1)
    forcedC.set(-1)
    everLiveC.set(false)
    lastPipeC.set(-1)
    arrivedC.set(false)
    lastLogC.set(-1)

  /** the uitest-only pipe override (-1 = read the interfaces). */
  def forcePipe(tag: scala.Int): Unit = forcedC.set(tag)

  /** has this session ever had a live link? False from session start until the
   *  first frame whose health is `NetLive`, true for the rest of the session. */
  def everLive(): Boolean = everLiveC.get()

  /** ONE frame's connectivity. Called exactly once per frame by `Ui.frameStep`
   *  — it advances the refresh countdown and the blink phase, and latches
   *  `everLive` the first frame the link is up. */
  def poll(c: ConnectionState): NetState =
    val h = healthTag(c)
    if h == 0 then everLiveC.set(true)
    val p = pipeTag()
    notePipe(p)
    NetState(pipeOf(p), healthOf(h), blinkPhase(h))

  // ---- the pipe-arrival edge -----------------------------------------------

  /** the frame on which the device goes from NO interface with an address to
   *  one arms a one-shot the frame loop takes (`takePipeArrival`) to poke the
   *  sync loop's backoff. Without it a client whose backoff had climbed to its
   *  60s ceiling while there was no network sits out that ceiling with wifi
   *  already up — a full minute of "can't reach server" over a working link.
   *
   *  Only a real transition counts: the first poll of a session (previous tag
   *  -1) arms nothing, since a session that starts with an interface has its
   *  backoff at 1s anyway. */
  def notePipe(tag: scala.Int): Unit =
    val prev = lastPipeC.get()
    if prev != tag then
      lastPipeC.set(tag)
      if prev >= 0 && !hasInterface(pipeOf(prev)) && hasInterface(pipeOf(tag)) then
        arrivedC.set(true)

  /** take the pipe-arrival one-shot (true at most once per arrival). */
  def takePipeArrival(): Boolean =
    val out = arrivedC.get()
    if out then arrivedC.set(false)
    out

  // ---- the clock -----------------------------------------------------------

  /** 2025-01-01Z in epoch ms: the floor below which the system clock is not a
   *  clock. Far above any unset one, far below any real one. */
  val CLOCK_FLOOR_MS: Long = 1735689600000L

  /** does this machine have a plausible wall clock? The handset has no
   *  battery-backed RTC, so it boots at 1970 and stays there until NTP lands —
   *  and at 1970 every TLS handshake fails certificate validation, which takes
   *  down iroh's relay and address-discovery paths outright. A dial that fails
   *  in that window says nothing about the server, so the boot screen stays
   *  calm (`Applets.bootMsg`) and the log says which state it is in.
   *
   *  LATCHED, and deliberately not cleared by `reset`: a clock only ever gets
   *  set, and it is a property of the machine, not of a client session. */
  def clockOk(): Boolean =
    var out = clockOkC.get()
    if !out && go.time.nowUnixMilli() >= CLOCK_FLOOR_MS then
      clockOkC.set(true)
      out = true
    out

  // ---- the transition log --------------------------------------------------

  /** print ONE line per change of (pipe, connection, clock) — the four or five
   *  lines that make a boot legible afterwards, against a transport whose own
   *  dial log prints once per distinct reason and can therefore look silent
   *  for an hour. Called once a frame; a frame that changed nothing prints
   *  nothing. */
  def logTransition(n: NetState, c: ConnectionState, clock: Boolean): Unit =
    var flag = 0
    if clock then flag = 1
    val key = (pipeTagOf(n.pipe) * 100) + (connTag(c) * 10) + flag
    if lastLogC.get() != key then
      val hadClock = (lastLogC.get() % 10) == 1
      lastLogC.set(key)
      val now = go.time.nowUnixMilli()
      // the stamps are wall-clock deltas, and on this device the wall clock
      // STEPS — from 1970 to now, the moment NTP lands. Re-zero on that step,
      // so the line reporting it reads +0s and the ones after it count from
      // there rather than from 1970 (a 56-year delta on every later line).
      if logZeroC.get() == 0L || (clock && !hadClock) then logZeroC.set(now)
      val secs = (now - logZeroC.get()) / 1000L
      println("net: +" + secs + "s pipe=" + pipeName(n.pipe) + " conn=" + connName(c) +
        " clock=" + clockName(clock))

  def pipeTagOf(p: NetPipe): scala.Int = p match
    case _: PipeWifi => P_WIFI
    case _: PipeCell => P_CELL
    case _: PipeNone => P_NONE
    case _           => P_UNKNOWN

  def pipeName(p: NetPipe): String = p match
    case _: PipeWifi => "wifi"
    case _: PipeCell => "cell"
    case _: PipeNone => "none"
    case _           => "unknown"

  def connTag(c: ConnectionState): scala.Int = c match
    case _: Connected        => 0
    case _: Syncing          => 1
    case _: Connecting       => 2
    case _: ConnError        => 3
    case _: Disconnected     => 4
    case _: ConnAuthRejected => 5

  def connName(c: ConnectionState): String = c match
    case _: Connected        => "connected"
    case _: Syncing          => "syncing"
    case _: Connecting       => "connecting"
    case _: ConnError        => "error"
    case _: Disconnected     => "disconnected"
    case _: ConnAuthRejected => "rejected"

  def clockName(ok: Boolean): String = if ok then "set" else "UNSET"

  // ---- what the client actually HAS ----------------------------------------
  // `net:` says the transport is healthy; it does not say a single message
  // arrived. A report of "messages do not appear live" is unanswerable without
  // that second fact — a session showing `conn=syncing` for an hour while its
  // message count never moves is a sync bug, and one whose count tracks the
  // server is a rendering bug, and from the outside the two look identical.
  //
  // So: one line whenever the totals CHANGE, plus a heartbeat every 60s so a
  // count that is standing still is visible as such rather than as silence.
  private val lastCountsC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  private val lastBeatC: sgo.Atomic[Long] = sgo.atomic(0L)
  val BEAT_MS: Long = 60000L

  def logSnapshot(s: StateSnapshot, c: ConnectionState): Unit =
    val convs = listLen(s.conversations)
    val msgs = totalMessages(s.conversations)
    val unplayed = totalUnplayed(s.conversations)
    val key = (convs * 1000000) + (msgs * 1000) + unplayed
    val now = go.time.nowUnixMilli()
    val beat = lastBeatC.get()
    val due = beat == 0L || (now - beat) >= BEAT_MS
    if lastCountsC.get() != key || due then
      lastCountsC.set(key)
      lastBeatC.set(now)
      if logZeroC.get() == 0L then logZeroC.set(now)
      val secs = (now - logZeroC.get()) / 1000L
      println("have: +" + secs + "s conv=" + intStr(convs) + " msgs=" + intStr(msgs) +
        " unplayed=" + intStr(unplayed) + " conn=" + connName(c))

  def intStr(n: scala.Int): String = "" + n

  def listLen(xs: List[Conversation]): scala.Int = xs match
    case _: Nil.type => 0
    case c: ::[Conversation] => 1 + listLen(c.tail)

  def totalMessages(xs: List[Conversation]): scala.Int = xs match
    case _: Nil.type => 0
    case c: ::[Conversation] => msgLen(c.head.messages) + totalMessages(c.tail)

  def msgLen(xs: List[VoiceMessage]): scala.Int = xs match
    case _: Nil.type => 0
    case c: ::[VoiceMessage] => 1 + msgLen(c.tail)

  def totalUnplayed(xs: List[Conversation]): scala.Int = xs match
    case _: Nil.type => 0
    case c: ::[Conversation] => c.head.unplayedCount + totalUnplayed(c.tail)

  // ---- the pipe (cached; re-read every REFRESH_FRAMES frames) ---------------
  def pipeTag(): scala.Int =
    val forced = forcedC.get()
    if forced >= 0 then forced else cachedPipe()

  def cachedPipe(): scala.Int =
    val left = leftC.get()
    var out = pipeC.get()
    if left > 0 then leftC.set(left - 1)
    else
      out = readPipe()
      pipeC.set(out)
      leftC.set(refreshFrames(out))
    out

  /** how long to wait before the next read: the ordinary cadence once an
   *  interface is carrying traffic, the fast one while none is. */
  def refreshFrames(tag: scala.Int): scala.Int =
    if hasInterface(pipeOf(tag)) then REFRESH_FRAMES else REFRESH_FRAMES_NO_PIPE

  /** wifi wins when wlan0 has an address, cellular when ppp0 is up; both
   *  sources answering `n/a` is the host (no interfaces to ask), anything
   *  else is a device with no usable interface. */
  def readPipe(): scala.Int =
    val wifi = Diag.wlanIp()
    val cell = Diag.cellData()
    var out = P_NONE
    if wifi == Diag.UNAVAILABLE && cell == Diag.UNAVAILABLE then out = P_UNKNOWN
    else if hasAddr(wifi) then out = P_WIFI
    else if cell.startsWith("up ") then out = P_CELL
    out

  /** `Diag.wlanIp` answers a dotted quad, or "no addr" / "n/a". */
  def hasAddr(s: String): Boolean =
    s != Diag.UNAVAILABLE && s != "no addr" && s != ""

  def pipeOf(tag: scala.Int): NetPipe =
    if tag == P_WIFI then PipeWifi()
    else if tag == P_CELL then PipeCell()
    else if tag == P_NONE then PipeNone()
    else PipeUnknown()

  // ---- health + the reconnecting animation ---------------------------------
  def healthTag(c: ConnectionState): scala.Int = c match
    case _: Connected    => 0
    case _: Syncing      => 0
    case _: Connecting   => 1
    case _: ConnError    => 1
    case _: Disconnected => 2
    // a rejected login is not "coming back": the credentials are the problem,
    // so it reads as down (red, no `..`) rather than as a reconnect.
    case _: ConnAuthRejected => 2

  def healthOf(tag: scala.Int): NetHealth =
    if tag == 0 then NetLive() else if tag == 1 then NetReconnecting() else NetDown()

  /** the `..` phase: on for the first BLINK_FRAMES frames of a health state,
   *  off for the next, alternating. A health change restarts the count. */
  def blinkPhase(h: scala.Int): Boolean =
    if lastHealthC.get() != h then
      lastHealthC.set(h)
      phaseC.set(0)
    val n = phaseC.get()
    phaseC.set(n + 1)
    (n / BLINK_FRAMES) % 2 == 0

  // ---- what the header draws ------------------------------------------------
  /** the glyph a pipe draws, or -1 when it draws a text label instead. */
  def glyph(p: NetPipe): scala.Int = p match
    case _: PipeWifi => Font.ICON_WIFI3
    case _: PipeCell => Font.ICON_CELL
    case _           => -1

  /** the text a pipe draws when it has no glyph: `OFF` when the device has no
   *  interface with an address, `NET` off-device where there is nothing to
   *  ask. */
  def label(p: NetPipe): String = p match
    case _: PipeNone => "OFF"
    case _           => "NET"

  /** `..` shows only while reconnecting, and only on the on-phase. */
  def showsDots(n: NetState): Boolean = isReconnecting(n.health) && n.blink

  def isReconnecting(h: NetHealth): Boolean = h match
    case _: NetReconnecting => true
    case _                  => false

  def isNoPipe(p: NetPipe): Boolean = p match
    case _: PipeNone => true
    case _           => false

  /** is an interface actually carrying traffic? `PipeUnknown` counts as no
   *  interface: it is the answer of a host (and of a device early in boot)
   *  where there is nothing to ask yet. */
  def hasInterface(p: NetPipe): Boolean = p match
    case _: PipeWifi => true
    case _: PipeCell => true
    case _           => false

  def isDown(h: NetHealth): Boolean = h match
    case _: NetDown => true
    case _          => false

  /** is the link working? The rolodex shows its connectivity element only when
   *  this is false: a resting card is the contact and nothing else (plan 0070),
   *  and an indicator that is always green is not information. */
  def isHealthy(h: NetHealth): Boolean = h match
    case _: NetLive => true
    case _          => false

  /** green while it is working, yellow while it is coming back, red when it
   *  is down or there is no pipe at all. */
  def color(n: NetState): scala.Int =
    if isNoPipe(n.pipe) then Color.red
    else n.health match
      case _: NetLive         => Color.green
      case _: NetReconnecting => Color.yellow
      case _: NetDown         => Color.red
