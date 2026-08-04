import language.experimental.saferExceptions
import sgo.add  // the Atomic[Long] add extension (the virtual clock cell)

/** The DETERMINISTIC SCRIPTED DRIVER — the ci half of the host simulator:
 *
 *    wata-fb uitest <script> <base> <user> <pass> <outdir>
 *
 *  runs one scripted session of the REAL frame loop (`Ui.frameStep`) against a
 *  live `wata-server`, driving input from a text script and dumping PNG
 *  checkpoints that `tools/fb-ui-tests.py` byte-compares against the goldens in
 *  `tools/fb-ui-golden/`. Prints `UITEST PASS <script>` or
 *  `UITEST FAIL <script>: <reason>`.
 *
 *  WHAT IS VIRTUAL AND WHAT IS NOT. The UI loop's clock is virtual: `ScriptClock`
 *  advances by exactly one frame per read, so every frame's `dt` is 33ms of
 *  simulated time no matter what the host was doing. That is what makes the
 *  time-dependent pixels — the PTT hold counter, the send/play status flash —
 *  reproducible. The CLIENT keeps the real clock and the real network: sync is a
 *  genuine long-poll against a genuine server, so a `wait` directive advances
 *  frames (each pausing `PACE_MS` of real time) until the snapshot catches up.
 *  A checkpoint is therefore taken from a SETTLED state — wait for the thing,
 *  then advance past every animation, then dump.
 *
 *  ONE SESSION PER RUN. `wataclient`'s Runtime is a single-client-per-process
 *  engine, so a two-user scenario is two script files run in sequence against
 *  the same server, exactly as `integ.scala` breaks its scenarios into
 *  sequential phases. The harness owns that sequencing.
 *
 *  SCRIPT LANGUAGE — one directive per line, `#` comments and blank lines
 *  ignored:
 *
 *    family <peerUserId>       create the `#family:` room out of band (a
 *                              direct login + createRoom, bypassing the UI):
 *                              the bootstrap that gives both users a
 *                              conversation to talk in
 *    advance <n>               run n frames
 *    idle <n>                  run n frames with NO real pause between them —
 *                              a timer expiring needs simulated time, not
 *                              network progress, so the screensaver's minutes
 *                              cost nothing
 *    tap <key>                 press + release <key>, then one frame
 *    key <key> <press|release> one edge of <key>, then one frame (PTT needs
 *                              its press and release separated)
 *    wait <probe> <n> <frames> advance until probe >= n, or fail after
 *                              <frames> frames
 *    waitmax <probe> <n> <frames>  the mirror — advance until probe <= n
 *                              (what a redaction needs: a shrinking count
 *                              already satisfies `wait`)
 *    expect <probe> <n>        fail unless probe >= n right now
 *    checkpoint <name>         write <outdir>/<name>.png from the live frame
 *
 *  keys: up down left right enter back ptt dot1 dot2 f2 (input.scala's names).
 *  probes: syncing (1 once the sync loop is live), convs (conversation count),
 *  msgs (messages in the conversation the wata applet is pointing at), played
 *  (of those, how many are marked played), nameset (1 once the self user's
 *  display name equals the settings applet's currently picked preset),
 *  screenoff (1 while the screensaver has the panel blanked). */

/** the virtual frame clock: one frame of simulated time per read, so `dt` is
 *  constant and the animated pixels are reproducible. Only the UI loop uses
 *  it — the client runtime keeps the real clock, or its long-poll backoff and
 *  its wait helpers would spin. */
class ScriptClock extends Clock:
  def nowUnixMillis(): Long = UiScript.tick()
  def sleepMs(ms: Long): Unit = ()

/** the scripted `UiDevice`: input comes from the script's injection cell, and
 *  there is no display to present to (the driver encodes the pixel buffer
 *  itself at a checkpoint). The frame pace is REAL time, small — it is what
 *  lets the live sync loop make progress between frames. */
final class ScriptDevice extends UiDevice:
  def pollInput(): KeyBatch = KeyBatch(UiScript.takeKeys())
  def present(px: go.Bytes): Unit = ()
  def leds(green: Boolean, red: Boolean): Unit = ()
  def backlight(level: scala.Int): Unit = ()
  def buttonBacklight(on: Boolean): Unit = ()
  def frameSleep(ms: Long): Unit = FbCaps.sleepMs(UiScript.pace())

