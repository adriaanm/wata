import language.experimental.saferExceptions

/** Device diagnostics + power actions for the settings applet — the minimum
 *  absorbed from system-menu (plan 0003, phase 5): the wlan0 address for the
 *  IP row, the ppp0 link for the cellular-data row, and poweroff /
 *  reboot-bootloader / reboot-edl for the power rows, each mirroring
 *  system-menu's own source (`ip -4 addr show <iface>`, the ppp0 sysfs node,
 *  and the same three commands). Device-layer app code over the `go.exec` /
 *  `go.sys` facades.
 *
 *  Everything compiles and renders on the host; `onDevice()` gates both the
 *  destructive paths and the reads, so the info rows answer "n/a" honestly
 *  off-device rather than inventing a value the host does not have. */
object Diag:
  /** what an info row shows where the source does not exist (every dev host). */
  val UNAVAILABLE = "n/a"

  /** on the BQ268 iff its LCD-backlight sysfs node exists — hardware no dev
   *  host has (a linux laptop has power_supply nodes; none has lcd-bl). */
  def onDevice(): Boolean = readable("/sys/class/leds/lcd-bl/brightness")

  def readable(path: String): Boolean =
    var ok = true
    try
      val raw = go.sys.readFile(path)
      ok = raw.length >= 0
    catch case e: sgo.GoError => ok = false
    ok

  // ---- info rows ---------------------------------------------------------------

  /** the IP row: wlan0's IPv4 address (system-menu's wifi_info source),
   *  "no addr" when the interface is down, "n/a" off-device. */
  def wlanIp(): String =
    var out = UNAVAILABLE
    if onDevice() then out = ifaceIp("wlan0")
    out

  /** `ip -4 addr show <iface>` — the exact read system-menu does — parsed for
   *  the first `inet a.b.c.d/nn`. */
  def ifaceIp(name: String): String =
    var out = "no addr"
    try
      val cmd = go.exec.command4("ip", "-4", "addr", "show", name)
      val raw = cmd.output()
      val found = parseInet(go.string(raw))
      if found != "" then out = found
    catch case e: sgo.GoError => out = UNAVAILABLE
    out

  /** the address after the first "inet ", up to its "/nn" mask; "" if none. */
  def parseInet(s: String): String =
    var out = ""
    val at = s.indexOf("inet ")
    if at >= 0 then
      val rest = s.substring(at + 5)
      out = addrPrefix(rest)
    out

  /** the leading run of digits and dots — the dotted quad before the mask. */
  def addrPrefix(s: String): String =
    var n = 0
    var going = true
    while n < s.length && going do
      val ch = s.substring(n, n + 1)
      if "0123456789.".indexOf(ch) >= 0 then n = n + 1
      else going = false
    s.substring(0, n)

  /** the cellular-data row, system-menu's Data row: ppp0 up -> "up" + its
   *  address, ppp0 absent -> "off"; off-device there is no modem to ask, so
   *  the row says "n/a" instead of faking an "off". */
  def cellData(): String =
    var out = UNAVAILABLE
    if onDevice() then out = pppStatus()
    out

  def pppStatus(): String =
    var out = "off"
    if readable("/sys/class/net/ppp0/operstate") then out = "up " + ifaceIp("ppp0")
    out

  // ---- power actions -----------------------------------------------------------

  /** `poweroff` from $PATH — what system-menu's Power Off item runs. */
  def powerOff(): Unit = runOnDevice("poweroff")
  /** reboot(RESTART2, "bootloader") via the device's helper binary — warm
   *  reset into fastboot (see bq268-alpine tools/src/reboot-bootloader.zig). */
  def rebootBootloader(): Unit = runOnDevice("/usr/local/bin/reboot-bootloader")
  /** reboot(RESTART2, "edl") via the device's helper binary — PBL forced
   *  download / 9008 mode (tools/src/reboot-edl.zig). */
  def rebootEdl(): Unit = runOnDevice("/usr/local/bin/reboot-edl")

  /** on the device: run the command to completion, reporting a failure loudly
   *  (on success the machine is going down — nothing left to report to).
   *  Off-device: a logged no-op — the rows render everywhere, the actions
   *  only exist on the hardware. */
  def runOnDevice(cmd: String): Unit =
    if onDevice() then runNow(cmd)
    else println("diag: not on device; skipping " + cmd)

  def runNow(cmd: String): Unit =
    try
      val c = go.exec.command(cmd)
      val raw = c.output()
      if raw.length < 0 then println("diag: unreachable")
    catch case e: sgo.GoError => println("diag: " + cmd + ": " + e.message)
