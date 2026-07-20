/** M8 chunk 3 — read-side JSON navigation over the `json` module, PORTABLE
 *  (ZERO `go`). A trimmed sibling of wata-server's `JsonNav` (which lives in
 *  another module and pulls the `strconv` facade): field lookup walks the insertion-
 *  ordered `JObj` list; every match arm is a simple expr and every if-chain sits
 *  at def-body top level (the json module's flattening idiom). */
object WJson:

  /** object field lookup (insertion-ordered `List[(String, Json)]`). */
  def getField(j: Json, key: String): Option[Json] = j match
    case o: JObj => lookupList(o.fields, key)
    case _       => None

  def lookupList(fs: List[(String, Json)], key: String): Option[Json] = fs match
    case p :: t => lookupStep(p, t, key)
    case Nil  => None

  def lookupStep(p: (String, Json), t: List[(String, Json)], key: String): Option[Json] =
    val k: String = p._1 // bind to a String local so `==` stays native (DOGFOOD)
    if k == key then Some(p._2) else lookupList(t, key)

  /** a string field, or the default when absent / non-string. */
  def strField(j: Json, key: String, dflt: String): String = getField(j, key) match
    case s: Some[Json] => strOr(s.value, dflt)
    case None => dflt

  def strOr(j: Json, dflt: String): String = j match
    case s: JStr => s.s
    case _       => dflt

  /** a boolean field, or false when absent / non-bool. */
  def boolField(j: Json, key: String): Boolean = getField(j, key) match
    case s: Some[Json] => boolOr(s.value)
    case None => false

  def boolOr(j: Json): Boolean = j match
    case b: JBool => b.b
    case _        => false

  /** an int64 field, or the default (accepts `JInt`; a `JFloat` is truncated). */
  def longField(j: Json, key: String, dflt: Long): Long = getField(j, key) match
    case s: Some[Json] => longOr(s.value, dflt)
    case None => dflt

  def longOr(j: Json, dflt: Long): Long = j match
    case i: JInt   => i.v
    case f: JFloat => f.d.toLong
    case _         => dflt

  /** does this value have a `JStr` at `key`? (presence test, no default). */
  def hasStr(j: Json, key: String): Boolean = getField(j, key) match
    case s: Some[Json] => isStr(s.value)
    case None => false

  def isStr(j: Json): Boolean = j match
    case _: JStr => true
    case _       => false

  /** the child object at `key`, or `JNull` when absent / non-object. */
  def objField(j: Json, key: String): Json = getField(j, key) match
    case s: Some[Json] => s.value
    case None => JNull()
