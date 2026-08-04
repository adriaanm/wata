/** A minimal DETERMINISTIC PNG encoder for the headless-host golden-frame
 *  oracle. RGB565 pixel buffer -> a 24-bit truecolor PNG. Pure byte work over
 *  the portable `BytesBuilder` prelude; the image data rides stored
 *  (uncompressed) DEFLATE blocks, so the output is Go-version-independent and
 *  byte-stable — a golden compared in ci, regenerated and reviewed like a
 *  baseline. CRC-32 here is the zlib/PNG variant (the REFLECTED poly
 *  0xEDB88320 — DISTINCT from the Ogg writer's non-reflected 0x04C11DB7);
 *  Adler-32 covers the raw stream. Both carry a `Long` masked to 32 bits (Go
 *  has no unsigned `int32`, so 32-bit arithmetic that can carry into bit 31
 *  needs an explicit mask to avoid sign-extension bugs). A stored block caps
 *  at 65535 bytes, so `zlib` chunks the raw stream; at the current geometry
 *  the raw stream is H*(1+W*3) = 128*481 = 61568 bytes, i.e. ONE block, and
 *  the golden bytes are what a single-block encoder produces. The multi-block
 *  path is held green by `PngCheck` (`wata-fb pngtest`, run by fb-smoke).
 *
 *  NB every helper builds a LOCAL `BytesBuilder` and RETURNS a `Bytes`; NONE
 *  mutates a passed-in builder. A `BytesBuilder` (like a `[]byte`) passed as a
 *  parameter and appended to does NOT propagate the appends back to the caller
 *  (the emitter lowers `add*` to `b = append(b, ...)` on a by-value slice) — a
 *  silent-wrong-Go trap this file deliberately sidesteps. */
object Png:
  val CRC_POLY: Long = 0xedb88320L
  val MASK: Long = 0xffffffffL

  /** reflected zlib/PNG CRC-32 over `data`. */
  def crc32(data: Bytes): Long =
    var crc = MASK
    var i = 0
    val n = data.size
    while i < n do
      crc = crc ^ data(i).toLong
      var b = 0
      while b < 8 do
        if (crc & 1L) != 0L then crc = (crc >> 1) ^ CRC_POLY
        else crc = crc >> 1
        b += 1
      i += 1
    crc ^ MASK

  def adler32(data: Bytes): Long =
    var a = 1L
    var s = 0L
    var i = 0
    val n = data.size
    while i < n do
      a = (a + data(i).toLong) % 65521L
      s = (s + a) % 65521L
      i += 1
    (s << 16) | a

  /** big-endian u32 (PNG chunk lengths + CRCs + Adler are network order). */
  def be32(v: Long): Bytes =
    val b = new BytesBuilder
    b.addByte(((v >> 24) & 0xffL).toInt)
    b.addByte(((v >> 16) & 0xffL).toInt)
    b.addByte(((v >> 8) & 0xffL).toInt)
    b.addByte((v & 0xffL).toInt)
    b.result()

  /** one chunk: length(BE) + type + data + CRC(BE over type+data). */
  def chunk(ctype: String, data: Bytes): Bytes =
    val cb = new BytesBuilder      // CRC pre-image: type + data
    cb.addAscii(ctype)
    cb.addBytes(data)
    val body = cb.result()
    val out = new BytesBuilder
    out.addBytes(be32(data.size.toLong))
    out.addBytes(body)
    out.addBytes(be32(crc32(body)))
    out.result()

  def ihdr(): Bytes =
    val d = new BytesBuilder
    d.addBytes(be32(Display.W.toLong))
    d.addBytes(be32(Display.H.toLong))
    d.addByte(8)   // bit depth
    d.addByte(2)   // color type: truecolor RGB
    d.addByte(0)   // compression: deflate
    d.addByte(0)   // filter method: adaptive
    d.addByte(0)   // interlace: none
    d.result()

  /** filtered scanlines: each row = 1 filter byte (0 = none) + W*RGB, RGB565
   *  expanded to 8-bit via bit-replication (the exact inverse of `Color.rgb`). */
  def rawImage(px: go.Bytes): Bytes =
    val d = new BytesBuilder
    var y = 0
    while y < Display.H do
      d.addByte(0)
      var x = 0
      while x < Display.W do
        val idx = (y * Display.W + x) * 2
        val lo = px(idx).toInt & 0xff
        val hi = px(idx + 1).toInt & 0xff
        val c = lo | (hi << 8)
        val r5 = (c >> 11) & 0x1f
        val g6 = (c >> 5) & 0x3f
        val b5 = c & 0x1f
        d.addByte((r5 << 3) | (r5 >> 2))
        d.addByte((g6 << 2) | (g6 >> 4))
        d.addByte((b5 << 3) | (b5 >> 2))
        x = x + 1
      y = y + 1
    d.result()

  /** zlib wrapper: header + stored block(s) + Adler-32(raw). A stored block
   *  carries at most 65535 bytes, so the raw stream is chunked; only the last
   *  block sets BFINAL. Empty input still emits one (empty) final block. */
  def zlib(raw: Bytes): Bytes =
    val z = new BytesBuilder
    z.addByte(0x78)   // CMF (deflate, 32K window)
    z.addByte(0x01)   // FLG (no dict, fastest) — (0x78*256+0x01) % 31 == 0
    val n = raw.size
    var off = 0
    var going = true
    while going do
      var blen = n - off
      if blen > 65535 then blen = 65535
      var bfinal = 0
      if off + blen == n then bfinal = 1
      z.addByte(bfinal)  // block header: BFINAL + BTYPE=00 (stored)
      val nlen = 0xffff ^ blen
      z.addByte(blen & 0xff)
      z.addByte((blen >> 8) & 0xff)
      z.addByte(nlen & 0xff)
      z.addByte((nlen >> 8) & 0xff)
      z.addBytes(raw.slice(off, off + blen))
      off = off + blen
      if bfinal == 1 then going = false
    z.addBytes(be32(adler32(raw)))
    z.result()

  /** encode the RGB565 buffer as a complete PNG. */
  def encode(px: go.Bytes): Bytes =
    val out = new BytesBuilder
    out.addByte(0x89); out.addAscii("PNG")
    out.addByte(0x0d); out.addByte(0x0a); out.addByte(0x1a); out.addByte(0x0a)
    out.addBytes(chunk("IHDR", ihdr()))
    out.addBytes(chunk("IDAT", zlib(rawImage(px))))
    out.addBytes(chunk("IEND", Bytes.empty))
    out.result()

