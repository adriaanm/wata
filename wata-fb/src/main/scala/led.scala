import language.experimental.saferExceptions

/** LED + backlight control via sysfs. Never throws, but not blindly silent:
 *  the FIRST write to a node probes for the hardware — if it fails, the node
 *  is absent (every dev host) and stays a silent no-op; if it succeeded and a
 *  LATER write fails, that is a real device fault and is logged ONCE per node
 *  (never spamming the ~30fps frame loop). Device-layer app code over the
 *  `go.syscall` facade. */
object Led:
  /** per-node write status, threaded through `writeSysfs`; the cells are
   *  `val`-held Atomics (module-level mutable state must be a synchronizer). */
  val UNTRIED = 0
  val PRESENT = 1
  val ABSENT  = 2
  val FAULTED = 3   // was PRESENT, then a write failed — already logged

  val blStatusC: sgo.Atomic[scala.Int] = sgo.atomic(UNTRIED)
  val redStatusC: sgo.Atomic[scala.Int] = sgo.atomic(UNTRIED)
  val greenStatusC: sgo.Atomic[scala.Int] = sgo.atomic(UNTRIED)
  val buttonStatusC: sgo.Atomic[scala.Int] = sgo.atomic(UNTRIED)
  val fbBlankStatusC: sgo.Atomic[scala.Int] = sgo.atomic(UNTRIED)

  def setBacklight(brightness: scala.Int): Unit =
    blStatusC.set(writeSysfs("/sys/class/leds/lcd-bl/brightness", brightness, blStatusC.get()))
  def setRedLed(on: Boolean): Unit =
    redStatusC.set(writeSysfs("/sys/class/leds/red/brightness", onValue(on), redStatusC.get()))
  def setGreenLed(on: Boolean): Unit =
    greenStatusC.set(writeSysfs("/sys/class/leds/green/brightness", onValue(on), greenStatusC.get()))
  def setButtonBacklight(on: Boolean): Unit =
    buttonStatusC.set(writeSysfs("/sys/class/leds/button-backlight/brightness", onValue(on), buttonStatusC.get()))

  /** UNBLANK the kernel framebuffer: write 0 (FB_BLANK_UNBLANK) to the fbcon
   *  blank node. The kernel blanks the panel independently of the backlight —
   *  a blanked ST7735S shows WHITE with the backlight on, and writes into the
   *  mmap'd /dev/fb0 do not reach it — so the app must unblank at startup and
   *  on every screensaver wake or its frames are invisible until a stray VT
   *  event unblanks for it (the "white screen until first keypress" symptom).
   *  The sysfs node is the proven-on-device path (the FBIOBLANK ioctl reaches
   *  the same handler). */
  def unblankFb(): Unit =
    fbBlankStatusC.set(writeSysfs("/sys/class/graphics/fb0/blank", 0, fbBlankStatusC.get()))

  /** 255 when on, else 0. */
  def onValue(on: Boolean): scala.Int =
    var v = 0
    if on then v = 255
    v

  /** battery charge 0..100 from sysfs, or -1 when the node is not there —
   *  which is every dev host, so the settings applet treats -1 as "no reading"
   *  rather than as an error. */
  def readBatteryPercent(): scala.Int =
    var out = -1
    try
      val fd = go.syscall.open("/sys/class/power_supply/battery/capacity",
        go.syscall.O_RDONLY, 0)
      val buf = go.makeSlice[Byte](8)
      val n = go.syscall.read(fd, buf)
      go.syscall.close(fd)
      out = decimalPrefix(buf, n)
    catch case e: sgo.GoError => out = -1
    out

  /** the leading decimal digits of a sysfs read, or -1 when there are none.
   *  Scans bytes rather than going through a String: the node's contents are
   *  a number and a newline. */
  def decimalPrefix(buf: go.Bytes, n: scala.Int): scala.Int =
    var acc = 0
    var seen = false
    var going = true
    var i = 0
    while i < n && going do
      val b = buf(i).toInt & 0xff
      if b >= 48 && b <= 57 then
        acc = acc * 10 + (b - 48)
        seen = true
      else if seen then going = false
      i = i + 1
    var out = -1
    if seen then out = acc
    out

  /** open WRONLY, write the decimal value, close; returns the node's new
   *  status. Failure on an UNTRIED node marks it ABSENT (expected off-device,
   *  silent); failure on a PRESENT node logs once and marks it FAULTED. */
  def writeSysfs(path: String, value: scala.Int, status: scala.Int): scala.Int =
    var failMsg = ""
    try
      val fd = go.syscall.open(path, go.syscall.O_WRONLY, 0)
      val n = go.syscall.writeChecked(fd, go.bytes("" + value))
      go.syscall.close(fd)
      if n <= 0 then failMsg = "wrote " + n + " bytes"
    catch case e: sgo.GoError => failMsg = e.message
    var out = status
    if failMsg == "" then
      if out == UNTRIED then out = PRESENT
    else if out == UNTRIED then out = ABSENT
    else if out == PRESENT then
      println("led: " + path + ": write failed on previously working hardware: "
        + failMsg + " (further failures on this node not reported)")
      out = FAULTED
    out
