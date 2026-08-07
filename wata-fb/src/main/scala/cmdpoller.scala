import language.experimental.saferExceptions
import sgo.add

/** The command poller (plan 0020): one goroutine long-polling the server's
 *  device-command mailbox (`GET /_wata/v1/cmd/poll`), dispatching each
 *  command to the same exec surface the settings applet's device rows use,
 *  and reporting each result back (`POST /_wata/v1/cmd/report`). No device
 *  UI: provisioning shows nothing on the handset beyond the connectivity
 *  element reacting to the network change.
 *
 *  The poller authenticates as the device's account with the session token
 *  (`Runtime.lastAuth` — device-login or password, whichever door the
 *  session came through), so it starts polling only once a session is up
 *  and rides whatever transport the client is configured for.
 *
 *  LIFECYCLE: `start` bumps an epoch and spawns the loop; `stop` bumps the
 *  epoch again, and the loop exits after its in-flight poll returns (the
 *  poll is bounded by `WAIT_S`, and the process usually ends right after a
 *  stop anyway). Started from the real device loop (`Ui.loopWithDevice`)
 *  only — sim and uitest stay deterministic; the integ scenario drives the
 *  same loop directly against a fake wifi seam.
 *
 *  THE WIFI SEAM (`WifiCmd`): the ops shell out exactly the way `Diag`
 *  does, and all are overridable for a host harness —
 *
 *    wifi_scan   `<cli> scan`, then `<cli> scan_results` polled until it
 *                moves off the pre-scan cache (plan 0031: the scan is
 *                async and an instant read reports the PREVIOUS sweep) —
 *                where `<cli>` is `$WATA_WIFI_CLI`, else `wpa_cli -i
 *                wlan0` on the device, else the op reports
 *                `{ok:false, detail:"not on device"}`.
 *    wifi_join   `$WATA_WIFI_JOIN`, else `/usr/local/bin/wifi-join` — the
 *                alpine-provided helper (bq268-alpine
 *                docs/planning/wifi-join-helper.md): argv is the ssid
 *                ALONE, the PSK goes to the helper's STDIN — argv and the
 *                environment are world-readable in /proc, stdin is not. A
 *                helper that is not there reports
 *                `{ok:false, detail:"wifi-join helper missing"}` honestly.
 *                The helper's exit 0 means CONFIG APPLIED, not joined: the
 *                verdict is the association probe (plan 0031) — `<cli>
 *                status` until the target ssid completes or the window
 *                passes — and a failed join rolls the previous conf back.
 *    wifi_off    `<cli> disable_network all` + an in-process auto-restore
 *                timer (plan 0031's cellular-fallback test switch);
 *                persistent config untouched, so a reboot also restores.
 */