/** `wata-fb pngtest` (run by fb-smoke): the stored-DEFLATE selfcheck for
 *  `Png.zlib`. Encodes deterministic raw streams around and past the 65535-byte
 *  stored-block cap, then DECODES the stream with an independent stored-block
 *  walker: header bytes, per-block BTYPE/BFINAL/len/nlen consistency, payload
 *  equality with the input, the trailing Adler-32, exact stream length, and
 *  the expected block count. Greppable output: one `pngtest ... ok` line per
 *  size and a final `pngtest: PASS`; any failure prints `pngtest ... FAIL`. */
object PngCheck:
  def run(): Unit =
    var ok = true
    if !one(0, 1) then ok = false          // empty input still carries one final block
    if !one(100, 1) then ok = false
    if !one(65535, 1) then ok = false      // exactly the cap: still a single block
    if !one(65536, 2) then ok = false      // one byte over: the second block
    if !one(150000, 3) then ok = false     // 65535 + 65535 + 18930
    if ok then println("pngtest: PASS")
    else println("pngtest: FAIL")

  /** deterministic pseudo-random raw stream of `n` bytes. */
  def pattern(n: scala.Int): Bytes =
    val b = new BytesBuilder
    var i = 0
    while i < n do
      b.addByte((i * 31 + 7) & 0xff)
      i += 1
    b.result()

  def one(rawSize: scala.Int, wantBlocks: scala.Int): Boolean =
    val msg = checkZlib(pattern(rawSize), wantBlocks)
    if msg == "" then println("pngtest " + rawSize + " bytes -> " + wantBlocks + " block(s) ok")
    else println("pngtest " + rawSize + " bytes FAIL: " + msg)
    msg == ""

  /** decode `Png.zlib(raw)` as a stored-block DEFLATE stream; "" when it
   *  round-trips exactly, else what went wrong. */
  def checkZlib(raw: Bytes, wantBlocks: scala.Int): String =
    val z = Png.zlib(raw)
    var bad = ""
    if z(0) != 0x78 || z(1) != 0x01 then bad = "zlib header " + z(0) + "," + z(1)
    var pos = 2
    var rawPos = 0
    var blocks = 0
    var sawFinal = false
    while bad == "" && !sawFinal do
      val hdr = z(pos)
      if (hdr >> 1) != 0 then bad = "block " + blocks + ": header byte " + hdr + " (BTYPE != stored)"
      else
        val blen = z(pos + 1) | (z(pos + 2) << 8)
        val nlen = z(pos + 3) | (z(pos + 4) << 8)
        if (0xffff ^ blen) != nlen then bad = "block " + blocks + ": nlen " + nlen + " vs len " + blen
        else if rawPos + blen > raw.size then bad = "block " + blocks + ": overruns raw"
        else
          pos = pos + 5
          var i = 0
          while bad == "" && i < blen do
            if z(pos + i) != raw(rawPos + i) then bad = "block " + blocks + ": payload byte " + i
            i += 1
          pos = pos + blen
          rawPos = rawPos + blen
          blocks += 1
          if (hdr & 1) == 1 then sawFinal = true
          else if blen != 65535 then bad = "block " + (blocks - 1) + ": non-final block of " + blen
    if bad == "" && rawPos != raw.size then bad = "decoded " + rawPos + " of " + raw.size + " raw bytes"
    if bad == "" && blocks != wantBlocks then bad = "" + blocks + " blocks, want " + wantBlocks
    if bad == "" then
      val adler = (z(pos).toLong << 24) | (z(pos + 1).toLong << 16) | (z(pos + 2).toLong << 8) | z(pos + 3).toLong
      if adler != Png.adler32(raw) then bad = "adler mismatch"
      else if pos + 4 != z.size then bad = "trailing bytes: stream " + z.size + ", consumed " + (pos + 4)
    bad
