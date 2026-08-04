import language.experimental.saferExceptions

/** Small string/list helpers. There is no `String.split` and no generic
 *  collection surface here, so tokenizing and indexing are hand-written — the
 *  same shapes wata-fb's script driver uses, plus the element-typed `nth`s the
 *  monomorphic subset needs. */
object Str:

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

  /** everything from token `n` on, VERBATIM (spaces preserved) — how `raw`
   *  keeps a JSON body in one piece. */
  def restLine(line: String, n: scala.Int): String =
    var rest = line
    var i = 0
    while i < n do
      rest = dropToken(rest)
      i = i + 1
    rest

  def dropToken(s: String): String =
    var i = skipSpace(s, 0)
    while i < s.length && !isSpace(s.substring(i, i + 1)) do i = i + 1
    s.substring(skipSpace(s, i), s.length)

  def skipSpace(s: String, from: scala.Int): scala.Int =
    var i = from
    while i < s.length && isSpace(s.substring(i, i + 1)) do i = i + 1
    i

  def isSpace(ch: String): Boolean = ch == " " || ch == "\t" || ch == "\r"

  def nth(xs: List[String], i: scala.Int): String =
    if i < 0 then ""
    else xs match
      case h :: t => nthStep(h, t, i)
      case Nil    => ""

  def nthStep(h: String, t: List[String], i: scala.Int): String =
    if i == 0 then h else nth(t, i - 1)

  def nthOfConv(xs: List[Conversation], i: scala.Int): Option[Conversation] =
    if i < 0 then None
    else xs match
      case h :: t => nthConvStep(h, t, i)
      case Nil    => None

  def nthConvStep(h: Conversation, t: List[Conversation], i: scala.Int): Option[Conversation] =
    if i == 0 then Some(h) else nthOfConv(t, i - 1)

  def nthOfMsg(xs: List[VoiceMessage], i: scala.Int): Option[VoiceMessage] =
    if i < 0 then None
    else xs match
      case h :: t => nthMsgStep(h, t, i)
      case Nil    => None

  def nthMsgStep(h: VoiceMessage, t: List[VoiceMessage], i: scala.Int): Option[VoiceMessage] =
    if i == 0 then Some(h) else nthOfMsg(t, i - 1)

  def restOf(ts: List[String]): List[String] = ts match
    case _ :: t => t
    case Nil    => Nil

  def appended(xs: List[String], x: String): List[String] =
    ListOps.reverse(x :: ListOps.reverse(xs))

  def len(xs: List[String]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  def lenMsg(xs: List[VoiceMessage]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  def lenConv(xs: List[Conversation]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  /** decimal parse with a fallback (a non-numeric token is not a number, which
   *  is how `send` tells a conversation ref from a user id). */
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

  def boolStr(b: Boolean): String = if b then "true" else "false"
