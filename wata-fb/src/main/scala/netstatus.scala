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

  /** a fresh session: re-read the interfaces on the next frame and start the
   *  reconnecting animation from its first phase (the host drivers run several
   *  sequential sessions in one process). */
  def reset(): Unit =
    pipeC.set(P_UNKNOWN)
    leftC.set(0)
    phaseC.set(0)
    lastHealthC.set(-1)
    forcedC.set(-1)

  /** the uitest-only pipe override (-1 = read the interfaces). */
  def forcePipe(tag: scala.Int): Unit = forcedC.set(tag)

  /** ONE frame's connectivity. Called exactly once per frame by `Ui.frameStep`
   *  — it advances the refresh countdown and the blink phase. */
  def poll(c: ConnectionState): NetState =
    val h = healthTag(c)
    NetState(pipeOf(pipeTag()), healthOf(h), blinkPhase(h))

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
      leftC.set(REFRESH_FRAMES)
    out

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

  /** green while it is working, yellow while it is coming back, red when it
   *  is down or there is no pipe at all. */
  def color(n: NetState): scala.Int =
    if isNoPipe(n.pipe) then Color.red
    else n.health match
      case _: NetLive         => Color.green
      case _: NetReconnecting => Color.yellow
      case _: NetDown         => Color.red
