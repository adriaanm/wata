/** THE DIFFER — two view trees in, an edit script out.
 *
 *  It exists for RETAINED backends: a native toolkit holds widgets, and
 *  rebuilding the whole widget graph every frame is both slow and destructive
 *  (it loses focus, selection and scroll position). The framebuffer backend
 *  does NOT use this — it clears and repaints the whole tree every frame,
 *  which is what makes golden-equivalence a byte-identical claim.
 *
 *  A patch names its target by PATH: the child indices from the root of the
 *  tree, outermost first. `Nil` is the root itself.
 *
 *  The script is ORDERED and must be applied in order — each patch is written
 *  against the tree the patches before it produced. `Patches.applyAll` is that
 *  application over a plain view tree (the "retained mirror" a real backend
 *  keeps alongside its widgets), and `applyAll(diff(a, b), a) == b` is the
 *  invariant the whole module is judged by.
 */
sealed trait Patch derives CanEqual

/** replace the subtree at `path` wholesale. Emitted at LEAF granularity: a
 *  changed line of text patches that one text node, not its group. */
case class PSet(path: List[Int], view: View) extends Patch

/** insert `keyed` as child `idx` of the group at `path`. */
case class PInsert(path: List[Int], idx: Int, keyed: Keyed) extends Patch

/** remove child `idx` of the group at `path`. */
case class PDelete(path: List[Int], idx: Int) extends Patch

object Diff:
  /** the edit script taking `a` to `b`. */
  def diff(a: View, b: View): List[Patch] =
    ListOps.reverse(walk(a, b, Nil, Nil))

  /** the recursive walk. `rpath` is the path to the pair under comparison held
   *  LEAF-FIRST (prepending a step is free; `pathOf` reverses it once, when a
   *  patch is actually emitted). `acc` is the script so far, newest first. */
  def walk(a: View, b: View, rpath: List[Int], acc: List[Patch]): List[Patch] =
    var out = acc
    a match
      case x: VText => b match
        case y: VText =>
          if x.col != y.col || x.row != y.row || x.text != y.text || x.color != y.color then
            out = PSet(pathOf(rpath), b) :: out
        case _ => out = PSet(pathOf(rpath), b) :: out
      case x: VGlyph => b match
        case y: VGlyph =>
          if x.x != y.x || x.y != y.y || x.glyph != y.glyph || x.color != y.color then
            out = PSet(pathOf(rpath), b) :: out
        case _ => out = PSet(pathOf(rpath), b) :: out
      case x: VRect => b match
        case y: VRect =>
          if x.x != y.x || x.y != y.y || x.w != y.w || x.h != y.h || x.color != y.color then
            out = PSet(pathOf(rpath), b) :: out
        case _ => out = PSet(pathOf(rpath), b) :: out
      case x: VImage => b match
        case y: VImage =>
          if !Views.eqView(x, y) then out = PSet(pathOf(rpath), b) :: out
        case _ => out = PSet(pathOf(rpath), b) :: out
      case x: VGroup => b match
        case y: VGroup => out = walkGroup(x.children, y.children, rpath, out)
        case _         => out = PSet(pathOf(rpath), b) :: out
    out

  /** the children of one group.
   *
   *  An in-order scan with a mirror of the child list as the patches so far
   *  leave it. At position `j`: if the child sitting there already carries the
   *  wanted identity, recurse into it; otherwise, if that identity turns up
   *  LATER in the mirror, delete forward until it arrives (the rows it passed
   *  are gone from the new list); otherwise it is new, so insert it. Trailing
   *  leftovers are deleted at the end.
   *
   *  A key of `""` on either side means "no identity": the two are matched
   *  POSITIONALLY and compared field by field, which is what every static
   *  screen (a title, a footer, a status line) wants — those children never
   *  move, and giving them names would be ceremony.
   *
   *  No move patch exists, so a reorder costs a delete and an insert of the
   *  moved rows. That is deliberate: the lists here are at most a dozen rows,
   *  and a minimal-move script (an LIS over the key positions) buys nothing a
   *  user could perceive at the price of the one part of this module that
   *  would be hard to read. */
  def walkGroup(olds: List[Keyed], news: List[Keyed], rpath: List[Int], acc: List[Patch]): List[Patch] =
    val path = pathOf(rpath)
    var cur = olds
    var out = acc
    val n = Views.len(news)
    var j = 0
    while j < n do
      val want = Views.nth(news, j)
      if j >= Views.len(cur) then
        out = PInsert(path, j, want) :: out
        cur = Views.insertAt(cur, j, want)
      else
        val here = Views.nth(cur, j)
        if sameIdentity(here, want) then
          out = walk(here.view, want.view, j :: rpath, out)
          cur = Views.setAt(cur, j, want)
        else
          val k = findKey(cur, j + 1, want.key)
          if k < 0 then
            out = PInsert(path, j, want) :: out
            cur = Views.insertAt(cur, j, want)
          else
            var d = k - j
            while d > 0 do
              out = PDelete(path, j) :: out
              cur = Views.removeAt(cur, j)
              d -= 1
            out = walk(Views.nth(cur, j).view, want.view, j :: rpath, out)
            cur = Views.setAt(cur, j, want)
      j += 1
    while Views.len(cur) > n do
      out = PDelete(path, n) :: out
      cur = Views.removeAt(cur, n)
    out

  /** do these two children name the same thing? An empty key on either side is
   *  the positional case: whatever is at this index is the counterpart. */
  def sameIdentity(a: Keyed, b: Keyed): Boolean =
    if a.key == "" || b.key == "" then true else a.key == b.key

  /** the index at or after `from` carrying `key`, or -1. An empty key never
   *  matches — an unnamed child cannot be searched for. */
  def findKey(xs: List[Keyed], from: Int, key: String): Int =
    var out = -1
    if key != "" then
      var i = from
      val n = Views.len(xs)
      var going = true
      while going && i < n do
        if Views.nth(xs, i).key == key then
          out = i
          going = false
        else i += 1
    out

  def pathOf(rpath: List[Int]): List[Int] = ListOps.reverse(rpath)

