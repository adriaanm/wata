/** the differ's oracle: `report()` returns a deterministic multi-line String
 *  that is byte-diffed against a pinned expectation
 *  (`tools/wataui-diff.expected.txt`, run by `tools/wataui-tests.sh`).
 *
 *  Two things are checked, and the difference matters. The `roundtrip` lines
 *  are a PROPERTY — `applyAll(diff(a, b), a)` must equal `b`, which is the
 *  differ's whole contract and would be a failure however the script were
 *  spelled. The `script` lines pin the exact patches for the cases the design
 *  makes claims about (leaf granularity, keyed matching, positional fallback,
 *  subtree replace), so a change in HOW the differ gets there is visible in
 *  review rather than silent.
 *
 *  Portable by construction: no go facade, no IO, no clock — the same code a
 *  retained backend will lean on. Every renderer here BUILDS AND RETURNS a
 *  String: a `StringBuilder` is a function-local accumulator in this dialect
 *  and never crosses a def boundary. */
object DiffOracle:

  // ---- rendering (report text only) --------------------------------------------
  def showView(v: View): String =
    val b = new StringBuilder
    v match
      case x: VText =>
        b.append("text(")
        b.append(x.col); b.append(','); b.append(x.row)
        b.append(",\""); b.append(x.text); b.append("\",")
        b.append(x.color); b.append(')')
      case x: VGlyph =>
        b.append("glyph(")
        b.append(x.x); b.append(','); b.append(x.y); b.append(',')
        b.append(x.glyph); b.append(','); b.append(x.color); b.append(')')
      case x: VRect =>
        b.append("rect(")
        b.append(x.x); b.append(','); b.append(x.y); b.append(',')
        b.append(x.w); b.append(','); b.append(x.h); b.append(',')
        b.append(x.color); b.append(')')
      case x: VImage =>
        b.append("image(")
        b.append(x.x); b.append(','); b.append(x.y); b.append(',')
        b.append(x.w); b.append(','); b.append(x.h); b.append(",bytes=")
        b.append(x.pixels.length); b.append(",sum="); b.append(byteSum(x.pixels))
        b.append(')')
      case x: VGroup =>
        b.append("group[")
        b.append(showChildren(x.children))
        b.append(']')
    b.toString

  def showChildren(xs: List[Keyed]): String =
    val b = new StringBuilder
    var cur = xs
    var first = true
    var going = true
    while going do
      cur match
        case h :: t =>
          if !first then b.append(' ')
          first = false
          b.append(h.key); b.append(':'); b.append(showView(h.view))
          cur = t
        case Nil => going = false
    b.toString

  def byteSum(bs: Bytes): Int =
    var s = 0
    var i = 0
    while i < bs.length do
      s += bs(i)
      i += 1
    s

  def showPath(p: List[Int]): String =
    val b = new StringBuilder
    b.append('[')
    var cur = p
    var first = true
    var going = true
    while going do
      cur match
        case h :: t =>
          if !first then b.append('.')
          first = false
          b.append(h)
          cur = t
        case Nil => going = false
    b.append(']')
    b.toString

  def showPatch(p: Patch): String =
    val b = new StringBuilder
    p match
      case x: PSet =>
        b.append("set "); b.append(showPath(x.path)); b.append(' ')
        b.append(showView(x.view))
      case x: PInsert =>
        b.append("insert "); b.append(showPath(x.path)); b.append(' ')
        b.append(x.idx); b.append(' '); b.append(x.keyed.key); b.append(':')
        b.append(showView(x.keyed.view))
      case x: PDelete =>
        b.append("delete "); b.append(showPath(x.path)); b.append(' ')
        b.append(x.idx)
    b.toString

  def boolStr(x: Boolean): String = if x then "true" else "false"

  // ---- one case: the property line, then the exact script ----------------------
  def scenario(name: String, a: View, c: View): String =
    val ps = Diff.diff(a, c)
    val got = Patches.applyAll(ps, a)
    val b = new StringBuilder
    b.append("roundtrip "); b.append(name)
    b.append(" patches="); b.append(patchCount(ps))
    b.append(" ok="); b.append(boolStr(Views.eqView(got, c)))
    b.append('\n')
    var cur = ps
    var going = true
    while going do
      cur match
        case h :: t =>
          b.append("  "); b.append(showPatch(h)); b.append('\n')
          cur = t
        case Nil => going = false
    b.toString

  def patchCount(ps: List[Patch]): Int =
    var n = 0
    var cur = ps
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  def row(key: String, text: String, color: Int): Keyed =
    Keyed(key, VText(0, 0, text, color))

  def bytesOf(n: Int, v: Int): Bytes =
    val b = new BytesBuilder
    var i = 0
    while i < n do
      b.addByte(v)
      i += 1
    b.result()

  def report(): String =
    val b = new StringBuilder

    // identical trees: no patches at all.
    val screen = VGroup(
      Keyed("title", VText(0, 0, "WATA", 0x07ff)) ::
      Keyed("net", VGlyph(150, 1, 0x84, 0x07e0)) ::
      Keyed("footer", VText(0, 14, "OK retry", 0x632c)) :: Nil)
    b.append(scenario("identical", screen, screen))

    // LEAF GRANULARITY: one line of text changes; exactly one patch, aimed at
    // that child, not at the group.
    val screen2 = VGroup(
      Keyed("title", VText(0, 0, "WATA", 0x07ff)) ::
      Keyed("net", VGlyph(150, 1, 0x84, 0x07e0)) ::
      Keyed("footer", VText(0, 14, "BACK again to exit", 0x632c)) :: Nil)
    b.append(scenario("leaf-text", screen, screen2))

    // a leaf nested two groups down: the path names both steps.
    val nestA = VGroup(Keyed("hdr", VText(0, 0, "a", 1)) ::
      Keyed("body", VGroup(Keyed("l1", VText(0, 2, "one", 1)) ::
                           Keyed("l2", VText(0, 3, "two", 1)) :: Nil)) :: Nil)
    val nestB = VGroup(Keyed("hdr", VText(0, 0, "a", 1)) ::
      Keyed("body", VGroup(Keyed("l1", VText(0, 2, "one", 1)) ::
                           Keyed("l2", VText(0, 3, "TWO", 1)) :: Nil)) :: Nil)
    b.append(scenario("leaf-nested", nestA, nestB))

    // SUBTREE REPLACE: the constructor at a position changes, so the whole
    // child is set rather than compared field by field.
    val ctorA = VGroup(Keyed("m", VText(0, 5, "hi", 1)) :: Nil)
    val ctorB = VGroup(Keyed("m", VRect(0, 40, 160, 8, 2)) :: Nil)
    b.append(scenario("ctor-change", ctorA, ctorB))
    // and at the root, where the path is empty.
    b.append(scenario("ctor-root", VText(0, 0, "x", 1), VRect(0, 0, 4, 4, 1)))
    b.append(scenario("ctor-root-group", ctorA, VText(0, 0, "x", 1)))

    // KEYED LISTS.
    val listA = VGroup(row("a", "Ann", 1) :: row("b", "Bob", 1) :: row("c", "Cal", 1) :: Nil)
    // a row removed from the middle: one delete, the rows after it untouched.
    val listDel = VGroup(row("a", "Ann", 1) :: row("c", "Cal", 1) :: Nil)
    b.append(scenario("keyed-delete", listA, listDel))
    // a row inserted in the middle.
    val listIns = VGroup(row("a", "Ann", 1) :: row("b2", "Bea", 1) ::
      row("b", "Bob", 1) :: row("c", "Cal", 1) :: Nil)
    b.append(scenario("keyed-insert", listA, listIns))
    // a REORDER: no move patch exists, so the rows that had to pass each other
    // are deleted and re-inserted — the round trip is what must hold.
    val listRe = VGroup(row("c", "Cal", 1) :: row("a", "Ann", 1) :: row("b", "Bob", 1) :: Nil)
    b.append(scenario("keyed-reorder", listA, listRe))
    // a row that both moves AND changes: the identity is still matched.
    val listMoveEdit = VGroup(row("b", "Bobby", 1) :: row("a", "Ann", 1) :: Nil)
    b.append(scenario("keyed-move-edit", listA, listMoveEdit))
    // every key replaced: nothing matches, the list is rewritten.
    val listNew = VGroup(row("x", "Xen", 1) :: row("y", "Yin", 1) :: Nil)
    b.append(scenario("keyed-replace-all", listA, listNew))
    // growing and shrinking past the end.
    b.append(scenario("keyed-empty-to-list", VGroup(Nil), listA))
    b.append(scenario("keyed-list-to-empty", listA, VGroup(Nil)))

    // POSITIONAL FALLBACK: unkeyed children are matched by index and compared,
    // so a changed line is one set and nothing reflows.
    val posA = Views.group(VText(0, 0, "one", 1) :: VText(0, 1, "two", 1) :: VText(0, 2, "three", 1) :: Nil)
    val posB = Views.group(VText(0, 0, "one", 1) :: VText(0, 1, "TWO", 1) :: VText(0, 2, "three", 1) :: Nil)
    b.append(scenario("positional-edit", posA, posB))
    // a shorter unkeyed list: the tail is deleted.
    val posC = Views.group(VText(0, 0, "one", 1) :: VText(0, 1, "two", 1) :: Nil)
    b.append(scenario("positional-shrink", posA, posC))
    // MIXED: an unkeyed child sitting next to keyed rows matches by position.
    val mixA = VGroup(Keyed("", VText(0, 0, "hdr", 1)) :: row("a", "Ann", 1) :: row("b", "Bob", 1) :: Nil)
    val mixB = VGroup(Keyed("", VText(0, 0, "hdr", 1)) :: row("b", "Bob", 1) :: Nil)
    b.append(scenario("mixed-keys", mixA, mixB))

    // the other leaves change field by field.
    b.append(scenario("glyph-edit", VGlyph(10, 1, 0x8c, 3), VGlyph(10, 1, 0x8c, 4)))
    b.append(scenario("rect-edit", VRect(0, 40, 160, 8, 3), VRect(0, 40, 160, 24, 3)))
    b.append(scenario("rect-same", VRect(0, 40, 160, 8, 3), VRect(0, 40, 160, 8, 3)))
    // VImage compares its BYTES, not its identity: same geometry, different
    // contents is a change.
    b.append(scenario("image-same", VImage(0, 0, 2, 2, bytesOf(8, 7)), VImage(0, 0, 2, 2, bytesOf(8, 7))))
    b.append(scenario("image-bytes", VImage(0, 0, 2, 2, bytesOf(8, 7)), VImage(0, 0, 2, 2, bytesOf(8, 9))))

    // a screen-shaped case: the boot screen's two states.
    val bootA = VGroup(
      Keyed("title", VText(0, 0, "WATA", 0x07ff)) ::
      Keyed("net", VGlyph(150, 1, 0x84, 0x632c)) ::
      Keyed("head", VText(6, 7, "starting up...", 0x632c)) ::
      Keyed("footer", VText(4, 14, "OK retry  BACK exit", 0x632c)) :: Nil)
    val bootB = VGroup(
      Keyed("title", VText(0, 0, "WATA", 0x07ff)) ::
      Keyed("net", VGlyph(150, 1, 0x84, 0xf800)) ::
      Keyed("head", VText(6, 6, "can't reach server", 0xf800)) ::
      Keyed("sub", VText(7, 8, "retrying...", 0x632c)) ::
      Keyed("footer", VText(4, 14, "OK retry  BACK exit", 0x632c)) :: Nil)
    b.append(scenario("boot-states", bootA, bootB))

    b.append("wataui diff oracle: done\n")
    b.toString
