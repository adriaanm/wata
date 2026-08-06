import language.experimental.saferExceptions

/** The DEVICE-ONLY objects the shared wata-fb sources reference, as mac
 *  stand-ins. The screen bodies and applet logic ride in as SYMLINKED source
 *  files (applets/display/paint/netstatus/input/syscall — see wata-mac.md),
 *  and those files name a handful of device-layer objects this app has no
 *  hardware for: sysfs diagnostics, LEDs, the config store, enrolment, the
 *  shell's key predicates. Each stub answers exactly what the shared code
 *  documents as the OFF-DEVICE answer ("n/a", -1, false), so the bodies
 *  render the same screens the fb host builds render — which is what keeps
 *  the fb goldens the semantic oracle for this backend.
 *
 *  Only what the symlinked files actually reference is stubbed; a new
 *  reference appearing upstream fails this module's build loudly, which is
 *  the tripwire keeping the two in step. */

/** wata-fb's `Shell` (shell.scala) is the applet multiplexer; the mac runs
 *  the wata applet alone, and the shared logic only needs the two key
 *  predicates. */
object Shell:
  def isPressed(ks: KeyState): Boolean = ks match
    case Pressed() => true
    case _         => false

  def isBackKey(k: Key): Boolean = k match
    case KBack() => true
    case _       => false

/** the enrolment screen's data — never shown here. The mac dials over iroh
 *  (plan 0034), but it enrols the way a laptop does rather than the way a
 *  handset does: its node id is allowlisted out of band, so there is no QR
 *  screen to show (wata-fb/enrol.scala is the real thing; plan 0027 owns a
 *  mac-shaped provisioning story). `required()` answering false is what
 *  routes `WataLogic.bodyContacts` past the QR screen. */
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
  def dataStart(): String = "not on device"
  def dataStop(): String = "not on device"
  def powerOff(): Unit = ()
  def rebootBootloader(): Unit = ()
  def rebootEdl(): Unit = ()

/** wata-fb's LED/backlight sysfs layer (led.scala): no battery node (-1 is
 *  the documented "no battery" answer), no backlight to set. */
object Led:
  def readBatteryPercent(): scala.Int = -1
  def setBacklight(brightness: scala.Int): Unit = ()

/** wata-fb's config store (config.scala): the mac app logs in from the
 *  environment each run (like wata-tui) and persists nothing. */
case class FbPrefs(brightness: scala.Int, timeoutIdx: scala.Int)

object FbConfig:
  def savePrefs(p: FbPrefs): Unit = ()

/** wata-fb's app-edge capability extras (caps.scala). Not a stub any more
 *  (plan 0034): the mac dials over iroh when `WATA_IROH_CONFIG` is set, so
 *  the same latch wata-fb keeps is real here and the boot screen names an
 *  unbringable transport outright instead of blaming the network. It stays in
 *  this file because the shared sources reach for `FbCaps` by that name;
 *  `MacCaps` owns the cell, beside the client construction that writes it. */
object FbCaps:
  def transportUnavailable(): Boolean = MacCaps.transportUnavailable()