object Patches:
  /** apply a whole script, in order, to a retained mirror. */
  def applyAll(ps: List[Patch], v: View): View =
    var out = v
    var cur = ps
    var going = true
    while going do
      cur match
        case h :: t =>
          out = applyOne(h, out)
          cur = t
        case Nil => going = false
    out

  def applyOne(p: Patch, v: View): View = p match
    case s: PSet    => replaceAt(v, s.path, s.view)
    case i: PInsert => editGroup(v, i.path, true, i.idx, i.keyed)
    case d: PDelete => editGroup(v, d.path, false, d.idx, Keyed("", VGroup(Nil)))

  /** put `nv` where `path` points, keeping the key of the child it replaces —
   *  a `PSet` changes what a child shows, never who it is. */
  def replaceAt(v: View, path: List[Int], nv: View): View =
    var out = nv
    path match
      case h :: t =>
        v match
          case g: VGroup =>
            val child = Views.nth(g.children, h)
            out = VGroup(Views.setAt(g.children, h, Keyed(child.key, replaceAt(child.view, t, nv))))
          case _ => out = v
      case Nil => out = nv
    out

  /** insert into / delete from the child list of the group `path` points at. */
  def editGroup(v: View, path: List[Int], insert: Boolean, idx: Int, k: Keyed): View =
    var out = v
    path match
      case h :: t =>
        v match
          case g: VGroup =>
            val child = Views.nth(g.children, h)
            out = VGroup(Views.setAt(g.children, h,
              Keyed(child.key, editGroup(child.view, t, insert, idx, k))))
          case _ => out = v
      case Nil =>
        v match
          case g: VGroup =>
            if insert then out = VGroup(Views.insertAt(g.children, idx, k))
            else out = VGroup(Views.removeAt(g.children, idx))
          case _ => out = v
    out
