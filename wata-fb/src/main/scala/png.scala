/** M8 chunk 5 — a minimal DETERMINISTIC PNG encoder for the headless-host
 *  golden-frame oracle (decision 4). RGB565 pixel buffer -> a 24-bit truecolor
 *  PNG. Pure byte work over the portable `BytesBuilder` prelude; the image data
 *  rides a SINGLE stored (uncompressed) DEFLATE block, so the output is
 *  Go-version-independent and byte-stable — a golden compared in ci, regenerated
 *  designer-reviewed like a baseline. CRC-32 here is the zlib/PNG variant (the
 *  REFLECTED poly 0xEDB88320 — DISTINCT from ogg.zig's non-reflected 0x04C11DB7);
 *  Adler-32 covers the raw stream. Both carry a `Long` masked to 32 bits (the
 *  ogg.scala width-trap precedent). One stored block suffices: the raw stream is
 *  H*(1+W*3) = 128*481 = 61568 bytes < 65535.
 *
 *  NB every helper builds a LOCAL `BytesBuilder` and RETURNS a `Bytes`; NONE
 *  mutates a passed-in builder. A `BytesBuilder` (like a `[]byte`) passed as a
 *  parameter and appended to does NOT propagate the appends back to the caller
 *  (the emitter lowers `add*` to `b = append(b, ...)` on a by-value slice) — a
 *  silent-wrong-Go trap this file deliberately sidesteps. See the chunk report
 *  (the shipped ogg.scala Ogg *writer* hits the same trap, latently). */
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
   *  expanded to 8-bit (bit-replication, the exact inverse of display.zig rgb). */
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

  /** zlib wrapper: header + one final stored block + Adler-32(raw). */
  def zlib(raw: Bytes): Bytes =
    val z = new BytesBuilder
    z.addByte(0x78)   // CMF (deflate, 32K window)
    z.addByte(0x01)   // FLG (no dict, fastest) — (0x78*256+0x01) % 31 == 0
    z.addByte(0x01)   // block header: BFINAL=1, BTYPE=00 (stored)
    val rlen = raw.size
    val nlen = 0xffff ^ rlen
    z.addByte(rlen & 0xff)
    z.addByte((rlen >> 8) & 0xff)
    z.addByte(nlen & 0xff)
    z.addByte((nlen >> 8) & 0xff)
    z.addBytes(raw)
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