object CmdPoller:
  private val epochC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private def JSON = "application/json"

  /** the long-poll ceiling asked of the server, seconds — under the http
   *  capability's own 30s bound so a quiet poll returns normally. */
  def WAIT_S: scala.Int = 20

  def start(c: MatrixClient): Unit =
    val e = epochC.add(1)
    sgo.spawn(() => loop(c, e))

  def stop(): Unit =
    val e = epochC.add(1)
    ()

  def loop(c: MatrixClient, epoch: scala.Int): Unit =
    while epochC.get() == epoch do cycle(c)

  /** one poll round: no session yet -> wait for one; a poll error -> back
   *  off rather than hammer; commands -> dispatch and report each. */
  def cycle(c: MatrixClient): Unit =
    val tok = Runtime.lastAuth.accessToken
    if tok == "" then FbCaps.sleepMs(1000L)
    else cyclePoll(Hs(c.http, c.clock, c.cfg.homeserver, tok))

  def cyclePoll(hs: Hs): Unit =
    val r = MatrixHttp.request(hs, "GET", "/_wata/v1/cmd/poll?wait=" + WAIT_S, JSON, "")
    if r.status != 200 then FbCaps.sleepMs(3000L)
    else handleAll(hs, cmdsOf(MatrixHttp.parseOrNull(r.body)))

  def cmdsOf(j: Json): List[Json] = WJson.getField(j, "cmds") match
    case s: Some[Json] => arrItems(s.value)
    case None => Nil

  def arrItems(j: Json): List[Json] = j match
    case a: JArr => a.items
    case _       => Nil

  def handleAll(hs: Hs, cmds: List[Json]): Unit =
    var cur = cmds
    var going = true
    while going do
      cur match
        case h :: t =>
          handleOne(hs, h)
          cur = t
        case Nil => going = false

  /** execute one command and report its result under the same op. */
  def handleOne(hs: Hs, cmd: Json): Unit =
    val op = WJson.strField(cmd, "op", "")
    val result = dispatch(op, cmd)
    var fs: List[(String, Json)] = Nil
    fs = ("result", result) :: fs
    fs = ("op", JStr(op)) :: fs
    val r = MatrixHttp.request(hs, "POST", "/_wata/v1/cmd/report", JSON, Json.write(JObj(fs)))
    println("cmd: " + op + " -> report " + r.status)

  def dispatch(op: String, cmd: Json): Json =
    if op == "wifi_scan" then WifiCmd.scan()
    else if op == "wifi_join" then
      WifiCmd.join(WJson.strField(cmd, "ssid", ""), WJson.strField(cmd, "psk", ""))
    else if op == "wifi_off" then WifiCmd.off(WJson.longField(cmd, "minutes", 10L))
    else WifiCmd.fail("unsupported op")

/** the two wifi ops' device mechanics — every command line is run the way
 *  `Diag` runs system-menu's (`sh -c`, the `go.exec` facade), and the join
 *  helper is a plain argv exec with the PSK on stdin. */
