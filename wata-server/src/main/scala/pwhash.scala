import language.experimental.saferExceptions

/** Password hashing at rest: PBKDF2-HMAC-SHA256, no external dependency.
 *
 *  `users.json` stores
 *
 *  {{{
 *  "hash": "pbkdf2-sha256$<iterations>$<saltB64>$<dkB64>"
 *  }}}
 *
 *  (standard base64, 16-byte salt, 32-byte derived key). Every parameter that
 *  affects the derivation rides the string, so raising `iterations` later
 *  re-hashes lazily on the next password set — a stored hash always carries
 *  the cost it was made with, and no migration is needed.
 *
 *  The derivation is the RFC 2898 §5.2 loop written out here over Go's
 *  `crypto/hmac` + `crypto/sha256` (the `go.hmac`/`go.sha256`/`go.hashpkg`
 *  facades, gocrypto.scala); it is oracled against the published
 *  PBKDF2-HMAC-SHA256 test vectors by `SelfCheck` (server.scala), byte-exact
 *  against `tools/wata-selfcheck.expected.txt`.
 *
 *  Bytes are `go.Bytes` (`[]byte`) rather than the portable `Bytes` prelude:
 *  every byte here crosses a Go facade boundary, and one representation
 *  avoids a conversion at each call.
 *
 *  Verification is constant-time (`Store.ctEq` — crypto/subtle) over the
 *  base64 of the derived key, so a comparison cannot leak how much of a guess
 *  matched.
 */
