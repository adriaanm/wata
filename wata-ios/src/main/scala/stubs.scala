import language.experimental.saferExceptions

/** The DEVICE-ONLY objects the shared wata-fb sources reference, as iOS
 *  stand-ins — wata-mac's stubs.scala carried over (honest duplication per
 *  plan 0044). The screen bodies and applet logic ride in as COPIED source
 *  files (applets/display/paint/netstatus/input/syscall — see wata-ios.md),
 *  and those files name a handful of device-layer objects this app has no
 *  hardware for: sysfs diagnostics, LEDs, enrolment, the shell's key
 *  predicates. Each stub answers exactly what the shared code documents as
 *  the OFF-DEVICE answer ("n/a", -1, false), so the bodies render the same
 *  screens the fb host builds render — which is what keeps the fb goldens
 *  the semantic oracle for this backend.
 *
 *  Only what the copied files actually reference is stubbed; a new
 *  reference appearing in a re-copied body fails this module's build loudly,
 *  which is the tripwire keeping the duplicate in step. */

/** wata-fb's `Shell` (shell.scala) is the applet multiplexer; the iOS client
 *  runs the wata applet alone, and the shared logic only needs the two key
 *  predicates. */
object Shell:
  def isPressed(ks: KeyState): Boolean = ks match
    case Pressed() => true
    case _         => false

  def isBackKey(k: Key): Boolean = k match
    case KBack() => true
    case _       => false

/** the enrolment screen's data — never shown here. A phone enrols the way a
 *  laptop does (out of band), not the way a handset does; `required()`
 *  answering false is what routes `WataLogic.bodyContacts` past the QR
 *  screen (wata-fb/enrol.scala is the real thing). */
case class EnrolSnap(hint: String)

object Enrol:
  def configured(): Boolean = false
  def required(): Boolean = false
  def provisioning(): Boolean = false
  def announceOnce(): Unit = ()
  def snap(hint: String): EnrolSnap = EnrolSnap(hint)
  /** unreachable while `required()` is false; loud rather than blank if it
   *  ever shows. */
  def body(e: EnrolSnap): View = VText(1, 6, "enrol: fb only", Color.red)

/** wata-fb's sysfs/modem diagnostics (diag.scala). Off-device every reading
 *  answers `UNAVAILABLE` and every action is a no-op that says so — the same
 *  contract the real Diag keeps on a dev host, which the settings body and
 *  `NetStatus.readPipe` are written against (`n/a` + `n/a` = PipeUnknown,
 *  the honest host answer). */
case class NetTestResult(line1: String, line2: String)

object Diag:
  val UNAVAILABLE = "n/a"
  def wlanIp(): String = UNAVAILABLE
  def cellData(): String = UNAVAILABLE
  def cellAddr(): String = ""
  def wifiState(): String = UNAVAILABLE
  def uptime(): String = UNAVAILABLE
  def memAvail(): String = UNAVAILABLE
  def startNetTest(): Unit = ()
  def takeNetTest(): Option[NetTestResult] = None
  def wifiStart(): String = "not on device"
  def wifiStop(): String = "not on device"
  def dataAuto(): String = "not on device"
  def dataForce(): String = "not on device"
  def dataOff(): String = "not on device"
  def powerOff(): Unit = ()
  def rebootBootloader(): Unit = ()
  def rebootEdl(): Unit = ()

/** wata-fb's LED/backlight sysfs layer (led.scala): no battery node (-1 is
 *  the documented "no battery" answer), no backlight to set. */
object Led:
  def readBatteryPercent(): scala.Int = -1
  def setBacklight(brightness: scala.Int): Unit = ()

/** wata-fb's preferences record. The STORE that persists it is real here —
 *  config.scala — so only the record itself lives in this file of
 *  device-shaped things; `FbConfig` is not a stub.
 *
 *  `FbOutbox` likewise: numbered slot files beside the config in the app's
 *  sandbox, the same shape the device uses, so a recording queued during an
 *  outage survives the app closing. */
case class FbPrefs(brightness: scala.Int, timeoutIdx: scala.Int)

/** the iOS `OutboxStore`: one file per queued voice message under the state
 *  directory. `writable` is probed once — a directory we cannot write is
 *  reported non-persistent, which degrades to a memory queue for the session
 *  and says so once, rather than pretending to persist. */
final class FbOutbox(val dir: String, val writable: Boolean) extends OutboxStore:
  def read(slot: scala.Int): String = FbConfig.readSlot(dir, slot)
  def write(slot: scala.Int, data: String): Boolean =
    var ok = false
    if writable then ok = FbConfig.writeSlot(dir, slot, data)
    ok
  def clear(slot: scala.Int): Unit = FbConfig.clearSlot(dir, slot)
  def persistent(): Boolean = writable

/** wata-fb's app-edge capability extras (caps.scala). The iOS client has no
 *  iroh transport yet (the mac's `WATA_IROH_CONFIG` seam is not carried),
 *  so the transport is never "configured but unbringable". */
object FbCaps:
  def transportUnavailable(): Boolean = false

/** wata-fb's startup bleep (chirp.scala, plan 0039): a handset answers "has
 *  it finished booting?" out loud because its screen shows nothing for ~40s;
 *  a phone app that appeared when tapped has already answered. The asset is
 *  device-shaped too (/opt/wata/chirp.ogg). */
object Chirp:
  def play(): Unit = ()
