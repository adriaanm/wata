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
    val root = IosStage.create(4, false)
    IosStage.setTree(v)
    mounted(root, "build") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, v, 4)
      case None                     => ()
    mirrorEq(v, "build")

  def patchSequenceMirrorsApplyAll(): Unit =
    val root = IosStage.create(4, false)
    IosStage.setTree(fixture())
    IosStage.applyScript(script())
    val want = Patches.applyAll(script(), fixture())
    mounted(root, "patch") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, want, 4)
      case None                     => ()
    mirrorEq(want, "patch")

  def psetSameKindMutatesInPlace(): Unit =
    val root = IosStage.create(2, false)
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
    val root = IosStage.create(2, false)
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
    val root = IosStage.create(2, false)
    IosStage.setTree(fixture())
    val flat = VText(0, 0, "ALL", 0x07ff)
    IosStage.applyScript(PSet(Nil, flat) :: Nil)
    mounted(root, "rootpset") match
      case m: Some[go.uikit.UIView] => assertMirrors("root", m.value, flat, 2)
      case None                     => ()
    mirrorEq(flat, "rootpset")

  def insertOrderIsPaintOrder(): Unit =
    val root = IosStage.create(2, false)
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
    val root = IosStage.create(scale, false)
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

  // ---- the pure arm: applyAll hand cases + the tables -----------------------

  def pure(): Unit =
    applyAllHandExpectation()
    psetKeepsTheChildKey()
    applyAllToleratesBadPaths()
    insertPastTheEndAppends()
    rgb565Components()
    expandAndScale()
    glyphMapping()
    pttTargetRule()

  /** plan 0067's target rule, on plain values: where a system-started PTT
   *  press goes. The default follows the most recent interaction; a fixed
   *  target wins while it exists and falls back when it does not (an account
   *  can be unenrolled while the phone is asleep, and a press must still go
   *  somewhere the user can find).
   *
   *  This is the only gate the rule gets: the framework does not exist in a
   *  simulator, so nothing here can press the system talk button.
   *
   *  The NAME is asserted through the same path the contact list renders,
   *  because `Conversation.name` is set for groups only — a DM is named by its
   *  contact and the family thread by the snapshot. */
  def pttTargetRule(): Unit =
    val fam = famConv("!fam", 100L)
    val bob = dmConv("!bob", "@bob:h", "Bob", 300L)
    val alma = dmConv("!alma", "@alma:h", "Alma", 200L)
    val all = fam :: bob :: alma :: Nil
    check(PttChan.targetRoom(all, "!fam", "") == "!bob",
      "ptt target: the default must follow the most recent interaction")
    check(PttChan.targetRoom(fam :: Nil, "!fam", "") == "!fam",
      "ptt target: one conversation with traffic is the target")
    check(PttChan.targetRoom(Nil, "!fam", "") == "!fam",
      "ptt target: with no traffic at all the family is the target")
    check(PttChan.targetRoom(all, "!fam", "!alma") == "!alma",
      "ptt target: a fixed room id must win over the most recent interaction")
    check(PttChan.targetRoom(all, "!fam", "@alma:h") == "!alma",
      "ptt target: a fixed CONTACT must resolve to that contact's room")
    check(PttChan.targetRoom(all, "!fam", "!gone") == "!bob",
      "ptt target: a fixed target that no longer exists must fall back")
    val snap = snapOf(all)
    check(PttChan.nameOf(snap, "!bob") == "Bob",
      "ptt target: a DM target is named by its contact")
    check(PttChan.nameOf(snap, "!fam") == "Kin",
      "ptt target: the family target is named by the snapshot's family")
    check(PttChan.nameOf(snap, "!gone") == "",
      "ptt target: an unknown room has no name to show")

  def famConv(room: String, at: Long): Conversation =
    Conversation(room, FamilyConv(), false, Contact(User("", "")),
      msgAt(room, at) :: Nil, 0, "")

  def dmConv(room: String, user: String, name: String, at: Long): Conversation =
    Conversation(room, DmConv(), true, Contact(User(user, name)),
      msgAt(room, at) :: Nil, 0, "")

  def msgAt(room: String, at: Long): VoiceMessage =
    VoiceMessage("$e" + room, User("@x:h", "x"), "mxc://x", 1000L, at,
      false, false, false)

  def snapOf(cs: List[Conversation]): StateSnapshot =
    StateSnapshot(Syncing(), true, User("@me:h", "Me"), Nil, cs, true,
      Family("!fam", "Kin", Nil), true)

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
