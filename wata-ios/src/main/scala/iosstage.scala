import language.experimental.saferExceptions

/** The retained interpreter: a wataui view tree as a live UIView tree, at an
 *  integer scale of the 160x128 stage — wata-mac's interp.scala (plans
 *  0032/0038) transliterated onto UIKit (plan 0044 stage 3): `wataui`'s
 *  `View` and `Patch` consumed DIRECTLY, over facades on the generated uikit
 *  bindings (`uikit.scala`) plus `go.iosui`'s Go glue for what a facade
 *  cannot say (casts, the raw RGBA bitmap crossing, the render probe).
 *
 *  The element table, per constructor:
 *
 *    VText  -> UILabel (monospaced system font sized to the cell)
 *    VGlyph -> UILabel over the glyph mapping (glyphs.scala)
 *    VRect  -> UIView with backgroundColor = the RGB565 color widened (no
 *              NSBox dance: a plain UIView draws its background through the
 *              layer, which renderInContext: sees)
 *    VImage -> UIImageView over RGB565->RGBA widening + nearest-neighbour
 *              pre-scaling (pixels.scala), handed across as raw RGBA bytes
 *              through a CGBitmapContext (glue) — UIKit never interpolates
 *              (the image arrives at the frame's exact pixel size)
 *    VGroup -> a plain container UIView; subview order IS paint order (UIKit
 *              draws later subviews over earlier — the same rule the algebra
 *              has)
 *
 *  Geometry: coordinates are wataui's semantic positions. VText addresses the
 *  26x15 character grid of the fb font (6x8 cells, text rows starting 1px
 *  down — display.scala's Font.drawText); everything else addresses stage
 *  pixels. Both scale by the integer scale. UIKit's y axis points DOWN — the
 *  stage's own convention — so unlike AppKit there is NO flip: a semantic
 *  rect scales straight into a frame. Group containers all span the full
 *  stage, which keeps child coordinates stage-absolute.
 *
 *  Retention: the mac stage retains its factory-made NSFont across pools
 *  (nativeui.retainFont); iOS has no retain glue, so the stage stores the
 *  POINT SIZE and mints the font inside each label call — the label retains
 *  the font it is handed, and nothing autoreleased outlives its pool.
 *
 *  Threading: every function that touches the native tree must run on the
 *  UIKit thread. Under the interptest that is the main thread inside
 *  UIApplicationMain's ready hop, and `submitTree`/`submitScript` run
 *  DIRECTLY (pool-bracketed); windowed (stage 4's pump) the pump PUBLISHES
 *  the frame and `go.iosui.onMain` dispatches the module-registered
 *  `go.callback` trampoline onto the main queue, which drains EVERYTHING
 *  pending and applies it in publish order — a queue turn may apply several
 *  frames' scripts, which is what the flat-patch-list shape below is for.
 *  The retained node tree and its mirror live in one atomic cell, only ever
 *  touched from the stage's thread; the cell exists so the windowed callback
 *  (a different goroutine than the one that created the stage) can reach
 *  them. */

/** one native view and, for groups, its children — the same shape as the
 *  mirror, so paths index both identically. `view` is only read for
 *  constructor checks (the PSet fast path, PInsert's group check); the
 *  authoritative tree is the mirror. */
case class IosNode(view: View, native: go.uikit.UIView, kids: List[IosNode])

/** the whole retained state: geometry scale, the container view, the label
 *  font's point size (see the retention note above), the mounted tree and
 *  the plain mirror (`Patches.applyAll` semantics — the invariant
 *  `wata-ios interptest` holds against the real hierarchy). */
case class IosStageSt(scale: Int, root: go.uikit.UIView, fontPts: Double,
  top: Option[IosNode], mirror: Option[View])

