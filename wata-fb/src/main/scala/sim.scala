import language.experimental.saferExceptions

/** The HOST SIMULATOR front end:
 *
 *    wata-fb sim <base> <user> <pass> [--once]
 *
 *  the same `Ui.frameStep` the device runs, over a `UiDevice` that draws the
 *  160x128 RGB565 frame into the terminal and reads keys from raw stdin. It
 *  needs no framebuffer, no evdev, no sysfs and no audio hardware — only a live
 *  `wata-server`. `tools/fb-sim.sh` is the wrapper that puts the terminal in
 *  raw mode and restores it.
 *
 *  RENDERING: ANSI truecolor half-blocks. One character cell is TWO stacked
 *  pixels — `U+2580 UPPER HALF BLOCK` with the foreground color set to the
 *  upper pixel and the background to the lower one — so the panel lands as a
 *  160x64 character grid at the panel's true aspect ratio. A frame is written
 *  only when the pixel buffer actually changed (the loop redraws every frame,
 *  but the bytes are usually identical), and within a frame an SGR sequence is
 *  emitted only where the fg/bg pair changes from the previous cell, which
 *  collapses the flat regions that dominate this UI.
 *
 *  INPUT: arrows = d-pad, Enter = select, Backspace/`b` = back, `z`/`x` =
 *  prev/next applet, `f` = F2 (delete), Space = PTT, `q` = quit. A terminal
 *  reports no key-up, so PTT RELEASE IS INFERRED: holding space produces a
 *  key-repeat stream, and a gap longer than `PTT_GAP_MS` in that stream is the
 *  release — the same trick the TypeScript TUI's `usePtt` hook uses.
 *
 *  AUDIO: `SimAudio` speaks the real `AudioCmd`/`AudioEvt` mailbox protocol
 *  (audiocmd.scala) with no codec behind it — a recording yields the canned Ogg
 *  payload the integ oracle uses, at a FIXED duration so a scripted run is
 *  byte-reproducible, and playback succeeds silently. The whole send path
 *  (PTT -> upload -> `m.audio` -> the other client) therefore runs host-side;
 *  only the codec stays device-only.
 *
 *  `--once` renders a single frame and exits — what `tools/fb-sim.sh` runs when
 *  stdin is not a terminal, so the recipe is still exercisable from a script. */

// ---- the sim audio actor -------------------------------------------------------

/** the host stand-in for `AudioThread.mainLoop`: same channels, same protocol,
 *  no opus and no alsa. */
object SimAudio:

  /** the duration every simulated recording reports, regardless of how long PTT
   *  was actually held — a scripted frame must be byte-identical across runs,
   *  and the hold time is wall-clock. */
  val RecordMs: Long = 1000L

  def mainLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt]): Unit =
    var run = true
    while run do
      run = dispatch(evts, cmds.recv())

  /** false only for AcQuit — everything else loops. */
  def dispatch(evts: sgo.Chan[AudioEvt], cmd: AudioCmd): Boolean = cmd match
    case _: AcRecordStart  => true                     // recording is instantaneous here
    case _: AcRecordStop   => finish(evts)
    case _: AcPlay         => done(evts)
    case _: AcStopPlayback => true
    case _: AcEchoTest     => echo(evts)
    case _: AcQuit         => false

  def finish(evts: sgo.Chan[AudioEvt]): Boolean =
    evts.trySend(AeRecordingDone(Integ.fakeOgg(), RecordMs))
    true

  def done(evts: sgo.Chan[AudioEvt]): Boolean =
    evts.trySend(AePlaybackDone())
    true

  def echo(evts: sgo.Chan[AudioEvt]): Boolean =
    evts.trySend(AeEchoRecording())
    evts.trySend(AeEchoPlaying())
    evts.trySend(AeEchoDone())
    true

// ---- the terminal renderer -----------------------------------------------------