object Pwhash:

  /** the format tag; also the "is this already hashed" probe. */
  def prefix: String = "pbkdf2-sha256$"

  /** the cost new hashes are minted with. Stored hashes carry their own, so
   *  changing this number invalidates nothing. */
  def defaultIterations: scala.Int = 600000

  /** derived-key length, bytes (one SHA-256 block — so the block loop runs
   *  once). */
  def dkLen: scala.Int = 32

  /** salt length, bytes. */
  def saltLen: scala.Int = 16

  // ---- the derivation ---------------------------------------------------------

  /** PBKDF2(P, S, c, dkLen) with HMAC-SHA256 as the PRF.
   *
   *  `DK = T1 || T2 || ...` truncated to `want`, where
   *  `Ti = U1 ^ U2 ^ ... ^ Uc`, `U1 = PRF(P, S || INT32BE(i))` and
   *  `Uj = PRF(P, U(j-1))`. ONE `hash.Hash` is keyed with the password once
   *  and `Reset()` between messages, rather than re-keying per iteration. */
  def derive(password: go.Bytes, salt: go.Bytes, iters: scala.Int, want: scala.Int): go.Bytes =
    val mac = go.hmac.New(go.sha256.New, password)
    var out: go.Bytes = go.makeSlice[Byte](0)
    var block = 1
    while out.length < want do
      out = appendUpTo(out, blockT(mac, salt, iters, block), want)
      block = block + 1
    out

  def appendUpTo(acc: go.Bytes, add: go.Bytes, want: scala.Int): go.Bytes =
    var out: go.Bytes = acc
    var i = 0
    while i < add.length && out.length < want do
      out = out.appended(add(i))
      i = i + 1
    out

  def blockT(mac: go.hashpkg.Hash, salt: go.Bytes, iters: scala.Int, index: scala.Int): go.Bytes =
    var u: go.Bytes = prf(mac, saltIndexed(salt, index))
    var acc: go.Bytes = u
    var i = 1
    while i < iters do
      u = prf(mac, u)
      acc = xorBytes(acc, u)
      i = i + 1
    acc

  /** one PRF application: reset the keyed HMAC, feed the message, take the
   *  digest. Go's `Sum` APPENDS to its argument, so it is handed an empty
   *  slice and returns the bare digest. */
  def prf(mac: go.hashpkg.Hash, msg: go.Bytes): go.Bytes =
    mac.reset()
    macWrite(mac, msg)
    // `go.makeSlice[Byte](0)` is VAL-BOUND rather than passed inline: on the
    // pinned toolchain, argument position emits an undefined
    // `go__gocore_package_makeSlice`. Upstream F104 fixed that; the bind can
    // go at the repin that consumes it.
    val zero: go.Bytes = go.makeSlice[Byte](0)
    mac.sum(zero)

  /** `hash.Hash.Write` never fails (that is its documented contract), but the
   *  facade surfaces Go's `(int, error)` faithfully — so the error is caught
   *  and dropped here rather than infecting the derivation loop. */
  def macWrite(mac: go.hashpkg.Hash, msg: go.Bytes): Unit =
    try
      val n = mac.write(msg)
      ()
    catch case e: sgo.GoError => ()

  /** `S || INT32BE(i)`. */
  def saltIndexed(salt: go.Bytes, index: scala.Int): go.Bytes =
    val n = salt.length
    val out = go.makeSlice[Byte](n + 4)
    var i = 0
    while i < n do
      out(i) = salt(i)
      i = i + 1
    out(n) = ((index >> 24) & 255).toByte
    out(n + 1) = ((index >> 16) & 255).toByte
    out(n + 2) = ((index >> 8) & 255).toByte
    out(n + 3) = (index & 255).toByte
    out

  def xorBytes(a: go.Bytes, b: go.Bytes): go.Bytes =
    val out = go.makeSlice[Byte](a.length)
    var i = 0
    while i < a.length do
      out(i) = ((a(i) ^ b(i)) & 255).toByte
      i = i + 1
    out

  // ---- the stored form --------------------------------------------------------

  def isHashed(s: String): Boolean = s.startsWith(prefix)

  /** mint a fresh hash for `password`: random salt, `defaultIterations`. */
  def hash(password: String): String =
    hashWith(password, randomSalt(), defaultIterations)

  def hashWith(password: String, salt: go.Bytes, iters: scala.Int): String =
    val dk = derive(go.bytes(password), salt, iters, dkLen)
    prefix + JsonNav.longStr(iters.toLong) + "$" + b64(salt) + "$" + b64(dk)

  def b64(b: go.Bytes): String = go.b64std.StdEncoding.encodeToString(b)

  /** `crypto/rand.Read` never fails (guaranteed since Go 1.24 — it panics
   *  internally rather than returning an error), so like `hash.Hash.Write`
   *  the faithful facade error is caught per that contract, not swallowed. */
  def randomSalt(): go.Bytes =
    val buf = go.makeSlice[Byte](saltLen)
    try
      go.crypto.rand.read(buf)
      ()
    catch case e: sgo.GoError => ()
    buf

  /** does `password` verify against the stored `pbkdf2-sha256$...` string? A
   *  malformed stored value verifies against nothing. */
  def verify(stored: String, password: String): Boolean =
    if !isHashed(stored) then false else verifyParts(stored, password)

  def verifyParts(stored: String, password: String): Boolean =
    val iters = itersOf(stored)
    val saltB64 = field(stored, 2)
    val dkB64 = field(stored, 3)
    if iters <= 0 || saltB64 == "" || dkB64 == "" then false
    else verifyDerive(decodeB64(saltB64), iters, dkB64, password)

  def verifyDerive(salt: go.Bytes, iters: scala.Int, dkB64: String, password: String): Boolean =
    Store.ctEq(b64(derive(go.bytes(password), salt, iters, dkLenOf(dkB64))), dkB64)

  /** derive exactly as many bytes as the stored key holds, so a hash written
   *  with a different dkLen still verifies. */
  def dkLenOf(dkB64: String): scala.Int =
    val n = decodeB64(dkB64).length
    if n <= 0 then dkLen else n

  def itersOf(stored: String): scala.Int =
    var n = 0
    try
      val v = go.strconv.parseInt(field(stored, 1), 10, 32)
      n = v.toInt
      ()
    catch case e: sgo.GoError => n = 0
    n

  def decodeB64(s: String): go.Bytes =
    val zero: go.Bytes = go.makeSlice[Byte](0)
    var out: go.Bytes = zero
    try
      val v = go.b64std.StdEncoding.decodeString(s)
      out = v
      ()
    catch case e: sgo.GoError => out = zero
    out

  /** the `want`-th `$`-separated field of the stored string ("" out of range).
   *  Base64's alphabet contains no `$`, so the split is unambiguous. */
  def field(s: String, want: scala.Int): String =
    var start = 0
    var k = 0
    var i = 0
    while i < s.length && k < want do
      if s.charAt(i) == '$' then
        k = k + 1
        start = i + 1
      else ()
      i = i + 1
    if k < want then "" else fieldFrom(s, start)

  def fieldFrom(s: String, start: scala.Int): String =
    var e = start
    while e < s.length && s.charAt(e) != '$' do e = e + 1
    s.substring(start, e)

  // ---- the oracle's rendering -------------------------------------------------

  /** lowercase hex of a byte slice — how the published PBKDF2 vectors are
   *  written, so `SelfCheck` compares against them verbatim. */
  def hex(b: go.Bytes): String =
    val sb = new StringBuilder
    var i = 0
    while i < b.length do
      // widen to Int explicitly: byte arithmetic stays `byte` in the emitted
      // Go, and a `byte` is not an `int` argument there.
      val v: scala.Int = b(i).toInt
      sb.append(hexDigit((v >> 4) & 15))
      sb.append(hexDigit(v & 15))
      i = i + 1
    sb.toString

  def hexDigit(v: scala.Int): Char =
    if v < 10 then ('0' + v).toChar else ('a' + (v - 10)).toChar
