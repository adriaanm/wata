import ListOps.*
import JsonNav.*

/** The E2EE device-key endpoints, as NO-OP STUBS.
 *
 *  wata-server implements no end-to-end encryption (see the scope note in
 *  docs/design/wata-server.md): nothing is stored, nothing is returned, no key
 *  is ever handed to another user. These three routes exist only so that a
 *  stock Matrix client — Element, FluffyChat — completes the device-key
 *  handshake it runs right after login and proceeds to `/sync` instead of
 *  stalling. The trust boundary here is the network, not the room.
 *
 *  The one stub that is not simply `{}` is `/keys/upload`: matrix-dart-sdk
 *  (FluffyChat) rejects the response unless the reported one-time-key counts
 *  match what it just sent, so the counts are tallied back per algorithm even
 *  though the keys themselves are discarded.
 *
 *  Unlike the reference implementation these were shaped against, all three
 *  require an access token — every other non-public endpoint on this server
 *  does, and an unauthenticated stub is a gratuitous hole.
 */
object Keys:

  def route(path: String, r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]  => Left(l.left)
    case _: Right[MErr, Auth] => routeAuthed(path, body)

  /** `/keys/device_signing/upload` is the fall-through — it also ends in
   *  `/upload`, so it must not be matched by the `/keys/upload` test. */
  def routeAuthed(path: String, body: String): Either[MErr, Json] =
    if path.endsWith("/keys/query") then Right(queryReply)
    else if path.endsWith("/keys/upload") then Right(uploadReply(body))
    else Right(emptyObj)

  /** every map empty: this server knows no device keys and no cross-signing
   *  identities, and failed for no user. */
  def queryReply: Json =
    var fs: List[(String, Json)] = startObj
    fs = ("device_keys", emptyObj) :: fs
    fs = ("master_keys", emptyObj) :: fs
    fs = ("self_signing_keys", emptyObj) :: fs
    fs = ("user_signing_keys", emptyObj) :: fs
    fs = ("failures", emptyObj) :: fs
    endObj(fs)

  def uploadReply(body: String): Json = Json.tryParse(body) match
    case Right(j) => obj1("one_time_key_counts", counts(j))
    case Left(_)  => obj1("one_time_key_counts", counts(emptyObj))

  /** `one_time_keys` is keyed `"<algorithm>:<id>"`; tally how many arrived per
   *  algorithm. The tally accumulates in a `JObj` so insertion order is kept
   *  and a repeat algorithm updates in place. */
  def counts(j: Json): Json =
    ensureCurve(tally(fieldsOf(getField(j, "one_time_keys")), emptyObj))

  def fieldsOf(o: Option[Json]): List[(String, Json)] = o match
    case s: Some[Json] => objFields(s.value)
    case None => Nil

  def objFields(j: Json): List[(String, Json)] = j match
    case o: JObj => o.fields
    case _       => Nil

  def tally(fs: List[(String, Json)], acc: Json): Json = fs match
    case p :: t => tallyStep(p, t, acc)
    case Nil  => acc

  def tallyStep(p: (String, Json), t: List[(String, Json)], acc: Json): Json =
    val k: String = p._1
    tally(t, bump(acc, algoOf(k)))

  def algoOf(keyId: String): String =
    val i = keyId.indexOf(":")
    if i < 0 then keyId else keyId.substring(0, i)

  def bump(acc: Json, algo: String): Json =
    jsonSet(acc, algo, JInt(longOrDflt(acc, algo, 0L) + 1L))

  /** the algorithm every Olm client asks after is always reported, even as 0 —
   *  an absent count reads as "unknown", a zero reads as "none left". */
  def ensureCurve(acc: Json): Json = getField(acc, "signed_curve25519") match
    case _: Some[Json] => acc
    case None => jsonSet(acc, "signed_curve25519", JInt(0L))