object UiScript:

  /** real milliseconds a scripted frame pauses, so the live sync loop can make
   *  progress while a `wait` spins frames. */
  val PACE_MS: Long = 10L

  // `val`-held Atomic cells (the UI goroutine's own, like Ui's).
  private val clkC: sgo.Atomic[Long] = sgo.atomic(0L)
  // the real pause a scripted frame takes. `PACE_MS` normally, 0 while an
  // `idle` directive is burning simulated time (see `idle`).
  private val paceC: sgo.Atomic[Long] = sgo.atomic(PACE_MS)
  private val pendC: sgo.Atomic[List[KeyEvent]] = sgo.atomic(Nil)
  private val outC: sgo.Atomic[String] = sgo.atomic("")
  private val baseC: sgo.Atomic[String] = sgo.atomic("")
  private val userC: sgo.Atomic[String] = sgo.atomic("")
  private val passC: sgo.Atomic[String] = sgo.atomic("")

  /** the real pause `ScriptDevice.frameSleep` takes this frame. */
  def pace(): Long = paceC.get()

  /** the virtual clock read: advance one frame, return the new time. */
  def tick(): Long = clkC.add(Ui.FRAME_MS)

  /** take the events the script injected for this frame. */
  def takeKeys(): List[KeyEvent] = ListOps.reverse(pendC.getAndSet(Nil))

  def inject(ev: KeyEvent): Unit = pendC.set(ev :: pendC.get())

  // ---- entry -----------------------------------------------------------------

  def run(args: Array[String]): Unit =
    if args.length < 6 then
      println("uitest: want  wata-fb uitest <script> <base> <user> <pass> <outdir>")
    else runScript(args(1), args(2), args(3), args(4), args(5))

  def runScript(path: String, base: String, user: String, pass: String, outdir: String): Unit =
    var body = ""
    try
      val raw = go.sys.readFile(path)
      body = go.string(raw)
    catch case e: sgo.GoError => body = ""
    if body == "" then println("UITEST FAIL " + path + ": cannot read script")
    else
      clkC.set(0L)
      pendC.set(Nil)
      outC.set(outdir)
      // `-` in a credential slot means "resume from the config store" — the
      // arguments are positional, so an unset one still needs a spelling. The
      // resolved values are what the out-of-band `family` bootstrap logs in
      // with, so a resuming phase cannot also bootstrap (it has no password),
      // which is the intended shape: bootstrap once, resume afterwards.
      val cfg = FbConfig.resolve(base, user, pass, 1000)
      baseC.set(cfg.homeserver)
      userC.set(cfg.username)
      passC.set(cfg.password)
      Ui.resetCells()
      val lines = splitLines(body)
      val clock = ScriptClock()
      val c = Runtime.makeWithAudio(cfg, FbCaps.httpDo(), FbCaps.clock())
      val dev = ScriptDevice()
      val px = Draw.newBuffer()
      // the error string rides OUT of the scope as supervised's value (the same
      // pattern devcli/integ use for their Boolean results).
      val err = sgo.supervised {
        val evts = sgo.makeChan[AudioEvt](16)
        // hoist the command Chan out of the fork — the body needs only the
        // channel (a synchronizer), not the whole client record.
        val audioCmds = c.audioCmds
        sgo.fork(SimAudio.mainLoop(audioCmds, evts))
        Runtime.start(c)
        val e = execAll(lines, c, clock, evts, dev, px)
        c.audioCmds.send(AcQuit())
        Runtime.stopClient(c)
        e
      }
      if err == "" then println("UITEST PASS " + path)
      else println("UITEST FAIL " + path + ": " + err)

  /** run every line; the FIRST failure short-circuits the rest (later lines are
   *  read but not executed, so the run still winds the client down cleanly). */
  def execAll(lines: List[String], c: MatrixClient, clock: Clock,
              evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    Ui.beginFrames(clock)
    var err = ""
    var cur = lines
    var going = true
    while going do
      cur match
        case h :: t =>
          if err == "" then err = execLine(h, c, clock, evts, dev, px)
          cur = t
        case Nil => going = false
    err

  def execLine(line: String, c: MatrixClient, clock: Clock,
               evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    val ts = splitWs(line)
    val cmd = nth(ts, 0)
    var err = ""
    if cmd == "" || cmd.startsWith("#") then ()
    else if cmd == "advance" then
      advance(num(nth(ts, 1), 1), c, clock, evts, dev, px)
    else if cmd == "idle" then
      idle(num(nth(ts, 1), 1), c, clock, evts, dev, px)
    else if cmd == "tap" then
      err = tap(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "key" then
      err = key(nth(ts, 1), nth(ts, 2), c, clock, evts, dev, px)
    else if cmd == "wait" then
      err = waitFor(nth(ts, 1), num(nth(ts, 2), 1), num(nth(ts, 3), 600),
        c, clock, evts, dev, px)
    else if cmd == "waitmax" then
      err = waitMax(nth(ts, 1), num(nth(ts, 2), 0), num(nth(ts, 3), 600),
        c, clock, evts, dev, px)
    else if cmd == "expect" then
      err = expect(nth(ts, 1), num(nth(ts, 2), 1))
    else if cmd == "checkpoint" then
      err = checkpoint(nth(ts, 1), px)
    else if cmd == "family" then
      err = family(nth(ts, 1))
    else err = "unknown directive '" + cmd + "'"
    err

  // ---- the frame steps ---------------------------------------------------------

  /** one frame; a quit edge inside a scripted run is not special — the script
   *  says when the session ends. */
  def step(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt],
           dev: UiDevice, px: go.Bytes): Unit =
    val quit = Ui.frameStep(c, clock, evts, dev, px)
    ()

  def advance(n: scala.Int, c: MatrixClient, clock: Clock,
              evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): Unit =
    var i = 0
    while i < n do
      step(c, clock, evts, dev, px)
      i = i + 1

  /** frames with the real pause switched off: the UI clock still advances a
   *  frame each step, so a timer measured in simulated seconds expires, but
   *  nothing waits on the network. Only safe when the script is waiting on a
   *  TIMER rather than on something arriving. */
  def idle(n: scala.Int, c: MatrixClient, clock: Clock,
           evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): Unit =
    paceC.set(0L)
    advance(n, c, clock, evts, dev, px)
    paceC.set(PACE_MS)

  def tap(name: String, c: MatrixClient, clock: Clock,
          evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var err = key(name, "press", c, clock, evts, dev, px)
    if err == "" then err = key(name, "release", c, clock, evts, dev, px)
    err

  def key(name: String, state: String, c: MatrixClient, clock: Clock,
          evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    val k = keyOf(name)
    var err = ""
    if !Evdev.known(k) then err = "unknown key '" + name + "'"
    else if state != "press" && state != "release" then
      err = "key wants press|release, got '" + state + "'"
    else
      inject(KeyEvent(k, stateOf(state)))
      step(c, clock, evts, dev, px)
    err

  def waitFor(name: String, want: scala.Int, maxFrames: scala.Int, c: MatrixClient,
              clock: Clock, evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var i = 0
    var ok = probe(name) >= want
    while !ok && i < maxFrames do
      step(c, clock, evts, dev, px)
      ok = probe(name) >= want
      i = i + 1
    var err = ""
    if !ok then
      err = "wait " + name + " >= " + want + " timed out after " + maxFrames +
        " frames (saw " + probe(name) + ")"
    err

  /** the mirror of `wait`: advance until a probe DROPS to `want` or below.
   *  A redaction is only observable this way — the count shrinking already
   *  satisfies a `wait`, which tests for `>=`. */
  def waitMax(name: String, want: scala.Int, maxFrames: scala.Int, c: MatrixClient,
              clock: Clock, evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var i = 0
    var ok = probe(name) <= want
    while !ok && i < maxFrames do
      step(c, clock, evts, dev, px)
      ok = probe(name) <= want
      i = i + 1
    var err = ""
    if !ok then
      err = "waitmax " + name + " <= " + want + " timed out after " + maxFrames +
        " frames (saw " + probe(name) + ")"
    err

  def expect(name: String, want: scala.Int): String =
    val got = probe(name)
    var err = ""
    if got < want then err = "expect " + name + " >= " + want + ", got " + got
    err

  // ---- checkpoints ---------------------------------------------------------------

  /** dump the live pixel buffer as a PNG the harness byte-compares. Same
   *  encoder as `fbdump`, so the golden contract is the one already in ci —
   *  now over the real applet UI instead of a fixed test pattern. */
  //  NB the failure cell here is NOT called `err`: the emitter names the Go
  //  error variable of a `catch` `err`, so an enclosing Scala `var err` assigned
  //  from the catch arm silently becomes an assignment to the Go `error` and the
  //  build fails on the type.
  def checkpoint(name: String, px: go.Bytes): String =
    var bad = ""
    if name == "" then bad = "checkpoint wants a name"
    else
      val path = outC.get() + "/" + name + ".png"
      val png = Png.encode(px)
      try
        val fd = go.syscall.open(path,
          go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 420)
        go.syscall.write(fd, go.bytes(png.rawString))
        go.syscall.close(fd)
      catch case e: sgo.GoError => bad = "cannot write " + path + ": " + e.message
    bad

  // ---- the family-room bootstrap ---------------------------------------------------

  /** create `#family:<server>` with the peer invited, by direct Matrix HTTP
   *  outside the client runtime — the same out-of-band setup `integ.scala`'s
   *  family scenario does. Both users then have the family conversation AND,
   *  through the family roster, a roomless DM row for each other, which is what
   *  gives the scripted UI something to select and send into. */
  def family(peerUserId: String): String =
    var err = ""
    if peerUserId == "" then err = "family wants a peer user id"
    else
      val anon = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), "")
      val lr = MatrixHttp.login(anon, userC.get(), passC.get())
      if lr.status != 200 then err = "family: login status " + lr.status
      else
        val tok = Matrix.parseLogin(MatrixHttp.parseOrNull(lr.body)).accessToken
        if tok == "" then err = "family: no access token"
        else
          val hs = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), tok)
          val cr = MatrixHttp.createRoomWithAlias(hs, "family", peerUserId)
          if cr.status != 200 then err = "family: createRoom status " + cr.status
    err

  // ---- probes ------------------------------------------------------------------------

  def probe(name: String): scala.Int =
    if name == "syncing" then syncingProbe()
    else if name == "convs" then WataLogic.convCount(Ui.frameSnap)
    else if name == "msgs" then WataLogic.msgCount(Ui.frameSnap, curConvIdx())
    else if name == "played" then playedCount(Ui.frameSnap, curConvIdx())
    else if name == "nameset" then nameSetProbe()
    else if name == "screenoff" then boolProbe(Ui.screenOff)
    else -1

  def boolProbe(b: Boolean): scala.Int =
    var out = 0
    if b then out = 1
    out

  /** 1 once the self user's display name in the snapshot equals the preset the
   *  settings applet is currently pointing at — the round trip of `OK` on the
   *  name item, which has no count to wait on. */
  def nameSetProbe(): scala.Int =
    val snap = Ui.frameSnap
    val want = SettingsLogic.displayName(Shell.settingsState(Ui.shellState).nameIdx)
    var out = 0
    if snap.hasSelfUser && snap.selfUser.displayName == want then out = 1
    out

  def syncingProbe(): scala.Int =
    var out = 0
    if Runtime.connTag(Ui.connection) == 3 then out = 1
    out

  /** the conversation the wata applet is pointing at — the open one in the
   *  conversation view, the selected one in the contact list. */
  def curConvIdx(): scala.Int = WataLogic.convIdxForSend(Shell.wataState(Ui.shellState))

  def playedCount(snap: StateSnapshot, idx: scala.Int): scala.Int =
    WataLogic.convAt(snap, idx) match
      case c: Some[Conversation] => playedIn(c.value.messages)
      case None => 0

  def playedIn(ms: List[VoiceMessage]): scala.Int =
    var n = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.isPlayed then n = n + 1
          cur = t
        case Nil => going = false
    n

  // ---- key names ------------------------------------------------------------------

  def keyOf(name: String): Key =
    if name == "up" then KUp()
    else if name == "down" then KDown()
    else if name == "left" then KLeft()
    else if name == "right" then KRight()
    else if name == "enter" then KEnter()
    else if name == "back" then KBack()
    else if name == "ptt" then KPtt()
    else if name == "dot1" then KDot1()
    else if name == "dot2" then KDot2()
    else if name == "f2" then KF2()
    else KUnknown()

  def stateOf(s: String): KeyState =
    var out: KeyState = Released()
    if s == "press" then out = Pressed()
    out

  // ---- the tiny script lexer ---------------------------------------------------

  def splitLines(body: String): List[String] =
    splitOn(body, "\n")

  def splitWs(line: String): List[String] =
    var acc: List[String] = Nil
    var cur = ""
    var i = 0
    while i < line.length do
      val ch = line.substring(i, i + 1)
      if ch == " " || ch == "\t" || ch == "\r" then
        if cur != "" then acc = cur :: acc
        cur = ""
      else cur = cur + ch
      i = i + 1
    if cur != "" then acc = cur :: acc
    ListOps.reverse(acc)

  def splitOn(s: String, sep: String): List[String] =
    var acc: List[String] = Nil
    var rest = s
    var going = true
    while going do
      val at = rest.indexOf(sep)
      if at < 0 then
        acc = rest :: acc
        going = false
      else
        acc = rest.substring(0, at) :: acc
        rest = rest.substring(at + sep.length)
    ListOps.reverse(acc)

  def nth(xs: List[String], i: scala.Int): String =
    if i < 0 then ""
    else xs match
      case h :: t => nthStep(h, t, i)
      case Nil => ""

  def nthStep(h: String, t: List[String], i: scala.Int): String =
    if i == 0 then h else nth(t, i - 1)

  /** decimal parse with a fallback (no exception surface needed here). */
  def num(s: String, dflt: scala.Int): scala.Int =
    var out = 0
    var ok = s.length > 0
    var i = 0
    while i < s.length do
      val d = digit(s.substring(i, i + 1))
      if d < 0 then ok = false
      else out = out * 10 + d
      i = i + 1
    var res = dflt
    if ok then res = out
    res

  def digit(ch: String): scala.Int =
    if ch == "0" then 0
    else if ch == "1" then 1
    else if ch == "2" then 2
    else if ch == "3" then 3
    else if ch == "4" then 4
    else if ch == "5" then 5
    else if ch == "6" then 6
    else if ch == "7" then 7
    else if ch == "8" then 8
    else if ch == "9" then 9
    else -1
