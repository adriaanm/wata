/** `wata-mac interptest` — the retained interpreter's test suite, run as an
 *  argv mode of the app binary (the `wata-fb difftest` idiom): no server, no
 *  login, no window — AppKit driven headlessly on the locked main goroutine,
 *  each case inside its own autorelease pool.
 *
 *  This is the Sgola port of the Go tests that judged `nativeui` before the
 *  interpreter crossed (plan 0038 / WIRE-DIES-INTERP-TO-SGOLA):
 *
 *    interp_test.go  -> the retained invariant on the real toolkit: after a
 *                       mount and after a patch script, the NATIVE hierarchy
 *                       (classes, order, frames, label strings) mirrors the
 *                       plain tree `Patches.applyAll` produces; in-place
 *                       mutation vs replacement; paint order on inserts
 *    render_test.go  -> offscreen render probes through AppKit's own
 *                       pipeline (cacheDisplayInRect), non-blank text and
 *                       probe pixels — NOT byte goldens (native fonts are
 *                       not the fb font; the semantic oracle for appearance
 *                       stays the fb goldens)
 *    view_test.go    -> the applyAll hand-expectation cases (the subject is
 *                       wataui's `Patches`, linked here), and the pure
 *                       pixel/glyph tables
 *    (keys)          -> the translation table (`MacKeys`), the half of
 *                       keys.go that crossed; the raw-code key view stays Go
 *                       and keeps its own go test
 *
 *  Output: one `interptest: FAIL <case>: <detail>` line per failure and a
 *  final `interptest: PASS` / `interptest: FAIL (<n>)`; the recipe greps the
 *  PASS line and the exit code carries the verdict. */
