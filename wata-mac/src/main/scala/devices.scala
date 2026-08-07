import language.experimental.saferExceptions

/** The Devices window's engine (plan 0037, slice 5): the admin work wata-tui
 *  does from a command line, driven by a window instead.
 *
 *  Nothing new goes on the wire. A wifi scan, a join and a `wifi off` are
 *  requests through the server's device-command mailbox (plans 0020/0031),
 *  and approving or denying a handset is the admin enrolment API (plan 0027).
 *  Both are exactly what `wata-tui`'s `wifi`/`join`/`raw` reach for, so this
 *  is the tui's logic with a window in front of it rather than a REPL.
 *
 *  IT RUNS ON ITS OWN GOROUTINE. A scan may take a minute to come back — the
 *  handset has to hear the command, run `iw scan` and report — and the frame
 *  pump cannot wait for that: the stage would freeze mid-animation and the
 *  window would stop redrawing. So the chrome's buttons push a command
 *  string, the pump forwards it to this worker's channel, and the worker
 *  answers by pushing content back into the chrome (`SetNetworks`,
 *  `SetPending`, `SetDevStatus`) and printing one decision line each.
 *
 *  THE PSK IS NEVER IN A COMMAND, NEVER IN A LOG LINE, NEVER IN THE CONFIG.
 *  The join command carries the handset and the ssid; the password is read
 *  straight out of the NSSecureTextField with `takePsk`, which clears the
 *  field as it reads, and goes into the request body and nowhere else. The
 *  printed line says how many characters it was, which is enough to tell an
 *  empty field from a typed one without saying what was typed. wata-fb's own
 *  join helper passes the PSK over stdin for the same reason argv and the
 *  environment are the wrong place for it (`wata-fb/netexec.scala`).
 *
 *  Every operation prints one `dev:` line, and those lines — not the pixels —
 *  are what `tools/mac-devices-smoke.py` asserts. Whether macOS drew the
 *  window is macOS's business; which node id this client would approve, and
 *  what a join request carries, is entirely ours. */

/** one row of a handset's wifi scan report — the tui's `WifiNet`, over here
 *  because the two clients do not share a module. */
case class MacNet(ssid: String, signal: Long, secured: Boolean)