object WifiCmd:

  def fail(detail: String): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("detail", JStr(detail)) :: fs
    fs = ("ok", JBool(false)) :: fs
    JObj(fs)

  /** the wpa_cli invocation prefix; "" = nowhere to scan (a dev host with no
   *  override). `$WATA_WIFI_CLI` names a harness fake. */
  def cliBase(): String =
    val env = go.sys.getenv("WATA_WIFI_CLI")
    if env != "" then env
    else if Diag.onDevice() then "wpa_cli -i wlan0"
    else ""

  // ---- wifi_scan -----------------------------------------------------------------

  /** `{ok, networks: [{ssid, signal, secured}]}` — trigger a scan, wait for
   *  the results to SETTLE, then parse `scan_results` (header line, then
   *  tab-separated bssid/freq/signal/flags/ssid rows). Duplicate ssids (one
   *  per band) collapse to the strongest signal; hidden networks (empty
   *  ssid) drop. */
  def scan(): Json =
    val base = cliBase()
    if base == "" then fail("not on device")
    else scanWith(base)

  def scanWith(base: String): Json =
    val baseline = readResults(base)
    if !Diag.shOk(base + " scan") then fail("scan failed")
    else scanSettled(base, baseline)

  /** `wpa_cli scan` is ASYNC: an instant `scan_results` read answers the
   *  previous cached sweep (plan 0031 — a visible network went missing from
   *  a listing that way). Poll (500ms steps) until the output moves off the
   *  pre-scan baseline or the settle window passes, and report the last
   *  read — a sweep that happens to equal the cache costs the full window,
   *  which is indistinguishable without wpa_supplicant event listening.
   *  `$WATA_WIFI_SETTLE_MS` (default 4000) bounds it; the harness sets 0 so
   *  its canned fake stays instant. */
  def scanSettled(base: String, baseline: String): Json =
    var out = readResults(base)
    var left = settleMs()
    while left > 0L && out == baseline do
      FbCaps.sleepMs(500L)
      left = left - 500L
      out = readResults(base)
    var fs: List[(String, Json)] = Nil
    fs = ("networks", JArr(netJsons(parseResults(out), Nil))) :: fs
    fs = ("ok", JBool(true)) :: fs
    JObj(fs)

  def readResults(base: String): String = Diag.shOut(base + " scan_results 2>/dev/null")

  def settleMs(): Long = envMsOr("WATA_WIFI_SETTLE_MS", 4000L)

  /** `$name` as milliseconds; the default on unset or junk (a leading digit
   *  run parses, so "0" is a real zero). */
  def envMsOr(name: String, dflt: Long): Long =
    val s = go.sys.getenv(name)
    if s == "" then dflt
    else
      val v = Diag.intPrefix(s)
      if v < 0 then dflt else v.toLong

  def netJsons(xs: List[ScanNet], acc: List[Json]): List[Json] = xs match
    case h :: t => netJsons(t, netJson(h) :: acc)
    case Nil  => revJson(acc, Nil)

  def revJson(xs: List[Json], acc: List[Json]): List[Json] = xs match
    case h :: t => revJson(t, h :: acc)
    case Nil  => acc

  def netJson(n: ScanNet): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("secured", JBool(n.secured)) :: fs
    fs = ("signal", JInt(n.signal)) :: fs
    fs = ("ssid", JStr(n.ssid)) :: fs
    JObj(fs)

  def parseResults(out: String): List[ScanNet] =
    var nets: List[ScanNet] = Nil
    var rest = out
    var first = true
    var going = true
    while going do
      val nl = rest.indexOf("\n")
      var line = rest
      if nl >= 0 then
        line = rest.substring(0, nl)
        rest = rest.substring(nl + 1, rest.length)
      else going = false
      if first then first = false           // the header row
      else nets = mergeNet(nets, parseRow(line))
    revNets(nets, Nil)

  def revNets(xs: List[ScanNet], acc: List[ScanNet]): List[ScanNet] = xs match
    case h :: t => revNets(t, h :: acc)
    case Nil  => acc

  /** one `scan_results` row: bssid \t freq \t signal \t flags \t ssid,
   *  consumed field by field (there is no 2-arg indexOf here). A short row —
   *  or a hidden network's empty ssid — parses to the drop marker. */
  def parseRow(line: String): ScanNet =
    val afterBssid = tailTab(line)
    val afterFreq = tailTab(afterBssid)
    val sig = headTab(afterFreq)
    val afterSig = tailTab(afterFreq)
    val flags = headTab(afterSig)
    val ssid = tailTab(afterSig)
    ScanNet(ssid, parseSigned(sig),
      flags.indexOf("WPA") >= 0 || flags.indexOf("WEP") >= 0 || flags.indexOf("RSN") >= 0)

  def headTab(s: String): String =
    val i = s.indexOf("\t")
    if i < 0 then s else s.substring(0, i)

  /** everything after the first tab; "" when there is none — which is what
   *  starves a short row's ssid into the drop marker. */
  def tailTab(s: String): String =
    val i = s.indexOf("\t")
    if i < 0 then "" else s.substring(i + 1, s.length)

  /** keep one row per ssid, the strongest signal winning (a base station
   *  answers once per band). */
  def mergeNet(nets: List[ScanNet], n: ScanNet): List[ScanNet] =
    if n.ssid == "" then nets
    else if hasStronger(nets, n) then nets
    else n :: dropSsid(nets, n.ssid, Nil)

  def hasStronger(nets: List[ScanNet], n: ScanNet): Boolean = nets match
    case h :: t => hasStrongerStep(h, t, n)
    case Nil  => false

  def hasStrongerStep(h: ScanNet, t: List[ScanNet], n: ScanNet): Boolean =
    if h.ssid == n.ssid && h.signal >= n.signal then true else hasStronger(t, n)

  def dropSsid(xs: List[ScanNet], ssid: String, acc: List[ScanNet]): List[ScanNet] = xs match
    case h :: t => dropSsidStep(h, t, ssid, acc)
    case Nil  => revNets(acc, Nil)

  def dropSsidStep(h: ScanNet, t: List[ScanNet], ssid: String, acc: List[ScanNet]): List[ScanNet] =
    var acc2: List[ScanNet] = acc
    if h.ssid == ssid then acc2 = acc else acc2 = h :: acc2
    dropSsid(t, ssid, acc2)

  /** decimal with an optional leading '-' (a dBm reading); 0 on junk. */
  def parseSigned(s: String): Long =
    var body = s
    var negate = false
    if s.startsWith("-") then
      negate = true
      body = s.substring(1, s.length)
    var v = Diag.intPrefix(body).toLong
    if v < 0L then v = 0L
    if negate then v = -v
    v

  // ---- wifi_join -----------------------------------------------------------------

  /** the helper path; "" = nothing to run here. */
  def joinHelper(): String =
    val env = go.sys.getenv("WATA_WIFI_JOIN")
    if env != "" then env
    else if Diag.onDevice() then "/usr/local/bin/wifi-join"
    else ""

  /** `{ok, detail}` — run `wifi-join <ssid>` with the PSK on stdin, then
   *  probe until the target ssid ASSOCIATES (plan 0031: the verdict IS the
   *  association outcome — the helper owns the config mechanics, exit 0
   *  means "config applied", and a mistyped PSK used to answer "join ok"
   *  while the radio silently roamed to a fallback). "ok" is impossible
   *  without association, and a failed join rolls the previous conf back so
   *  a bad join never destroys a working credential. */
  def join(ssid: String, psk: String): Json =
    val helper = joinHelper()
    if ssid == "" then fail("no ssid")
    else if helper == "" || !Diag.readable(helper) then fail("wifi-join helper missing")
    else if cliBase() == "" then fail("no wpa_cli to verify association")
    else joinRun(cliBase(), helper, ssid, psk)

  def joinRun(base: String, helper: String, ssid: String, psk: String): Json =
    backupConf()
    var applied = true
    var detail = ""
    try
      val cmd = go.exec.command(helper, ssid)
      cmd.stdin = go.strings.newReader(psk)
      detail = firstLine(go.string(cmd.output()))
    catch case e: sgo.GoError =>
      applied = false
      detail = e.message
    if !applied then
      dropBackup()               // helper contract: non-zero = config not applied
      fail(detail)
    else joinVerdict(base, ssid)

  /** probe `status` (1s steps) until the TARGET ssid completes or the window
   *  (`$WATA_WIFI_ASSOC_MS`, default 20000) passes; commit or roll back the
   *  conf backup accordingly. */
  def joinVerdict(base: String, ssid: String): Json =
    var left = envMsOr("WATA_WIFI_ASSOC_MS", 20000L)
    var done = associated(base, ssid)
    while left > 0L && !done do
      FbCaps.sleepMs(1000L)
      left = left - 1000L
      done = associated(base, ssid)
    if done then
      dropBackup()
      okDetail("joined " + ssid)
    else
      restoreConf(base)
      fail("auth failed for " + ssid + ", still on " + currentSsid(base))

  def associated(base: String, ssid: String): Boolean =
    val out = Diag.shOut(base + " status 2>/dev/null")
    statusField(out, "ssid") == ssid && statusField(out, "wpa_state") == "COMPLETED"

  def currentSsid(base: String): String =
    val s = statusField(Diag.shOut(base + " status 2>/dev/null"), "ssid")
    if s == "" then "nothing" else s

  /** `key=value` out of `wpa_cli status` output, matched per LINE (a bare
   *  `indexOf("ssid=")` would hit `bssid=`); "" when absent. */
  def statusField(out: String, key: String): String =
    var rest = out
    var found = ""
    var going = true
    while going do
      val nl = rest.indexOf("\n")
      var line = rest
      if nl >= 0 then
        line = rest.substring(0, nl)
        rest = rest.substring(nl + 1, rest.length)
      else going = false
      if found == "" && line.startsWith(key + "=") then
        found = line.substring(key.length + 1, line.length)
    found

  // the conf backup that makes a bad join non-destructive: the live file is
  // copied aside (OPAQUE BYTES — its format stays the helper's business)
  // before the helper rewrites it, restored + reconfigured on a failed
  // association, deleted on success. No conf file (every dev host, and the
  // harness) = every leg a silent no-op.

  def confPath(): String = "/etc/wpa_supplicant/wpa_supplicant.conf"
  def bakPath(): String = confPath() + ".wata-prev"

  def backupConf(): Unit =
    if Diag.readable(confPath()) then discard(Diag.shOk("cp " + confPath() + " " + bakPath()))

  def dropBackup(): Unit =
    discard(Diag.shOk("rm -f " + bakPath()))

  def restoreConf(base: String): Unit =
    if Diag.readable(bakPath()) then
      discard(Diag.shOk("mv " + bakPath() + " " + confPath() + " && " + base + " reconfigure"))

  def discard(b: Boolean): Unit = ()

  def okDetail(detail: String): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("detail", JStr(detail)) :: fs
    fs = ("ok", JBool(true)) :: fs
    JObj(fs)

  def firstLine(s: String): String =
    val nl = s.indexOf("\n")
    if nl < 0 then s else s.substring(0, nl)

  // ---- wifi_off -----------------------------------------------------------------

  // the auto-restore epoch: a second wifi_off re-arms the window by bumping
  // it, so a stale timer never restores early (it checks its epoch first).
  private val offEpochC: sgo.Atomic[scala.Int] = sgo.atomic(0)

  /** `{ok, detail}` — take wlan0 down at RUNTIME only (`disable_network
   *  all`; nothing calls save_config, so persistent config is untouched and
   *  a reboot restores wifi) and arm the in-process auto-restore timer. A
   *  stranded handset is impossible: the timer, a reboot, or a power cycle
   *  each restore. The report goes out AFTER the radio drops — arriving
   *  over whatever transport survives is the point (plan 0031). */
  def off(minutes: Long): Json =
    val base = cliBase()
    if base == "" then fail("not on device")
    else offWith(base, clampMinutes(minutes))

  /** absent/junk -> the 10min default; ceiling 120 so a typo cannot park a
   *  handset off-wifi for a day. */
  def clampMinutes(m: Long): scala.Int =
    if m <= 0L then 10 else if m > 120L then 120 else m.toInt

  def offWith(base: String, minutes: scala.Int): Json =
    if !Diag.shOk(base + " disable_network all") then fail("disable_network failed")
    else
      val e = offEpochC.add(1)
      sgo.spawn(() => restoreJob(base, e, restoreDelayMs(minutes)))
      okDetail("wifi off for " + minutes + " min; auto-restore armed")

  /** `$WATA_WIFI_RESTORE_MS` overrides the window so a harness can watch the
   *  restore fire without waiting minutes. */
  def restoreDelayMs(minutes: scala.Int): Long =
    envMsOr("WATA_WIFI_RESTORE_MS", minutes.toLong * 60000L)

  /** sleep out the window in 500ms slices, abandoning the moment a newer
   *  wifi_off supersedes this one; then restore. */
  def restoreJob(base: String, epoch: scala.Int, delayMs: Long): Unit =
    var left = delayMs
    while left > 0L && offEpochC.get() == epoch do
      var step = 500L
      if left < 500L then step = left
      FbCaps.sleepMs(step)
      left = left - step
    if offEpochC.get() == epoch then
      discard(Diag.shOk(base + " enable_network all && " + base + " reassociate"))

/** one parsed scan row. */
case class ScanNet(ssid: String, signal: Long, secured: Boolean)
