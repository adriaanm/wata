import language.experimental.saferExceptions

/** Small string helpers for the headless command loop — there is no
 *  `String.split` in this dialect, so tokenizing and indexing are
 *  hand-written (wata-tui's Str, trimmed to what this app parses). */
object MacStr:

  def splitWs(line: String): List[String] =
    var acc: List[String] = Nil
    var cur = ""
    var i = 0
    while i < line.length do
      val ch = line.substring(i, i + 1)
      if ch == " " || ch == "\t" || ch == "\r" then
        if cur != "" then acc = cur :: acc
        cur = ""
      else cur = cur + ch
      i = i + 1
    if cur != "" then acc = cur :: acc
    ListOps.reverse(acc)

  def nth(xs: List[String], i: scala.Int): String =
    if i < 0 then ""
    else xs match
      case h :: t => nthStep(h, t, i)
      case Nil    => ""

  def nthStep(h: String, t: List[String], i: scala.Int): String =
    if i == 0 then h else nth(t, i - 1)

  /** decimal parse with a fallback. */
  def num(s: String, dflt: scala.Int): scala.Int =
    var out = 0
    var ok = s.length > 0
    var i = 0
    while i < s.length do
      val d = digit(s.substring(i, i + 1))
      if d < 0 then ok = false
      else out = out * 10 + d
      i = i + 1
    var res = dflt
    if ok then res = out
    res

  def digit(ch: String): scala.Int =
    if ch == "0" then 0
    else if ch == "1" then 1
    else if ch == "2" then 2
    else if ch == "3" then 3
    else if ch == "4" then 4
    else if ch == "5" then 5
    else if ch == "6" then 6
    else if ch == "7" then 7
    else if ch == "8" then 8
    else if ch == "9" then 9
    else -1

  /** the non-empty lines of a newline-separated block — the shape every
   *  macshell list setter speaks. */
  def lines(block: String): List[String] =
    var acc: List[String] = Nil
    var cur = ""
    var i = 0
    while i < block.length do
      val ch = block.substring(i, i + 1)
      if ch == "\n" then
        if cur != "" then acc = cur :: acc
        cur = ""
      else if ch != "\r" then cur = cur + ch
      i = i + 1
    if cur != "" then acc = cur :: acc
    ListOps.reverse(acc)

  def boolStr(b: Boolean): String = if b then "true" else "false"

  /** field `i` of a TAB-separated line, or "" past the end. The login sheet's
   *  answer is tab-separated rather than whitespace-separated because a
   *  password may hold spaces and a homeserver may not be typed tidily —
   *  `splitWs` would tear both apart. Hand-scanned: the subset has no split.
   */
  def tabField(line: String, i: scala.Int): String =
    var start = 0
    var field = 0
    var out = ""
    var found = false
    var p = 0
    while p <= line.length do
      val atEnd = p == line.length
      if atEnd || line.substring(p, p + 1) == "\t" then
        if field == i && !found then
          out = line.substring(start, p)
          found = true
        field = field + 1
        start = p + 1
      p = p + 1
    out
