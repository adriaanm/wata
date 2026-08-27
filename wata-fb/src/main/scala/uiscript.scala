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
 *    group <name> [member ...] mint a group out of band (a direct login +
 *                              POST /_wata/v1/group as the phase user): the
 *                              server stamps the room and joins the members —
 *                              no script creates the FAMILY room, because the
 *                              server mints that at boot with every account
 *                              joined (plan 0018)
 *    advance <n>               run n frames
 *    idle <n>                  run n frames with NO real pause between them —
 *                              a timer expiring needs simulated time, not
 *                              network progress, so the screensaver's minutes
 *                              cost nothing. CAVEAT: an idle that crosses the
 *                              screen-timeout budget blanks the panel (a later
 *                              checkpoint then pins a stale frame) and the
 *                              next tap is EATEN as the wake key — burn long
 *                              waits in chunks under the threshold with an
 *                              applet-ignored key between, or manage the wake
 *                              explicitly (alice-kid-settings.txt's timeout
 *                              leg is the template)
 *    tap <key>                 press + release <key>, then one frame
 *    key <key> <press|release|repeat> one edge of <key>, then one frame (PTT
 *                              its press and release separated)
 *    wait <probe> <n> <frames> advance until probe >= n, or fail after
 *                              <frames> frames
 *    waitmax <probe> <n> <frames>  the mirror — advance until probe <= n
 *                              (what a redaction needs: a shrinking count
 *                              already satisfies `wait`)
 *    expect <probe> <n>        fail unless probe >= n right now
 *    checkpoint <name>         write <outdir>/<name>.png from the live frame
 *    failnext <n> [status]     arm the server's WATA_TEST_HOOKS=1
 *                              fail-on-demand counter (an out-of-band POST,
 *                              like `family`): the next n media
 *                              uploads/downloads answer status (default 500)
 *                              — how the SEND FAILED / PLAY FAILED flashes
 *                              get provoked; a 4xx provokes the outbox's
 *                              UNDELIVERABLE drop (the red "not sent" band)
 *    conn <state>              force the connection every later frame reports
 *                              (off|connecting|connected|syncing|error, or
 *                              `live` to hand it back to the client), then one
 *                              frame. The sync loop republishes `Syncing` on
 *                              every snapshot, so a bad-connection frame
 *                              cannot be pinned any other way
 *    netpipe <pipe>            force the connectivity element's interface pipe
 *                              (wifi|cell|none|auto), then one frame — the
 *                              device-only renderings (the wifi and cellular
 *                              glyphs, `OFF`) pinned without faking
 *                              interfaces under `Diag`, which would make the
 *                              goldens depend on the host's network
 *    enrolid <nodeId> <nonce>  pin the identity the enrolment QR encodes —
 *                              both halves are otherwise minted (a fresh key,
 *                              the wall clock), so a goldened QR needs them
 *                              fixed. The admin URL comes from the harness's
 *                              `WATA_ADMIN_URL`
 *    enrolstate <state>        force the not-allowlisted verdict
 *                              (refused|ok|auto), then one frame — the real
 *                              one comes from an iroh dial refusal, which a
 *                              hermetic run cannot provoke
 *    caplevel <n>              post `AeCaptureLevel(n)` (0..32) through the
 *                              real audio-event mailbox, then one frame — the
 *                              recording meter pinned at a known level.
 *                              SimAudio's recording is instantaneous by
 *                              design, so a script injects the tick a real
 *                              record loop would post
 *    notifymode <play|quiet>   force the arrival-notification mode's cell
 *                              (plan 0041) with no config I/O — what a
 *                              notify leg sets instead of walking Settings
 *    fakeradios <on|off>       flip Diag's WATA_FAKE_RADIOS seam (plan
 *                              0056): guarded radio commands answer ""
 *                              without running anything — the off-device
 *                              way into the kid data row's applying state
 *    charge <bad|ok|auto>      force the charge-anomaly READ (plan 0073) —
 *                              the debounce still runs, which is the point:
 *                              the scenario pins the mark staying OFF for
 *                              the whole 3-minute window. `auto` hands the
 *                              read back to sysfs (false on every host)
 *    sendas <user> [durMs]     send one voice message into the FAMILY room
 *                              as <user>, out of band (a direct login +
 *                              upload + send with the phase's password, like
 *                              `group`) — the only way a scripted session
 *                              sees a mid-session ARRIVAL, since its own
 *                              sends never raise its unplayed counts; the
 *                              optional duration (default 1000 ms) is
 *                              metadata, what the bar-length claims vary
 *
 *  keys: up down left right enter back ptt dot1 dot2 f2 (input.scala's names).
 *  probes: syncing (1 once the sync loop is live), convs (conversation count),
 *  msgs (messages in the conversation the wata applet is pointing at), played
 *  (of those, how many are marked played), peer (of those, how many a
 *  non-sender has receipted — the sent-message second check), favs (of those, how many carry the
 *  server's favorite marker), screenoff (1 while the screensaver has the panel blanked), sendfail /
 *  playfail (the session's failed-send / failed-play tallies — what a script
 *  waits on after provoking a failure with `failnext`), connerr (the
 *  session's `ConnError` transitions), conntag (the connection the frames
 *  report, `Runtime.connTag` — 4 = error, 5 = auth rejected), logins (the
 *  client's login/resume attempts, which is how a script sees the retry loop
 *  turning and a retry-now poke landing), quitarm (1 while the two-step quit
 *  is armed), unsent / undeliv (conversations carrying an outbox marker: a
 *  send still queued, or one the server refused), frames (the session's frame
 *  counter — what a script watches to prove the frame loop is not blocked),
 *  and the arrival-notification probes (plan 0041): unplayed (the summed
 *  unplayed counts the LED/banner/highlight all derive from), notifyled (the
 *  green LED's computed policy: 0 off, 1 steady, 2 blinking), notifyred (1
 *  while the arbiter holds the red LED on), notifybanner (1 while the quiet
 *  banner is up), caplevel (the wata applet's recording-meter level, what
 *  the `caplevel` directive set), msgsel (the conversation view's message
 *  cursor index — how a script observes the event-id anchoring across an
 *  arrival), and the kid-settings probes (plan 0053): applet (the shell's
 *  active applet index — 3 while the developer panel is open), kidrow (the
 *  kid panel's selected row, 4 = the hidden development row), kidtarget
 *  (the data row's pending tri-state target, shifted non-negative for the
 *  unsigned script parser: 0 none, 1 off, 2 wifi, 3 cell), kidapply
 *  (the applying wait's frame count, same shift: 0 = not applying), chargebad
 *  (plan 0073: 1 while the debounced charge-anomaly mark is up), and the
 *  motion-pump probes (plan 0077): motioncentre (the rolodex integrator's
 *  centre index against the live conversation count — the selection itself,
 *  since the pump writes it into `selected`), motionlive (1 while the
 *  integrator is still moving), and the rolodex-emphasis pixel probes
 *  (stage 4): rollcw/rollnw (lit pixels on a scanline through the centre
 *  band's middle / through the row above it) and rollcl/rollnl (the same
 *  scanlines' summed brightness /16) — what pins "the centre card is wider
 *  and brighter than a neighbour" as numbers that fail when the emphasis is
 *  inverted. */

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
  def pollInput(): List[KeyEvent] = UiScript.takeKeys()
  def present(px: go.Bytes): Unit = ()
  def leds(green: Boolean, red: Boolean): Unit = ()
  def backlight(level: scala.Int): Unit = ()
  def unblank(): Unit = ()
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
      val c = Runtime.makeWithAudioStored(cfg, FbCaps.httpDo(), FbCaps.clock(), FbConfig.outbox())
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
      err = expect(nth(ts, 1), num(nth(ts, 2), 1), px)
    else if cmd == "checkpoint" then
      err = checkpoint(nth(ts, 1), px)
    else if cmd == "group" then
      err = group(nth(ts, 1), restOf(restOf(ts)))
    else if cmd == "failnext" then
      err = failNext(num(nth(ts, 1), 1), num(nth(ts, 2), 500))
    else if cmd == "conn" then
      err = connDirective(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "netpipe" then
      err = pipeDirective(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "enrolid" then
      err = enrolIdDirective(nth(ts, 1), nth(ts, 2))
    else if cmd == "enrolstate" then
      err = enrolStateDirective(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "notifymode" then
      err = notifyModeDirective(nth(ts, 1))
    else if cmd == "fakeradios" then
      err = fakeRadiosDirective(nth(ts, 1))
    else if cmd == "charge" then
      err = chargeDirective(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "caplevel" then
      err = capLevelDirective(nth(ts, 1), c, clock, evts, dev, px)
    else if cmd == "sendas" then
      err = sendAs(nth(ts, 1), nth(ts, 2))
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
    else if state != "press" && state != "release" && state != "repeat" then
      err = "key wants press|release|repeat, got '" + state + "'"
    else
      inject(KeyEvent(k, stateOf(state)))
      step(c, clock, evts, dev, px)
    err

  def waitFor(name: String, want: scala.Int, maxFrames: scala.Int, c: MatrixClient,
              clock: Clock, evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var i = 0
    var ok = probe(name, px) >= want
    while !ok && i < maxFrames do
      step(c, clock, evts, dev, px)
      ok = probe(name, px) >= want
      i = i + 1
    var err = ""
    if !ok then
      err = "wait " + name + " >= " + want + " timed out after " + maxFrames +
        " frames (saw " + probe(name, px) + diag() + ")"
    err

  /** the mirror of `wait`: advance until a probe DROPS to `want` or below.
   *  A redaction is only observable this way — the count shrinking already
   *  satisfies a `wait`, which tests for `>=`. */
  def waitMax(name: String, want: scala.Int, maxFrames: scala.Int, c: MatrixClient,
              clock: Clock, evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var i = 0
    var ok = probe(name, px) <= want
    while !ok && i < maxFrames do
      step(c, clock, evts, dev, px)
      ok = probe(name, px) <= want
      i = i + 1
    var err = ""
    if !ok then
      err = "waitmax " + name + " <= " + want + " timed out after " + maxFrames +
        " frames (saw " + probe(name, px) + diag() + ")"
    err

  /** the wait-timeout postmortem: connection tag + the session's send/conn
   *  tallies, so a timeout log already says whether the send failed, sync hit
   *  error backoff, or the echo simply never arrived. */
  def diag(): String =
    "; conn=" + Runtime.connTag(Ui.connection) + " sendok=" + Ui.sendOks +
      " sendfail=" + Ui.sendFails + " playfail=" + Ui.playFails +
      " connerr=" + Ui.connErrs

  def expect(name: String, want: scala.Int, px: go.Bytes): String =
    val got = probe(name, px)
    var err = ""
    if got < want then err = "expect " + name + " >= " + want + ", got " + got
    err

  // ---- the connectivity overrides ------------------------------------------------

  /** force the connection state the frames report, then advance one frame so
   *  the forced state is what the next checkpoint draws. The frame also
   *  restarts the reconnecting animation's phase (a health change resets it),
   *  which is what makes the `..` alternation reproducible from a script. */
  def connDirective(name: String, c: MatrixClient, clock: Clock,
                    evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    val tag = connTagOf(name)
    var err = ""
    if tag == -2 then err = "conn wants off|connecting|connected|syncing|error|live"
    else
      Ui.forceConn(tag)
      step(c, clock, evts, dev, px)
    err

  def connTagOf(name: String): scala.Int =
    if name == "live" then -1
    else if name == "off" then 0
    else if name == "connecting" then 1
    else if name == "connected" then 2
    else if name == "syncing" then 3
    else if name == "error" then 4
    else if name == "authrejected" then 5
    else -2

  /** force the interface pipe, then advance one frame. */
  def pipeDirective(name: String, c: MatrixClient, clock: Clock,
                    evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    val tag = pipeTagOf(name)
    var err = ""
    if tag == -2 then err = "netpipe wants wifi|cell|none|auto"
    else
      NetStatus.forcePipe(tag)
      step(c, clock, evts, dev, px)
    err

  def pipeTagOf(name: String): scala.Int =
    if name == "auto" then -1
    else if name == "wifi" then NetStatus.P_WIFI
    else if name == "cell" then NetStatus.P_CELL
    else if name == "none" then NetStatus.P_NONE
    else if name == "net" then NetStatus.P_UNKNOWN
    else -2

  // ---- the enrolment overrides ------------------------------------------------------

  /** pin the identity the enrolment QR encodes. The code is a pure function of
   *  node id + nonce + admin URL, and the first two are minted (a fresh key,
   *  the wall clock) — so without this a goldened QR frame would differ every
   *  run. The admin URL is the harness's, through `WATA_ADMIN_URL`. */
  def enrolIdDirective(id: String, n: String): String =
    var err = ""
    if id == "" || n == "" then err = "enrolid wants <nodeId> <nonce>"
    else Enrol.force(id, n)
    err

  /** force the not-allowlisted verdict, then advance one frame so the forced
   *  state is what the next checkpoint draws. `auto` hands it back to the
   *  transport. The real verdict comes from an iroh dial refusal, which a
   *  hermetic scripted run has no way to provoke. */
  def enrolStateDirective(name: String, c: MatrixClient, clock: Clock,
                          evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    val tag = enrolTagOf(name)
    var err = ""
    if tag == -2 then err = "enrolstate wants refused|ok|auto"
    else
      Enrol.forceRefused(tag)
      step(c, clock, evts, dev, px)
    err

  def enrolTagOf(name: String): scala.Int =
    if name == "auto" then -1
    else if name == "ok" then 0
    else if name == "refused" then 1
    else -2

  // ---- the arrival-notification overrides (plan 0041) --------------------------

  /** force the notify-mode CELL, no config I/O — the mode is device config,
   *  and a script that wants quiet mode should not have to walk Settings or
   *  write a file another phase would then resume into. */
  def notifyModeDirective(name: String): String =
    var err = ""
    if name != Notify.MODE_PLAY && name != Notify.MODE_CHIME && name != Notify.MODE_QUIET then
      err = "notifymode wants play|chime|quiet"
    else FbConfig.forceNotifyMode(Notify.parseMode(name))
    err

  /** flip `Diag`'s WATA_FAKE_RADIOS seam (plan 0056): with it on, the
   *  guarded radio commands answer "" without running anything, which is
   *  the only off-device way into the kid data row's APPLYING state — the
   *  sim's honest answer is "not on device", the report arm. Script-local
   *  so ONE scenario pins the report arm first and the applying arms
   *  after. */
  def fakeRadiosDirective(name: String): String =
    var err = ""
    if name == "on" then Diag.setFakeRadios(true)
    else if name == "off" then Diag.setFakeRadios(false)
    else err = "fakeradios wants on|off"
    err

  /** force the charge-anomaly READ (plan 0073), then advance one frame so the
   *  forced verdict is what the next poll counts. Deliberately upstream of
   *  the debounce — the mark must still take DEBOUNCE_FRAMES of `bad` to
   *  arm, which is exactly what the charge-anomaly scenario pins. `auto`
   *  hands the read back to sysfs (false on every host). */
  def chargeDirective(name: String, c: MatrixClient, clock: Clock,
                      evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var err = ""
    if name == "bad" then ChargeStatus.forceRead(1)
    else if name == "ok" then ChargeStatus.forceRead(0)
    else if name == "auto" then ChargeStatus.forceRead(-1)
    else err = "charge wants bad|ok|auto"
    if err == "" then step(c, clock, evts, dev, px)
    err

  /** post one capture-level tick through the REAL audio-event mailbox — the
   *  same channel the frame loop drains — then advance one frame so the
   *  drained level is what the next checkpoint draws. SimAudio's recording is
   *  deliberately instantaneous (fixed RecordMs, byte-reproducible frames),
   *  so the meter is pinned by injecting the tick a real record loop posts at
   *  25 Hz (plan 0042). */
  def capLevelDirective(arg: String, c: MatrixClient, clock: Clock,
                        evts: sgo.Chan[AudioEvt], dev: UiDevice, px: go.Bytes): String =
    var err = ""
    val n = num(arg, -1)
    if arg == "" || n < 0 || n > 32 then err = "caplevel wants <0..32>"
    else
      evts.trySend(AeCaptureLevel(n))
      step(c, clock, evts, dev, px)
    err

  /** txn ids for `sendas` sends — each direct login is its own device, so
   *  these only have to be distinct within one script. */
  private val txnC: sgo.Atomic[scala.Int] = sgo.atomic(9000)

  /** one out-of-band voice message into the FAMILY room as `user` (the
   *  phase's password — the harness gives every account the same one): a
   *  direct login + media upload + send, outside the client runtime, exactly
   *  the `group` shape. This is what makes a mid-session ARRIVAL scriptable:
   *  the frame loop's own sends never raise its unplayed counts. The family
   *  room id comes from the live snapshot, so the script must have waited
   *  for `convs` first. An optional second token is the reported duration in
   *  ms (default 1000) — durations only differ in metadata here, which is
   *  exactly what the normalised bar-length claims need. */
  def sendAs(user: String, durStr: String): String =
    if user == "" then "sendas wants a <user>"
    else
      val room = familyRoom(Ui.frameSnap)
      if room == "" then "sendas: no family room in the snapshot yet"
      else sendAsInto(user, room, num(durStr, 1000).toLong)

  def sendAsInto(user: String, room: String, durMs: Long): String =
    val anon = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), "")
    val lr = MatrixHttp.login(anon, user, passC.get())
    if lr.status != 200 then "sendas: login status " + lr.status
    else
      val tok = Matrix.parseLogin(MatrixHttp.parseOrNull(lr.body)).accessToken
      if tok == "" then "sendas: no access token"
      else sendVoiceAs(Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), tok), room, durMs)

  def sendVoiceAs(hs: Hs, room: String, durMs: Long): String =
    val ogg = Integ.fakeOgg()
    val up = MatrixHttp.uploadMedia(hs, ogg)
    if up.status != 200 then "sendas: upload status " + up.status
    else
      val mxc = MatrixHttp.parseMxcUrl(up.body)
      if mxc == "" then "sendas: no content_uri"
      else
        val resp = MatrixHttp.sendVoiceMessage(hs, room, mxc, durMs, ogg.size, txnC.add(1))
        if resp.status != 200 then "sendas: send status " + resp.status
        else ""

  def familyRoom(snap: StateSnapshot): String =
    Integ.findFamilyConv(snap.conversations) match
      case c: Some[Conversation] => c.value.roomId
      case None                  => ""

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

  // ---- the group bootstrap ---------------------------------------------------------

  /** mint a group through the dialect endpoint, as the phase user, by direct
   *  Matrix HTTP outside the client runtime (the same out-of-band shape
   *  `integ.scala`'s group scenario uses). The FAMILY room needs no script
   *  step: the server mints it at boot with every account joined. */
  def group(name: String, members: List[String]): String =
    if name == "" then "group wants a name"
    else
      var err = ""
      val anon = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), "")
      val lr = MatrixHttp.login(anon, userC.get(), passC.get())
      if lr.status != 200 then err = "group: login status " + lr.status
      else
        val tok = Matrix.parseLogin(MatrixHttp.parseOrNull(lr.body)).accessToken
        if tok == "" then err = "group: no access token"
        else
          val hs = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), tok)
          val resp = MatrixHttp.request(hs, "POST", "/_wata/v1/group",
            "application/json", groupBody(name, members))
          if resp.status != 200 then err = "group: status " + resp.status
      err

  def groupBody(name: String, members: List[String]): String =
    "{\"name\":\"" + name + "\",\"members\":[" + quoteJoin(members) + "]}"

  def quoteJoin(xs: List[String]): String = xs match
    case h :: t => quoteJoinStep(h, t)
    case Nil  => ""

  def quoteJoinStep(h: String, t: List[String]): String = t match
    case _ :: _ => "\"" + h + "\"," + quoteJoin(t)
    case Nil  => "\"" + h + "\""

  /** arm the server's fail-on-demand hook (testhooks.scala, wata-server;
   *  registered only under WATA_TEST_HOOKS=1): the next `n` media
   *  uploads/downloads answer `status` (default 500 — RETRY class; a 4xx is
   *  how a script provokes the outbox's UNDELIVERABLE drop). Out-of-band and
   *  unauthenticated, like the hook itself. */
  def failNext(n: scala.Int, status: scala.Int): String =
    val hs = Hs(FbCaps.httpDo(), FbCaps.clock(), baseC.get(), "")
    val resp = MatrixHttp.request(hs, "POST", "/_wata/v1/test/fail",
      "application/json", "{\"count\":" + n + ",\"status\":" + status + "}")
    if resp.status != 200 then "failnext: status " + resp.status else ""

  // ---- probes ------------------------------------------------------------------------

  def probe(name: String, px: go.Bytes): scala.Int =
    if name == "syncing" then syncingProbe()
    else if name == "convs" then WataLogic.convCount(Ui.frameSnap)
    else if name == "msgs" then WataLogic.msgCount(Ui.frameSnap, curConvIdx())
    else if name == "played" then playedCount(Ui.frameSnap, curConvIdx())
    else if name == "peer" then peerCount(Ui.frameSnap, curConvIdx())
    else if name == "favs" then favCount(Ui.frameSnap, curConvIdx())
    else if name == "screenoff" then boolProbe(Ui.screenOff)
    else if name == "sendfail" then Ui.sendFails
    else if name == "playfail" then Ui.playFails
    else if name == "connerr" then Ui.connErrs
    else if name == "conntag" then Runtime.connTag(Ui.connection)
    else if name == "logins" then Runtime.loginAttempts
    else if name == "quitarm" then boolProbe(Ui.quitArmed)
    // the exit menu (plan 0040): open, which row, and how many OKs have landed
    // on it — `exitconfirm` is what pins the two-step rows, where the check is
    // that ONE OK on "Reboot to EDL" runs nothing and only raises this to 1.
    else if name == "exitopen" then boolProbe(Ui.exitMenuOpen)
    else if name == "exitrow" then Ui.exitMenuRow
    else if name == "exitconfirm" then Ui.exitMenuConfirm
    else if name == "unsent" then countKeys(Ui.unsentKeys)
    else if name == "undeliv" then countKeys(Ui.undeliveredKeys)
    else if name == "frames" then Ui.frames
    else if name == "nettest" then netTestProbe()
    // the arrival-notification probes (plan 0041): the summed counts, the LED
    // arbiter's computed decision, and the banner cell.
    else if name == "unplayed" then Notify.totalUnplayed(Ui.frameSnap)
    else if name == "notifyled" then Ui.ledGreenNow
    else if name == "notifyred" then boolProbe(Ui.ledRedNow)
    else if name == "notifybanner" then boolProbe(Ui.bannerOn)
    // the recording meter's level (plan 0042) — what `caplevel` just set.
    else if name == "caplevel" then Shell.wataState(Ui.shellState).captureLevel
    // the message cursor's index — what pins the event-id anchoring: an
    // arrival shifts an anchored cursor's index by one (same message), and
    // leaves an idle cursor on 0 (tracking newest). Exactness comes from
    // pairing `expect` (>=) with `waitmax` (<=) on the same value.
    else if name == "msgsel" then Shell.wataState(Ui.shellState).msgSelected
    // the kid-settings probes (plan 0053): which applet is showing (the dev
    // door and the red return are Shell transitions, so a script asserts the
    // index), the kid panel's selected row, and the data row's pending target
    // SHIFTED to non-negative (0 none, 1 off, 2 wifi, 3 cell — the script
    // parser reads unsigned numbers only) — how a script pins "targets only,
    // nothing applied yet" without reading pixels.
    else if name == "applet" then Ui.shellState.active
    else if name == "kidrow" then Shell.kidState(Ui.shellState).selected
    else if name == "kidtarget" then Shell.kidState(Ui.shellState).dataTarget + 1
    // the applying wait's frame count, shifted non-negative like kidtarget
    // (0 = not applying) — how a script proves the spinner state was entered
    // (or left) without counting pixels.
    else if name == "kidapply" then Shell.kidState(Ui.shellState).applyFrames + 1
    // the charge-anomaly mark's debounced flag (plan 0073) — what the
    // `charge` directive's forced reads feed. 0 for the whole debounce
    // window is the assertion that matters.
    else if name == "chargebad" then boolProbe(ChargeStatus.active())
    // the rolodex motion integrator (plan 0077): the centre index the physics
    // has converged on — the selection itself, since the pump writes it into
    // `selected` — and whether it is still moving. The fixed 33ms virtual
    // clock is what makes "advance N then expect" deterministic against it.
    else if name == "motioncentre" then Ui.motionCentre
    else if name == "motionlive" then boolProbe(Ui.motionLive)
    // the rolodex EMPHASIS probes (plan 0077 stage 4), read straight off the
    // live pixel buffer: the lit width and summed brightness of one scanline
    // through the centre band's middle (rollcw/rollcl) and one through the
    // row above it (rollnw/rollnl). With the stack open and near a detent the
    // first crosses the centre card and the second a quiet neighbour, so the
    // centre-card-is-marked claims — wider (inset) and brighter (QUIET_ALPHA)
    // — are numbers a script can pin, and numbers that FAIL when the
    // emphasis is inverted (quiet forced to zero), which is what makes the
    // green run believable.
    // the DRAWN THREAD probes (plan 0078), read off the live pixel buffer at
    // two scanlines — through the centre row's middle and through the row
    // below it — restricted to one column band each, so a claim about ink is
    // a claim about the thing that carries it:
    //   thrcl/thrnl  summed brightness of the BAR FIELD on the two scanlines
    //                (played-third vs unheard-ink discriminates here)
    //   thrsq        lit pixels in the delivery GUTTER, summed over THREE
    //                scanlines of the centre row — the stacked pair's two
    //                dot centres (rowMid ∓ 3) and the row middle (the gap
    //                between them, and the centred red disc's middle) —
    //                (the dots: green+ring vs green+green vs the red disc
    //                 differ — a broken slot mapping shifts the count)
    //   thrbw/thrnw  lit pixels in the BAR FIELD on the two scanlines — at a
    //                bar's mid-height that is its WIDTH, which is what pins
    //                the normalised length mapping: the thread's longest row
    //                at 80% of the field (thrbw at the centre), a shorter
    //                one proportional (thrnw on the row below). A normaliser
    //                that ignores maxDur (a fixed scale) moves both numbers
    //                and the claims go red.
    //   thrstc/thrstn  lit pixels in the STAMP column on the two scanlines
    //                (the collapse: a burst's second row has an empty column;
    //                 x starts past the left nub so the band cannot count it)
    else if name == "thrcl" then scanLumRange(px, thrRowY(0), Thread.fieldX0(Display.W), Thread.fieldX1(Display.W))
    else if name == "thrnl" then scanLumRange(px, thrRowY(1), Thread.fieldX0(Display.W), Thread.fieldX1(Display.W))
    else if name == "thrsq" then
      scanLitRange(px, thrRowY(0) - 3, Display.W - Thread.GUTTER_W, Display.W) +
        scanLitRange(px, thrRowY(0), Display.W - Thread.GUTTER_W, Display.W) +
        scanLitRange(px, thrRowY(0) + 3, Display.W - Thread.GUTTER_W, Display.W)
    else if name == "thrbw" then scanLitRange(px, thrRowY(0), Thread.fieldX0(Display.W), Thread.fieldX1(Display.W))
    else if name == "thrnw" then scanLitRange(px, thrRowY(1), Thread.fieldX0(Display.W), Thread.fieldX1(Display.W))
    //   thrylw       YELLOW pixels in the stamp column on the centre scanline
    //                — the playing row's stamp treatment, pinned as ink
    //                rather than as text: the hh:mm it spells is the real
    //                wall clock's (the client keeps the real clock), so the
    //                text itself cannot be a golden
    else if name == "thrylw" then scanYellow(px, thrRowY(0), 6, Thread.STAMP_W)
    else if name == "thrstc" then scanLitRange(px, thrRowY(0), 6, Thread.STAMP_W)
    else if name == "thrstn" then scanLitRange(px, thrRowY(1), 6, Thread.STAMP_W)
    //   thrchip      is the scrub chip's WINDOW open (the applet's frame
    //                counter — live motion pins it, the linger drains it)?
    //                The state probe, so a disabled linger is a red at the
    //                settle frame without any pixel having to prove absence.
    //   thrchw       NEAR-WHITE pixels in the chip's box (the top of the
    //                message area, over the bar field) — the chip's white
    //                CAPTION text, pinned as ink rather than text: the
    //                hh:mm it spells is the real wall clock's (thrylw's
    //                caveat), so neither the text nor the frame can be a
    //                golden while the chip shows. Near-white excludes both
    //                the dark chip fill and anything bleeding through it.
    else if name == "thrchip" then boolProbe(WataLogic.chipShowing(Shell.wataState(Ui.shellState)))
    else if name == "thrchw" then scanWhiteBox(px, Thread.fieldX0(Display.W),
      Thread.fieldX1(Display.W), Thread.CHIP_Y, Thread.CHIP_Y + 20)
    else if name == "rollcw" then scanLit(px, Rolodex.centreY(Display.H) + Rolodex.rowH(Display.H) / 2)
    else if name == "rollnw" then scanLit(px, Rolodex.centreY(Display.H) - Rolodex.rowH(Display.H) / 2)
    else if name == "rollcl" then scanLum(px, Rolodex.centreY(Display.H) + Rolodex.rowH(Display.H) / 2)
    else if name == "rollnl" then scanLum(px, Rolodex.centreY(Display.H) - Rolodex.rowH(Display.H) / 2)
    else -1

  /** the mid-height scanline of the thread row `off` rows below the centre
   *  band. */
  def thrRowY(off: scala.Int): scala.Int =
    Thread.centreY(Display.H) + off * Thread.rowH(Display.H) + Thread.rowH(Display.H) / 2

  /** lit pixels in `[x0, x1)` on scanline `y`. */
  def scanLitRange(px: go.Bytes, y: scala.Int, x0: scala.Int, x1: scala.Int): scala.Int =
    var n = 0
    if px.length >= Display.BYTES then
      var x = x0
      while x < x1 do
        if pixSum(px, x, y) > 4 then n += 1
        x += 1
    n

  /** NEAR-WHITE pixels in the box `[x0,x1) x [y0,y1)`: all three channels
   *  high — the chip's full-strength white text, and not the ~18% of a bar
   *  bleeding through the chip's 210-alpha dark fill. */
  def scanWhiteBox(px: go.Bytes, x0: scala.Int, x1: scala.Int,
      y0: scala.Int, y1: scala.Int): scala.Int =
    var n = 0
    if px.length >= Display.BYTES then
      var y = y0
      while y < y1 do
        var x = x0
        while x < x1 do
          val i = (y * Display.W + x) * 2
          val v = (px(i).toInt & 0xff) | ((px(i + 1).toInt & 0xff) << 8)
          val r = (v >> 11) & 31
          val g = (v >> 5) & 63
          val bch = v & 31
          if r >= 20 && g >= 45 && bch >= 20 then n += 1
          x += 1
        y += 1
    n

  /** YELLOW pixels in `[x0, x1)` on scanline `y`: lit in red+green with a
   *  dark blue channel — the panel's yellow (0xFFE0) and its antialiased
   *  edges, and nothing the white or gray text produces. */
  def scanYellow(px: go.Bytes, y: scala.Int, x0: scala.Int, x1: scala.Int): scala.Int =
    var n = 0
    if px.length >= Display.BYTES then
      var x = x0
      while x < x1 do
        val i = (y * Display.W + x) * 2
        val v = (px(i).toInt & 0xff) | ((px(i + 1).toInt & 0xff) << 8)
        val r = (v >> 11) & 31
        val g = (v >> 5) & 63
        val bch = v & 31
        if r + g > 8 && bch < 2 then n += 1
        x += 1
    n

  /** summed channel values in `[x0, x1)` on scanline `y`, /16. */
  def scanLumRange(px: go.Bytes, y: scala.Int, x0: scala.Int, x1: scala.Int): scala.Int =
    var tot = 0
    if px.length >= Display.BYTES then
      var x = x0
      while x < x1 do
        tot += pixSum(px, x, y)
        x += 1
    tot / 16

  /** pixels on scanline `y` that are not (near-)black — the lit width the
   *  emphasis probes compare. RGB565 little-endian, the buffer's own format. */
  def scanLit(px: go.Bytes, y: scala.Int): scala.Int =
    var n = 0
    if px.length >= Display.BYTES then
      var x = 0
      while x < Display.W do
        if pixSum(px, x, y) > 4 then n += 1
        x += 1
    n

  /** the scanline's summed channel values, /16 to keep the number readable in
   *  a script — the brightness the emphasis probes compare. */
  def scanLum(px: go.Bytes, y: scala.Int): scala.Int =
    var tot = 0
    if px.length >= Display.BYTES then
      var x = 0
      while x < Display.W do
        tot += pixSum(px, x, y)
        x += 1
    tot / 16

  /** r5+g6+b5 of one pixel (0..125). */
  def pixSum(px: go.Bytes, x: scala.Int, y: scala.Int): scala.Int =
    val i = (y * Display.W + x) * 2
    val v = (px(i).toInt & 0xff) | ((px(i + 1).toInt & 0xff) << 8)
    ((v >> 11) & 31) + ((v >> 5) & 63) + (v & 31)

  /** 1 once the net test's verdicts are IN THE APPLET's state. The probes run
   *  on a goroutine of their own, so "OK pressed" and "the row shows a result"
   *  are different frames — a scripted run waits for the second one rather than
   *  assuming a number of frames is enough. */
  def netTestProbe(): scala.Int =
    boolProbe(SettingsLogic.hasNetTestResult(Shell.devState(Ui.shellState)))

  /** how many conversations carry one of the outbox markers. */
  def countKeys(xs: List[String]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  def boolProbe(b: Boolean): scala.Int =
    var out = 0
    if b then out = 1
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

  /** favorited messages in the conversation the applet points at — what a
   *  script waits on after the hold-OK gesture, since the star only appears
   *  once the server's `net.wata.favorite` state has come back through sync. */
  def favCount(snap: StateSnapshot, idx: scala.Int): scala.Int =
    WataLogic.convAt(snap, idx) match
      case c: Some[Conversation] => favIn(c.value.messages)
      case None => 0

  def favIn(ms: List[VoiceMessage]): scala.Int =
    var n = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.isFavorite then n = n + 1
          cur = t
        case Nil => going = false
    n

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

  /** messages in the pointed-at conversation somebody OTHER than their sender
   *  has receipted — what a script waits on to see the second check land. */
  def peerCount(snap: StateSnapshot, idx: scala.Int): scala.Int =
    WataLogic.convAt(snap, idx) match
      case c: Some[Conversation] => peerIn(c.value.messages)
      case None => 0

  def peerIn(ms: List[VoiceMessage]): scala.Int =
    var n = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.playedByPeer then n = n + 1
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
    if s == "repeat" then out = Repeat()
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

  /** every token after the directive word. */
  def restOf(ts: List[String]): List[String] = ts match
    case _ :: t => t
    case Nil  => Nil

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
