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

  /** open WRONLY, write the decimal value, close — errors ignored. */
  def writeSysfs(path: String, value: scala.Int): Unit =
    try
      val fd = go.syscall.open(path, go.syscall.O_WRONLY, 0)
      go.syscall.write(fd, go.bytes("" + value))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()
