import language.experimental.saferExceptions

/** The ENCODER half of the frame-handoff wire (the decoder is
 *  go-pkgs/macshell/wire.go, whose header pins the grammar — the two must
 *  agree byte for byte). One String crosses the facade per frame: the whole
 *  first tree, or the differ's script in order.
 *
 *  Every token is followed by exactly one space. Strings are length-prefixed
 *  and VERBATIM (`Bytes.rawString` for pixel payloads — a Go string is just
 *  bytes), so nothing needs escaping. Lengths are BYTE lengths. */
object Wire:

  def encodeTree(v: View): String = "= " + view(v)

  def encodeScript(ps: List[Patch]): String =
    var out = "* " + num(lenPatches(ps))
    var cur = ps
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + patch(h)
          cur = t
        case Nil => going = false
    out

  def view(v: View): String = v match
    case x: VText  => "T " + num(x.col) + num(x.row) + num(x.color) + str(x.text)
    case x: VGlyph => "G " + num(x.x) + num(x.y) + num(x.glyph) + num(x.color)
    case x: VRect  => "R " + num(x.x) + num(x.y) + num(x.w) + num(x.h) + num(x.color)
    case x: VImage => "I " + num(x.x) + num(x.y) + num(x.w) + num(x.h) + raw(x.pixels)
    case x: VGroup => "P " + num(Views.len(x.children)) + children(x.children)

  def children(xs: List[Keyed]): String =
    var out = ""
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + str(h.key) + view(h.view)
          cur = t
        case Nil => going = false
    out

  def patch(p: Patch): String = p match
    case x: PSet    => "S " + path(x.path) + view(x.view)
    case x: PInsert => "N " + path(x.path) + num(x.idx) + str(x.keyed.key) + view(x.keyed.view)
    case x: PDelete => "D " + path(x.path) + num(x.idx)

  def path(p: List[Int]): String =
    var out = num(lenInts(p))
    var cur = p
    var going = true
    while going do
      cur match
        case h :: t =>
          out = out + num(h)
          cur = t
        case Nil => going = false
    out

  def num(n: Int): String = "" + n + " "

  /** a text token: BYTE length (these strings are ASCII, but the grammar is
   *  byte-counted either way), then the bytes verbatim, then the space. */
  def str(s: String): String = "" + go.bytes(s).length + " " + s + " "

  /** a pixel payload: the bytes verbatim as a String (`rawString` — Go:
   *  `string(b)`). */
  def raw(bs: Bytes): String = "" + bs.length + " " + bs.rawString + " "

  def lenPatches(ps: List[Patch]): Int =
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