/** RGB565 pixel buffer -> ANSI truecolor half-block cells. */
object SimTerm:
  /** character rows: two pixel rows each. */
  val ROWS: scala.Int = Display.H / 2

  /** ESC + `s` as bytes. Escapes are built here rather than in string literals
   *  because the subset has no `\u` escape and no `\e`. */
  def esc(s: String): Bytes =
    val b = new BytesBuilder
    b.addByte(0x1b)
    b.addAscii(s)
    b.result()

  /** `U+2580 UPPER HALF BLOCK` in UTF-8. */
  def halfBlock(): Bytes =
    val b = new BytesBuilder
    b.addByte(0xe2)
    b.addByte(0x96)
    b.addByte(0x80)
    b.result()

  /** the pixel at (x, y) as packed 0xRRGGBB — RGB565 expanded by bit
   *  replication, the same inverse of `Color.rgb` the PNG encoder uses. */
  def rgb888(px: go.Bytes, x: scala.Int, y: scala.Int): scala.Int =
    val idx = (y * Display.W + x) * 2
    val lo = px(idx).toInt & 0xff
    val hi = px(idx + 1).toInt & 0xff
    val c = lo | (hi << 8)
    val r5 = (c >> 11) & 0x1f
    val g6 = (c >> 5) & 0x3f
    val b5 = c & 0x1f
    val r = (r5 << 3) | (r5 >> 2)
    val g = (g6 << 2) | (g6 >> 4)
    val b = (b5 << 3) | (b5 >> 2)
    (r << 16) | (g << 8) | b

  /** the SGR that sets fg = upper pixel, bg = lower pixel. */
  def sgr(fg: scala.Int, bg: scala.Int): Bytes =
    esc("[38;2;" + ((fg >> 16) & 0xff) + ";" + ((fg >> 8) & 0xff) + ";" + (fg & 0xff) +
        ";48;2;" + ((bg >> 16) & 0xff) + ";" + ((bg >> 8) & 0xff) + ";" + (bg & 0xff) + "m")

  /** the whole frame: cursor home, 64 half-block rows, then the status row.
   *
   *  NB this builds ONE local `BytesBuilder` and returns its result — a
   *  `BytesBuilder` passed to a helper and appended to does NOT propagate the
   *  appends back (the emitter lowers `add*` to `b = append(b, …)` on a
   *  by-value slice), so every helper here RETURNS bytes instead. */
  def frame(px: go.Bytes, green: Boolean, red: Boolean): Bytes =
    val cell = halfBlock()
    val out = new BytesBuilder
    out.addBytes(esc("[H"))
    var lastFg = -1
    var lastBg = -1
    var row = 0
    while row < ROWS do
      var x = 0
      while x < Display.W do
        val fg = rgb888(px, x, row * 2)
        val bg = rgb888(px, x, row * 2 + 1)
        if fg != lastFg || bg != lastBg then
          out.addBytes(sgr(fg, bg))
          lastFg = fg
          lastBg = bg
        out.addBytes(cell)
        x = x + 1
      out.addBytes(esc("[K"))
      out.addAscii("\r\n")
      lastFg = -1                       // the row reset drops the carried colors
      lastBg = -1
      row = row + 1
    out.addBytes(esc("[0m"))
    out.addBytes(statusRow(green, red))
    out.result()

  /** the LED + key-hint row under the frame: two colored cells mirroring the
   *  device's green/red LEDs. */
  def statusRow(green: Boolean, red: Boolean): Bytes =
    val out = new BytesBuilder
    var greenSgr = "[48;2;20;40;20m"
    if green then greenSgr = "[48;2;0;220;0m"
    var redSgr = "[48;2;40;20;20m"
    if red then redSgr = "[48;2;220;0;0m"
    out.addBytes(esc(greenSgr))
    out.addAscii("  ")
    out.addBytes(esc("[0m"))
    out.addAscii(" ")
    out.addBytes(esc(redSgr))
    out.addAscii("  ")
    out.addBytes(esc("[0m"))
    out.addAscii("  arrows=dpad  enter=ok  b=back  space=PTT  z/x=applet  f=del  q=quit")
    out.addBytes(esc("[K"))
    out.addAscii("\r\n")
    out.result()

// ---- the device backend ----------------------------------------------------------

/** the terminal `UiDevice`. `prev` is the last presented frame, kept so an
 *  unchanged frame writes nothing. */
final class SimDevice(prev: go.Bytes) extends UiDevice:
  def pollInput(): KeyBatch = KeyBatch(Sim.pollKeys())
  def present(px: go.Bytes): Unit = Sim.present(prev, px)
  def leds(green: Boolean, red: Boolean): Unit = Sim.setLeds(green, red)
  def backlight(level: scala.Int): Unit = ()          // no panel to dim
  def buttonBacklight(on: Boolean): Unit = ()
  def frameSleep(ms: Long): Unit = FbCaps.sleepMs(ms)

