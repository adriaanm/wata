import language.experimental.saferExceptions

/** The stage's METRICS: what this device's surface is, so layout is computed
 *  rather than written down (plan 0071, "metrics, not constants").
 *
 *  The watch used to run wata's grid at a fixed 160x128 with an integer
 *  scale, which is the handset's panel wearing a wrist's clothes: at scale 2
 *  the footer fell off the right-hand edge, at scale 1 half the panel was
 *  black and the type was 6.8 points. Nothing here is a size: the panel's
 *  own bounds arrive from `UIScreen` (through watchshell's container) at
 *  launch, and every number below is derived from them. 208x248 is a value
 *  this code OBSERVES; the next watch is a different observation and no
 *  edit.
 *
 *  What a shell states, per the plan: its size in points, its scale, its
 *  safe insets, and its KIND. The kind is what lets one body read the same
 *  metrics and decide differently — a wrist is read at arm's length for two
 *  seconds, a handset is held, a desktop is a window.
 *
 *  ## From a panel to a grid
 *
 *  The bodies are still wata-fb's, and they address a character grid: a
 *  `VText` at (col, row), everything else in the pixel space that grid sits
 *  in. So the metrics answer a grid, and the derivation has exactly two
 *  rules:
 *
 *  - **The columns come from the LINE, not from the panel.** wata's screens
 *    are written to 26 columns; that is a property of the text they hold,
 *    and dividing a wrist's 208 points by it gives the cell width. Choosing
 *    a wider cell here (bigger type) would truncate every footer legend,
 *    which is the very cropping this step exists to remove. Plan 0070's
 *    rolodex changes the LINE, and this number with it.
 *  - **The rows come from the panel.** A row is `rowRatio` cells tall —
 *    taller than the fb's 6x8 cell, because a wrist reads short lines with
 *    air between them — and the panel's usable height divided by that is how
 *    many there are. On a 208x248 watch that lands back on 15 rows, which is
 *    the fb's row count by coincidence rather than by construction.
 *
 *  Usable height excludes the TOP safe inset: watchOS draws the time overlay
 *  in that band, over the app, and a status row underneath it is unreadable.
 *  The bottom inset is deliberately NOT excluded — it is a cosmetic corner
 *  margin, and the footer legend sitting in it costs a descender at worst,
 *  where excluding it would cost a whole row of content.
 *
 *  ## Type
 *
 *  Type comes from ROLES resolved against the metrics, never from a global
 *  point size. Three today — name, caption, status — which is the vocabulary
 *  the plan names and enough to make a list of names read louder than the
 *  legend under it. The role of a given piece of text is decided by
 *  `TypeRoles.forRow`, because the element vocabulary cannot carry a role
 *  yet (sized text is plan 0071's step 3); when it can, that function is the
 *  one line that changes. */
case class StageMetrics(
  // the panel, in points — what the device said, never a literal
  wPt: scala.Double,
  hPt: scala.Double,
  // points to pixels (2.0 on every watch so far). Layout never divides by
  // it; it says how much detail a frame may hold.
  screenScale: scala.Double,
  // the system's own bands: the top one holds the time overlay
  insetTop: scala.Double,
  insetBottom: scala.Double,
  // Metrics.WRIST / HANDSET / DESKTOP
  kind: scala.Int,
  // the character grid this panel carries
  cols: scala.Int,
  rows: scala.Int,
  cellW: scala.Double,
  cellH: scala.Double,
  // the stage's top-left corner in the container, in points
  originY: scala.Double
) derives CanEqual