object Devices:

  /** how long a `wifi off` window is, matching the chrome's button copy. The
   *  device clamps it and restores on its own, so this is a request, not a
   *  guarantee. */
  val OFF_MINUTES: scala.Int = 10

  private def JSON = "application/json"

  // the networks the last scan reported, so a join can name the one the
  // window's popup is pointing at even though the command carries the ssid
  private val netsC: sgo.Atomic[List[MacNet]] = sgo.atomic(Nil)

  // the session is winding down. Checked by every report poll, because a
  // worker parked on a 60-second wait would otherwise hold the scope open
  // for a minute after the user pressed Quit.
  private val stopC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val handsetsC: sgo.Atomic[String] = sgo.atomic("")

  def stop(): Unit = stopC.set(true)
  def resume(): Unit =
    stopC.set(false)
    handsetsC.set("")

  /** the worker: one command at a time until the pump hands it the empty
   *  string on the way out. Forked beside the audio thread. */
  def worker(cmds: sgo.Chan[String], c: MatrixClient): Unit =
    var going = true
    while going do
      val cmd = cmds.recv()
      if cmd == "" then going = false
      else run(c, cmd)

  /** dispatch one tab-separated command from the chrome. */
  def run(c: MatrixClient, cmd: String): Unit =
    val op = MacStr.tabField(cmd, 0)
    val a1 = MacStr.tabField(cmd, 1)
    val a2 = MacStr.tabField(cmd, 2)
    if op == "dev:scan" then scan(c, a1)
    else if op == "dev:join" then join(c, a1, a2)
    else if op == "dev:off" then off(c, a1, MacStr.num(a2, OFF_MINUTES))
    else if op == "dev:pending" then pending(c)
    else if op == "dev:approve" then approve(c, a1, a2)
    else if op == "dev:deny" then deny(c, a1)
    else println("dev: ? " + op)

  /** the handset picker's contents: everyone this account has a contact for.
   *  Run once a frame off the snapshot the frame already read, and pushed to
   *  the chrome only when it CHANGES — the window is opened from a menu at an
   *  arbitrary moment, so it has to already know, but redrawing it sixty
   *  times a second would fight the user's own selection. */
  def publishHandsets(snap: StateSnapshot): Unit =
    val rows = handsetRows(snap.contacts)
    if rows != handsetsC.get() then
      handsetsC.set(rows)
      go.macshell.setHandsets(rows)

  def handsetRows(contacts: List[Contact]): String =
    var cur = contacts
    var out = ""
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + h.user.id + "\t" +
            Names.displayOr(h.user.displayName, h.user.id) + "\n"
          cur = t
        case Nil => going = false
    out

  // ---- wifi, over the device-command mailbox --------------------------------

  /** queue `wifi_scan` and wait for the handset's report, then fill the
   *  window's network list. The wait is skew-free the way the tui's is: every
   *  report carries a server-stamped `seq`, and "the answer to THIS scan" is
   *  the seq moving past what it read before queueing, so a stale report from
   *  an earlier scan can never satisfy a new one. */
  def scan(c: MatrixClient, target: String): Unit =
    if target == "" then status("Pick a handset first.")
    else
      println("dev: scan " + target)
      status("Asking " + Names.localpart(target) + "'s handset what it can see…")
      val hs = hsOf(c)
      val before = reportSeq(hs, target, "wifi_scan")
      val q = MatrixHttp.request(hs, "POST", cmdPath(target), JSON, "{\"op\":\"wifi_scan\"}")
      if q.status != 200 then failed("scan", q.status, q.body)
      else scanWait(c, target, before)

  def scanWait(c: MatrixClient, target: String, before: Long): Unit =
    val rep = awaitReport(c, target, "wifi_scan", before, 60000L)
    if !WJson.boolField(rep, "arrived") then
      println("dev: scan timed out")
      status("No answer from " + Names.localpart(target) + "'s handset.")
    else scanResult(WJson.objField(rep, "result"))

  def scanResult(result: Json): Unit =
    if !WJson.boolField(result, "ok") then
      val detail = WJson.strField(result, "detail", "")
      println("dev: scan failed: " + detail)
      status("The handset could not scan: " + detail)
    else
      val nets = netsOf(WJson.getField(result, "networks"))
      netsC.set(nets)
      printNets(nets, 1)
      go.macshell.setNetworks(netRows(nets))
      status(netCount(nets) + " nearby.")

  /** queue `wifi_join` with the ssid the window picked and the PSK the secure
   *  field holds — `takePsk` reads and clears it in one call, so the password
   *  exists in this process only for the length of the request. */
  def join(c: MatrixClient, target: String, ssid: String): Unit =
    val psk = go.macshell.takePsk()
    if target == "" || ssid == "" then status("Pick a handset and a network first.")
    else joinWith(c, target, ssid, psk)

  def joinWith(c: MatrixClient, target: String, ssid: String, psk: String): Unit =
    println("dev: join " + target + " " + ssid + " psk=" + pskShape(psk))
    status("Handing " + ssid + " to " + Names.localpart(target) + "'s handset…")
    val hs = hsOf(c)
    val before = reportSeq(hs, target, "wifi_join")
    val q = MatrixHttp.request(hs, "POST", cmdPath(target), JSON, joinBody(ssid, psk))
    if q.status != 200 then failed("join", q.status, q.body)
    else joinWait(c, target, ssid, before)

  /** what a printed line may say about a password: whether there was one, and
   *  how long. Never the value. */
  def pskShape(psk: String): String =
    if psk == "" then "none"
    else go.strconv.formatInt(psk.length.toLong, 10) + " chars"

  def joinBody(ssid: String, psk: String): String =
    var fs: List[(String, Json)] = Nil
    fs = ("psk", JStr(psk)) :: fs
    fs = ("ssid", JStr(ssid)) :: fs
    fs = ("op", JStr("wifi_join")) :: fs
    Json.write(JObj(fs))

  def joinWait(c: MatrixClient, target: String, ssid: String, before: Long): Unit =
    val rep = awaitReport(c, target, "wifi_join", before, 90000L)
    if !WJson.boolField(rep, "arrived") then
      println("dev: join timed out")
      status("No answer — the handset may have moved to " + ssid + " and lost us.")
    else joinResult(WJson.objField(rep, "result"), ssid)

  def joinResult(result: Json, ssid: String): Unit =
    val detail = WJson.strField(result, "detail", "")
    if WJson.boolField(result, "ok") then
      println("dev: join ok " + detail)
      status("On " + ssid + " now.")
    else
      println("dev: join failed: " + detail)
      status("Could not join " + ssid + ": " + detail)

  /** the cellular-fallback test switch: the handset drops wlan0 for a window
   *  and restores itself. The report ARRIVING is the test — it comes back
   *  over whatever transport survived the drop. */
  def off(c: MatrixClient, target: String, minutes: scala.Int): Unit =
    if target == "" then status("Pick a handset first.")
    else
      println("dev: off " + target + " " + minutes)
      status("Dropping wifi on " + Names.localpart(target) +
        "'s handset for " + minutes + " minutes…")
      val hs = hsOf(c)
      val before = reportSeq(hs, target, "wifi_off")
      val q = MatrixHttp.request(hs, "POST", cmdPath(target), JSON, offBody(minutes))
      if q.status != 200 then failed("off", q.status, q.body)
      else offWait(c, target, before)

  def offBody(minutes: scala.Int): String =
    var fs: List[(String, Json)] = Nil
    fs = ("minutes", JInt(minutes.toLong)) :: fs
    fs = ("op", JStr("wifi_off")) :: fs
    Json.write(JObj(fs))

  def offWait(c: MatrixClient, target: String, before: Long): Unit =
    val rep = awaitReport(c, target, "wifi_off", before, 60000L)
    if !WJson.boolField(rep, "arrived") then
      println("dev: off timed out")
      status("No answer — the handset has no way back to us without wifi.")
    else offResult(WJson.objField(rep, "result"))

  def offResult(result: Json): Unit =
    val detail = WJson.strField(result, "detail", "")
    if WJson.boolField(result, "ok") then
      println("dev: off ok " + detail)
      status("Wifi is off — the handset is on the mobile network, and answered over it.")
    else
      println("dev: off failed: " + detail)
      status("Could not drop wifi: " + detail)

  // ---- enrolment, over the admin API ---------------------------------------

  /** the handsets waiting for a verdict, plus the accounts an approval may
   *  bind to. One GET answers both (plan 0027). */
  def pending(c: MatrixClient): Unit =
    val r = MatrixHttp.request(hsOf(c), "GET", "/_wata/v1/admin/enroll", JSON, "")
    if r.status != 200 then
      println("dev: pending failed: " + r.status)
      status(adminExcuse(r.status))
    else pendingResult(MatrixHttp.parseOrNull(r.body))

  def pendingResult(j: Json): Unit =
    val rows = pendingRows(WJson.getField(j, "pending"))
    go.macshell.setPending(rows)
    go.macshell.setRoster(rosterRows(WJson.getField(j, "users")))
    printPending(rows)

  /** `403` here is the ordinary case, not a fault: an account without the
   *  admin flag can use every other part of this app. */
  def adminExcuse(st: scala.Int): String =
    if st == 403 then "This account cannot approve handsets — sign in as a parent account."
    else "The server would not answer (" + st + ")."

  def approve(c: MatrixClient, nodeId: String, account: String): Unit =
    if nodeId == "" then status("No handset is waiting.")
    else if account == "" then status("Type the account this handset belongs to.")
    else
      println("dev: approve " + nodeId + " user=" + account)
      val r = MatrixHttp.request(hsOf(c), "POST",
        "/_wata/v1/admin/enroll/" + nodeId + "/approve", JSON, userBody(account))
      if r.status != 200 then
        println("dev: approve failed: " + r.status)
        status(adminExcuse(r.status))
      else
        println("dev: approve ok " + nodeId + " " + account)
        status("Approved — bound to " + account + "; the handset will connect itself.")
        pending(c)

  def deny(c: MatrixClient, nodeId: String): Unit =
    if nodeId == "" then status("No handset is waiting.")
    else
      println("dev: deny " + nodeId)
      val r = MatrixHttp.request(hsOf(c), "POST",
        "/_wata/v1/admin/enroll/" + nodeId + "/deny", JSON, "{}")
      if r.status != 200 then
        println("dev: deny failed: " + r.status)
        status(adminExcuse(r.status))
      else
        println("dev: deny ok " + nodeId)
        status("Denied — that handset has to be enrolled again.")
        pending(c)

  def userBody(account: String): String =
    var fs: List[(String, Json)] = Nil
    fs = ("user", JStr(account)) :: fs
    Json.write(JObj(fs))

  // ---- rendering the answers -----------------------------------------------

  def printNets(ns: List[MacNet], i: scala.Int): Unit =
    var cur = ns
    var n = i
    var going = true
    while going do
      cur match
        case h :: t =>
          println("dev: net " + n + " " + h.ssid +
            " signal=" + go.strconv.formatInt(h.signal, 10) +
            " secured=" + MacStr.boolStr(h.secured))
          n = n + 1
          cur = t
        case Nil => going = false

  def netRows(ns: List[MacNet]): String =
    var cur = ns
    var out = ""
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + h.ssid + "\t" + go.strconv.formatInt(h.signal, 10) + "\t" +
            (if h.secured then "1" else "0") + "\n"
          cur = t
        case Nil => going = false
    out

  def netCount(ns: List[MacNet]): String =
    var cur = ns
    var n = 0
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    if n == 1 then "1 network" else n + " networks"

  def netsOf(j: Option[Json]): List[MacNet] = j match
    case s: Some[Json] => netItems(s.value)
    case None          => Nil

  def netItems(j: Json): List[MacNet] = j match
    case a: JArr => netList(a.items)
    case _       => Nil

  def netList(xs: List[Json]): List[MacNet] =
    var cur = xs
    var acc: List[MacNet] = Nil
    var going = true
    while going do
      cur match
        case h :: t =>
          acc = netOf(h) :: acc
          cur = t
        case Nil => going = false
    reverseNets(acc)

  def reverseNets(xs: List[MacNet]): List[MacNet] =
    var cur = xs
    var acc: List[MacNet] = Nil
    var going = true
    while going do
      cur match
        case h :: t =>
          acc = h :: acc
          cur = t
        case Nil => going = false
    acc

  def netOf(j: Json): MacNet =
    MacNet(WJson.strField(j, "ssid", ""), WJson.longField(j, "signal", 0L),
      WJson.boolField(j, "secured"))

  /** the pending rows as the chrome's `<nodeId>\t<code>` lines. */
  def pendingRows(j: Option[Json]): String = j match
    case s: Some[Json] => pendingItems(s.value)
    case None          => ""

  def pendingItems(j: Json): String = j match
    case a: JArr => pendingList(a.items)
    case _       => ""

  def pendingList(xs: List[Json]): String =
    var cur = xs
    var out = ""
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + WJson.strField(h, "node_id", "") + "\t" +
            WJson.strField(h, "nonce", "") + "\n"
          cur = t
        case Nil => going = false
    out

  def printPending(rows: String): Unit =
    var cur = MacStr.lines(rows)
    var going = true
    var n = 0
    while going do
      cur match
        case h :: t =>
          println("dev: pending " + MacStr.tabField(h, 0) + " " + MacStr.tabField(h, 1))
          n = n + 1
          cur = t
        case Nil => going = false
    if n == 0 then println("dev: pending none")

  /** the account roster the approve field hints at — the `users` array's
   *  localparts, one per line. */
  def rosterRows(j: Option[Json]): String = j match
    case s: Some[Json] => rosterItems(s.value)
    case None          => ""

  def rosterItems(j: Json): String = j match
    case a: JArr => rosterList(a.items)
    case _       => ""

  def rosterList(xs: List[Json]): String =
    var cur = xs
    var out = ""
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + rosterName(h) + "\n"
          cur = t
        case Nil => going = false
    out

  /** a roster entry is either a bare name or an object with one. */
  def rosterName(j: Json): String = j match
    case s: JStr => s.s
    case _       => WJson.strField(j, "user", "")

  // ---- plumbing -------------------------------------------------------------

  def status(s: String): Unit = go.macshell.setDevStatus(s)

  def failed(what: String, st: scala.Int, body: String): Unit =
    println("dev: " + what + " queue failed: " + st + " " + body)
    status("The server would not take that (" + st + ").")

  def cmdPath(target: String): String = "/_wata/v1/cmd/" + target

  def hsOf(c: MatrixClient): Hs =
    Hs(c.http, c.clock, c.cfg.homeserver, Runtime.lastAuth.accessToken)

  /** the current report's seq for (target, op); 0 when none has ever
   *  arrived. */
  def reportSeq(hs: Hs, target: String, op: String): Long =
    val r = MatrixHttp.request(hs, "GET", cmdPath(target) + "/report?op=" + op, JSON, "")
    if r.status != 200 then 0L
    else WJson.longField(MatrixHttp.parseOrNull(r.body), "seq", 0L)

  /** poll the report until its seq moves past `before` or the deadline
   *  passes. Answers `{"arrived": bool, "result": …}`. */
  def awaitReport(c: MatrixClient, target: String, op: String, before: Long,
                  timeoutMs: Long): Json =
    val hs = hsOf(c)
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var out: List[(String, Json)] = Nil
    out = ("arrived", JBool(false)) :: out
    var run = true
    while run do
      if c.clock.nowUnixMillis() >= deadline || stopC.get() then run = false
      else
        val r = MatrixHttp.request(hs, "GET", cmdPath(target) + "/report?op=" + op, JSON, "")
        val j = MatrixHttp.parseOrNull(r.body)
        if r.status == 200 && WJson.longField(j, "seq", 0L) > before then
          var fs: List[(String, Json)] = Nil
          fs = ("result", WJson.objField(j, "result")) :: fs
          fs = ("arrived", JBool(true)) :: fs
          out = fs
          run = false
        else c.clock.sleepMs(500L)
    JObj(out)
