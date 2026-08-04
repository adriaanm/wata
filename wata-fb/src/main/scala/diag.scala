import language.experimental.saferExceptions

/** Device diagnostics + power actions for the settings applet — the minimum
 *  absorbed from system-menu (plan 0003, phase 5): the wlan0 address for the
 *  IP row, the ppp0 link for the cellular-data row, and poweroff /
 *  reboot-bootloader / reboot-edl for the power rows (stdlib `net` for the
 *  addresses, the ppp0 sysfs node, and system-menu's own three power
 *  commands). Device-layer app code over the `go.netif` / `go.exec` /
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

  /** the IP row: wlan0's IPv4 address (what system-menu's wifi_info shows),
   *  "no addr" when the interface is down, "n/a" off-device. */
  def wlanIp(): String =
    var out = UNAVAILABLE
    if onDevice() then out = ifaceIp("wlan0")
    out

  /** the interface's first IPv4 address, via `net.InterfaceByName(name)` +
   *  `Interface.Addrs()`; "no addr" when the interface has none, UNAVAILABLE
   *  when it does not exist. `Addr.String()` is CIDR text, so the dotted
   *  quad is the leading digits-and-dots run — an IPv6 address never yields
   *  one (its text has no dot before the mask). */
  def ifaceIp(name: String): String =
    var out = "no addr"
    try
      val iface = go.netif.interfaceByName(name)
      val addrs = iface.addrs()
      var i = 0
      while i < addrs.length do
        if out == "no addr" then
          val quad = addrPrefix(addrs(i).show())
          if quad.indexOf(".") >= 0 then out = quad
        i = i + 1
    catch case e: sgo.GoError => out = UNAVAILABLE
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
    try go.exec.command(cmd).run()
    catch case e: sgo.GoError => println("diag: " + cmd + ": " + e.message)