object Metrics:
  val WRIST = 0
  val HANDSET = 1
  val DESKTOP = 2

  /** wata's line: the column count the bodies are written to. */
  val LINE_COLS = 26
  /** a row is this many cell-widths tall — the fb cell is 6x8 (1.33); a
   *  wrist gets 2.0, which is the air that makes a short line readable at
   *  arm's length. */
  val ROW_RATIO = 2.0
  /** the fb glyph cell, which is the unit the bodies' PIXEL coordinates are
   *  in (a highlight is `Font.GLYPH_H` tall). The stage maps that space onto
   *  the cell, so these two are a change of units and not a size. */
  val FB_GLYPH_W = 6
  val FB_GLYPH_H = 8

  /** the metrics before a screen exists: the handset's panel, so anything
   *  that reads geometry at module-init time (or in a headless test) gets
   *  wata's own grid rather than a division by zero. Replaced by `set` in
   *  the ready hop, before any body runs. */
  val fallback: StageMetrics = uniform(1)

  private val curC: sgo.Atomic[StageMetrics] = sgo.atomic(fallback)

  /** what this surface is. Read freely — the cell is written once, on the
   *  main thread, before the pump's first frame. */
  def cur(): StageMetrics = curC.get()

  def set(m: StageMetrics): Unit = curC.set(m)

  /** derive the grid from a panel. The whole layout rule, in one place. */
  def grid(wPt: scala.Double, hPt: scala.Double, screenScale: scala.Double,
      insetTop: scala.Double, insetBottom: scala.Double,
      kind: scala.Int): StageMetrics =
    val cellW = wPt / LINE_COLS.toDouble
    val cellH = cellW * ROW_RATIO
    val usableH = hPt - insetTop
    var rows = (usableH / cellH).toInt
    if rows < 1 then rows = 1
    StageMetrics(wPt, hPt, screenScale, insetTop, insetBottom, kind,
      LINE_COLS, rows, cellW, cellH, insetTop)

  /** the metrics a TEST states: a uniform integer scale of the fb's own
   *  6x8 cell, which is the geometry convention `interptest` pins
   *  independently. Nothing about a real panel is involved. */
  def uniform(scale: scala.Int): StageMetrics =
    val s = if scale < 1 then 1 else scale
    StageMetrics((160 * s).toDouble, (128 * s).toDouble, 1.0, 0.0, 0.0, HANDSET,
      LINE_COLS, 15, (FB_GLYPH_W * s).toDouble, (FB_GLYPH_H * s).toDouble, 0.0)

  def kindName(kind: scala.Int): String =
    if kind == WRIST then "wrist"
    else if kind == HANDSET then "handset"
    else "desktop"

  /** the pixel space the bodies' non-text coordinates live in: the grid,
   *  measured in fb glyph cells. `Display.W`/`Display.H` answer from here,
   *  so a full-width rule is as wide as the grid is — on any panel. */
  def pixW(m: StageMetrics): scala.Int = m.cols * FB_GLYPH_W
  def pixH(m: StageMetrics): scala.Int = m.rows * FB_GLYPH_H + 1 // +1: the status line the text grid starts below

/** Type by role. A role is resolved against the metrics — never a constant,
 *  and never one global size for everything on the panel.
 *
 *  The sizes are fractions of what the cell can hold: a monospaced system
 *  font advances about 0.6em and its ascent+descent is about 1.16em, so the
 *  largest size that both fits the cell's width and keeps the line inside
 *  its height is the cap, and each role takes a share of it. NAME gets all
 *  of it because the names are what a wrist is read for; CAPTION and STATUS
 *  step down so the legend and the header do not compete with the content. */
object TypeRoles:
  val NAME = 0    // list rows: contacts, messages — the content
  val CAPTION = 1 // the footer legend
  val STATUS = 2  // the header: title, connectivity, counts

  /** the monospaced system font's advance per em — MEASURED at 0.616 on
   *  watchOS 26 rather than the nominal 0.6, and taken a little wider still
   *  because a label whose text is exactly as wide as its frame TRUNCATES:
   *  at the exact fit `Family` renders as `Fami…` and `Bob` as `B…`. The
   *  frame is one grid cell per character, so this ratio is what keeps a
   *  full line inside its cells. */
  val ADVANCE_EM = 0.64
  /** ascent + descent per em: the line has to fit the cell's height. */
  val LINE_EM = 1.16

  /** the largest point size a cell can hold, width and height both. */
  def capPts(m: StageMetrics): scala.Double =
    val byW = m.cellW / ADVANCE_EM
    val byH = m.cellH / LINE_EM
    if byW < byH then byW else byH

  def points(role: scala.Int, m: StageMetrics): scala.Double =
    val cap = capPts(m)
    if role == CAPTION then cap * 0.82
    else if role == STATUS then cap * 0.86
    else cap

  def name(role: scala.Int): String =
    if role == CAPTION then "caption" else if role == STATUS then "status" else "name"

  /** which role a grid row carries. The element vocabulary has no place for
   *  a role yet (plan 0071 step 3 adds sized text), so the ROW says it: the
   *  header rows above the list are status, the last row is the footer
   *  legend, and everything between is content. Every body here is
   *  header/list/footer, so this is exact rather than a guess — and it is
   *  the single line that goes away when a `VText` can name its own role. */
  def forRow(row: scala.Int, m: StageMetrics): scala.Int =
    if row <= 0 then STATUS
    else if row >= m.rows - 1 then CAPTION
    else NAME
