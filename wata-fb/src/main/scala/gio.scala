import language.experimental.saferExceptions

/** The GIO front end — plan 0023 milestone 2:
 *
 *    wata-fb gio <base> <user> <pass> [--scale N] [--frames N]
 *
 *  the same `Ui.frameStep` the device runs, over a `UiDevice` that hands the
 *  160x128 RGB565 frame to a Gio window (go-pkgs/gioshell) which blits it as
 *  an integer-scaled nearest-neighbour texture with five touch buttons under
 *  it. "The phone is a bigger BQ268": the entire existing UI — applets,
 *  goldens and all — runs here with no new UI code, because the window is
 *  just another backend behind the `UiDevice` seam.
 *
 *  THREADING. Gio's event loop owns the main goroutine and never returns, so
 *  this is the one front end whose frame loop is NOT the main goroutine: it is
 *  forked, and the main goroutine sits in `go.gioshell.eventLoop()`. The
 *  device object stays inside that fork (`drive` builds it there), which is
 *  what keeps "a `UiDevice` never crosses a goroutine boundary" true — only
 *  the frame bytes and the packed key ints cross, through the shell's own
 *  synchronized state.
 *
 *  AUDIO is `SimAudio`, the host no-op stand-in the terminal sim uses: this
 *  shell has no codec and no ALSA, so a recording yields the canned payload
 *  and playback succeeds silently. VOICE DOES NOT WORK HERE — the button row
 *  says so on screen.
 *
 *  `--frames N` quits after N presented frames, which is how an unattended
 *  run proves the window came up and a real frame reached it; `--scale N`
 *  forces a magnification instead of fitting the window. */

/** the Gio `UiDevice`. No fields: everything it touches lives in the shell's
 *  own synchronized state, so the object is built inside the frame fork and
 *  never leaves it. */
final class GioDevice() extends UiDevice:
  def pollInput(): List[KeyEvent] = Gio.pollKeys()
  def present(px: go.Bytes): Unit = go.gioshell.present(px)
  def leds(green: Boolean, red: Boolean): Unit = go.gioshell.leds(green, red)
  def backlight(level: scala.Int): Unit = ()          // no panel to dim
  def unblank(): Unit = ()                            // no kernel fb to unblank
  def buttonBacklight(on: Boolean): Unit = ()
  def frameSleep(ms: Long): Unit = FbCaps.sleepMs(ms)

object Gio:

  /** the credentials are optional here too — `wata-fb gio` with none resumes
   *  the stored session, the same way the device's `ui` does. */
  def run(args: Array[String]): Unit =
    val cfg = FbConfig.resolve(FbConfig.argAt(args, 1), FbConfig.argAt(args, 2),
      FbConfig.argAt(args, 3), 5000)
    if cfg.homeserver == "" then println(FbConfig.noServerMsg("gio"))
    else loop(cfg, flag(args, "--scale"), flag(args, "--frames"))

  /** `--name N` anywhere in the arguments, 0 if absent. */
  def flag(args: Array[String], name: String): scala.Int =
    var out = 0
    var i = 1
    while i < args.length do
      if args(i) == name && i + 1 < args.length then out = DevCli.atoiOr(args(i + 1), 0)
      i = i + 1
    out

  def loop(cfg: ClientConfig, scale: scala.Int, maxFrames: scala.Int): Unit =
    val clock = FbCaps.clock()
    val c = Runtime.makeWithAudioStored(cfg, FbCaps.httpDo(), clock, FbConfig.outbox())
    Ui.resetCells()
    try
      go.gioshell.start(Display.W, Display.H, scale, maxFrames)
      sgo.supervised {
        val evts = sgo.makeChan[AudioEvt](16)
        // hoist the command Chan out of the fork — the body needs only the
        // channel (a synchronizer), not the whole client record.
        val audioCmds = c.audioCmds
        sgo.fork(SimAudio.mainLoop(audioCmds, evts))
        Runtime.start(c)
        sgo.fork(Gio.drive(c, clock, evts))
        // the main goroutine from here on: Gio's loop, which never returns.
        go.gioshell.eventLoop()
      }
    catch case e: sgo.GoError => println("gio: " + e.message)

  /** the frame loop, on its own goroutine. It builds the device and the pixel
   *  buffer here rather than taking them as arguments, so nothing that is not
   *  crossable is captured by the fork. */
  def drive(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt]): Unit =
    val dev = GioDevice()
    val px = Draw.newBuffer()
    Ui.beginFrames(clock)
    var run = true
    while run do
      if Ui.frameStep(c, clock, evts, dev, px) then run = false
      else if go.gioshell.quit() then run = false
    println("gio: frames " + go.gioshell.framesPresented() +
      " painted " + go.gioshell.framesPainted())
    c.audioCmds.send(AcQuit())
    Runtime.stopClient(c)
    go.gioshell.done()

  // ---- input ---------------------------------------------------------------

  /** drain the shell's key queue (non-blocking, -1 = empty), oldest first. */
  def pollKeys(): List[KeyEvent] =
    var acc: List[KeyEvent] = Nil
    var run = true
    while run do
      val k = go.gioshell.nextKey()
      if k < 0 then run = false
      else acc = decode(k) :: acc
    ListOps.reverse(acc)

  /** `code*2 + pressed` back into a `KeyEvent`. PTT arrives as a real
   *  press/release PAIR — a touch hold and a held space bar both deliver both
   *  edges — so unlike the terminal sim there is no release to infer. */
  def decode(k: scala.Int): KeyEvent =
    var st: KeyState = Released()
    if k % 2 == 1 then st = Pressed()
    KeyEvent(keyOf(k / 2), st)

  /** the codes go-pkgs/gioshell's `Key*` constants name, in the same order. */
  def keyOf(code: scala.Int): Key =
    if code == 0 then KUp()
    else if code == 1 then KDown()
    else if code == 2 then KLeft()
    else if code == 3 then KRight()
    else if code == 4 then KEnter()
    else if code == 5 then KBack()
    else if code == 6 then KPtt()
    else if code == 7 then KDot1()
    else if code == 8 then KDot2()
    else if code == 9 then KF2()
    else KUnknown()