object InterpTest:

  private val failsC: sgo.Atomic[Int] = sgo.atomic(0)

  def fail(what: String): Unit =
    val n = failsC.update(x => x + 1)
    println("interptest: FAIL " + what)

  def check(ok: Boolean, what: String): Unit =
    if !ok then fail(what)

  def run(): Int =
    pure()
    withPool(() => buildFromScratchMirrors())
    withPool(() => patchSequenceMirrorsApplyAll())
    withPool(() => psetSameKindMutatesInPlace())
    withPool(() => psetKindChangeReplaces())
    withPool(() => rootPSetReplacesWholeTree())
    withPool(() => insertOrderIsPaintOrder())
    withPool(() => offscreenRenderProbes())
    val n = failsC.get()
    if n == 0 then println("interptest: PASS")
    else println("interptest: FAIL (" + n + ")")
    n

  def withPool(f: () => Unit): Unit =
    val pool = go.nativeui.poolPush()
    f()
    go.nativeui.poolPop(pool)

  // ---- fixtures (view_test.go's, verbatim) ----------------------------------

  def row(key: String, hl: Boolean, name: String, y: Int): Keyed =
    var kids: List[Keyed] = Nil
    kids = Keyed("name", VText(0, (y - 1) / 8, name, 0xffff)) :: kids
    if hl then kids = Keyed("hl", VRect(0, y, 160, 8, 0x07e0)) :: kids
    Keyed(key, VGroup(kids))

  /** mirrors the shape of WataLogic's contact-list body. */
  def fixture(): View =
    VGroup(
      Keyed("title", VText(0, 0, "WATA", 0x07ff)) ::
      Keyed("net", VGroup(Keyed("mark", VGlyph(150, 1, 0x84, 0x07e0)) :: Nil)) ::
      Keyed("rows", VGroup(
        row("@alice:h", true, "alice", 17) ::
        row("@bob:h", false, "bob", 33) :: Nil)) ::
      Keyed("footer", VText(0, 14, "OK open", 0x632c)) :: Nil)

  /** a plausible differ output: selection moves, the title recolors, a new
   *  row appears — in script order against the tree the previous patches
   *  produced. */
  def script(): List[Patch] =
    PDelete(2 :: 0 :: Nil, 0) ::
    PInsert(2 :: 1 :: Nil, 0, Keyed("hl", VRect(0, 33, 160, 8, 0x07e0))) ::
    PSet(0 :: Nil, VText(0, 0, "WATA", 0xffe0)) ::
    PInsert(2 :: Nil, 2, row("@carol:h", false, "carol", 49)) :: Nil

  /** an 8x8 two-tone checker, RGB565 LE — the qr block stand-in. */
  def qrImage(): View =
    val b = new BytesBuilder
    var i = 0
    while i < 64 do
      if (i / 8 + i % 8) % 2 == 0 then b.addU16LE(0xffff) else b.addU16LE(0)
      i += 1
    VImage(100, 60, 8, 8, b.result())

  def fixturePlusQr(): View = fixture() match
    case g: VGroup => VGroup(Views.insertAt(g.children, Views.len(g.children), Keyed("qr", qrImage())))
    case v         => v

  // ---- the retained-invariant walk ------------------------------------------

  def wantClass(v: View): String = v match
    case _: VGroup => "NSView"
    case _: VText  => "NSTextField"
    case _: VGlyph => "NSTextField"
    case _: VRect  => "NSBox"
    case _: VImage => "NSImageView"
    case _: VFill  => "NSBox" // VRect's native, plus a radius and an alpha
    case _: VLabel => "NSTextField"

  /** recompute the scaled, flipped SEMANTIC rect independently of the
   *  interpreter — the geometry convention, pinned. Groups answer None
   *  (they span the stage). */
  def expectFrame(v: View, scale: Int): Option[go.appkit.CGRect] =
    def rect(x: Int, y: Int, w: Int, h: Int): Option[go.appkit.CGRect] =
      Some(go.appkit.CGRect(
        go.appkit.CGPoint((x * scale).toDouble, ((128 - y - h) * scale).toDouble),
        go.appkit.CGSize((w * scale).toDouble, (h * scale).toDouble)))
    v match
      case t: VText  => rect(t.col * 6, 1 + t.row * 8, t.text.bytes.length * 6, 8)
      case t: VGlyph => rect(t.x, t.y, 6, 8)
      case t: VRect  => rect(t.x, t.y, t.w, t.h)
      case t: VImage => rect(t.x, t.y, t.w, t.h)
      case t: VFill  => rect(t.x, t.y, t.w, t.h)
      case t: VLabel => rect(t.x, t.y, t.w, t.h) // the frame IS the box
      case _: VGroup => None

  /** walk the native hierarchy and the plain tree side by side. */
  def assertMirrors(path: String, native: go.appkit.NSView, v: View, scale: Int): Unit =
    val cls = go.nativeui.viewClassName(native)
    check(cls == wantClass(v), path + ": native class " + cls + ", want " + wantClass(v))
    expectFrame(v, scale) match
      case want: Some[go.appkit.CGRect] =>
        val f = native.frame()
        check(f == want.value, path + ": frame " + f + ", want " + want.value)
      case None => ()
    v match
      case x: VText =>
        val got = go.nativeui.labelText(native)
        check(got == x.text, path + ": label \"" + got + "\", want \"" + x.text + "\"")
      case x: VLabel =>
        val got = go.nativeui.labelText(native)
        check(got == x.text, path + ": label \"" + got + "\", want \"" + x.text + "\"")
      case x: VGlyph =>
        val got = go.nativeui.labelText(native)
        check(got == MacGlyphs.glyphString(x.glyph),
          path + ": glyph label \"" + got + "\", want \"" + MacGlyphs.glyphString(x.glyph) + "\"")
      case x: VGroup =>
        val n = go.nativeui.subviewCount(native)
        check(n == Views.len(x.children),
          path + ": " + n + " subviews, want " + Views.len(x.children))
        if n == Views.len(x.children) then
          var i = 0
          while i < n do
            assertMirrors(path + "/" + i, go.nativeui.subviewAt(native, i),
              Views.nth(x.children, i).view, scale)
            i += 1
      case _ => ()

  def showV(v: View): String = v match
    case x: VText  => "VText(" + x.col + "," + x.row + ")"
    case x: VGlyph => "VGlyph(" + x.x + "," + x.y + ")"
    case x: VRect  => "VRect(" + x.x + "," + x.y + "," + x.w + "," + x.h + ")"
    case x: VImage => "VImage(" + x.x + "," + x.y + "," + x.w + "," + x.h + ")"
    case x: VFill  => "VFill(" + x.x + "," + x.y + "," + x.w + "," + x.h + ",r=" + x.radius + ")"
    case x: VLabel => "VLabel(" + x.x + "," + x.y + "," + TypeRole.show(x.role) + ")"
    case _: VGroup => "VGroup"

  /** the stage's single mounted subview (the tree's root). */
  def mounted(root: go.appkit.NSView, what: String): Option[go.appkit.NSView] =
    val n = go.nativeui.subviewCount(root)
    if n != 1 then
      fail(what + ": stage should hold exactly one mounted tree, has " + n)
      None
    else Some(go.nativeui.subviewAt(root, 0))

  def mirrorEq(want: View, what: String): Unit = MacStage.mirror() match
    case m: Some[View] => check(Views.eqView(m.value, want), what + ": mirror diverged")
    case None          => fail(what + ": no mirror after mounting")

  // ---- interp_test.go's cases -----------------------------------------------

  def buildFromScratchMirrors(): Unit =
    val v = fixturePlusQr()
    val root = MacStage.create(4, false)
    MacStage.setTree(v)
    mounted(root, "build") match
      case m: Some[go.appkit.NSView] => assertMirrors("root", m.value, v, 4)
      case None                      => ()
    mirrorEq(v, "build")

  def patchSequenceMirrorsApplyAll(): Unit =
    val root = MacStage.create(4, false)
    MacStage.setTree(fixture())
    MacStage.applyScript(script())
    val want = Patches.applyAll(script(), fixture())
    mounted(root, "patch") match
      case m: Some[go.appkit.NSView] => assertMirrors("root", m.value, want, 4)
      case None                      => ()
    mirrorEq(want, "patch")

  def psetSameKindMutatesInPlace(): Unit =
    val root = MacStage.create(2, false)
    MacStage.setTree(VGroup(Keyed("", VText(0, 0, "one", 0xffff)) :: Nil))
    mounted(root, "mutate") match
      case m: Some[go.appkit.NSView] =>
        val before = go.nativeui.subviewAt(m.value, 0)
        MacStage.applyScript(PSet(0 :: Nil, VText(2, 3, "two", 0xf800)) :: Nil)
        val after = go.nativeui.subviewAt(m.value, 0)
        check(go.nativeui.sameView(before, after),
          "mutate: a same-constructor PSet must mutate the native view, not replace it")
        assertMirrors("root", m.value, VGroup(Keyed("", VText(2, 3, "two", 0xf800)) :: Nil), 2)
      case None => ()

  def psetKindChangeReplaces(): Unit =
    val root = MacStage.create(2, false)
    MacStage.setTree(VGroup(
      Keyed("a", VText(0, 0, "gone", 0xffff)) ::
      Keyed("b", VRect(0, 100, 10, 10, 0x001f)) :: Nil))
    MacStage.applyScript(PSet(0 :: Nil, VRect(4, 8, 20, 16, 0x07e0)) :: Nil)
    val want = VGroup(
      Keyed("a", VRect(4, 8, 20, 16, 0x07e0)) ::
      Keyed("b", VRect(0, 100, 10, 10, 0x001f)) :: Nil)
    mounted(root, "replace") match
      case m: Some[go.appkit.NSView] => assertMirrors("root", m.value, want, 2)
      case None                      => ()

  def rootPSetReplacesWholeTree(): Unit =
    val root = MacStage.create(2, false)
    MacStage.setTree(fixture())
    val flat = VText(0, 0, "ALL", 0x07ff)
    MacStage.applyScript(PSet(Nil, flat) :: Nil)
    mounted(root, "rootpset") match
      case m: Some[go.appkit.NSView] => assertMirrors("root", m.value, flat, 2)
      case None                      => ()
    mirrorEq(flat, "rootpset")

  def insertOrderIsPaintOrder(): Unit =
    val root = MacStage.create(2, false)
    MacStage.setTree(VGroup(Keyed("b", VText(0, 2, "b", 0xffff)) :: Nil))
    // insert before, after, and in the middle; the subview walk pins that
    // native order tracks child order.
    MacStage.applyScript(
      PInsert(Nil, 0, Keyed("a", VText(0, 0, "a", 0xffff))) ::
      PInsert(Nil, 2, Keyed("d", VText(0, 6, "d", 0xffff))) ::
      PInsert(Nil, 2, Keyed("c", VText(0, 4, "c", 0xffff))) :: Nil)
    val want = VGroup(
      Keyed("a", VText(0, 0, "a", 0xffff)) ::
      Keyed("b", VText(0, 2, "b", 0xffff)) ::
      Keyed("c", VText(0, 4, "c", 0xffff)) ::
      Keyed("d", VText(0, 6, "d", 0xffff)) :: Nil)
    mounted(root, "insert") match
      case m: Some[go.appkit.NSView] => assertMirrors("root", m.value, want, 2)
      case None                      => ()

  // ---- render_test.go's probes ----------------------------------------------

  def near(v: Double, want: Double): Boolean =
    val d = v - want
    d < 0.15 && d > -0.15

  /** read a rendered pixel at STAGE coordinates (top-left origin, semantic
   *  pixels), through the rep's own pixel size so a backing scale other than
   *  1 cannot skew the address. */
  def probe(rep: go.appkit.NSBitmapImageRep, x: Int, y: Int, scale: Int): go.appkit.NSColor =
    val px = go.nativeui.repPixelsWide(rep) * (x * scale + scale / 2) / (160 * scale)
    val py = go.nativeui.repPixelsHigh(rep) * (y * scale + scale / 2) / (128 * scale)
    rep.colorAtXY(px, py)

  def probeIs(rep: go.appkit.NSBitmapImageRep, x: Int, y: Int, scale: Int,
    r: Double, g: Double, b: Double, what: String): Unit =
    val c = probe(rep, x, y, scale)
    check(near(c.redComponent(), r) && near(c.greenComponent(), g) && near(c.blueComponent(), b),
      what + " probe = (" + c.redComponent() + " " + c.greenComponent() + " " + c.blueComponent() + ")")

  def offscreenRenderProbes(): Unit =
    val scale = 4
    val root = MacStage.create(scale, false)
    MacStage.setTree(VGroup(
      Keyed("bg", VRect(0, 0, 160, 128, 0x0000)) ::      // black stage
      Keyed("red", VRect(8, 8, 40, 24, 0xf800)) ::       // top-left red
      Keyed("blue", VRect(120, 100, 32, 20, 0x001f)) ::  // bottom-right blue
      Keyed("title", VText(2, 6, "WATA", 0xffff)) ::     // white text
      Keyed("qr", qrImage()) :: Nil))                    // the checker block
    val bounds = root.bounds()
    val rep = root.bitmapImageRepForCachingDisplayInRect(bounds)
    root.cacheDisplayInRectToBitmapImageRep(bounds, rep)
    // The red rect's center, in stage coordinates — also pins the y flip:
    // red sits near the TOP of the stage, blue near the bottom.
    probeIs(rep, 28, 20, scale, 1.0, 0.0, 0.0, "red")
    probeIs(rep, 136, 110, scale, 0.0, 0.0, 1.0, "blue")
    probeIs(rep, 80, 60, scale, 0.0, 0.0, 0.0, "background")
    // The image blit: the checker's white top-left cell.
    probeIs(rep, 100, 60, scale, 1.0, 1.0, 1.0, "image")
    // Non-blank in the text run: some pixel in the title's cell block must
    // not be background black (the label drew SOMETHING white-ish).
    var found = false
    var y = 49
    while y < 57 && !found do
      var x = 12
      while x < 36 && !found do
        val c = probe(rep, x, y, scale)
        if c.redComponent() + c.greenComponent() + c.blueComponent() > 1.2 then found = true
        x += 1
      y += 1
    check(found, "text: the label rendered nothing visible")

  // ---- the pure arm: applyAll hand cases + the tables -----------------------

  def pure(): Unit =
    applyAllHandExpectation()
    psetKeepsTheChildKey()
    applyAllToleratesBadPaths()
    insertPastTheEndAppends()
    rgb565Components()
    expandAndScale()
    glyphMapping()
    keyTranslation()

  def applyAllHandExpectation(): Unit =
    val got = Patches.applyAll(script(), fixture())
    val want = VGroup(
      Keyed("title", VText(0, 0, "WATA", 0xffe0)) ::
      Keyed("net", VGroup(Keyed("mark", VGlyph(150, 1, 0x84, 0x07e0)) :: Nil)) ::
      Keyed("rows", VGroup(
        Keyed("@alice:h", VGroup(Keyed("name", VText(0, 2, "alice", 0xffff)) :: Nil)) ::
        row("@bob:h", true, "bob", 33) ::
        row("@carol:h", false, "carol", 49) :: Nil)) ::
      Keyed("footer", VText(0, 14, "OK open", 0x632c)) :: Nil)
    check(Views.eqView(got, want), "applyAll: diverged from the hand expectation")

  def psetKeepsTheChildKey(): Unit =
    // A PSet changes what a child shows, never who it is (Patches.replaceAt).
    val v = Patches.applyAll(PSet(0 :: Nil, VText(1, 1, "x", 0)) :: Nil,
      VGroup(Keyed("keyed", VText(0, 0, "a", 0)) :: Nil))
    v match
      case g: VGroup => check(Views.nth(g.children, 0).key == "keyed", "applyAll: PSet lost the key")
      case _         => fail("applyAll: PSet changed the root constructor")

  def applyAllToleratesBadPaths(): Unit =
    // Mirror semantics: a path into nothing is a no-op, not a crash.
    val orig = fixture()
    val got = Patches.applyAll(
      PSet(9 :: 9 :: Nil, VText(0, 0, "x", 0)) ::
      PDelete(0 :: Nil, 0) :: // path lands on a VText, not a group
      PInsert(2 :: Nil, -1, Keyed("", VText(0, 0, "x", 0))) :: Nil, orig)
    check(Views.eqView(got, orig), "applyAll: bad paths should leave the tree untouched")

  def insertPastTheEndAppends(): Unit =
    val got = Patches.applyAll(PInsert(Nil, 99, Keyed("k", VText(0, 0, "x", 0))) :: Nil,
      VGroup(Nil))
    got match
      case g: VGroup => check(Views.len(g.children) == 1, "applyAll: append expected")
      case _         => fail("applyAll: insert changed the root constructor")

  def rgb565Components(): Unit =
    check(MacPixels.red(0x0000) == 0.0 && MacPixels.green(0x0000) == 0.0 && MacPixels.blue(0x0000) == 0.0, "rgb565: 0x0000")
    check(MacPixels.red(0xffff) == 1.0 && MacPixels.green(0xffff) == 1.0 && MacPixels.blue(0xffff) == 1.0, "rgb565: 0xffff")
    check(MacPixels.red(0xf800) == 1.0 && MacPixels.green(0xf800) == 0.0 && MacPixels.blue(0xf800) == 0.0, "rgb565: 0xf800")
    check(MacPixels.red(0x07e0) == 0.0 && MacPixels.green(0x07e0) == 1.0 && MacPixels.blue(0x07e0) == 0.0, "rgb565: 0x07e0")
    check(MacPixels.red(0x001f) == 0.0 && MacPixels.green(0x001f) == 0.0 && MacPixels.blue(0x001f) == 1.0, "rgb565: 0x001f")

  def expandAndScale(): Unit =
    // One red pixel, one green (RGB565 little-endian pairs).
    val b = new BytesBuilder
    b.addU16LE(0xf800)
    b.addU16LE(0x07e0)
    val rgba = MacPixels.expandRGB565(b.result(), 2, 1)
    check(rgba(0) == 255.toByte && rgba(1) == 0.toByte && rgba(2) == 0.toByte && rgba(3) == 255.toByte &&
      rgba(4) == 0.toByte && rgba(5) == 255.toByte && rgba(6) == 0.toByte && rgba(7) == 255.toByte,
      "expand: RGBA bytes diverge")
    val big = MacPixels.scaleRGBANearest(rgba, 2, 1, 3)
    check(big.length == 6 * 3 * 4, "scale: length " + big.length)
    // pixel (5,2) is the bottom-right of the green block
    val o = (2 * 6 + 5) * 4
    check(big(o) == 0.toByte && big(o + 1) == 255.toByte && big(o + 2) == 0.toByte, "scale: corner pixel")

  def glyphMapping(): Unit =
    check(MacGlyphs.glyphString(0x80) == "✓" && MacGlyphs.glyphString(0x90) == "▶" &&
      MacGlyphs.glyphString(0x8d) == "★", "glyphs: icon mapping broken")
    check(MacGlyphs.glyphString(65) == "A", "glyphs: ASCII should render as itself")
    check(MacGlyphs.glyphString(0xB0) == MacGlyphs.PLACEHOLDER &&
      MacGlyphs.glyphString(0x05) == MacGlyphs.PLACEHOLDER, "glyphs: unmapped codes should render the placeholder")

  def keyTranslation(): Unit =
    check(MacKeys.translate(126) == MacKeys.UP && MacKeys.translate(125) == MacKeys.DOWN &&
      MacKeys.translate(123) == MacKeys.LEFT && MacKeys.translate(124) == MacKeys.RIGHT,
      "keys: arrows")
    check(MacKeys.translate(36) == MacKeys.ENTER && MacKeys.translate(76) == MacKeys.ENTER,
      "keys: enter")
    check(MacKeys.translate(53) == MacKeys.BACK && MacKeys.translate(49) == MacKeys.PTT,
      "keys: back/ptt")
    check(MacKeys.translate(12) == MacKeys.NONE, "keys: unknown code must answer NONE")