object IosStage:
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

  private val stC: sgo.Atomic[Option[IosStageSt]] = sgo.atomic(None)
  /** windowed = publish to the main queue; headless = apply inline. */
  private val windowedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** patches the pump published and the main-queue callback has not applied
   *  yet, NEWEST FIRST (drain reverses back to publish order). One flat
   *  patch list, not a list of frames: scripts concatenate soundly (each is
   *  written against the tree the previous ones produced), and a whole-tree
   *  handoff IS a root `PSet` in the patch vocabulary. */
  private val pendingC: sgo.Atomic[List[Patch]] = sgo.atomic(Nil)

  /** the windowed apply trampoline, minted ONCE at module init (the
   *  registration contract: never per-frame). The literal captures nothing —
   *  it reaches the stage through the module cells. */
  val applyCb: go.Uintptr = go.callback(() => IosStage.drainPending())

  /** create the scaled stage container (scale*160 x scale*128) — UIKit
   *  thread only; bracket in a pool. Answers the root view for
   *  `iosshell.adoptRoot`. */
  def create(scale0: Int, windowed: Boolean): go.uikit.UIView =
    val scale = if scale0 < 1 then 1 else scale0
    val root = go.uikit.getUIViewClass().alloc().initWithFrame(stageRect(scale))
    windowedC.set(windowed)
    stC.set(Some(IosStageSt(scale, root, cellFontPts * scale.toDouble, None, None)))
    root

  /** the plain view tree the native tree currently shows — the "retained
   *  mirror" of wataui's applyAll contract. None before the first tree. */
  def mirror(): Option[View] = stC.get() match
    case s: Some[IosStageSt] => s.value.mirror
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
      go.iosui.onMain(applyCb)
    else
      // non-windowed: the caller IS the stage's thread — apply inline,
      // pool-bracketed
      val pool = go.iosui.poolPush()
      applyScript(ps)
      go.iosui.poolPop(pool)

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
   *  order. UIKit thread; the pool is the dispatcher's. */
  def drainPending(): Unit =
    applyScript(ListOps.reverse(pendingC.getAndSet(Nil)))

  // ---- mounting and patching (UIKit thread only) ----------------------------

  /** build the native tree from scratch, replacing whatever was mounted. */
  def setTree(v: View): Unit = stC.get() match
    case s0: Some[IosStageSt] =>
      val s = s0.value
      s.top match
        case t: Some[IosNode] => t.value.native.removeFromSuperview()
        case None             => ()
      val top = build(s, v)
      s.root.addSubview(top.native)
      stC.set(Some(IosStageSt(s.scale, s.root, s.fontPts, Some(top), Some(v))))
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
    case s0: Some[IosStageSt] =>
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
      stC.set(Some(IosStageSt(s.scale, s.root, s.fontPts, top, mirror)))
    case None => ()

  /** swap `fresh` into `old`'s slot in `parent` — UIKit has no
   *  replaceSubview:with:, so: insert directly below (same index), then
   *  remove the old view. */
  def replaceIn(parent: go.uikit.UIView, old: go.uikit.UIView, fresh: go.uikit.UIView): Unit =
    parent.insertSubviewBelowSubview(fresh, old)
    old.removeFromSuperview()

  // ---- patch arms — each answers the updated top ----------------------------

  def doSet(s: IosStageSt, top: Option[IosNode], path: List[Int], v: View): Option[IosNode] =
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
      case t: Some[IosNode] =>
        path match
          case Nil =>
            mutate(s, t.value, v) match
              case n2: Some[IosNode] => Some(n2.value)
              case None =>
                val fresh = build(s, v)
                replaceIn(s.root, t.value.native, fresh.native)
                Some(fresh)
          case _ => Some(setIn(s, t.value, path, v))

  /** replace the child `path` points at inside `n` — mutate in place when the
   *  constructor matches, else build + replace. A path into nothing is a
   *  no-op (mirror semantics). */
  def setIn(s: IosStageSt, n: IosNode, path: List[Int], v: View): IosNode = path match
    case h :: Nil =>
      nthNode(n.kids, h) match
        case c: Some[IosNode] =>
          val child = c.value
          mutate(s, child, v) match
            case c2: Some[IosNode] => IosNode(n.view, n.native, setNodeAt(n.kids, h, c2.value))
            case None =>
              val fresh = build(s, v)
              replaceIn(n.native, child.native, fresh.native)
              IosNode(n.view, n.native, setNodeAt(n.kids, h, fresh))
        case None => n // mirror semantics: a path into nothing is a no-op
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[IosNode] =>
          IosNode(n.view, n.native, setNodeAt(n.kids, h, setIn(s, c.value, t, v)))
        case None => n
    case Nil => n // unreachable: the root case is doSet's

  def doInsert(s: IosStageSt, top: Option[IosNode], path: List[Int], idx: Int, k: Keyed): Option[IosNode] =
    top match
      case t: Some[IosNode] => Some(insertIn(s, t.value, path, idx, k))
      case None             => None

  def insertIn(s: IosStageSt, n: IosNode, path: List[Int], idx: Int, k: Keyed): IosNode = path match
    case Nil =>
      val isGroup = n.view match
        case _: VGroup => true
        case _         => false
      if !isGroup || idx < 0 then n
      else
        nthNode(n.kids, idx) match
          case at: Some[IosNode] =>
            // below the current occupant of idx: earlier in subviews =
            // painted first
            val fresh = build(s, k.view)
            n.native.insertSubviewBelowSubview(fresh.native, at.value.native)
            IosNode(n.view, n.native, insertNodeAt(n.kids, idx, fresh))
          case None =>
            val fresh = build(s, k.view)
            n.native.addSubview(fresh.native)
            IosNode(n.view, n.native, appendNode(n.kids, fresh))
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[IosNode] =>
          IosNode(n.view, n.native, setNodeAt(n.kids, h, insertIn(s, c.value, t, idx, k)))
        case None => n

  def doDelete(s: IosStageSt, top: Option[IosNode], path: List[Int], idx: Int): Option[IosNode] =
    top match
      case t: Some[IosNode] => Some(deleteIn(s, t.value, path, idx))
      case None             => None

  def deleteIn(s: IosStageSt, n: IosNode, path: List[Int], idx: Int): IosNode = path match
    case Nil =>
      nthNode(n.kids, idx) match
        case at: Some[IosNode] =>
          at.value.native.removeFromSuperview()
          IosNode(n.view, n.native, removeNodeAt(n.kids, idx))
        case None => n
    case h :: t =>
      nthNode(n.kids, h) match
        case c: Some[IosNode] =>
          IosNode(n.view, n.native, setNodeAt(n.kids, h, deleteIn(s, c.value, t, idx)))
        case None => n

  // ---- building -------------------------------------------------------------

  def build(s: IosStageSt, v: View): IosNode = v match
    case x: VGroup =>
      val native = go.uikit.getUIViewClass().alloc().initWithFrame(stageRect(s.scale))
      var kids: List[IosNode] = Nil
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
      IosNode(v, native, ListOps.reverse(kids))
    case x: VText =>
      IosNode(v, label(s, x.text, textFrame(s, x), x.color), Nil)
    case x: VGlyph =>
      IosNode(v, label(s, IosGlyphs.glyphString(x.glyph),
        frame(s, x.x, x.y, GlyphW, GlyphH), x.color), Nil)
    case x: VRect =>
      // -init may return a different object than -alloc did; always adopt
      // the returned id. The VRect element: a plain UIView, background-filled.
      val native = go.uikit.getUIViewClass().alloc().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
      native.setBackgroundColor(color(x.color))
      IosNode(v, native, Nil)
    case x: VImage =>
      val native = go.iosui.allocImageViewAsView().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
      // never interpolate: the pixels arrive pre-scaled to the frame's size
      go.iosui.asImageView(native).setImage(image(s, x))
      IosNode(v, native, Nil)
    case x: VFill =>
      // the rolodex fill: VRect's plain UIView plus the two properties this
      // element exists for — the radius through the layer (glue: cornerRadius
      // lives on CALayer, which bindgen does not allow), the alpha in the
      // colour. The layer draws the background, so the radius clips it.
      val native = go.uikit.getUIViewClass().alloc().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
      native.setBackgroundColor(colorA(x.color, x.alpha))
      go.iosui.setCornerRadius(native, radiusPts(s, x))
      IosNode(v, native, Nil)
    case x: VLabel =>
      IosNode(v, roleLabel(s, x), Nil)

  /** update one native view's properties in place when the new view has the
   *  same constructor — the PSet fast path. Groups always answer None: a
   *  group PSet is a subtree replace (the differ only emits it on a
   *  constructor change). */
  def mutate(s: IosStageSt, n: IosNode, v: View): Option[IosNode] = n.view match
    case old: VText => v match
      case x: VText =>
        relabel(s, n.native, x.text, textFrame(s, x), x.color, old.text, old.color)
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case old: VGlyph => v match
      case x: VGlyph =>
        relabel(s, n.native, IosGlyphs.glyphString(x.glyph),
          frame(s, x.x, x.y, GlyphW, GlyphH), x.color,
          IosGlyphs.glyphString(old.glyph), old.color)
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case old: VRect => v match
      case x: VRect =>
        if x.color != old.color then n.native.setBackgroundColor(color(x.color))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case old: VImage => v match
      case x: VImage =>
        go.iosui.asImageView(n.native).setImage(image(s, x))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case old: VFill => v match
      case x: VFill =>
        if x.color != old.color || x.alpha != old.alpha then
          n.native.setBackgroundColor(colorA(x.color, x.alpha))
        // the radius is clamped to the box, so a resize moves it with the
        // radius field untouched
        if x.radius != old.radius || x.w != old.w || x.h != old.h then
          go.iosui.setCornerRadius(n.native, radiusPts(s, x))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case old: VLabel => v match
      case x: VLabel =>
        val lbl = go.iosui.asLabel(n.native)
        if x.text != old.text then lbl.setText(x.text)
        if x.color != old.color || x.alpha != old.alpha then
          lbl.setTextColor(colorA(x.color, x.alpha))
        if x.role != old.role || x.weight != old.weight || x.h != old.h then
          setRoleFont(n.native, labelPts(s, x), x.weight)
        if x.align != old.align then
          go.iosui.setLabelAlignment(n.native, IosType.alignment(x.align))
        n.native.setFrame(frame(s, x.x, x.y, x.w, x.h))
        Some(IosNode(v, n.native, n.kids))
      case _ => None
    case _ => None

  def relabel(s: IosStageSt, native: go.uikit.UIView, text: String,
    fr: go.uikit.CGRect, c: Int, oldText: String, oldColor: Int): Unit =
    if text != oldText then go.iosui.asLabel(native).setText(text)
    if c != oldColor then go.iosui.asLabel(native).setTextColor(color(c))
    native.setFrame(fr)

  // ---- elements -------------------------------------------------------------

  def label(s: IosStageSt, text: String, fr: go.uikit.CGRect, c: Int): go.uikit.UIView =
    val v = go.iosui.allocLabelAsView().initWithFrame(fr)
    val lbl = go.iosui.asLabel(v)
    lbl.setText(text)
    // minted per label, retained by the label (the retention note above)
    lbl.setFont(go.uikit.getUIFontClass()
      .monospacedSystemFontOfSizeWeight(s.fontPts, 0.0 /* regular */))
    lbl.setTextColor(color(c))
    v

  /** a `VLabel`: pixel-placed text at a ROLE, aligned inside its box. The
   *  frame IS the box and UIKit does the alignment — a body that had to
   *  measure a native font to centre a name would be doing the renderer's job
   *  with the renderer's numbers missing. Not monospaced: grid text is
   *  (its columns must line up with the cells a body counted), a name on a
   *  card is read rather than tabulated. */
  def roleLabel(s: IosStageSt, x: VLabel): go.uikit.UIView =
    val v = go.iosui.allocLabelAsView().initWithFrame(frame(s, x.x, x.y, x.w, x.h))
    val lbl = go.iosui.asLabel(v)
    lbl.setText(x.text)
    setRoleFont(v, labelPts(s, x), x.weight)
    lbl.setTextColor(colorA(x.color, x.alpha))
    go.iosui.setLabelAlignment(v, IosType.alignment(x.align))
    v

  def setRoleFont(v: go.uikit.UIView, pts: Double, weight: Int): Unit =
    go.iosui.asLabel(v).setFont(go.uikit.getUIFontClass()
      .systemFontOfSizeWeight(pts, IosType.weight(weight)))

  /** the point size a `VLabel` gets: its ROLE at this stage's scale, clamped
   *  to the box it was given, so a label never overflows its own box. */
  def labelPts(s: IosStageSt, x: VLabel): Double =
    val byRole = IosType.points(x.role, s.scale)
    val byBox = (x.h * s.scale).toDouble / IosType.LINE_EM
    if byBox < byRole then byBox else byRole

  /** a `VFill`'s corner radius in points: semantic pixels at the stage's
   *  scale, clamped to half the shorter side (wataui's `VFill` contract). */
  def radiusPts(s: IosStageSt, x: VFill): Double =
    val half = (if x.w < x.h then x.w else x.h) / 2
    val r = if x.radius < 0 then 0 else if x.radius > half then half else x.radius
    (r * s.scale).toDouble

  def image(s: IosStageSt, x: VImage): go.uikit.UIImage =
    val rgba = IosPixels.scaleRGBANearest(
      IosPixels.expandRGB565(x.pixels, x.w, x.h), x.w, x.h, s.scale)
    go.iosui.imageFromRGBA(rgba, x.w * s.scale, x.h * s.scale)

  def color(c: Int): go.uikit.UIColor = colorA(c, Alpha.OPAQUE)

  /** the same colour at a coverage: `Alpha`'s 0..255 as UIKit's fraction. */
  def colorA(c: Int, alpha: Int): go.uikit.UIColor =
    go.uikit.getUIColorClass().colorWithRedGreenBlueAlpha(
      IosPixels.red(c), IosPixels.green(c), IosPixels.blue(c), Alpha.fraction(alpha))

  // ---- geometry -------------------------------------------------------------

  def stageRect(scale: Int): go.uikit.CGRect =
    go.uikit.CGRect(go.uikit.CGPoint(0.0, 0.0), go.uikit.CGSize(
      (StageW * scale).toDouble, (StageH * scale).toDouble))

  /** map a semantic stage rect (origin top-left, pixels) to a scaled UIKit
   *  frame — same origin corner, so no flip (the one geometry difference
   *  from the mac stage). */
  def frame(s: IosStageSt, x: Int, y: Int, w: Int, h: Int): go.uikit.CGRect =
    go.uikit.CGRect(
      go.uikit.CGPoint((x * s.scale).toDouble, (y * s.scale).toDouble),
      go.uikit.CGSize((w * s.scale).toDouble, (h * s.scale).toDouble))

  /** the grid cell run a VText occupies: x = col*6, y = 1 + row*8
   *  (display.scala's Font.drawText), one 6x8 cell per BYTE of text. */
  def textFrame(s: IosStageSt, t: VText): go.uikit.CGRect =
    frame(s, t.col * GlyphW, TextTopPad + t.row * GlyphH, t.text.bytes.length * GlyphW, GlyphH)

  // ---- List[IosNode] helpers (concrete-shaped, wataui's Views idiom) --------

  def lenNodes(xs: List[IosNode]): Int =
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
  def nthNode(xs: List[IosNode], i: Int): Option[IosNode] =
    var cur = xs
    var j = 0
    var out: Option[IosNode] = None
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

  def setNodeAt(xs: List[IosNode], i: Int, k: IosNode): List[IosNode] =
    var acc: List[IosNode] = Nil
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

  def insertNodeAt(xs: List[IosNode], i: Int, k: IosNode): List[IosNode] =
    var acc: List[IosNode] = Nil
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

  def appendNode(xs: List[IosNode], k: IosNode): List[IosNode] =
    insertNodeAt(xs, lenNodes(xs), k)

  def removeNodeAt(xs: List[IosNode], i: Int): List[IosNode] =
    var acc: List[IosNode] = Nil
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