object Sim:

  /** a gap longer than this in the space-key repeat stream is the PTT release. */
  val PTT_GAP_MS: Long = 250L

  // UI-goroutine cells, the same `val`-held Atomic discipline the shell uses.
  private val pttDownC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val pttMsC: sgo.Atomic[Long] = sgo.atomic(0L)
  private val quitC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val greenC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val redC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val dirtyC: sgo.Atomic[Boolean] = sgo.atomic(true)

  def run(args: Array[String]): Unit =
    if args.length < 4 then println("sim: want  wata-fb sim <base> <user> <pass> [--once]")
    else
      var once = false
      if args.length > 4 && args(4) == "--once" then once = true
      loop(args(1), args(2), args(3), once)

  def loop(base: String, user: String, pass: String, once: Boolean): Unit =
    val clock = FbCaps.clock()
    val cfg = ClientConfig(base, user, pass, 5000, Session("", "", "", "", ""))
    val c = Runtime.makeWithAudio(cfg, FbCaps.httpDo(), clock)
    Ui.resetCells()
    resetCells()
    val dev = SimDevice(Draw.newBuffer())
    val px = Draw.newBuffer()
    write(SimTerm.esc("[2J"))
    write(SimTerm.esc("[?25l"))       // hide the cursor for the duration
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(SimAudio.mainLoop(audioCmds, evts))
      Runtime.start(c)
      drive(c, clock, evts, dev, px, once)
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
    }
    write(SimTerm.esc("[?25h"))
    write(SimTerm.esc("[0m"))
    println("")
    println("sim: done")

  /** frames until the shell's quit edge or `q`; one frame in `--once` mode. */
  def drive(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt],
            dev: UiDevice, px: go.Bytes, once: Boolean): Unit =
    Ui.beginFrames(clock)
    var run = true
    while run do
      if Ui.frameStep(c, clock, evts, dev, px) then run = false
      else if quitC.get() then run = false
      else if once then run = false

  def resetCells(): Unit =
    pttDownC.set(false)
    pttMsC.set(0L)
    quitC.set(false)
    greenC.set(false)
    redC.set(false)
    dirtyC.set(true)

  // ---- output -------------------------------------------------------------------

  def write(b: Bytes): Unit = go.syscall.write(1, go.bytes(b.rawString))

  /** write the frame only when the pixel buffer (or an LED) actually changed. */
  def present(prev: go.Bytes, px: go.Bytes): Unit =
    if dirtyC.get() || differs(prev, px) then
      copyInto(prev, px)
      dirtyC.set(false)
      write(SimTerm.frame(px, greenC.get(), redC.get()))

  def differs(a: go.Bytes, b: go.Bytes): Boolean =
    var i = 0
    val n = Display.BYTES
    var out = false
    while i < n do
      if a(i) != b(i) then
        out = true
        i = n
      else i = i + 1
    out

  def copyInto(dst: go.Bytes, src: go.Bytes): Unit =
    var i = 0
    val n = Display.BYTES
    while i < n do
      dst(i) = src(i)
      i = i + 1

  /** the LED row lives in the same write as the frame, so an LED change alone
   *  has to force the next present to redraw. */
  def setLeds(green: Boolean, red: Boolean): Unit =
    if green != greenC.get() || red != redC.get() then
      greenC.set(green)
      redC.set(red)
      dirtyC.set(true)

  // ---- input --------------------------------------------------------------------

  /** drain whatever raw stdin has (the wrapper's `stty -icanon min 0 time 0`
   *  makes this return immediately with 0 bytes when nothing is pending), then
   *  append the inferred PTT release if the repeat stream has gone quiet. */
  def pollKeys(): List[KeyEvent] =
    val buf = go.makeSlice[Byte](64)
    var n = 0
    try
      val r = go.syscall.read(0, buf)
      n = r
    catch case e: sgo.GoError => n = 0
    var acc: List[KeyEvent] = Nil
    var i = 0
    while i < n do
      val b0 = buf(i).toInt & 0xff
      var step = 1
      if b0 == 0x1b && i + 2 < n && (buf(i + 1).toInt & 0xff) == 0x5b then
        acc = arrow(buf(i + 2).toInt & 0xff, acc)
        step = 3
      else acc = plain(b0, acc)
      i = i + step
    ListOps.reverse(pttGap(acc))

  /** ESC [ A/B/C/D — the d-pad. */
  def arrow(code: scala.Int, acc: List[KeyEvent]): List[KeyEvent] =
    var out = acc
    if code == 0x41 then out = press(KUp(), acc)
    else if code == 0x42 then out = press(KDown(), acc)
    else if code == 0x43 then out = press(KRight(), acc)
    else if code == 0x44 then out = press(KLeft(), acc)
    out

  def plain(b: scala.Int, acc: List[KeyEvent]): List[KeyEvent] =
    if b == 0x71 then quitC.set(true)                       // q
    var out = acc
    if b == 0x20 then out = ptt(acc)                        // space
    else if b == 0x0d || b == 0x0a then out = press(KEnter(), acc)
    else if b == 0x7f || b == 0x08 || b == 0x62 then out = press(KBack(), acc)
    else if b == 0x7a then out = press(KDot1(), acc)        // z
    else if b == 0x78 then out = press(KDot2(), acc)        // x
    else if b == 0x66 then out = press(KF2(), acc)          // f
    out

  /** a discrete key: press then release. `acc` is in reverse order, so the
   *  release goes on first. */
  def press(k: Key, acc: List[KeyEvent]): List[KeyEvent] =
    KeyEvent(k, Released()) :: (KeyEvent(k, Pressed()) :: acc)

  /** a space byte: the first one is the press, every later one just refreshes
   *  the repeat deadline. */
  def ptt(acc: List[KeyEvent]): List[KeyEvent] =
    pttMsC.set(go.time.nowUnixMilli())
    var out = acc
    if !pttDownC.get() then
      pttDownC.set(true)
      out = KeyEvent(KPtt(), Pressed()) :: acc
    out

  /** the release edge a terminal never delivers: PTT is held, and the repeat
   *  stream has been quiet longer than the gap. */
  def pttGap(acc: List[KeyEvent]): List[KeyEvent] =
    var out = acc
    if pttDownC.get() && go.time.nowUnixMilli() - pttMsC.get() > PTT_GAP_MS then
      pttDownC.set(false)
      out = KeyEvent(KPtt(), Released()) :: acc
    out
