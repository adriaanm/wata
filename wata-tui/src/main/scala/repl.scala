import language.experimental.saferExceptions

/** The tui's LINE-ORIENTED COMMAND REPL (plan 0016): one command per stdin
 *  line, plain deterministic lines out. That shape is what makes the tui both
 *  a hand-usable admin tool and a scriptable oracle — `tools/tui-smoke.py`
 *  drives it by piping a command script and asserting on the printed lines.
 *
 *  Commands (`?` prefixes every rejection):
 *
 *    snap                     the current StateSnapshot: connection, self,
 *                             contacts, conversations (this is what numbers
 *                             the conversations)
 *    msgs <conv#>             that conversation's voice messages, numbered
 *    send <conv#|user> <ogg>  upload + send an Ogg file (a non-numeric target
 *                             is a user id; the DM room resolves server-side)
 *    play <conv#> <msg#>      download the Ogg, hand it to the external
 *                             player, then mark it played
 *    mark <conv#> <msg#>      the read receipt only
    fav <conv#> <msg#>       TOGGLE the server's favorite marker on that
                             message (plan 0019): a favorited voice message is
                             kept past the media-retention window
 *    wifi <conv#|user>        the wifi panel (plan 0020): queue a wifi_scan
 *                             for that user's handset through the server's
 *                             command mailbox, wait for its report, print the
 *                             networks numbered
 *    join <net#>              prompt for the PSK (the next stdin line), queue
 *                             wifi_join for the last `wifi` target, wait for
 *                             its report, say what happened
 *    raw <METHOD> <path> [json]   an authenticated request straight at the
 *                             server; prints status, length, and the body
 *    wait <ms>                poll snapshots for that long, printing each
 *                             change — the scripting primitive that replaces
 *                             watching the screen
 *    quit                     wind the client down and exit
 *
 *  NUMBERED REFERENCES both index the LAST `snap`: `snap` stashes the
 *  conversation list it printed, and `msgs`/`play`/`mark` index into that list
 *  and into the conversation's own message order — which is exactly the order
 *  `msgs` prints. So a script's numbers are stable against whatever arrives
 *  mid-run, and `play` does not depend on `msgs` having been run first. Ids
 *  are printed alongside for `raw` use.
 *
 *  Everything runs on the MAIN goroutine inside the caller's supervised scope,
 *  so the listing cells below are touched by exactly one goroutine. */
/** one row of a handset's wifi scan report (plan 0020). */
case class WifiNet(ssid: String, signal: Long, secured: Boolean)