/** what wataui's TYPE ROLES resolve to on this stage.
 *
 *  The phone is a development surface, so its stage is still an integer scale
 *  of the handset's 160x128 panel rather than a `StageMetrics` read off the
 *  screen (plan 0071 step 2 has only reached the watch). The roles are
 *  therefore multiples of the grid's own cell font. When this stage grows
 *  metrics, these fractions move there and this object goes away — the BODIES
 *  never change, which is the whole reason the element names a role and not a
 *  size. */
object IosType:
  /** ascent + descent per em: what clamping a role to its box measures. */
  val LINE_EM = 1.16

  def points(role: Int, scale: Int): Double =
    val cell = IosStage.cellFontPts * scale.toDouble
    if role == TypeRole.DISPLAY then cell * 3.2
    else if role == TypeRole.NAME then cell * 1.6
    else if role == TypeRole.CAPTION then cell * 1.1
    else cell

  /** the system font's weight axis: regular 0.0, medium 0.23, bold 0.4. */
  def weight(w: Int): Double =
    if w == TypeWeight.MEDIUM then 0.23
    else if w == TypeWeight.BOLD then 0.4
    else 0.0

  /** UIKit's NSTextAlignment: left 0, CENTER 1, right 2 — deliberately not
   *  AppKit's ordering (left 0, right 1, center 2). */
  def alignment(a: Int): Int =
    if a == TextAlign.CENTER then 1 else if a == TextAlign.TRAILING then 2 else 0
