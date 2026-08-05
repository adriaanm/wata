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
 *  THE WIFI SEAM (`WifiCmd`): the two ops shell out exactly the way `Diag`
 *  does, and both are overridable for a host harness —
 *
 *    wifi_scan   `<cli> scan` + `<cli> scan_results` where `<cli>` is
 *                `$WATA_WIFI_CLI`, else `wpa_cli -i wlan0` on the device,
 *                else the op reports `{ok:false, detail:"not on device"}`.
 *    wifi_join   `$WATA_WIFI_JOIN`, else `/usr/local/bin/wifi-join` — the
 *                alpine-provided helper (bq268-alpine
 *                docs/planning/wifi-join-helper.md): argv is the ssid
 *                ALONE, the PSK goes to the helper's STDIN — argv and the
 *                environment are world-readable in /proc, stdin is not. A
 *                helper that is not there reports
 *                `{ok:false, detail:"wifi-join helper missing"}` honestly.
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

  /** `{ok, networks: [{ssid, signal, secured}]}` — trigger a scan, then parse
   *  `scan_results` (header line, then tab-separated
   *  bssid/freq/signal/flags/ssid rows). Duplicate ssids (one per band)
   *  collapse to the strongest signal; hidden networks (empty ssid) drop. */
  def scan(): Json =
    val base = cliBase()
    if base == "" then fail("not on device")
    else scanWith(base)

  def scanWith(base: String): Json =
    if !Diag.shOk(base + " scan") then fail("scan failed")
    else
      var fs: List[(String, Json)] = Nil
      fs = ("networks", JArr(netJsons(parseResults(Diag.shOut(base + " scan_results 2>/dev/null")), Nil))) :: fs
      fs = ("ok", JBool(true)) :: fs
      JObj(fs)

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

  /** `{ok, detail}` — run `wifi-join <ssid>` with the PSK on stdin. The
   *  helper owns the join mechanics (the wpa_supplicant block, reconfigure,
   *  surviving reboot); this end only reports what it said. */
  def join(ssid: String, psk: String): Json =
    val helper = joinHelper()
    if ssid == "" then fail("no ssid")
    else if helper == "" || !Diag.readable(helper) then fail("wifi-join helper missing")
    else joinRun(helper, ssid, psk)

  def joinRun(helper: String, ssid: String, psk: String): Json =
    var ok = true
    var detail = ""
    try
      val cmd = go.exec.command1(helper, ssid)
      cmd.stdin = go.strings.newReader(psk)
      detail = firstLine(go.string(cmd.output()))
    catch case e: sgo.GoError =>
      ok = false
      detail = e.message
    var fs: List[(String, Json)] = Nil
    fs = ("detail", JStr(detail)) :: fs
    fs = ("ok", JBool(ok)) :: fs
    JObj(fs)

  def firstLine(s: String): String =
    val nl = s.indexOf("\n")
    if nl < 0 then s else s.substring(0, nl)

/** one parsed scan row. */
case class ScanNet(ssid: String, signal: Long, secured: Boolean)
