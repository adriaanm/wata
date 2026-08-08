/** DIFF-RETAINS-REPRO — does `Diff.diff` retain the trees it walks?
 *
 *  Five arms, one loop each, run one per process (`diff-spike a|b|c|d|e`,
 *  optional second arg `big`) so no arm's residue contaminates another's
 *  series. Every arm prints the same thing: the live heap (after a forced
 *  GC — see go.memprobe) every SAMPLE iterations. A leak is a straight
 *  line; a bounded working set plateaus.
 *
 *  - a: build two trees per iteration, do NOT diff — the control. Must be
 *       flat, or the finding is about tree construction and the ticket is
 *       misaimed.
 *  - b: diff a fresh tree against one allocated once outside the loop — the
 *       app's shape (`old` is the long-lived st.last, `new` is each frame's).
 *       The returned script is held one iteration, as the app's frame does.
 *  - c: diff two trees BOTH allocated outside the loop — nothing fresh is
 *       walked. If this leaks, retention is not about the argument's age.
 *  - d: as b, but the script is dropped immediately — whether holding the
 *       List[Patch] even one iteration matters.
 *  - e: old is last iteration's new — the pump's real `st.last` shape.
 *  - f: diff a fresh tree against an EQUAL long-lived one (empty script) —
 *       the idle pump's real case, where the walk takes the all-fields-equal
 *       path. The sanity assertion is skipped for this arm only.
 *
 *  The trees are shaped like the app's: a VGroup of KEYED rows (keys make the
 *  child scan take the keyed path), each row a group of a highlight VRect and
 *  a VText, with one row's text differing between old and new so the script
 *  is non-empty — an empty diff may take a path that never walks the nodes,
 *  which would be a clean false negative.
 */
object Main:

  val Iters: Int = 100000
  val Sample: Int = 5000


  /** the app-shaped tree: keyed rows over VRect/VText leaves. `tag` lands in
   *  row 0's text, so trees with different tags differ in exactly one leaf. */
  def tree(tag: Int, rows0: Int): View =
    var rows: List[Keyed] = Nil
    var i = rows0 - 1
    while i >= 0 do
      val hl = VRect(0, i * 12, 160, 12, if i == 0 then 0x1234 else 0)
      val txt =
        if i == 0 then VText(2, i, "row " + i + " v" + tag, 0xFFFF)
        else VText(2, i, "row " + i, 0xFFFF)
      val gl = VGlyph(150, i * 12, 0x80 + (if i == 0 then tag else 0), 0xFFFF)
      rows = Keyed("row-" + i, VGroup(Keyed("", hl) :: Keyed("", txt) :: Keyed("", gl) :: Nil)) :: rows
      i -= 1
    VGroup(rows)

  def lenPatches(xs: List[Patch]): Int =
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

  def sampleLine(arm: String, i: Int): Unit =
    println("arm=" + arm + " iter=" + i + " liveKB=" + go.memprobe.liveHeap() / 1024)

  /** the root's child count — the read that keeps a built tree from being
   *  elidable without walking its nodes. NOT `==` on the views:
   *  GENERIC-FAMILY-EQUALS — `==` over a case class carrying a generic
   *  sealed-family field (List[Keyed]) is a loud upstream wall until the
   *  reference-collapsed instantiation learns a real equals; sgola has it
   *  queued (was EQUALS-LIST-EMIT-BROKEN-CONS, whose mangler half landed). */
  def rootLen(v: View): Int =
    v match
      case VGroup(children) => Views.len(children)
      case _ => 0

  /** a: build both trees, never diff. The builds land in vars so nothing can
   *  elide them; the vars are read once after the loop. */
  def runA(rows: Int): Unit =
    var lastOld: View = tree(0, rows)
    var lastNew: View = tree(1, rows)
    var i = 0
    while i < Iters do
      lastOld = tree(0, rows)
      lastNew = tree(1, rows)
      if i % Sample == 0 then sampleLine("a", i)
      i += 1
    sampleLine("a", Iters)
    println("arm=a done sink=" + (rootLen(lastOld) + rootLen(lastNew)))

  /** b: fresh new vs long-lived old; script held one iteration. */
  def runB(rows: Int): Unit =
    val base = tree(0, rows)
    var held: List[Patch] = Nil
    var sink = 0
    var i = 0
    while i < Iters do
      sink += lenPatches(held)
      held = Diff.diff(base, tree(1, rows))
      if i % Sample == 0 then sampleLine("b", i)
      i += 1
    sampleLine("b", Iters)
    println("arm=b done sink=" + sink)

  /** c: both trees long-lived; nothing fresh is walked. */
  def runC(rows: Int): Unit =
    val base = tree(0, rows)
    val other = tree(1, rows)
    var sink = 0
    var i = 0
    while i < Iters do
      sink += lenPatches(Diff.diff(base, other))
      if i % Sample == 0 then sampleLine("c", i)
      i += 1
    sampleLine("c", Iters)
    println("arm=c done sink=" + sink)

  /** e: the pump's REAL shape — old is last iteration's new (`st.last`), so
   *  every tree was once the fresh argument and then becomes the long-lived
   *  one. Tags alternate so consecutive trees always differ. If this leaks
   *  where b/c/d are flat, the retaining edge links successive trees. */
  def runE(rows: Int): Unit =
    var last: View = tree(0, rows)
    var held: List[Patch] = Nil
    var sink = 0
    var i = 0
    while i < Iters do
      val n = tree(1 + i % 2, rows)
      sink += lenPatches(held)
      held = Diff.diff(last, n)
      last = n
      if i % Sample == 0 then sampleLine("e", i)
      i += 1
    sampleLine("e", Iters)
    println("arm=e done sink=" + sink)

  /** f: as b but the fresh tree EQUALS the long-lived one, so the script is
   *  empty and the walk takes the all-fields-equal path — the idle pump's
   *  real case, and the one path no other arm exercises. */
  def runF(rows: Int): Unit =
    val base = tree(0, rows)
    var sink = 0
    var i = 0
    while i < Iters do
      sink += lenPatches(Diff.diff(base, tree(0, rows)))
      if i % Sample == 0 then sampleLine("f", i)
      i += 1
    sampleLine("f", Iters)
    println("arm=f done sink=" + sink)

  /** d: as b, script dropped immediately. */
  def runD(rows: Int): Unit =
    val base = tree(0, rows)
    var sink = 0
    var i = 0
    while i < Iters do
      sink += lenPatches(Diff.diff(base, tree(1, rows)))
      if i % Sample == 0 then sampleLine("d", i)
      i += 1
    sampleLine("d", Iters)
    println("arm=d done sink=" + sink)

  def main(args: Array[String]): Unit =
    val arm = if args.length > 0 then args(0) else ""
    // `big` as a second argument makes the trees 20x (200 rows, ~40 KB) to
    // probe whether retention needs app-sized trees
    val rows = if args.length > 1 && args(1) == "big" then 200 else 10
    // one sanity print so a broken tree shape fails loudly, not silently flat
    val script = Diff.diff(tree(0, rows), tree(1, rows))
    println("arm=" + arm + " sanity patches=" + lenPatches(script) + " (must be >0)")
    if arm == "a" then runA(rows)
    else if arm == "b" then runB(rows)
    else if arm == "c" then runC(rows)
    else if arm == "d" then runD(rows)
    else if arm == "e" then runE(rows)
    else if arm == "f" then runF(rows)
    else println("usage: diff-spike a|b|c|d|e|f")
