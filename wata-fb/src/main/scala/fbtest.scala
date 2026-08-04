import language.experimental.saferExceptions

/** The framebuffer test driver: the deterministic golden pattern, the
 *  headless-host PNG dump, and the on-device smoke test.
 *
 *  `dump` (host, ci): draw the pattern into an in-memory RGB565 buffer, encode
 *  it to PNG, and write the raw PNG bytes to fd 1 — NOTHING else goes to stdout
 *  in this mode, so `wata-fb fbdump > frame.png` is a clean, byte-stable golden.
 *  `smoke` (device only, manual verification): the real fbdev/evdev/LED path. */
object FbTest:

  /** the deterministic golden pattern — exercises clear / fillRect / strokeRect
   *  / setPixel / text / font (incl. a custom icon glyph). No time / randomness
   *  -> byte-stable across runs (the golden-frame contract). */
  def drawPattern(px: go.Bytes): Unit =
    Draw.clear(px, Color.black)
    // four color quadrants
    Draw.fillRect(px, 0, 0, 80, 64, Color.red)
    Draw.fillRect(px, 80, 0, 80, 64, Color.green)
    Draw.fillRect(px, 0, 64, 80, 64, Color.blue)
    Draw.fillRect(px, 80, 64, 80, 64, Color.yellow)
    // white 1px border
    Draw.strokeRect(px, 0, 0, Display.W, Display.H, Color.white)
    // a dark panel for the text block
    Draw.fillRect(px, 6, 36, 148, 56, Color.darkGray)
    Font.drawText(px, "WATA-FB M8", 2, 5, Color.white, true, Color.darkGray)
    Font.drawText(px, "0123456789", 2, 6, Color.cyan, true, Color.darkGray)
    Font.drawText(px, "abcXYZ !?+-", 2, 7, Color.orange, true, Color.darkGray)
    Font.drawTextCentered(px, "rgb565 ok", 9, Color.white, true, Color.darkGray)
    // custom glyphs (transparent bg): battery-full + double-check
    Font.drawChar(px, Font.ICON_BAT_FULL, 4, 3, Color.green, false, 0)
    Font.drawChar(px, Font.ICON_CHECK, 148, 3, Color.white, false, 0)
    Font.drawChar(px, Font.ICON_CHECK, 152, 3, Color.white, false, 0)
    // a diagonal of individual pixels
    var i = 0
    while i < 24 do
      Draw.setPixel(px, 18 + i, 98 + i, Color.white)
      i = i + 1

  /** host: PNG of the pattern -> stdout (fd 1), nothing else. */
  def dump(): Unit =
    val px = Draw.newBuffer()
    drawPattern(px)
    val png = Png.encode(px)
    go.syscall.write(1, go.bytes(png.rawString))

  // -------- on-device smoke (manual verification) ---------------------------

  def smoke(): Unit =
    println("fbsmoke: opening /dev/fb0 ...")
    try
      val fd = go.syscall.open("/dev/fb0", go.syscall.O_RDWR, 0)
      val mem = go.syscall.mmap(fd, 0L, Display.BYTES,
        go.syscall.PROT_READ | go.syscall.PROT_WRITE, go.syscall.MAP_SHARED)
      runDevice(fd, mem)
    catch case e: sgo.GoError => println("fbsmoke: device unavailable: " + e.message)

  /** blit the pixel buffer to the mmap'd fb — a direct 160x128 blit, no
   *  stride fixup. */
  def present(mem: go.Bytes, px: go.Bytes): Unit =
    var i = 0
    val n = Display.BYTES
    while i < n do
      mem(i) = px(i)
      i = i + 1

  def runDevice(fd: scala.Int, mem: go.Bytes): Unit =
    val px = Draw.newBuffer()
    drawPattern(px)
    present(mem, px)
    println("fbsmoke: test pattern drawn to /dev/fb0")
    Led.setBacklight(255)
    var b = 0
    while b < 3 do
      Led.setRedLed(true); Led.setGreenLed(false); FbCaps.sleepMs(200L)
      Led.setRedLed(false); Led.setGreenLed(true); FbCaps.sleepMs(200L)
      b = b + 1
    Led.setRedLed(false); Led.setGreenLed(false)
    val fds = Evdev.open()
    println("fbsmoke: input devices open: " + Evdev.count(fds))
    println("fbsmoke: polling ~20s — press keys (echoed below) ...")
    var t = 0
    while t < 200 do
      printEvents(Evdev.poll(fds))
      FbCaps.sleepMs(100L)
      t = t + 1
    Draw.clear(px, Color.black)
    present(mem, px)
    Evdev.closeAll(fds)
    go.syscall.munmap(mem)
    go.syscall.close(fd)
    println("fbsmoke: done (screen cleared, fb released)")

  def printEvents(evs: List[KeyEvent]): Unit =
    var cur = evs
    var going = true
    while going do
      cur match
        case ev :: t =>
          println("key " + Evdev.keyName(ev.key) + " " + Evdev.stateName(ev.state))
          cur = t
        case Nil => going = false
