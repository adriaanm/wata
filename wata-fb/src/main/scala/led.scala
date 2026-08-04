import language.experimental.saferExceptions

/** LED + backlight control via sysfs. Best-effort: silently no-ops on failure
 *  (the dev host has no leds sysfs tree). Device-layer app code over the
 *  `go.syscall` facade. */
object Led:
  def setBacklight(brightness: scala.Int): Unit =
    writeSysfs("/sys/class/leds/lcd-bl/brightness", brightness)
  def setRedLed(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/red/brightness", onValue(on))
  def setGreenLed(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/green/brightness", onValue(on))
  def setButtonBacklight(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/button-backlight/brightness", onValue(on))

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

  /** open WRONLY, write the decimal value, close — errors ignored. */
  def writeSysfs(path: String, value: scala.Int): Unit =
    try
      val fd = go.syscall.open(path, go.syscall.O_WRONLY, 0)
      go.syscall.write(fd, go.bytes("" + value))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()
