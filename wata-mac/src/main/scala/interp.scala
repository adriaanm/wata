import language.experimental.saferExceptions

/** The retained interpreter: a wataui view tree as a live NSView tree, at an
 *  integer scale of the 160x128 stage (plans 0032/0038) — `wataui`'s `View`
 *  and `Patch` consumed DIRECTLY, over facades on the generated appkit
 *  bindings (`appkit.scala`) plus `go.nativeui`'s Go glue for what a facade
 *  cannot say (casts, named-scalar params, the raw RGBA bitmap crossing).
 *  The frame wire this replaced — a per-frame string grammar between the
 *  Sgola differ and a Go interpreter — is deleted, not ported.
 *
 *  The element table, per constructor:
 *
 *    VText  -> NSTextField label (monospaced system font sized to the cell)
 *    VGlyph -> NSTextField label over the glyph mapping (glyphs.scala)
 *    VRect  -> NSBox, custom/borderless, fillColor = the RGB565 color widened
 *    VImage -> NSImageView over RGB565->RGBA widening + nearest-neighbour
 *              pre-scaling (pixels.scala), handed across as raw RGBA bytes
 *              into one NSBitmapImageRep (glue) — AppKit never interpolates
 *    VGroup -> a plain container NSView; subview order IS paint order (AppKit
 *              draws later subviews over earlier — the same rule the algebra
 *              has)
 *
 *  VRect is an NSBox rather than a layer-backed NSView: layer background
 *  colors speak CGColorRef, which bindgen refuses, while NSBox's fillColor is
 *  a plain NSColor — and NSBox draws through drawRect:, so offscreen renders
 *  (cacheDisplayInRect) see it without a presented layer tree.
 *
 *  Geometry: coordinates are wataui's semantic positions. VText addresses the
 *  26x15 character grid of the fb font (6x8 cells, text rows starting 1px
 *  down — display.scala's Font.drawText); everything else addresses stage
 *  pixels. Both scale by the integer scale. AppKit's y axis points UP, so
 *  every frame is flipped against the stage height; group containers all span
 *  the full stage, which keeps child coordinates stage-absolute.
 *
 *  Threading: every function that touches the native tree must run on the
 *  AppKit thread. Headless that is the locked main goroutine, and the pump
 *  calls `submitTree`/`submitScript` which run DIRECTLY (pool-bracketed);
 *  windowed the pump PUBLISHES the frame and `go.nativeui.onMain` dispatches
 *  the module-registered `go.callback` trampoline onto the main queue, which
 *  drains and applies — one frame per queue turn. The retained node tree and
 *  its mirror live in one atomic cell, only ever touched from the stage's
 *  thread; the cell exists so the windowed callback (a different goroutine
 *  than the one that created the stage) can reach them. */

/** one native view and, for groups, its children — the same shape as the
 *  mirror, so paths index both identically. `view` is only read for
 *  constructor checks (the PSet fast path, PInsert's group check); the
 *  authoritative tree is the mirror. */
case class MacNode(view: View, native: go.appkit.NSView, kids: List[MacNode])

/** the whole retained state: geometry scale, the container view, the shared
 *  label font, the mounted tree and the plain mirror (`Patches.applyAll`
 *  semantics — the invariant `wata-mac interptest` holds against the real
 *  hierarchy). */
case class MacStageSt(scale: Int, root: go.appkit.NSView, font: go.appkit.NSFont,
  top: Option[MacNode], mirror: Option[View])

