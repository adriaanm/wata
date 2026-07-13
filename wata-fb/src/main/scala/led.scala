import language.experimental.saferExceptions

/** M8 chunk 5 — LED + backlight control via sysfs, ported from fbclient
 *  `led.zig`. Best-effort: silently no-ops on failure (the dev host has no
 *  leds sysfs tree — the `catch` = Zig's `catch return`). Device-layer app
 *  code over the `go.syscall` facade. `readBatteryPercent` (a status-bar
 *  concern) is deferred to chunk 7. */
object Led:
  def setBacklight(brightness: scala.Int): Unit =
    writeSysfs("/sys/class/leds/lcd-bl/brightness", brightness)
  def setRedLed(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/red/brightness", onValue(on))
  def setGreenLed(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/green/brightness", onValue(on))
  def setButtonBacklight(on: Boolean): Unit =
    writeSysfs("/sys/class/leds/button-backlight/brightness", onValue(on))

  /** 255 when on, else 0 (if-as-expression lowered to a var — the emitter has
   *  no If-in-value-position). */
  def onValue(on: Boolean): scala.Int =
    var v = 0
    if on then v = 255
    v

  /** open WRONLY, write the decimal value, close — errors ignored (Zig). */
  def writeSysfs(path: String, value: scala.Int): Unit =
    try
      val fd = go.syscall.open(path, go.syscall.O_WRONLY, 0)
      go.syscall.write(fd, go.bytes("" + value))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()
