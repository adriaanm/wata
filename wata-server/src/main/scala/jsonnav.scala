import ListOps.*

/** JSON navigation + envelope glue over the `json` module.
 *
 *  Read side: `getField`/`asStr` walk the insertion-ordered `JObj` field list
 *  (the json module's `List[(String, Json)]`). Write side: envelope + tiny
 *  builders. Every match arm is a simple expr or a single call, and every
 *  if-chain lives at a def-body top level — this Scala subset only lowers
 *  `throw`/`try`/value-`if` in those positions, so this file follows the json
 *  module's own flattening idiom. No `OptionOps`/`EitherOps`/`ListOps` inline
 *  combinators (their `inline f` beta-reduction over destructured binders is
 *  fragile — the json module hand-writes its walks too).
 */
object JsonNav:

  // ---- the errcode -> string tag map ------------------------------------------
  def codeStr(c: ErrCode): String = c match
    case _: M_FORBIDDEN     => "M_FORBIDDEN"
    case _: M_NOT_FOUND     => "M_NOT_FOUND"
    case _: M_UNKNOWN_TOKEN => "M_UNKNOWN_TOKEN"
    case _: M_MISSING_TOKEN => "M_MISSING_TOKEN"
    case _: M_UNRECOGNIZED  => "M_UNRECOGNIZED"
    case _: M_BAD_JSON      => "M_BAD_JSON"
    case _: M_UNKNOWN       => "M_UNKNOWN"

  def boolStr(b: Boolean): String = if b then "true" else "false"

  /** an int64 as its decimal string (Go `strconv`), for sync tokens
   *  (`next_batch`/`prev_batch` = `"s" + seq`). */
  def longStr(x: scala.Long): String = go.strconv.formatInt(x, 10)

  // ---- JSON builders ---------------------------------------------------------
  // Every object/array is built by prepending single conses onto a `var` (the
  // json module's proven idiom). A MULTI-CONS `::` LITERAL in expression/arg
  // position lowers incorrectly in this compiler (an emitted Go return type
  // that is a bare undefined `List` head), so it is avoided everywhere.

  /** empty object `{}` — the success body for logout / set-* endpoints. */
  def emptyObj: Json = JObj(Nil)

  def obj1(k1: String, v1: Json): Json =
    var fs: List[(String, Json)] = Nil
    fs = (k1, v1) :: fs
    JObj(fs)

  def obj2(k1: String, v1: Json, k2: String, v2: Json): Json =
    var fs: List[(String, Json)] = Nil
    fs = (k2, v2) :: fs
    fs = (k1, v1) :: fs
    JObj(fs)

  def obj4(k1: String, v1: Json, k2: String, v2: Json,
           k3: String, v3: Json, k4: String, v4: Json): Json =
    var fs: List[(String, Json)] = Nil
    fs = (k4, v4) :: fs
    fs = (k3, v3) :: fs
    fs = (k2, v2) :: fs
    fs = (k1, v1) :: fs
    JObj(fs)

  def arr1(v1: Json): Json =
    var xs: List[Json] = Nil
    xs = v1 :: xs
    JArr(xs)

  /** the standard Matrix `{errcode, error}` body as a `Json` value. */
  def errEnvelope(e: MErr): Json =
    obj2("errcode", JStr(codeStr(e.code)), "error", JStr(e.msg))

  // ---- object field lookup ---------------------------------------------------

  def getField(j: Json, key: String): Option[Json] = j match
    case o: JObj => lookupList(o.fields, key)
    case _       => None

  def lookupList(fs: List[(String, Json)], key: String): Option[Json] = fs match
    case p :: t => lookupStep(p, t, key)
    case Nil  => None

  def lookupStep(p: (String, Json), t: List[(String, Json)], key: String): Option[Json] =
    // bind the tuple's `_1` to a String local before comparing: comparing the
    // tuple accessor directly makes the compiler pick a boxed-equality helper
    // over an undefined `Object` (a broken stub) rather than native string `==`.
    // The local's declared String type restores native ==.
    val k: String = p._1
    if k == key then Some(p._2) else lookupList(t, key)

  /** a JSON value as a String, or `None` if it is not a `JStr`. */
  def asStr(j: Json): Option[String] = j match
    case s: JStr => Some(s.s)
    case _       => None

  /** a required string field, or the default when absent / non-string. */
  def strField(j: Json, key: String, dflt: String): String = getField(j, key) match
    case s: Some[Json] => strOr(s.value, dflt)
    case None => dflt

  def strOr(j: Json, dflt: String): String = j match
    case s: JStr => s.s
    case _       => dflt

  /** an int64 field, or the default when absent / not an integer (the
   *  power-levels table is read entirely through this). */
  def longOrDflt(j: Json, key: String, dflt: scala.Long): scala.Long = getField(j, key) match
    case s: Some[Json] => longValOr(s.value, dflt)
    case None => dflt

  def longValOr(j: Json, dflt: scala.Long): scala.Long = j match
    case i: JInt => i.v
    case _       => dflt

  /** is this JSON value an object (spec: account-data bodies must be objects). */
  def isObj(j: Json): Boolean = j match
    case _: JObj => true
    case _       => false

  /** a boolean field, or the default when absent / non-bool. */
  def boolField(j: Json, key: String): Boolean = getField(j, key) match
    case s: Some[Json] => boolOr(s.value, false)
    case None => false

  def boolOr(j: Json, dflt: Boolean): Boolean = j match
    case b: JBool => b.b
    case _        => dflt

  /** finish an object built by prepending single conses onto a `var` (the proven
   *  idiom): the reverse restores insertion order. */
  def endObj(fs: List[(String, Json)]): Json = JObj(ListOps.reverse(fs))

  def startObj: List[(String, Json)] = Nil

  def obj3(k1: String, v1: Json, k2: String, v2: Json, k3: String, v3: Json): Json =
    var fs: List[(String, Json)] = Nil
    fs = (k1, v1) :: fs
    fs = (k2, v2) :: fs
    fs = (k3, v3) :: fs
    endObj(fs)

  /** set (replace-or-append, insertion-order-preserving) a field in a JSON object;
   *  a non-object becomes a fresh one-field object. Used by the profile fan-out. */
  def jsonSetStr(j: Json, key: String, value: String): Json = jsonSet(j, key, JStr(value))

  def jsonSet(j: Json, key: String, value: Json): Json = j match
    case o: JObj => JObj(setField(o.fields, key, value))
    case _       => obj1(key, value)

  def setField(fs: List[(String, Json)], key: String, value: Json): List[(String, Json)] =
    if fieldHas(fs, key) then fieldReplace(fs, key, value, Nil)
    else appendField(fs, key, value)

  def fieldHas(fs: List[(String, Json)], key: String): Boolean = fs match
    case p :: t => fieldHasStep(p, t, key)
    case Nil  => false

  def fieldHasStep(p: (String, Json), t: List[(String, Json)], key: String): Boolean =
    val k: String = p._1
    if k == key then true else fieldHas(t, key)

  def fieldReplace(fs: List[(String, Json)], key: String, value: Json, acc: List[(String, Json)]): List[(String, Json)] = fs match
    case p :: t => fieldReplaceStep(p, t, key, value, acc)
    case Nil  => ListOps.reverse(acc)

  def fieldReplaceStep(p: (String, Json), t: List[(String, Json)], key: String, value: Json, acc: List[(String, Json)]): List[(String, Json)] =
    val k: String = p._1
    var acc2: List[(String, Json)] = acc
    if k == key then acc2 = (key, value) :: acc2 else acc2 = p :: acc2
    fieldReplace(t, key, value, acc2)

  def appendField(fs: List[(String, Json)], key: String, value: Json): List[(String, Json)] =
    var r: List[(String, Json)] = ListOps.reverse(fs)
    r = (key, value) :: r
    ListOps.reverse(r)

  /** the m.room.member profile fan-out merge (`Store.updateMemberProfile`):
   *  set displayname; set avatar_url only when the profile has one. */
  def mergeProfile(content: Json, prof: Profile): Json =
    val c1 = jsonSetStr(content, "displayname", prof.displayname)
    if prof.avatarUrl == "" then c1 else jsonSetStr(c1, "avatar_url", prof.avatarUrl)

  /** an event as its Matrix wire object (used for `redacted_because` and by
   *  /sync). Optional fields present only when their has-flag is set. */
  def eventToJson(ev: Event): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("event_id", JStr(ev.eventId)) :: fs
    fs = ("type", JStr(ev.etype)) :: fs
    fs = ("sender", JStr(ev.sender)) :: fs
    fs = ("room_id", JStr(ev.roomId)) :: fs
    fs = ("origin_server_ts", JInt(ev.ts)) :: fs
    fs = ("content", ev.content) :: fs
    if ev.hasStateKey then fs = ("state_key", JStr(ev.stateKey)) :: fs else ()
    if ev.hasRedacts then fs = ("redacts", JStr(ev.redacts)) :: fs else ()
    fs = maybeUnsigned(fs, ev.unsigned)
    JObj(ListOps.reverse(fs))

  def maybeUnsigned(fs: List[(String, Json)], u: Json): List[(String, Json)] = u match
    case _: JNull => fs
    case _        => consUnsigned(fs, u)

  def consUnsigned(fs: List[(String, Json)], u: Json): List[(String, Json)] =
    var r: List[(String, Json)] = fs
    r = ("unsigned", u) :: r
    r