object MacStage:
  // The stage the view algebra addresses: the device panel's geometry
  // (display.scala's Display/Font constants).
  val StageW = 160
  val StageH = 128
  val GlyphW = 6 // 5px glyph + 1px spacing
  val GlyphH = 8
  val TextTopPad = 1 // text row 0 starts at y=1, below the 1px status line

  /** monospaced font points per unit of scale. The fb glyph cell is 6x8; no
   *  vector font matches both, and the row pitch is what must hold, so the
   *  size keeps the line height inside the 8px cell (SF Mono's ascent+descent
   *  is ~1.16em). The advance (~0.6em) is then narrower than the 6px cell —
   *  columns still line up, because every VText is framed from its own grid
   *  cell; only intra-string width shrinks. The semantic oracle for
   *  appearance stays the fb goldens. */
  val cellFontPts = 6.8

  private val stC: sgo.Atomic[Option[MacStageSt]] = sgo.atomic(None)
  /** windowed = publish to the main queue; headless = apply inline. */
  private val windowedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** patches the pump published and the main-queue callback has not applied
   *  yet, NEWEST FIRST (drain reverses back to publish order). One flat
   *  patch list, not a list of frames: scripts concatenate soundly (each is
   *  written against the tree the previous ones produced), and a whole-tree
   *  handoff IS a root `PSet` in the patch vocabulary.
   *  SUM-CASE-GENERIC-FIELD: a dedicated sum type here (a case carrying
   *  `List[Patch]`) emits an unparameterized `List` field — flat patches
   *  until the flattened-case emission carries the instantiation. */
  private val pendingC: sgo.Atomic[List[Patch]] = sgo.atomic(Nil)

  /** the windowed apply trampoline, minted ONCE at module init (the
   *  registration contract: never per-frame). The literal captures nothing —
   *  it reaches the stage through the module cells. */
  val applyCb: go.Uintptr = go.callback(() => MacStage.drainPending())

  /** create the scaled stage container (scale*160 x scale*128) and the label
   *  font. AppKit thread only; bracket in a pool. Answers the root view for
   *  `macshell.adoptRoot`. */
  def create(scale0: Int, windowed: Boolean): go.appkit.NSView =
    val scale = if scale0 < 1 then 1 else scale0
    val root = go.appkit.getNSViewClass().alloc().initWithFrame(stageRect(scale))
    val font = go.appkit.getNSFontClass()
      .monospacedSystemFontOfSizeWeight(cellFontPts * scale.toDouble, 0.0 /* regular */)
    // The factory answer is AUTORELEASED, and the stage outlives the frame's
    // pool (every apply runs in a pool of its own); retain it, or the second
    // frame's label call dies on a freed font. Everything else the
    // interpreter allocates is either owned (-alloc) or retained by its
    // superview before the pool pops.
    go.nativeui.retainFont(font)
    windowedC.set(windowed)
    stC.set(Some(MacStageSt(scale, root, font, None, None)))
    root

  /** the plain view tree the native tree currently shows — the "retained
   *  mirror" of wataui's applyAll contract. None before the first tree. */
  def mirror(): Option[View] = stC.get() match
    case s: Some[MacStageSt] => s.value.mirror
    case None                => None

  // ---- the per-mode frame handoff -------------------------------------------

  /** hand the whole first tree to the stage (the pump's seam) — a root PSet
   *  in the patch vocabulary. */
  def submitTree(v: View): Unit = submit(PSet(Nil, v) :: Nil)

  /** hand one differ script to the stage, in script order (the pump's seam). */
  def submitScript(ps: List[Patch]): Unit = submit(ps)

  def submit(ps: List[Patch]): Unit =
    if windowedC.get() then
      val stored = pendingC.update(ms => prependReversed(ps, ms))
      go.nativeui.onMain(applyCb)
    else
      // headless: the caller IS the stage's thread (the locked main
      // goroutine) — apply inline, pool-bracketed
      val pool = go.nativeui.poolPush()
      applyScript(ps)
      go.nativeui.poolPop(pool)

  /** push `ps` onto `ms` element by element, so the accumulated cell stays
   *  newest-first and ONE reverse at the drain restores publish order.
   *  Pure — `Atomic.update`'s CAS loop may run it more than once. */
  def prependReversed(ps: List[Patch], ms: List[Patch]): List[Patch] =
    var out = ms
    var cur = ps
    var going = true
    while going do
      cur match
        case h :: t =>
          out = h :: out
          cur = t
        case Nil => going = false
    out

  /** the main-queue callback's body: apply everything published, in publish
   *  order. AppKit thread; the pool is the Go dispatcher's. */
  def drainPending(): Unit =
    applyScript(ListOps.reverse(pendingC.getAndSet(Nil)))

  // ---- mounting and patching (AppKit thread only) ---------------------------

  /** build the native tree from scratch, replacing whatever was mounted. */
  def setTree(v: View): Unit = stC.get() match
    case s0: Some[MacStageSt] =>
      val s = s0.value
      s.top match
        case t: Some[MacNode] => t.value.native.removeFromSuperview()
        case None             => ()
      val top = build(s, v)
      s.root.addSubview(top.native)
      stC.set(Some(MacStageSt(s.scale, s.root, s.font, Some(top), Some(v))))
    case None => ()

  /** mutate the native tree by the differ's script, in script order — the
   *  retained arm of Patches.applyAll. */
  def applyScript(ps: List[Patch]): Unit =
    var cur = ps
    var going = true
    while going do
      cur match
        case p :: t =>
          applyPatch(p)
          cur = t
        case Nil => going = false

  def applyPatch(p: Patch): Unit = stC.get() match
    case s0: Some[MacStageSt] =>
      val s = s0.value
      val top = p match
        case q: PSet    => doSet(s, s.top, q.path, q.view)
        case q: PInsert => doInsert(s, s.top, q.path, q.idx, q.keyed)
        case q: PDelete => doDelete(s, s.top, q.path, q.idx)
      val mirror = s.mirror match
        case m: Some[View] => Some(Patches.applyOne(p, m.value))
        case None => p match
          // mirror semantics: only a root PSet can mount into nothing
          case q: PSet => if lenInts(q.path) == 0 then Some(q.view) else None
          case _       => None
      stC.set(Some(MacStageSt(s.scale, s.root, s.font, top, mirror)))
    case None => ()

  // ---- patch arms — each answers the updated top ----------------------------

  def doSet(s: MacStageSt, top: Option[MacNode], path: List[Int], v: View): Option[MacNode] =
    top match
      case None =>
        // nothing mounted: a root PSet mounts (SetTree semantics), any other
        // path is a no-op into nothing
        path match
          case Nil =>
            val fresh = build(s, v)
            s.root.addSubview(fresh.native)
            Some(fresh)
          case _ => None
      case t: Some[MacNode] =>
        path match
          case Nil =>
            mutate(s, t.value, v) match
              case n2: Some[MacNode] => Some(n2.value)
              case None =>
                val fresh = build(s, v)
                s.root.replaceSubviewWith(t.value.native, fresh.native)
                Some(fresh)
          case _ => Some(setIn(s, t.value, path, v))

  /** replace the child `path` points at inside `n` — mutate in place when the
   *  constructor matches, else build + replaceSubview. A path into nothing is
   *  a no-op (mirror semantics). */
  def setIn(s: MacStageSt, n: MacNode, path: List[Int], v: View): MacNode = path match
    case h :: Nil =>
      nthNode(n.kids, h) match
        case c: Some[MacNode] =>
          val child = c.value
          mutate(s, child, v) match
            case c2: Some[MacNode] => MacNode(n.view, n.native, setNodeAt(n.kids, h, c2.value))
            case None =>
              val fresh = build(s, v)
              n.native.replaceSubviewWith(child.native, fresh.native)
              MacNode(n.view, n.native, setNodeAt(n.kids, h, fresh))
        case None => n // mirror semantics: a path into nothing is a no-op
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[MacNode] =>
          MacNode(n.view, n.native, setNodeAt(n.kids, h, setIn(s, c.value, t, v)))
        case None => n
    case Nil => n // unreachable: the root case is doSet's

  def doInsert(s: MacStageSt, top: Option[MacNode], path: List[Int], idx: Int, k: Keyed): Option[MacNode] =
    top match
      case t: Some[MacNode] => Some(insertIn(s, t.value, path, idx, k))
      case None             => None

  def insertIn(s: MacStageSt, n: MacNode, path: List[Int], idx: Int, k: Keyed): MacNode = path match
    case Nil =>
      val isGroup = n.view match
        case _: VGroup => true
        case _         => false
      if !isGroup || idx < 0 then n
      else
        nthNode(n.kids, idx) match
          case at: Some[MacNode] =>
            // below the current occupant of idx: earlier in subviews =
            // painted first
            val fresh = build(s, k.view)
            go.nativeui.addSubviewBelow(n.native, fresh.native, at.value.native)
            MacNode(n.view, n.native, insertNodeAt(n.kids, idx, fresh))
          case None =>
            val fresh = build(s, k.view)
            n.native.addSubview(fresh.native)
            MacNode(n.view, n.native, appendNode(n.kids, fresh))
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[MacNode] =>
          MacNode(n.view, n.native, setNodeAt(n.kids, h, insertIn(s, c.value, t, idx, k)))
        case None => n

  def doDelete(s: MacStageSt, top: Option[MacNode], path: List[Int], idx: Int): Option[MacNode] =
    top match
      case t: Some[MacNode] => Some(deleteIn(s, t.value, path, idx))
      case None             => None

  def deleteIn(s: MacStageSt, n: MacNode, path: List[Int], idx: Int): MacNode = path match
    case Nil =>
      nthNode(n.kids, idx) match
        case at: Some[MacNode] =>
          at.value.native.removeFromSuperview()
          MacNode(n.view, n.native, removeNodeAt(n.kids, idx))
        case None => n
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[MacNode] =>
          MacNode(n.view, n.native, setNodeAt(n.kids, h, deleteIn(s, c.value, t, idx)))
        case None => n

  // ---- building -------------------------------------------------------------

  def build(s: MacStageSt, v: View): MacNode = v match
    case x: VGroup =>
      val native = go.appkit.getNSViewClass().alloc().initWithFrame(stageRect(s.scale))
      var kids: List[MacNode] = Nil
      var cur = x.children
      var going = true
      while going do
        cur match
          case h :: t =>
            val kid = build(s, h.view)
            native.addSubview(kid.native)
            kids = kid :: kids
            cur = t
          case Nil => going = false
      MacNode(v, native, ListOps.reverse(kids))
    case x: VText =>
      MacNode(v, label(s, x.text, textFrame(s, x), x.color), Nil)
    case x: VGlyph =>
      MacNode(v, label(s, MacGlyphs.glyphString(x.glyph),
        frame(s, x.x, x.y, GlyphW, GlyphH), x.color), Nil)
    case x: VRect =>
      // -init may return a different object than -alloc did; always adopt
      // the returned id.
      val native = go.nativeui.allocBoxAsView().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
      go.nativeui.setupBox(native, color(x.color))
      MacNode(v, native, Nil)
    case x: VImage =>
      val native = go.nativeui.allocImageViewAsView().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
      go.nativeui.setupImageView(native, image(s, x))
      MacNode(v, native, Nil)

  /** update one native view's properties in place when the new view has the
   *  same constructor — the PSet fast path. Groups always answer None: a
   *  group PSet is a subtree replace (the differ only emits it on a
   *  constructor change). */
  def mutate(s: MacStageSt, n: MacNode, v: View): Option[MacNode] = n.view match
    case old: VText => v match
      case x: VText =>
        relabel(s, n.native, x.text, textFrame(s, x), x.color, old.text, old.color)
        Some(MacNode(v, n.native, n.kids))
      case _ => None
    case old: VGlyph => v match
      case x: VGlyph =>
        relabel(s, n.native, MacGlyphs.glyphString(x.glyph),
          frame(s, x.x, x.y, GlyphW, GlyphH), x.color,
          MacGlyphs.glyphString(old.glyph), old.color)
        Some(MacNode(v, n.native, n.kids))
      case _ => None
    case old: VRect => v match
      case x: VRect =>
        if x.color != old.color then go.nativeui.setBoxFill(n.native, color(x.color))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(MacNode(v, n.native, n.kids))
      case _ => None
    case old: VImage => v match
      case x: VImage =>
        go.nativeui.setImageViewImage(n.native, image(s, x))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(MacNode(v, n.native, n.kids))
      case _ => None
    case _ => None

  def relabel(s: MacStageSt, native: go.appkit.NSView, text: String,
    fr: go.appkit.CGRect, c: Int, oldText: String, oldColor: Int): Unit =
    if text != oldText then go.nativeui.setLabelText(native, text)
    if c != oldColor then go.nativeui.setLabelColor(native, color(c))
    native.setFrame(fr)

  // ---- elements -------------------------------------------------------------

  def label(s: MacStageSt, text: String, fr: go.appkit.CGRect, c: Int): go.appkit.NSView =
    val v = go.nativeui.newLabel(text)
    go.nativeui.setLabelFont(v, s.font)
    go.nativeui.setLabelColor(v, color(c))
    v.setFrame(fr)
    v

  def image(s: MacStageSt, x: VImage): go.appkit.NSImage =
    val rgba = MacPixels.scaleRGBANearest(
      MacPixels.expandRGB565(x.pixels, x.w, x.h), x.w, x.h, s.scale)
    go.nativeui.imageFromRGBA(rgba, x.w * s.scale, x.h * s.scale)

  def color(c: Int): go.appkit.NSColor =
    go.appkit.getNSColorClass().colorWithSRGBRedGreenBlueAlpha(
      MacPixels.red(c), MacPixels.green(c), MacPixels.blue(c), 1.0)

  // ---- geometry -------------------------------------------------------------

  def stageRect(scale: Int): go.appkit.CGRect =
    go.appkit.CGRect(go.appkit.CGPoint(0.0, 0.0), go.appkit.CGSize(
      (StageW * scale).toDouble, (StageH * scale).toDouble))

  /** map a semantic stage rect (origin top-left, pixels) to a scaled AppKit
   *  frame (origin bottom-left). */
  def frame(s: MacStageSt, x: Int, y: Int, w: Int, h: Int): go.appkit.CGRect =
    go.appkit.CGRect(
      go.appkit.CGPoint((x * s.scale).toDouble, ((StageH - y - h) * s.scale).toDouble),
      go.appkit.CGSize((w * s.scale).toDouble, (h * s.scale).toDouble))

  /** the grid cell run a VText occupies: x = col*6, y = 1 + row*8
   *  (display.scala's Font.drawText), one 6x8 cell per BYTE of text. */
  def textFrame(s: MacStageSt, t: VText): go.appkit.CGRect =
    frame(s, t.col * GlyphW, TextTopPad + t.row * GlyphH, t.text.bytes.length * GlyphW, GlyphH)

  // ---- List[MacNode] helpers (concrete-shaped, wataui's Views idiom) --------

  def lenNodes(xs: List[MacNode]): Int =
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

  /** the `i`th child, or None when the path/index points into nothing —
   *  which every caller treats as the mirror's no-op. */
  def nthNode(xs: List[MacNode], i: Int): Option[MacNode] =
    var cur = xs
    var j = 0
    var out: Option[MacNode] = None
    var going = i >= 0
    while going do
      cur match
        case h :: t =>
          if j == i then
            out = Some(h)
            going = false
          else
            j += 1
            cur = t
        case Nil => going = false
    out

  def setNodeAt(xs: List[MacNode], i: Int, k: MacNode): List[MacNode] =
    var acc: List[MacNode] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then acc = k :: acc else acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def insertNodeAt(xs: List[MacNode], i: Int, k: MacNode): List[MacNode] =
    var acc: List[MacNode] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then acc = h :: (k :: acc)
          else acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    if i >= j then acc = k :: acc
    ListOps.reverse(acc)

  def appendNode(xs: List[MacNode], k: MacNode): List[MacNode] =
    insertNodeAt(xs, lenNodes(xs), k)

  def removeNodeAt(xs: List[MacNode], i: Int): List[MacNode] =
    var acc: List[MacNode] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j != i then acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def lenInts(xs: List[Int]): Int =
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
