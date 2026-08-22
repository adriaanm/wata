/** `wata-ios interptest` — the retained interpreter's test suite, run as an
 *  argv mode of the app binary in the SIMULATOR (the `wata-mac interptest`
 *  idiom, executed by tools/ios-interptest.py): no server, no login — UIKit
 *  driven inside UIApplicationMain's ready hop on the main thread, each case
 *  inside its own autorelease pool.
 *
 *  This is wata-mac's interptest.scala transliterated, same case tables:
 *
 *    - the retained invariant on the real toolkit: after a mount and after a
 *      patch script, the NATIVE hierarchy (classes, order, frames, label
 *      strings) mirrors the plain tree `Patches.applyAll` produces; in-place
 *      mutation vs replacement; paint order on inserts
 *    - offscreen render probes through the layer pipeline
 *      (iosui.RenderPixel — iOS UIColor has no component reads, so the
 *      colour assertions read rendered pixels), non-blank text and probe
 *      pixels — NOT byte goldens (native fonts are not the fb font; the
 *      semantic oracle for appearance stays the fb goldens)
 *    - the applyAll hand-expectation cases (the subject is wataui's
 *      `Patches`, linked here), and the pure pixel/glyph tables
 *
 *  Dropped from the mac tables as AppKit-specific: keyTranslation (MacKeys
 *  maps RAW macOS virtual key codes — iOS has no key queue; stage 4's touch
 *  mapping brings its own tests).
 *
 *  Output: one `interptest: FAIL <case>: <detail>` line per failure and a
 *  final `interptest: PASS` / `interptest: FAIL (<n>)`; the harness greps
 *  the verdict line (the app cannot hand an exit code out of the
 *  simulator's launchd). */
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
    withPool(() => rolodexVocabularyDraws())
    withPool(() => rolodexAtRestIsOneCard())
    withPool(() => rolodexMidScrollIsAStack())
    withPool(() => rolodexCentreCardIsMarked())
    val n = failsC.get()
    if n == 0 then println("interptest: PASS")
    else println("interptest: FAIL (" + n + ")")
    n

  def withPool(f: () => Unit): Unit =
    val pool = go.iosui.poolPush()
    f()
    go.iosui.poolPop(pool)

  // ---- fixtures (wata-mac's, verbatim) --------------------------------------

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
    case _: VGroup => "UIView"
    case _: VText  => "UILabel"
    case _: VGlyph => "UILabel"
    case _: VRect  => "UIView" // a plain background-filled UIView, like the group
    case _: VImage => "UIImageView"
    case _: VFill  => "UIView" // VRect's native, plus a radius and an alpha
    case _: VLabel => "UILabel"

  /** recompute the scaled SEMANTIC rect independently of the interpreter —
   *  the geometry convention, pinned. UIKit's y points down like the stage's,
   *  so no flip (the one difference from the mac walk). Groups answer None
   *  (they span the stage). */
  def expectFrame(v: View, scale: Int): Option[go.uikit.CGRect] =
    def rect(x: Int, y: Int, w: Int, h: Int): Option[go.uikit.CGRect] =
      Some(go.uikit.CGRect(
        go.uikit.CGPoint((x * scale).toDouble, (y * scale).toDouble),
        go.uikit.CGSize((w * scale).toDouble, (h * scale).toDouble)))
    v match
      case t: VText  => rect(t.col * 6, 1 + t.row * 8, t.text.bytes.length * 6, 8)
      case t: VGlyph => rect(t.x, t.y, 6, 8)
      case t: VRect  => rect(t.x, t.y, t.w, t.h)
      case t: VImage => rect(t.x, t.y, t.w, t.h)
      case t: VFill  => rect(t.x, t.y, t.w, t.h)
      case t: VLabel => rect(t.x, t.y, t.w, t.h) // the frame IS the box
      case _: VGroup => None

  /** walk the native hierarchy and the plain tree side by side. */
  def assertMirrors(path: String, native: go.uikit.UIView, v: View, scale: Int): Unit =
    val cls = go.iosui.viewClassName(native)
    check(cls == wantClass(v), path + ": native class " + cls + ", want " + wantClass(v))
    expectFrame(v, scale) match
      case want: Some[go.uikit.CGRect] =>
        val f = native.frame()
        check(f == want.value, path + ": frame " + f + ", want " + want.value)
      case None => ()
    v match
      case x: VText =>
        val got = go.iosui.asLabel(native).text()
        check(got == x.text, path + ": label \"" + got + "\", want \"" + x.text + "\"")
      case x: VLabel =>
        val got = go.iosui.asLabel(native).text()
        check(got == x.text, path + ": label \"" + got + "\", want \"" + x.text + "\"")
      case x: VGlyph =>
        val got = go.iosui.asLabel(native).text()
        check(got == IosGlyphs.glyphString(x.glyph),
          path + ": glyph label \"" + got + "\", want \"" + IosGlyphs.glyphString(x.glyph) + "\"")
      case x: VGroup =>
        val n = go.iosui.subviewCount(native)
        check(n == Views.len(x.children),
          path + ": " + n + " subviews, want " + Views.len(x.children))
        if n == Views.len(x.children) then
          var i = 0
          while i < n do
            assertMirrors(path + "/" + i, go.iosui.subviewAt(native, i),
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
  def mounted(root: go.uikit.UIView, what: String): Option[go.uikit.UIView] =
    val n = go.iosui.subviewCount(root)
    if n != 1 then
      fail(what + ": stage should hold exactly one mounted tree, has " + n)
      None
    else Some(go.iosui.subviewAt(root, 0))

  def mirrorEq(want: View, what: String): Unit = IosStage.mirror() match
    case m: Some[View] => check(Views.eqView(m.value, want), what + ": mirror diverged")
    case None          => fail(what + ": no mirror after mounting")

  // ---- the retained-invariant cases -----------------------------------------

  def buildFromScratchMirrors(): Unit =
    val v = fixturePlusQr()
    val root = IosStage.create(Metrics.uniform(4), false)
    IosStage.setTree(v)
    mounted(root, "build") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, v, 4)
      case None                     => ()
    mirrorEq(v, "build")

  def patchSequenceMirrorsApplyAll(): Unit =
    val root = IosStage.create(Metrics.uniform(4), false)
    IosStage.setTree(fixture())
    IosStage.applyScript(script())
    val want = Patches.applyAll(script(), fixture())
    mounted(root, "patch") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, want, 4)
      case None                     => ()
    mirrorEq(want, "patch")

  def psetSameKindMutatesInPlace(): Unit =
    val root = IosStage.create(Metrics.uniform(2), false)
    IosStage.setTree(VGroup(Keyed("", VText(0, 0, "one", 0xffff)) :: Nil))
    mounted(root, "mutate") match
      case m: Some[go.uikit.UIView] =>
        val before = go.iosui.subviewAt(m.value, 0)
        IosStage.applyScript(PSet(0 :: Nil, VText(2, 3, "two", 0xf800)) :: Nil)
        val after = go.iosui.subviewAt(m.value, 0)
        check(go.iosui.sameView(before, after),
          "mutate: a same-constructor PSet must mutate the native view, not replace it")
        assertMirrors("root", m.value, VGroup(Keyed("", VText(2, 3, "two", 0xf800)) :: Nil), 2)
      case None => ()

  def psetKindChangeReplaces(): Unit =
    val root = IosStage.create(Metrics.uniform(2), false)
    IosStage.setTree(VGroup(
      Keyed("a", VText(0, 0, "gone", 0xffff)) ::
      Keyed("b", VRect(0, 100, 10, 10, 0x001f)) :: Nil))
    IosStage.applyScript(PSet(0 :: Nil, VRect(4, 8, 20, 16, 0x07e0)) :: Nil)
    val want = VGroup(
      Keyed("a", VRect(4, 8, 20, 16, 0x07e0)) ::
      Keyed("b", VRect(0, 100, 10, 10, 0x001f)) :: Nil)
    mounted(root, "replace") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, want, 2)
      case None                     => ()

  def rootPSetReplacesWholeTree(): Unit =
    val root = IosStage.create(Metrics.uniform(2), false)
    IosStage.setTree(fixture())
    val flat = VText(0, 0, "ALL", 0x07ff)
    IosStage.applyScript(PSet(Nil, flat) :: Nil)
    mounted(root, "rootpset") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, flat, 2)
      case None                     => ()
    mirrorEq(flat, "rootpset")

  def insertOrderIsPaintOrder(): Unit =
    val root = IosStage.create(Metrics.uniform(2), false)
    IosStage.setTree(VGroup(Keyed("b", VText(0, 2, "b", 0xffff)) :: Nil))
    // insert before, after, and in the middle; the subview walk pins that
    // native order tracks child order.
    IosStage.applyScript(
      PInsert(Nil, 0, Keyed("a", VText(0, 0, "a", 0xffff))) ::
      PInsert(Nil, 2, Keyed("d", VText(0, 6, "d", 0xffff))) ::
      PInsert(Nil, 2, Keyed("c", VText(0, 4, "c", 0xffff))) :: Nil)
    val want = VGroup(
      Keyed("a", VText(0, 0, "a", 0xffff)) ::
      Keyed("b", VText(0, 2, "b", 0xffff)) ::
      Keyed("c", VText(0, 4, "c", 0xffff)) ::
      Keyed("d", VText(0, 6, "d", 0xffff)) :: Nil)
    mounted(root, "insert") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, want, 2)
      case None                     => ()

  // ---- the render probes (iosui.RenderPixel: packed 0xRRGGBB, 0..255) -------

  def near(v: Int, want: Int): Boolean =
    val d = v - want
    d < 38 && d > -38 // the mac walk's 0.15 tolerance, in 8-bit components

  /** read a rendered pixel at STAGE coordinates (top-left origin, semantic
   *  pixels): the layer render is one pixel per point, row 0 = top, so the
   *  address is the scaled center of the semantic pixel. */
  def probe(root: go.uikit.UIView, x: Int, y: Int, scale: Int): Int =
    go.iosui.renderPixel(root, x * scale + scale / 2, y * scale + scale / 2)

  def probeIs(root: go.uikit.UIView, x: Int, y: Int, scale: Int,
    r: Int, g: Int, b: Int, what: String): Unit =
    val c = probe(root, x, y, scale)
    check(near((c >> 16) & 0xff, r) && near((c >> 8) & 0xff, g) && near(c & 0xff, b),
      what + " probe = " + ((c >> 16) & 0xff) + " " + ((c >> 8) & 0xff) + " " + (c & 0xff))

  def offscreenRenderProbes(): Unit =
    val scale = 4
    val root = IosStage.create(Metrics.uniform(scale), false)
    IosStage.setTree(VGroup(
      Keyed("bg", VRect(0, 0, 160, 128, 0x0000)) ::      // black stage
      Keyed("red", VRect(8, 8, 40, 24, 0xf800)) ::       // top-left red
      Keyed("blue", VRect(120, 100, 32, 20, 0x001f)) ::  // bottom-right blue
      Keyed("title", VText(2, 6, "WATA", 0xffff)) ::     // white text
      Keyed("qr", qrImage()) :: Nil))                    // the checker block
    // The red rect's center, in stage coordinates — also pins the row
    // orientation: red sits near the TOP of the stage, blue near the bottom.
    probeIs(root, 28, 20, scale, 255, 0, 0, "red")
    probeIs(root, 136, 110, scale, 0, 0, 255, "blue")
    probeIs(root, 80, 60, scale, 0, 0, 0, "background")
    // The image blit: the checker's white top-left cell.
    probeIs(root, 100, 60, scale, 255, 255, 255, "image")
    // Non-blank in the text run: some pixel in the title's cell block must
    // not be background black (the label drew SOMETHING white-ish).
    var found = false
    var y = 49
    while y < 57 && !found do
      var x = 12
      while x < 36 && !found do
        val c = probe(root, x, y, scale)
        if ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff) > 306 then found = true
        x += 1
      y += 1
    check(found, "text: the label rendered nothing visible")

  // ---- the rolodex vocabulary, drawn for real -------------------------------

  /** is any pixel in this semantic rectangle near-white? The label assertions
   *  are ink/no-ink rather than exact colours: a native face antialiases, and
   *  what is being proved is WHERE the glyphs landed, not their coverage. */
  def anyInk(root: go.uikit.UIView, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int): Boolean =
    var found = false
    var y = y0
    while y < y1 && !found do
      var x = x0
      while x < x1 && !found do
        val c = probe(root, x, y, scale)
        if ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff) > 306 then found = true
        x += 1
      y += 1
    found

  /** PLAN 0070's THREE ELEMENTS, drawn on the real toolkit and read back as
   *  pixels: a rounded card, a `display`-role name optically centred in it, a
   *  caption under that, and a translucent black band over the top.
   *
   *  This is the case that says the vocabulary is real rather than declared.
   *  Each probe is aimed at one claim:
   *
   *   - the CARD is filled and its corners are ROUNDED — a point outside the
   *     arc reads background, a point on the same edge below the arc reads
   *     card, so a stage that dropped the radius fails on the first and a
   *     stage that clipped the whole corner fails on the second;
   *   - the BAND is TRANSLUCENT — half-black over a saturated blue reads half
   *     blue, which an opaque black band and a missing band both fail;
   *   - the NAME is CENTRED — ink in the middle of the box and none against
   *     its leading edge, which is the alignment the design is made of and the
   *     one thing a body cannot compute for itself;
   *   - the CAPTION drew, under the name, in its own smaller box. */
  def rolodexVocabularyDraws(): Unit =
    val scale = 4
    val root = IosStage.create(Metrics.uniform(scale), false)
    // semantic pixels, the space every body addresses. The card is x 24..136,
    // y 12..116, with a 20px radius; the band covers its top 20 rows.
    val blue = 0x001f
    IosStage.setTree(VGroup(
      Keyed("bg", VRect(0, 0, 160, 128, 0x0000)) ::
      Keyed("card", VFill(24, 12, 112, 104, 20, blue, Alpha.OPAQUE)) ::
      Keyed("band", VFill(24, 12, 112, 20, 0, 0x0000, 128)) ::
      Keyed("name", VLabel(24, 40, 112, 28, "Ada", TypeRole.DISPLAY,
        TypeWeight.BOLD, TextAlign.CENTER, 0xffff, Alpha.OPAQUE)) ::
      Keyed("cap", VLabel(24, 76, 112, 12, "2 unheard", TypeRole.CAPTION,
        TypeWeight.REGULAR, TextAlign.CENTER, 0xffff, Alpha.OPAQUE)) :: Nil))

    // the fill, where nothing is over it
    probeIs(root, 80, 100, scale, 0, 0, 255, "vfill: the card")
    // the ROUNDED corner: (27,15) is 24 semantic px from the top-left arc's
    // centre (44,32) and the radius is 20, so it is OUTSIDE the card — square
    // corners would put half-black-over-blue there instead of background
    probeIs(root, 27, 15, scale, 0, 0, 0, "vfill: the top-left corner is rounded")
    // ... and the same left edge below the arc is still card, so the radius
    // rounded a corner rather than eating the side
    probeIs(root, 26, 60, scale, 0, 0, 255, "vfill: the left edge below the arc")
    // the TRANSLUCENT band: 50% black over saturated blue
    probeIs(root, 80, 20, scale, 0, 0, 128, "vfill: the band is translucent")

    // the display name is CENTRED in its box: ink in the middle, none against
    // the leading edge
    check(anyInk(root, 70, 44, 92, 64, scale),
      "vlabel: the display name drew nothing in the centre of its box")
    check(!anyInk(root, 26, 44, 40, 64, scale),
      "vlabel: a centred name must leave its leading edge empty")
    // the caption drew, in its own row under the name
    check(anyInk(root, 40, 77, 120, 87, scale),
      "vlabel: the caption drew nothing")

    // and the roles order by size, which is what makes a role worth naming
    val m = Metrics.uniform(scale)
    val big = 1.0e9
    val disp = TypeRoles.labelPoints(TypeRole.DISPLAY, m, big)
    val nm = TypeRoles.labelPoints(TypeRole.NAME, m, big)
    val cap = TypeRoles.labelPoints(TypeRole.CAPTION, m, big)
    val st = TypeRoles.labelPoints(TypeRole.STATUS, m, big)
    check(disp > nm && nm > cap && cap > st, "roles: display > name > caption > status")
    // a role is CLAMPED to the box it was given, so a display name in a
    // caption-sized strip is the biggest thing that fits, never an overflow
    check(TypeRoles.labelPoints(TypeRole.DISPLAY, m, 24.0) <= 24.0,
      "roles: a role must never overflow its box")

  // ---- the rolodex itself, drawn (plan 0070) --------------------------------

  /** any near-BLACK pixel in this semantic rectangle. The rolodex's ink is
   *  black on a saturated card (`Palette.INK` — every hue in the palette
   *  carries black text, which is the palette's constraint), so `anyInk`'s
   *  near-white test says nothing here. Read inside a card's own box, where the
   *  only thing that can be black is a glyph. */
  def anyBlackInk(root: go.uikit.UIView, x0: Int, y0: Int, x1: Int, y1: Int,
      scale: Int): Boolean =
    var found = false
    var y = y0
    while y < y1 && !found do
      var x = x0
      while x < x1 && !found do
        val c = probe(root, x, y, scale)
        if ((c >> 16) & 0xff) < 70 && ((c >> 8) & 0xff) < 70 && (c & 0xff) < 70 then found = true
        x += 1
      y += 1
    found

  /** three contacts with hand-picked hues, so the pixel assertions below say
   *  "this card" rather than "some card": blue, red, green in list order. */
  def roloCards(): List[RoloCard] =
    RoloCard("@ada:h", "Ada", 0x001f, "just now", 2, 0) ::
      RoloCard("@bob:h", "Bob", 0xf800, "5m ago", 0, 0) ::
      RoloCard("@cy:h", "Cy", 0x07e0, "no messages", 0, 0) :: Nil

  /** the semantic panel these two cases lay out in — a portrait-ish box, stated
   *  here rather than read from `Display`, so the geometry the assertions
   *  compute against is the geometry the body was handed. */
  val ROLO_W = 156
  val ROLO_H = 120

  def roloTree(m: Motion): View =
    Rolodex.body(roloCards(), 3, m, ROLO_W, ROLO_H)

  /** AT REST: one contact, FULL BLEED, in that person's colour — plan 0070's
   *  first sentence, read back as pixels.
   *
   *  Each probe discriminates one claim: the card reaches the panel's corner
   *  (full bleed has no rounded corner and no margin — a stack row would put
   *  background there), the unheard band is across its top, the name is
   *  CENTRED (ink in the middle, none against the leading edge), and the
   *  neighbours are not on the panel at all — which is what "the list still
   *  exists, but only when asked for" means as a frame. */
  def rolodexAtRestIsOneCard(): Unit =
    val scale = 4
    val root = IosStage.create(Metrics.uniform(scale), false)
    val v = roloTree(Motion.initial())
    IosStage.setTree(v)
    v match
      case g: VGroup =>
        check(Views.len(g.children) == 1,
          "rolodex rest: " + Views.len(g.children) + " cards on the panel, want 1")
      case _ => fail("rolodex rest: the body should be a group of cards")
    // Ada's blue, well below the name and the state line
    probeIs(root, 10, 110, scale, 0, 0, 255, "rolodex rest: the card is full bleed")
    // ... including the panel's very corner: a full-bleed card has no radius
    // and no side margin, so a stack row's geometry would read background here
    probeIs(root, 1, 110, scale, 0, 0, 255, "rolodex rest: the card reaches the edge")
    // the unheard band across the top (2 unheard), in yellow
    probeIs(root, 10, 10, scale, 255, 255, 0, "rolodex rest: the unheard band")
    // the name is CENTRED in the card: black ink in the middle of its box...
    check(anyBlackInk(root, 60, 48, 96, 72, scale),
      "rolodex rest: the display name drew nothing in the centre of the card")
    // ... and none against the leading edge, which is what centring MEANS
    check(!anyBlackInk(root, 4, 48, 20, 72, scale),
      "rolodex rest: a centred name must leave the card's leading edge empty")
    // the state line under it
    check(anyBlackInk(root, 40, 82, 116, 94, scale),
      "rolodex rest: the state line drew nothing")

  /** MID-SCROLL: the stack open, the centre card under a fixed band and a
   *  neighbour above and below — the same three cards, the same body, a
   *  different `Motion`.
   *
   *  The geometry is recomputed here by hand from the panel rather than read
   *  out of `Rolodex`: rows are `H/5 = 24` tall with a 2px gutter, the centre
   *  band starts at `(H - 24)/2 = 48`, and the side margin is `W/26 = 6`. A
   *  layout change that means to move things has to say so here. */
  def rolodexMidScrollIsAStack(): Unit =
    val scale = 4
    val root = IosStage.create(Metrics.uniform(scale), false)
    // the second card centred, the stack fully open, an input just now
    val m = Motion(MotionAxis(1.0, 0.0, 0.0), MotionAxis(0.0, 0.0, 0.0), 0.0, 1.0)
    val v = roloTree(m)
    IosStage.setTree(v)
    v match
      case g: VGroup =>
        check(Views.len(g.children) == 5,
          "rolodex stack: " + Views.len(g.children) + " children on the panel, " +
            "want 3 cards and the band's two nubs")
      case _ => fail("rolodex stack: the body should be a group of cards")
    // the centre band holds Bob (red), rows 48..70
    probeIs(root, 135, 60, scale, 255, 0, 0, "rolodex stack: the centre card")
    // Ada (blue) above it, rows 27..43 — a NEIGHBOUR, so inset and at half
    // strength over the black panel (the centre treatment, below)
    probeIs(root, 135, 35, scale, 0, 0, 166, "rolodex stack: the neighbour above")
    // Cy (green) below it, rows 75..91
    probeIs(root, 135, 82, scale, 0, 166, 0, "rolodex stack: the neighbour below")
    // the gutter between two rows is the panel, not a card
    probeIs(root, 80, 47, scale, 0, 0, 0, "rolodex stack: the gutter between rows")
    // the rows are CARDS: the arc's centre is (14,56) and its radius is 8
    // semantic px, so (7,49) — read at its pixel CENTRE, 7.8 away — is outside
    // the card where a square-cornered row would read red
    probeIs(root, 7, 49, scale, 0, 0, 0, "rolodex stack: the centre card's corner is rounded")
    // ... and the same left edge below the arc is still card, so the radius
    // rounded a corner rather than eating the side
    probeIs(root, 8, 60, scale, 255, 0, 0, "rolodex stack: the left edge below the arc")
    // the stack's names are LEADING-aligned — a roster is read down its left
    // edge, not down its middle
    check(anyBlackInk(root, 16, 52, 44, 68, scale),
      "rolodex stack: the centre card's name drew nothing at its leading edge")
    check(!anyBlackInk(root, 120, 52, 146, 68, scale),
      "rolodex stack: a leading-aligned name must leave the trailing edge empty")

  /** WHICH CARD THE TALK BUTTON REACHES, read off the panel.
   *
   *  The three cards here are the SAME COLOUR on purpose: identity is removed
   *  from the frame, so the only thing that can tell the centre row from a
   *  neighbour is the emphasis itself. A stack that drew every row alike — what
   *  this screen did before — fails every probe below.
   *
   *  Each probe is one of the three means, plus the band:
   *
   *   - BRIGHTNESS: the centre card is at full strength and a neighbour is
   *     dimmed over the black panel;
   *   - WIDTH: `x = 147` is inside the centre card and outside an inset
   *     neighbour, so one reads card and the other reads panel;
   *   - the BAND: the fixed white nubs are at the centre row's height and
   *     nowhere else, which is what holds mid-scroll;
   *   - and it all holds when the centre card is only PARTLY aligned with the
   *     band, which is the frame a screenshot is most likely to catch. */
  def rolodexCentreCardIsMarked(): Unit =
    val scale = 4
    val root = IosStage.create(Metrics.uniform(scale), false)
    val same =
      RoloCard("@a:h", "Ada", 0x001f, "", 0, 0) ::
        RoloCard("@b:h", "Bob", 0x001f, "", 0, 0) ::
        RoloCard("@c:h", "Cy", 0x001f, "", 0, 0) :: Nil
    // card 1 centred, the stack fully open
    IosStage.setTree(Rolodex.body(same, 3,
      Motion(MotionAxis(1.0, 0.0, 0.0), MotionAxis(0.0, 0.0, 0.0), 0.0, 1.0),
      ROLO_W, ROLO_H))
    val cb = probe(root, 80, 60, scale) & 0xff
    val nb = probe(root, 80, 35, scale) & 0xff
    check(cb > nb + 60,
      "rolodex centre: the centre card is no brighter than its neighbour (" +
        cb + " vs " + nb + ")")
    probeIs(root, 147, 60, scale, 0, 0, 255, "rolodex centre: the centre card is the wide one")
    probeIs(root, 147, 35, scale, 0, 0, 0, "rolodex centre: a neighbour is inset")
    probeIs(root, 1, 60, scale, 255, 255, 255, "rolodex centre: the band's nub")
    probeIs(root, 1, 35, scale, 0, 0, 0, "rolodex centre: the nub marks the band, not the panel")

    // MID-SCROLL: p = 1.3, so card 1 is still what `Motion.centre` rounds to
    // and is still the emphasised one, though it sits 7px above the band.
    val root2 = IosStage.create(Metrics.uniform(scale), false)
    IosStage.setTree(Rolodex.body(same, 3,
      Motion(MotionAxis(1.3, 0.0, 0.0), MotionAxis(0.0, 0.0, 0.0), 0.0, 1.0),
      ROLO_W, ROLO_H))
    probeIs(root2, 147, 50, scale, 0, 0, 255,
      "rolodex centre: mid-scroll the centre card is still the wide one")
    probeIs(root2, 147, 75, scale, 0, 0, 0,
      "rolodex centre: mid-scroll a neighbour is still inset")

  // ---- the pure arm: applyAll hand cases + the tables -----------------------

  def pure(): Unit =
    motionSettlesOnADetent()
    motionFlickCoastsFurther()
    motionEndSpringHolds()
    motionHorizontalIsPinned()
    motionSurvivesALongFrame()
    motionAxesMatchTheIntents()
    paletteIsDeterministic()
    applyAllHandExpectation()
    psetKeepsTheChildKey()
    applyAllToleratesBadPaths()
    insertPastTheEndAppends()
    rgb565Components()
    expandAndScale()
    glyphMapping()
    // (wata-ios's pttTargetRule case is dropped here: plan 0067's target
    // rule lives in ptt.scala, which is the phone's PushToTalk binding.
    // The watch has no probed equivalent yet — WATCH-AUDIO's question —
    // and the rule is pure list logic that the phone's suite already
    // gates, so copying it would duplicate a gate, not extend one.)

  // ---- the motion integrator (wataui/motion.scala, plan 0070) ---------------

  /** run the model at a fixed frame rate for `secs`, with no further input. */
  def coast(m0: Motion, secs: Double, count: Int): Motion =
    var m = m0
    val dt = 1.0 / 60.0
    var t = 0.0
    while t < secs do
      m = Motion.step(m, dt, count)
      t = t + dt
    m

  /** ONE CROWN DETENT IS ONE CARD, and it comes to a full stop.
   *
   *  This is the whole model in one assertion: an impulse of one item adds 7
   *  items/s, friction takes it down through the snap threshold having coasted
   *  about half a card, the critically damped detent spring carries it the rest
   *  of the way, and the rest snap parks it EXACTLY on the detent — which is
   *  what lets the frame clock go quiet. A model that jittered forever would
   *  pass "near 1.0" and fail `live`. */
  def motionSettlesOnADetent(): Unit =
    val m = coast(Motion.impulse(Motion.initial(), MotionAxes.V, 1.0), 2.0, 8)
    check(Motion.offset(m) == 1.0,
      "motion: one detent should land exactly on card 1, landed on " + Intents.fmt(Motion.offset(m)))
    check(!Motion.live(m), "motion: the model must come to rest, so the pump can stop painting")
    check(Motion.openness(m) == 0.0,
      "motion: the stack must close " + Intents.fmt(Motion.SETTLE_S) + "s after the last input")
    check(Motion.centre(m, 8) == 1, "motion: the centre detent should be card 1")

  /** TWO QUICK PRESSES ARE TWICE THE SHOVE — acceleration falls out of adding
   *  velocity, so a flick coasts several cards where a nudge coasts one, with
   *  no curve anywhere in the shell. */
  def motionFlickCoastsFurther(): Unit =
    val nudge = coast(Motion.impulse(Motion.initial(), MotionAxes.V, 1.0), 2.0, 20)
    val flick = coast(Motion.impulse(Motion.initial(), MotionAxes.V, 3.0), 2.0, 20)
    check(Motion.offset(flick) > Motion.offset(nudge) + 1.0,
      "motion: a flick must travel further than a nudge (" +
        Intents.fmt(Motion.offset(flick)) + " vs " + Intents.fmt(Motion.offset(nudge)) + ")")
    check(Motion.offset(flick) == Motion.nearest(Motion.offset(flick)),
      "motion: a flick must still land on a detent, landed on " + Intents.fmt(Motion.offset(flick)))

  /** THE END GIVES AND BOUNCES BACK. A shove far past the last card overshoots
   *  — that overshoot is how a kid learns the list has an end — and then comes
   *  back to the last card and stops there, rather than being clamped (which
   *  would feel like hitting a wall that was always there). */
  def motionEndSpringHolds(): Unit =
    var m = Motion.impulse(Motion.initial(), MotionAxes.V, 20.0)
    var maxPos = 0.0
    var t = 0.0
    val dt = 1.0 / 60.0
    while t < 2.0 do
      m = Motion.step(m, dt, 3)
      if Motion.offset(m) > maxPos then maxPos = Motion.offset(m)
      t = t + dt
    check(maxPos > 2.0, "motion: the end must GIVE — nothing overshot card 2")
    check(maxPos < 4.0, "motion: the end spring let the list run away to " + Intents.fmt(maxPos))
    check(Motion.offset(m) == 2.0,
      "motion: the list must come back to its last card, rests at " + Intents.fmt(Motion.offset(m)))

  /** THE HORIZONTAL AXIS IS RESERVED AND UNUSED. It is integrated by the same
   *  code against a single item, so even a shove leaves it where it started —
   *  pinned by construction, not by a branch that would have to be found and
   *  removed the day something does move sideways. */
  def motionHorizontalIsPinned(): Unit =
    val m = coast(Motion.impulse(Motion.initial(), MotionAxes.H, 5.0), 2.0, 8)
    check(m.h.pos == 0.0,
      "motion: the reserved axis moved to " + Intents.fmt(m.h.pos))
    check(Motion.offset(m) == 0.0, "motion: a horizontal shove moved the vertical list")

  /** A LONG FRAME MUST NOT EXPLODE. The springs are explicit Euler at
   *  stiffness 340, which is unstable at a whole frame — the accumulator and
   *  the sub-step are what make a stalled frame a slow frame rather than a
   *  detonation, and `MAX_DT` caps what one step will ever simulate. */
  def motionSurvivesALongFrame(): Unit =
    var m = Motion.impulse(Motion.initial(), MotionAxes.V, 2.0)
    m = Motion.step(m, 5.0, 8) // a five-second hitch, handed over in one go
    check(Motion.offset(m) > -1.0 && Motion.offset(m) < 8.0,
      "motion: a long frame blew the integrator to " + Intents.fmt(Motion.offset(m)))
    m = coast(m, 2.0, 8)
    check(Motion.offset(m) == Motion.nearest(Motion.offset(m)),
      "motion: after a long frame the model must still settle on a detent")

  /** the shell's axis constants and the model's are the same two numbers. They
   *  are declared in two modules — `Intents` is the seam with watchshell's
   *  input.go, `MotionAxes` is the model's — and a silent disagreement would
   *  send every crown turn into the reserved axis. */
  def motionAxesMatchTheIntents(): Unit =
    check(Intents.AXIS_V == MotionAxes.V && Intents.AXIS_H == MotionAxes.H,
      "motion: the intent axes and the model's axes have drifted apart")

  // ---- the palette (wataclient/palette.scala, plan 0070) --------------------

  /** THE SAME PERSON IS THE SAME COLOUR EVERYWHERE. The derivation is the
   *  fallback until the profile field ships, and its whole job is that a
   *  handset and a wrist agree without talking to each other — so it must be a
   *  pure function of the id, the eight hues must be eight, and the family
   *  thread must stay out of the rotation. */
  def paletteIsDeterministic(): Unit =
    check(Palette.forUser("@ada:example.org") == Palette.forUser("@ada:example.org"),
      "palette: the derivation is not a function of the id")
    check(Palette.forUser("@ada:h") != Palette.forUser("@bob:h"),
      "palette: two ids that should differ do not (a real collision is fine; " +
        "these two are the fixture)")
    var i = 0
    var distinct = true
    while i < Palette.COUNT do
      var j = i + 1
      while j < Palette.COUNT do
        if Palette.hue(i) == Palette.hue(j) then distinct = false
        j += 1
      i += 1
    check(distinct, "palette: two hues in the palette are the same colour")
    check(Palette.hue(Palette.COUNT) == Palette.hue(0), "palette: the palette must wrap")
    check(Palette.hue(-1) == Palette.hue(Palette.COUNT - 1),
      "palette: a negative index must wrap the same way")
    var famClash = false
    i = 0
    while i < Palette.COUNT do
      if Palette.hue(i) == Palette.FAMILY then famClash = true
      i += 1
    check(!famClash, "palette: a person's hue collides with the family thread's cyan")
    check(Palette.forConversation(FamilyConv(), false, "", "!fam:h") == Palette.FAMILY,
      "palette: the family thread must keep cyan")
    check(Palette.forConversation(DmConv(), true, "@ada:h", "!dm:h") ==
      Palette.forUser("@ada:h"),
      "palette: a DM must take its CONTACT's colour, not its room's")
    paletteRosterIsDistinct()

  /** A ROSTER'S COLOURS ARE ALL DIFFERENT, up to the palette's size.
   *
   *  This is what a per-id hash cannot promise: eight hues and five people
   *  collide about four times in five, and a screen where two of five contacts
   *  are the same colour is a screen whose whole argument ("roll to my colour")
   *  has failed. `forRoster` assigns over the SET, so the only thing left to
   *  prove is that the answer is distinct, order-independent, and the same on
   *  every client — which for a pure function means: the same set in, the same
   *  colours out, whatever order they arrive in. */
  def nthInt(xs: List[Int], i: Int): Int =
    var cur = xs
    var j = 0
    var out = -1
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then
            out = h
            going = false
          else
            j += 1
            cur = t
        case Nil => going = false
    out

  def lenInt(xs: List[Int]): Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  def paletteRosterIsDistinct(): Unit =
    // the five the owner watched collide on the simulator, plus the family
    // thread (the `""` subject, which takes no hue out of the rotation)
    val ids = "" :: "@bob:h" :: "@carol:h" :: "@dave:h" :: "@erin:h" :: "@alice:h" :: Nil
    val cols = Palette.forRoster(ids)
    check(lenInt(cols) == 6,
      "palette: forRoster must answer one colour per subject, answered " + lenInt(cols))
    check(nthInt(cols, 0) == Palette.FAMILY,
      "palette: the empty subject is the family thread and must stay cyan")
    var i = 1
    var clash = false
    while i < 6 do
      var j = i + 1
      while j < 6 do
        if nthInt(cols, i) == nthInt(cols, j) then clash = true
        j += 1
      i += 1
    check(!clash,
      "palette: two people in a five-person roster came out the same colour")
    // the SET decides, not the order a client's sync happened to build its list
    // in — which is the property that lets two devices agree without talking
    val shuffled = "@erin:h" :: "@alice:h" :: "@dave:h" :: "@bob:h" :: "@carol:h" :: Nil
    check(nthInt(Palette.forRoster(shuffled), 0) == nthInt(cols, 4),
      "palette: the same roster in a different order gave a person a different colour")
    // a roster larger than the palette must still answer, wrapping rather than
    // stalling once all eight hues are spent
    var big: List[String] = Nil
    var n = 0
    while n < 20 do
      big = ("@u" + n + ":h") :: big
      n += 1
    check(lenInt(Palette.forRoster(big)) == 20,
      "palette: a roster larger than the palette must still get a colour each")

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
    check(IosPixels.red(0x0000) == 0.0 && IosPixels.green(0x0000) == 0.0 && IosPixels.blue(0x0000) == 0.0, "rgb565: 0x0000")
    check(IosPixels.red(0xffff) == 1.0 && IosPixels.green(0xffff) == 1.0 && IosPixels.blue(0xffff) == 1.0, "rgb565: 0xffff")
    check(IosPixels.red(0xf800) == 1.0 && IosPixels.green(0xf800) == 0.0 && IosPixels.blue(0xf800) == 0.0, "rgb565: 0xf800")
    check(IosPixels.red(0x07e0) == 0.0 && IosPixels.green(0x07e0) == 1.0 && IosPixels.blue(0x07e0) == 0.0, "rgb565: 0x07e0")
    check(IosPixels.red(0x001f) == 0.0 && IosPixels.green(0x001f) == 0.0 && IosPixels.blue(0x001f) == 1.0, "rgb565: 0x001f")

  def expandAndScale(): Unit =
    // One red pixel, one green (RGB565 little-endian pairs).
    val b = new BytesBuilder
    b.addU16LE(0xf800)
    b.addU16LE(0x07e0)
    val rgba = IosPixels.expandRGB565(b.result(), 2, 1)
    check(rgba(0) == 255.toByte && rgba(1) == 0.toByte && rgba(2) == 0.toByte && rgba(3) == 255.toByte &&
      rgba(4) == 0.toByte && rgba(5) == 255.toByte && rgba(6) == 0.toByte && rgba(7) == 255.toByte,
      "expand: RGBA bytes diverge")
    val big = IosPixels.scaleRGBANearest(rgba, 2, 1, 3)
    check(big.length == 6 * 3 * 4, "scale: length " + big.length)
    // pixel (5,2) is the bottom-right of the green block
    val o = (2 * 6 + 5) * 4
    check(big(o) == 0.toByte && big(o + 1) == 255.toByte && big(o + 2) == 0.toByte, "scale: corner pixel")

  def glyphMapping(): Unit =
    check(IosGlyphs.glyphString(0x80) == "✓" && IosGlyphs.glyphString(0x90) == "▶" &&
      IosGlyphs.glyphString(0x8d) == "★", "glyphs: icon mapping broken")
    check(IosGlyphs.glyphString(65) == "A", "glyphs: ASCII should render as itself")
    check(IosGlyphs.glyphString(0xB0) == IosGlyphs.PLACEHOLDER &&
      IosGlyphs.glyphString(0x05) == IosGlyphs.PLACEHOLDER, "glyphs: unmapped codes should render the placeholder")