object Repl:

  // `val`-held Atomic cells (single-goroutine; the module-state idiom).
  private val convsC: sgo.Atomic[List[Conversation]] = sgo.atomic(Nil)
  private val playSeqC: sgo.Atomic[Int] = sgo.atomic(0)
  // the wifi panel's numbering base: the last `wifi` target and the networks
  // its scan printed — what `join <n>` indexes into.
  private val wifiUserC: sgo.Atomic[String] = sgo.atomic("")
  private val wifiNetsC: sgo.Atomic[List[WifiNet]] = sgo.atomic(Nil)

  private def JSON = "application/json"

  /** read commands until stdin ends or `quit`. The scanner threads into
   *  `exec` because `join` reads its PSK as the NEXT stdin line — a prompt,
   *  not an argument, so a shoulder-surfable secret never sits in a shell
   *  history line beside the command that used it. */
  def loop(c: MatrixClient, sc: go.bufio.Scanner): Unit =
    var going = true
    while going do
      if !sc.scan() then going = false
      else going = exec(c, sc, sc.text())

  /** false only for `quit`. */
  def exec(c: MatrixClient, sc: go.bufio.Scanner, line: String): Boolean =
    val ts = Str.splitWs(line)
    val cmd = Str.nth(ts, 0)
    var going = true
    if cmd == "" || cmd.startsWith("#") then ()
    else if cmd == "snap" then snap(c)
    else if cmd == "msgs" then msgs(Str.num(Str.nth(ts, 1), 0))
    else if cmd == "send" then send(c, Str.nth(ts, 1), Str.nth(ts, 2))
    else if cmd == "play" then play(c, Str.num(Str.nth(ts, 1), 0), Str.num(Str.nth(ts, 2), 0))
    else if cmd == "mark" then mark(c, Str.num(Str.nth(ts, 1), 0), Str.num(Str.nth(ts, 2), 0))
    else if cmd == "fav" then fav(c, Str.num(Str.nth(ts, 1), 0), Str.num(Str.nth(ts, 2), 0))
    else if cmd == "wifi" then wifi(c, Str.nth(ts, 1))
    else if cmd == "join" then join(c, sc, Str.num(Str.nth(ts, 1), 0))
    else if cmd == "raw" then raw(c, Str.nth(ts, 1), Str.nth(ts, 2), Str.restLine(line, 3))
    else if cmd == "wait" then waitMs(c, Str.num(Str.nth(ts, 1), 1000))
    else if cmd == "quit" then
      println("bye")
      going = false
    else println("? unknown command '" + cmd + "'")
    going

  // ---- snap --------------------------------------------------------------------

  /** the freshest published snapshot, printed and stashed as the numbering
   *  base. Snapshots are TAKEN from a capacity-1 cell, so draining to the last
   *  one is what "current" means. */
  def snap(c: MatrixClient): Unit =
    drainSnaps(c)
    val s = Runtime.lastSnap
    println("conn " + connName(s.connection))
    println("self " + s.selfUser.id + " " + display(s.selfUser))
    printContacts(s.contacts)
    convsC.set(s.conversations)
    printConvs(s.conversations, 1)

  def drainSnaps(c: MatrixClient): Unit =
    var going = true
    while going do
      Runtime.pollSnap(c) match
        case s: Some[StateSnapshot] => ()
        case None                   => going = false

  def connName(s: ConnectionState): String = s match
    case _: Disconnected => "disconnected"
    case _: Connecting   => "connecting"
    case _: Connected    => "connected"
    case _: Syncing      => "syncing"
    case _: ConnError    => "error"
    case _: ConnAuthRejected => "rejected"

  /** the name a person reads: the display name, or the LOCALPART of the mxid
   *  when the account has none (`Names.displayOr`, the same fallback the
   *  device client uses) — never a blank field and never the raw mxid, which
   *  the line already prints in full next to it. */
  def display(u: User): String = Names.displayOr(u.displayName, u.id)

  def printContacts(cs: List[Contact]): Unit =
    var cur = cs
    var going = true
    while going do
      cur match
        case h :: t =>
          println("contact " + h.user.id + " " + display(h.user))
          cur = t
        case Nil => going = false

  /** the recursive shape — this exact def was the THICKET-RECURSIVE-UNIT-WALK
   *  backend crash before toolchain `2083ef6`; it stays recursive as the
   *  repin's downstream proof (tail recursion emits a bounded Go loop). */
  def printConvs(cs: List[Conversation], i: scala.Int): Unit = cs match
    case h :: t =>
      println(convLine(h, i))
      printConvs(t, i + 1)
    case Nil => ()

  def convLine(cv: Conversation, i: scala.Int): String =
    var who = "-"
    if cv.hasContact then who = cv.contact.user.id
    var room = cv.roomId
    if room == "" then room = "-"
    "conv " + i + " " + convKind(cv.convType) + " " + who + " " + room +
      " msgs=" + Str.lenMsg(cv.messages) + " unplayed=" + cv.unplayedCount

  def convKind(t: ConversationType): String = t match
    case _: FamilyConv => "family"
    case _: GroupConv  => "group"
    case _: DmConv     => "dm"

  // ---- msgs --------------------------------------------------------------------

  def msgs(n: scala.Int): Unit = convAt(n) match
    case cv: Some[Conversation] => printMsgs(cv.value.messages)
    case None                   => println("? no conversation " + n)

  def convAt(n: scala.Int): Option[Conversation] =
    if n < 1 then None else Str.nthOfConv(convsC.get(), n - 1)

  def printMsgs(ms: List[VoiceMessage]): Unit =
    var cur = ms
    var i = 1
    var going = true
    while going do
      cur match
        case h :: t =>
          println(msgLine(h, i))
          i = i + 1
          cur = t
        case Nil => going = false

  def msgLine(m: VoiceMessage, i: scala.Int): String =
    "msg " + i + " " + m.sender.id +
      " dur=" + go.strconv.formatInt(m.durationMs, 10) +
      " ts=" + go.strconv.formatInt(m.timestamp, 10) +
      " played=" + Str.boolStr(m.isPlayed) +
      " peer=" + Str.boolStr(m.playedByPeer) +
      " id=" + m.id + " mxc=" + m.mxcUrl

  def msgAt(cv: Conversation, n: scala.Int): Option[VoiceMessage] =
    if n < 1 then None else Str.nthOfMsg(cv.messages, n - 1)

  // ---- send --------------------------------------------------------------------

  /** `who` is a conversation number when it parses as one, else a user id the
   *  server resolves a DM room for. Duration is derived from the container's
   *  frame count (20ms Opus frames) so a listing shows something honest. */
  def send(c: MatrixClient, who: String, file: String): Unit =
    if who == "" || file == "" then println("? send wants <conv#|user> <file.ogg>")
    else
      val ogg = readFile(file)
      if ogg.size == 0 then println("send failed: cannot read " + file)
      else sendOgg(c, who, ogg)

  def sendOgg(c: MatrixClient, who: String, ogg: Bytes): Unit =
    val n = Str.num(who, 0)
    if n > 0 then
      convAt(n) match
        case cv: Some[Conversation] => sendTo(c, cv.value.roomId, contactOf(cv.value), ogg)
        case None                   => println("? no conversation " + n)
    else sendTo(c, "", who, ogg)

  def contactOf(cv: Conversation): String =
    if cv.hasContact then cv.contact.user.id else ""

  def sendTo(c: MatrixClient, roomId: String, contactId: String, ogg: Bytes): Unit =
    if roomId == "" && contactId == "" then println("send failed: no room and no contact")
    else
      Runtime.sendAction(c, ActSendVoice(roomId, contactId, ogg, durationOf(ogg)))
      if waitSendResult(c, 30000L) then println("sent " + ogg.size)
      else println("send failed")

  /** 20ms per Opus frame; an unreadable container still sends, reported as 0. */
  def durationOf(ogg: Bytes): Long = Ogg.frameCount(ogg).toLong * 20L

  def waitSendResult(c: MatrixClient, timeoutMs: Long): Boolean =
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var res = false
    var run = true
    while run do
      if c.clock.nowUnixMillis() >= deadline then run = false
      else
        Runtime.pollEvent(c) match
          case s: Some[UiEvent] =>
            val v = sendResultOf(s.value)
            if v == 1 then
              res = true
              run = false
            else if v == 2 then run = false
          case None => c.clock.sleepMs(20L)
    res

  def sendResultOf(e: UiEvent): Int = e match
    case _: EvSendComplete => 1
    case _: EvSendFailed   => 2
    case _                 => 0

  // ---- play / mark ---------------------------------------------------------------

  /** download the Ogg through the same authenticated surface the core uses,
   *  drop it in a temp file, hand that to the player, then send the receipt.
   *  `ActPlay` is not used: a headless client routes it to an audio thread
   *  that does not exist here, and the tui needs the bytes anyway. */
  def play(c: MatrixClient, ci: scala.Int, mi: scala.Int): Unit = convAt(ci) match
    case cv: Some[Conversation] => playIn(c, cv.value, mi)
    case None                   => println("? no conversation " + ci)

  def playIn(c: MatrixClient, cv: Conversation, mi: scala.Int): Unit = msgAt(cv, mi) match
    case m: Some[VoiceMessage] => playMsg(c, cv, m.value)
    case None                  => println("? no message " + mi)

  def playMsg(c: MatrixClient, cv: Conversation, m: VoiceMessage): Unit =
    val resp = MatrixHttp.downloadMedia(hsOf(c), m.mxcUrl)
    if resp.status != 200 then println("play failed: download " + resp.status)
    else
      val ogg = Bytes.fromRawString(resp.body)
      val path = tmpPath()
      if !writeFile(path, ogg) then println("play failed: cannot write " + path)
      else
        println("play " + m.mxcUrl + " bytes=" + ogg.size + " file=" + path)
        val err = Player.run(path)
        if err == "" then println("player ok") else println("player failed: " + err)
        markMsg(c, cv, m)

  def mark(c: MatrixClient, ci: scala.Int, mi: scala.Int): Unit = convAt(ci) match
    case cv: Some[Conversation] => markIn(c, cv.value, mi)
    case None                   => println("? no conversation " + ci)

  def markIn(c: MatrixClient, cv: Conversation, mi: scala.Int): Unit = msgAt(cv, mi) match
    case m: Some[VoiceMessage] => markMsg(c, cv, m.value)
    case None                  => println("? no message " + mi)

  def markMsg(c: MatrixClient, cv: Conversation, m: VoiceMessage): Unit =
    Runtime.sendAction(c, ActReceipt(cv.roomId, m.id))
    println("marked " + m.id)

  // ---- fav ------------------------------------------------------------------------

  /** the favorite TOGGLE, called synchronously (not through the action queue)
   *  so the admin sees the resulting state rather than a fire-and-forget. The
   *  star the device draws comes from the same state event, via sync. */
  def fav(c: MatrixClient, ci: scala.Int, mi: scala.Int): Unit = convAt(ci) match
    case cv: Some[Conversation] => favIn(c, cv.value, mi)
    case None                   => println("? no conversation " + ci)

  def favIn(c: MatrixClient, cv: Conversation, mi: scala.Int): Unit = msgAt(cv, mi) match
    case m: Some[VoiceMessage] => favMsg(c, cv, m.value)
    case None                  => println("? no message " + mi)

  def favMsg(c: MatrixClient, cv: Conversation, m: VoiceMessage): Unit =
    val resp = MatrixHttp.setFavorite(hsOf(c), cv.roomId, m.id)
    if resp.status != 200 then println("fav failed: " + resp.status + " " + resp.body)
    else println("fav " + m.id + " " + Str.boolStr(MatrixHttp.parseFavorite(resp.body)))

  // ---- wifi (plan 0020) -----------------------------------------------------------

  /** the wifi panel over the server's command mailbox: queue `wifi_scan` for
   *  the target's handset, wait for the device's report, print the networks
   *  numbered. `join <n>` then picks one. The wait is skew-free: each report
   *  carries a server-stamped `seq`, so "the answer to THIS scan" is "the
   *  seq moved past what it was before the queue" — no clock comparison
   *  between two machines. */
  def wifi(c: MatrixClient, who: String): Unit =
    val target = wifiTarget(who)
    if target == "" then println("? wifi wants <conv#|user>")
    else wifiScan(c, target)

  /** a conversation number resolves to its contact; anything else is a user
   *  id (bare localpart or full mxid — the server normalizes). */
  def wifiTarget(who: String): String =
    val n = Str.num(who, 0)
    if n > 0 then wifiTargetConv(n) else who

  def wifiTargetConv(n: scala.Int): String = convAt(n) match
    case cv: Some[Conversation] => contactOf(cv.value)
    case None                   => ""

  def wifiScan(c: MatrixClient, target: String): Unit =
    val hs = hsOf(c)
    val before = reportSeq(hs, target, "wifi_scan")
    val q = MatrixHttp.request(hs, "POST", cmdPath(target), JSON, "{\"op\":\"wifi_scan\"}")
    if q.status != 200 then println("? wifi queue failed: " + q.status + " " + q.body)
    else
      println("wifi scan queued " + target)
      wifiScanWait(c, target, before)

  def wifiScanWait(c: MatrixClient, target: String, before: Long): Unit =
    val rep = awaitReport(c, target, "wifi_scan", before, 60000L)
    if !WJson.boolField(rep, "arrived") then println("wifi scan timed out")
    else wifiScanResult(target, WJson.objField(rep, "result"))

  def wifiScanResult(target: String, result: Json): Unit =
    if !WJson.boolField(result, "ok") then
      println("wifi scan failed: " + WJson.strField(result, "detail", ""))
    else
      wifiUserC.set(target)
      val nets = netsOf(WJson.getField(result, "networks"))
      wifiNetsC.set(nets)
      printNets(nets, 1)
      if Str.lenNets(nets) == 0 then println("no networks")

  def netsOf(j: Option[Json]): List[WifiNet] = j match
    case s: Some[Json] => netItems(s.value)
    case None => Nil

  def netItems(j: Json): List[WifiNet] = j match
    case a: JArr => netList(a.items, Nil)
    case _       => Nil

  def netList(xs: List[Json], acc: List[WifiNet]): List[WifiNet] = xs match
    case h :: t => netList(t, netOf(h) :: acc)
    case Nil  => reverseNets(acc, Nil)

  def reverseNets(xs: List[WifiNet], acc: List[WifiNet]): List[WifiNet] = xs match
    case h :: t => reverseNets(t, h :: acc)
    case Nil  => acc

  def netOf(j: Json): WifiNet =
    WifiNet(WJson.strField(j, "ssid", ""), WJson.longField(j, "signal", 0L),
      WJson.boolField(j, "secured"))

  def printNets(ns: List[WifiNet], i: scala.Int): Unit = ns match
    case h :: t =>
      println("net " + i + " " + h.ssid + " signal=" + go.strconv.formatInt(h.signal, 10)
        + " secured=" + Str.boolStr(h.secured))
      printNets(t, i + 1)
    case Nil => ()

  /** `join <n>`: prompt for the PSK — the NEXT stdin line, empty for an open
   *  network — then queue `wifi_join` for the last `wifi` target and wait
   *  for the device's verdict. */
  def join(c: MatrixClient, sc: go.bufio.Scanner, n: scala.Int): Unit =
    val target = wifiUserC.get()
    if target == "" then println("? no wifi scan yet")
    else netAt(n) match
      case s: Some[WifiNet] => joinNet(c, sc, target, s.value)
      case None => println("? no network " + n)

  def netAt(n: scala.Int): Option[WifiNet] =
    if n < 1 then None else Str.nthOfNet(wifiNetsC.get(), n - 1)

  def joinNet(c: MatrixClient, sc: go.bufio.Scanner, target: String, net: WifiNet): Unit =
    println("psk?")
    var psk = ""
    if sc.scan() then psk = sc.text()
    val hs = hsOf(c)
    val before = reportSeq(hs, target, "wifi_join")
    val body = joinBody(net.ssid, psk)
    val q = MatrixHttp.request(hs, "POST", cmdPath(target), JSON, body)
    if q.status != 200 then println("? wifi queue failed: " + q.status + " " + q.body)
    else
      println("wifi join queued " + net.ssid)
      joinWait(c, target, before)

  def joinBody(ssid: String, psk: String): String =
    var fs: List[(String, Json)] = Nil
    fs = ("psk", JStr(psk)) :: fs
    fs = ("ssid", JStr(ssid)) :: fs
    fs = ("op", JStr("wifi_join")) :: fs
    Json.write(JObj(fs))

  def joinWait(c: MatrixClient, target: String, before: Long): Unit =
    val rep = awaitReport(c, target, "wifi_join", before, 90000L)
    if !WJson.boolField(rep, "arrived") then println("wifi join timed out")
    else joinResult(WJson.objField(rep, "result"))

  def joinResult(result: Json): Unit =
    val detail = WJson.strField(result, "detail", "")
    if WJson.boolField(result, "ok") then println("wifi join ok " + detail)
    else println("wifi join failed: " + detail)

  // -- mailbox plumbing --

  def cmdPath(target: String): String = "/_wata/v1/cmd/" + target

  /** the current report's seq for (target, op); 0 when none has ever
   *  arrived. */
  def reportSeq(hs: Hs, target: String, op: String): Long =
    val r = MatrixHttp.request(hs, "GET", cmdPath(target) + "/report?op=" + op, JSON, "")
    if r.status != 200 then 0L
    else WJson.longField(MatrixHttp.parseOrNull(r.body), "seq", 0L)

  /** poll the report every 500 ms until its seq moves past `before` or the
   *  deadline passes. Answers `{"arrived": bool, "result": …}`. */
  def awaitReport(c: MatrixClient, target: String, op: String, before: Long, timeoutMs: Long): Json =
    val hs = hsOf(c)
    val deadline = c.clock.nowUnixMillis() + timeoutMs
    var out: List[(String, Json)] = Nil
    out = ("arrived", JBool(false)) :: out
    var run = true
    while run do
      if c.clock.nowUnixMillis() >= deadline then run = false
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

  // ---- raw ----------------------------------------------------------------------

  /** the admin escape hatch: the request goes through the same `Hs` the core
   *  uses, so it carries the bearer token and the 429 retry. */
  def raw(c: MatrixClient, method: String, path: String, body: String): Unit =
    if method == "" || path == "" then println("? raw wants <METHOD> <path> [json]")
    else
      val resp = MatrixHttp.request(hsOf(c), method, path, JSON, body)
      println("raw " + resp.status + " " + resp.body.length)
      println(resp.body)

  /** a request context on the session the sync loop logged in with. */
  def hsOf(c: MatrixClient): Hs =
    Hs(c.http, c.clock, c.cfg.homeserver, Runtime.lastAuth.accessToken)

  // ---- wait ----------------------------------------------------------------------

  /** poll snapshots for `ms`, printing a `change` line whenever the summary
   *  moves. The summary — conversation count and total unplayed — is what a
   *  script waits on; the detail is one `snap` away. */
  def waitMs(c: MatrixClient, ms: scala.Int): Unit =
    val deadline = c.clock.nowUnixMillis() + ms.toLong
    var sig = sigOf(Runtime.lastSnap)
    var run = true
    while run do
      if c.clock.nowUnixMillis() >= deadline then run = false
      else
        Runtime.pollSnap(c) match
          case s: Some[StateSnapshot] =>
            val cur = sigOf(s.value)
            if cur != sig then
              sig = cur
              println("change " + cur)
          case None => c.clock.sleepMs(20L)
    println("waited " + ms)

  def sigOf(s: StateSnapshot): String =
    "convs=" + Str.lenConv(s.conversations) + " unplayed=" + unplayedTotal(s.conversations)

  def unplayedTotal(cs: List[Conversation]): scala.Int =
    var n = 0
    var cur = cs
    var going = true
    while going do
      cur match
        case h :: t =>
          n = n + h.unplayedCount
          cur = t
        case Nil => going = false
    n

  // ---- files ---------------------------------------------------------------------

  /** the nested-assignment shape — this exact form was rejected (and, worse,
   *  in other contexts silently bypassed the catch) before toolchain
   *  `c81ed0f` (TRY-STMT-NESTED-THROWING-SHAPES); it stays nested as the
   *  repin's downstream proof that the error edge reaches this catch. */
  def readFile(path: String): Bytes =
    var out = Bytes.empty
    try out = GoBytes.toPortable(go.sys.readFile(path))
    catch case e: sgo.GoError => out = Bytes.empty
    out

  /** open 0600, truncate, write; false on any failure or short write. */
  def writeFile(path: String, b: Bytes): Boolean =
    var ok = false
    try
      val fd = go.syscall.open(path,
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      val n = go.syscall.write(fd, GoBytes.fromPortable(b))
      go.syscall.close(fd)
      ok = n == b.size
    catch case e: sgo.GoError => ok = false
    ok

  /** the scratch file the player opens. `$WATA_TUI_TMPDIR` (default `/tmp`)
   *  keeps a harness's files inside its own tree; the counter keeps successive
   *  plays in one session from overwriting each other. */
  def tmpPath(): String =
    var dir = go.sys.getenv("WATA_TUI_TMPDIR")
    if dir == "" then dir = "/tmp"
    val n = playSeqC.get() + 1
    playSeqC.set(n)
    dir + "/wata-tui-play-" + n + ".ogg"

