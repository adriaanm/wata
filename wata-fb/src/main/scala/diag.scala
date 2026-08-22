import language.experimental.saferExceptions

/** the net test's verdicts, as the two 26-column lines the row's detail
 *  block has room for. */
case class NetTestResult(line1: String, line2: String)

/** Device diagnostics + power actions for the settings applet — the minimum
 *  absorbed from system-menu (plan 0003, phase 5): the wlan0 address for the
 *  IP row, the ppp0 link plus the modem's signal for the cellular-data row,
 *  uptime and free memory for the Device Info block, the ping/DNS net test,
 *  the wifi and cellular-data toggles, and poweroff / reboot-bootloader /
 *  reboot-edl for the power rows. Every source and command line is
 *  system-menu's own (stdlib `net` for the addresses, the sysfs nodes,
 *  /proc/uptime and /proc/meminfo read directly, and its qmicli / ping /
 *  nslookup / rc-service / pppd lines run through `sh -c`). Device-layer app
 *  code over the `go.netif` / `go.exec` / `go.sys` facades.
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

  /** the cellular-data row, system-menu's Data + Sig rows in one: the ppp0
   *  link state ("up"/"off") followed by the modem's signal strength
   *  ("-85dBm", or "--" when the modem reports nothing). Off-device there is
   *  no modem to ask, so the row says "n/a" instead of faking an "off".
   *
   *  The ppp0 ADDRESS does not fit next to the signal on a 26-column row, so
   *  it moves to the row's detail block (`cellAddr`). `NetStatus.readPipe`
   *  keys on the "up " prefix, which this keeps. */
  def cellData(): String =
    var out = UNAVAILABLE
    if onDevice() then out = pppStatus() + " " + signalDbm()
    out

  def pppStatus(): String =
    var out = "off"
    if readable("/sys/class/net/ppp0/operstate") then out = "up"
    out

  /** the ppp0 address for the cellular row's detail line; "" when the link is
   *  down or there is no device to ask. */
  def cellAddr(): String =
    var out = ""
    if onDevice() && readable("/sys/class/net/ppp0/operstate") then out = ifaceIp("ppp0")
    out

  /** signal strength from the modem, system-menu's `modem_info` source:
   *  `qmicli --nas-get-signal-strength` over the shared proxy on msmipc://0.
   *  `--` when the modem is silent or the call fails. */
  def signalDbm(): String =
    parseDbm(shOut("qmicli -p -d msmipc://0 --nas-get-signal-strength 2>&1"))

  /** qmicli prints a block per source under `Current:`; the number wanted is
   *  the one before the first ` dBm'` after that header. -128 is qmicli's
   *  "no measurement", which system-menu also drops. */
  def parseDbm(out: String): String =
    var res = "--"
    val ci = out.indexOf("Current:")
    if ci >= 0 then
      val rest = out.substring(ci, out.length)
      val di = rest.indexOf(" dBm'")
      if di >= 0 then
        val num = numberBefore(rest, di)
        if num != "" && num != "-128" then res = num + "dBm"
    res

  /** the run of digits (with a leading `-`) ending at `end`. */
  def numberBefore(s: String, end: scala.Int): String =
    var n = end
    var going = true
    while n > 0 && going do
      val ch = s.substring(n - 1, n)
      if "0123456789-".indexOf(ch) >= 0 then n = n - 1 else going = false
    s.substring(n, end)

  // ---- Device Info: uptime and free memory --------------------------------------

  /** uptime as "2h13m", from `/proc/uptime`'s first field (seconds) — the
   *  same source system-menu's sysinfo reads. Gated on `onDevice()` like
   *  every other row: a dev host HAS a /proc/uptime, and a row whose value
   *  depends on which machine ran the build is a row no golden can pin. */
  def uptime(): String =
    var out = UNAVAILABLE
    if onDevice() then out = uptimeText(readText("/proc/uptime"))
    out

  def uptimeText(raw: String): String =
    val secs = intPrefix(raw)
    var out = UNAVAILABLE
    if secs >= 0 then out = "" + (secs / 3600) + "h" + ((secs % 3600) / 60) + "m"
    out

  /** available memory as "123M", read out of `/proc/meminfo`'s MemAvailable
   *  line directly (system-menu shells out to awk for the same number). */
  def memAvail(): String =
    var out = UNAVAILABLE
    if onDevice() then out = memText(readText("/proc/meminfo"))
    out

  def memText(raw: String): String =
    var out = UNAVAILABLE
    val i = raw.indexOf("MemAvailable:")
    if i >= 0 then
      val kb = intPrefix(skipSpaces(raw.substring(i + 13, raw.length)))
      if kb >= 0 then out = "" + (kb / 1024) + "M"
    out

  def skipSpaces(s: String): String =
    var n = 0
    while n < s.length && s.substring(n, n + 1) == " " do n = n + 1
    s.substring(n, s.length)

  /** the leading run of decimal digits as an Int, or -1 when there is none. */
  def intPrefix(s: String): scala.Int =
    var acc = 0
    var seen = false
    var going = true
    var i = 0
    while i < s.length && going do
      val ch = s.substring(i, i + 1)
      val d = "0123456789".indexOf(ch)
      if d >= 0 then
        acc = acc * 10 + d
        seen = true
        i = i + 1
      else going = false
    var out = -1
    if seen then out = acc
    out

  /** a whole small text file as a String; "" when it does not exist. */
  def readText(path: String): String =
    var out = ""
    try
      val raw = go.sys.readFile(path)
      out = go.string(raw)
    catch case e: sgo.GoError => out = ""
    out

  // ---- charge stat: charger status + pack voltage ---------------------------------

  /** the charge stat the settings screens pair with the battery percentage
   *  (plan 0072): a three-letter verb plus the pack voltage — "chg 4.19V"
   *  while the charger runs, "bat 3.82V" on battery, and "usb 3.82V" when
   *  VBUS is present but the charger is idle: the docked-but-not-charging
   *  failure mode (FB-CHARGE-ANOMALY-GLYPH queues its status-bar glyph).
   *  "" where there is no voltage to read (every dev host), which is how
   *  the rows know to leave the stat out. */
  def chargeStat(): String =
    var out = ""
    if onDevice() then
      out = chargeText(readText("/sys/class/power_supply/battery/status"),
        readText("/sys/class/power_supply/usb/online"),
        readText("/sys/class/power_supply/battery/voltage_now"))
    out

  /** status + usb-online + voltage_now (microvolts) -> the stat text.
   *  "Discharging" and "Not charging" both contain lowercase "charging", so
   *  the charger verb keys on the capital-C prefix; "Full" counts as the
   *  charger doing its job. */
  def chargeText(status: String, usb: String, volt: String): String =
    val uv = intPrefix(volt)
    var out = ""
    if uv > 0 then
      var verb = "bat"
      if status.startsWith("Charging") || status.startsWith("Full") then verb = "chg"
      else if intPrefix(usb) == 1 then verb = "usb"
      out = verb + " " + voltText(uv)
    out

  /** microvolts as "4.19V": centivolts by integer division, no floats, the
   *  fraction zero-padded to two digits. */
  def voltText(uv: scala.Int): String =
    val cv = uv / 10000
    var frac = "" + (cv % 100)
    if frac.length < 2 then frac = "0" + frac
    "" + (cv / 100) + "." + frac + "V"

  // ---- net test ------------------------------------------------------------------

  // the running test's answer, handed from the goroutine that runs it to the
  // UI goroutine that draws it. Three cells rather than one holding the record:
  // `done` is the publication, written LAST and read FIRST, so a reader that
  // sees it set sees both lines.
  private val ntDoneC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val ntL1C: sgo.Atomic[String] = sgo.atomic("")
  private val ntL2C: sgo.Atomic[String] = sgo.atomic("")

  /** start the probes ON A GOROUTINE OF THEIR OWN and return immediately. They
   *  take seconds — four network round trips with their own timeouts — and the
   *  frame loop has 33ms. The caller (the settings applet) shows the row as
   *  running until `takeNetTest` hands it the verdicts. */
  def startNetTest(): Unit =
    ntDoneC.set(false)
    sgo.spawn(() => runNetTestJob())

  def runNetTestJob(): Unit =
    val r = netTest()
    ntL1C.set(r.line1)
    ntL2C.set(r.line2)
    ntDoneC.set(true)

  /** the finished test's verdicts, ONCE — `None` while a run is still going, or
   *  when there is nothing new to collect. */
  def takeNetTest(): Option[NetTestResult] =
    if !ntDoneC.getAndSet(false) then None
    else Some(NetTestResult(ntL1C.get(), ntL2C.get()))

  /** a fresh session per run, like `Ui.resetCells`' other cells: a verdict left
   *  over from the previous session must not land in a new applet's state. */
  def resetNetTest(): Unit =
    ntDoneC.set(false)
    ntL1C.set("")
    ntL2C.set("")

  /** the net test's four probes, system-menu's `show_nettest` line for line:
   *  ping the default gateway (auto-detected off wlan0, then ppp0), 1.1.1.1
   *  and 8.8.8.8, then an nslookup DNS probe. Returns the two detail lines
   *  the row renders; off-device it runs NOTHING and says so.
   *
   *  The probes are synchronous, which is why nobody calls this on the UI
   *  goroutine — `startNetTest` is the entry point. */
  def netTest(): NetTestResult =
    var out = NetTestResult(UNAVAILABLE, "not on device")
    if onDevice() then out = runNetTest()
    out

  def runNetTest(): NetTestResult =
    val gw = defaultGateway()
    var gwRes = "skip"
    if gw != "" then gwRes = pingResult(gw)
    NetTestResult("GW:" + gwRes + " 1.1.1.1:" + pingResult("1.1.1.1"),
      "8.8.8.8:" + pingResult("8.8.8.8") + " DNS:" + dnsResult())

  def pingResult(addr: String): String =
    var out = "fail"
    if shOk("ping -c2 -W3 " + addr + " >/dev/null 2>&1") then out = "ok"
    out

  /** system-menu's own DNS verdict: an answer with an Address line and no
   *  NXDOMAIN. busybox nslookup's exit status is not trustworthy enough to
   *  key on alone. */
  def dnsResult(): String =
    val out = shOut("nslookup google.com 2>&1")
    var res = "fail"
    if out.indexOf("Address") >= 0 && out.indexOf("NXDOMAIN") < 0 then res = "ok"
    res

  /** `ip route show dev <iface>` -> the address after "default via", wlan0
   *  first then ppp0 (system-menu's order); "" when neither has a default. */
  def defaultGateway(): String =
    var gw = gatewayOn("wlan0")
    if gw == "" then gw = gatewayOn("ppp0")
    gw

  def gatewayOn(iface: String): String =
    val out = shOut("ip route show dev " + iface + " 2>/dev/null")
    var gw = ""
    val i = out.indexOf("default via ")
    if i >= 0 then gw = wordAt(out, i + 12)
    gw

  /** the run of non-space characters starting at `from`. */
  def wordAt(s: String, from: scala.Int): String =
    var n = from
    var going = true
    while n < s.length && going do
      val ch = s.substring(n, n + 1)
      if ch == " " || ch == "\n" || ch == "\t" then going = false else n = n + 1
    s.substring(from, n)

  // ---- wifi / cellular-data toggles ----------------------------------------------

  /** the wifi row's state, system-menu's own test: wlan0 exists iff the
   *  service is up. "n/a" off-device. */
  def wifiState(): String =
    var out = UNAVAILABLE
    if onDevice() then
      var s = "OFF"
      if readable("/sys/class/net/wlan0/operstate") then s = "ON"
      out = s
    out

  /** `rc-service wifi start` / `stop` — system-menu's wifi toggle. */
  def wifiStart(): String = runGuarded("rc-service wifi start")
  def wifiStop(): String = runGuarded("rc-service wifi stop")

  /** the net-watchdog's POLICY verbs (plan 0057) — the data row sets policy
   *  through the rootfs's `cell-data` wrapper rather than driving pppd raw,
   *  because the supervised watchdog owns the cellular link and raw commands
   *  fight it (an "off" it does not know about is undone by its next health
   *  check). `auto` restores failover and returns quickly, so it runs in the
   *  foreground; `force` (pin cellular up, failover disabled) blocks up to
   *  the modem's 45s attach budget and `off` (tear down + pin down) can take
   *  ~10s, so both are BACKGROUNDED like the old raw dial — the applying
   *  spinner is the wait UI, and the row's own ppp0/wlan0 refresh reports
   *  the outcome. The pin lives in /run (tmpfs): a reboot returns to
   *  auto-with-wifi, the safe default. */
  def dataAuto(): String = runGuarded("cell-data auto")
  def dataForce(): String = runGuarded("cell-data force >/dev/null 2>&1 &")
  def dataOff(): String = runGuarded("cell-data off >/dev/null 2>&1 &")

  /** run a command for its exit status, reporting the failure as text for the
   *  row to show: "" on success, a short reason otherwise. Off-device it runs
   *  nothing and says so — the rows still arm, so the whole gesture is
   *  walkable in the sim. */
  /** WATA_FAKE_RADIOS — the radio commands' test seam (plan 0056). Off-device
   *  every guarded command answers "not on device", so the kid data row's
   *  APPLYING arm (entered only on a SILENT report) is unreachable in the
   *  sim. With the knob on, the commands answer "" without running anything,
   *  which is the scripted harness's way into the spinner and timeout arms —
   *  the diagnostics still read "n/a", so the radios never agree and the
   *  timeout is walkable too. Env-primed (`WATA_FAKE_RADIOS=1`) for hand
   *  runs, and flipped mid-script by the `fakeradios` uitest directive so
   *  ONE scenario can pin both the report arm and the applying arms — the
   *  same shape as the other forced seams (`conn`, `netpipe`, `enrolstate`).
   *  A module-level cell, like every sgo.Atomic here. */
  private val fakeRadiosC: sgo.Atomic[scala.Int] = sgo.atomic(fakeRadiosEnv())

  def fakeRadiosEnv(): scala.Int =
    var out = 0
    if go.sys.getenv("WATA_FAKE_RADIOS") == "1" then out = 1
    out

  def setFakeRadios(on: Boolean): Unit =
    var v = 0
    if on then v = 1
    fakeRadiosC.set(v)

  def fakeRadios(): Boolean = fakeRadiosC.get() == 1

  def runGuarded(line: String): String =
    var out = "not on device"
    if fakeRadios() then out = ""
    else if onDevice() then out = shStatus(line)
    out

  def shStatus(line: String): String =
    var out = ""
    try go.exec.command("sh", "-c", line).run()
    catch case e: sgo.GoError => out = clipReason(e.message)
    out

  /** an exec error's text is long enough to run off a 26-column row. */
  def clipReason(m: String): String =
    var out = m
    if m.length > 24 then out = m.substring(0, 24)
    out

  // ---- shell helpers --------------------------------------------------------------

  /** stdout of `sh -c <line>`; "" when the command fails to run or exits
   *  non-zero (the callers all treat no output as "no answer"). */
  def shOut(line: String): String =
    var out = ""
    try
      val raw = go.exec.command("sh", "-c", line).output()
      out = go.string(raw)
    catch case e: sgo.GoError => out = ""
    out

  /** whether `sh -c <line>` exited zero. */
  def shOk(line: String): Boolean =
    var ok = true
    try go.exec.command("sh", "-c", line).run()
    catch case e: sgo.GoError => ok = false
    ok

  // ---- power actions -----------------------------------------------------------

  /** `poweroff` from $PATH — what system-menu's Power Off item runs. */
  def powerOff(): Unit = runOnDevice("poweroff")
  /** an ordinary warm reboot back into Linux — the exit menu's middle rung,
   *  between restarting the app and the two modes that need a cable. */
  def reboot(): Unit = runOnDevice("reboot")
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
