/** M8 chunk 3 — the Ogg/Opus container, ported from fbclient `ogg.zig` (BOTH
 *  directions). Pure byte work over the portable `Bytes`/`BytesBuilder` prelude
 *  (decision 11) — ZERO `go` imports. This is the chunk's byte oracle: our
 *  writer's output round-trips through our reader byte-exact, the page CRC checks
 *  out, and the golden CRC-32 vectors match an independent reference (computed by
 *  the same poly-0x04C11DB7/init-0/no-reflection algorithm ogg.zig uses).
 *
 *  CRC note: ogg.zig uses a 256-entry lookup table; we use the mathematically
 *  IDENTICAL bit-serial MSB-first form (verified byte-for-byte against the golden
 *  vectors). The bit-serial form is a pure function with NO module-level table
 *  state (the determinism law) and sidesteps the 32-bit-Int-on-64-bit-Go width
 *  trap by carrying the CRC as a `Long` (Go int64) masked to 32 bits each step —
 *  a 256-entry table would hit the same trap at construction time.
 */

object Crc:
  val POLY: Long = 0x04c11db7L
  val MASK: Long = 0xffffffffL
  val TOP: Long = 0x80000000L

  /** Ogg CRC-32 update — poly 0x04C11DB7, init `crcIn`, no in/out reflection,
   *  no final XOR. `data(i)` is already an unsigned 0..255 read. */
  def crc32Update(crcIn: Long, data: Bytes): Long =
    var crc = crcIn & MASK
    var i = 0
    val n = data.size
    while i < n do
      crc = (crc ^ (data(i).toLong << 24)) & MASK
      var b = 0
      while b < 8 do
        val top = crc & TOP
        crc = (crc << 1) & MASK
        if top != 0L then crc = crc ^ POLY
        b += 1
      i += 1
    crc & MASK

  def crc32(data: Bytes): Long = crc32Update(0L, data)

object Ogg:
  val FLAG_BOS: Int = 0x02
  val FLAG_EOS: Int = 0x04
  val SERIAL: Int = 0x77617461     // "wata"
  val SAMPLES_PER_FRAME: Int = 960 // 20ms @ 48kHz

  // ---- payload builders -----------------------------------------------------

  /** the 19-byte OpusHead identification payload (mono, 48kHz, 312 pre-skip). */
  def opusHead(): Bytes =
    val b = new BytesBuilder
    b.addAscii("OpusHead")
    b.addByte(1)        // version
    b.addByte(1)        // channel count (mono)
    b.addU16LE(312)     // pre-skip (6.5ms @ 48kHz)
    b.addU32LE(48000)   // input sample rate
    b.addU16LE(0)       // output gain
    b.addByte(0)        // channel mapping family (mono)
    b.result()

  /** the 20-byte OpusTags comment payload ("wata" vendor, no comments). */
  def opusTags(): Bytes =
    val b = new BytesBuilder
    b.addAscii("OpusTags")
    b.addU32LE(4)       // vendor string length
    b.addAscii("wata")
    b.addU32LE(0)       // comment count
    b.result()

  // ---- writer ---------------------------------------------------------------

  /** number of lacing segments for a payload (0 -> 1 empty segment). */
  def segCount(len: Int): Int = if len == 0 then 1 else (len + 254) / 255

  /** build one page WITH the CRC field zeroed (the pre-image the CRC is over). */
  def pageNoCrc(payload: Bytes, granule: Long, headerType: Int, seq: Int): Bytes =
    val nseg = segCount(payload.size)
    val b = new BytesBuilder
    b.addAscii("OggS")
    b.addByte(0)               // stream structure version
    b.addByte(headerType)
    b.addU64LE(granule)
    b.addU32LE(SERIAL)
    b.addU32LE(seq)
    b.addU32LE(0)              // CRC placeholder (zeroed for the pre-image)
    b.addByte(nseg)
    var remaining = payload.size
    var i = 0
    while i < nseg do
      if remaining >= 255 then
        b.addByte(255)
        remaining = remaining - 255
      else
        b.addByte(remaining)
        remaining = 0
      i += 1
    b.addBytes(payload)
    b.result()

  /** one complete page, CRC patched into bytes 22..26 (the 4-byte field).
   *  Build-and-return (DATA-10, M8 chunk 5 completion cycle: the original
   *  `writePage(out: BytesBuilder, …)` mutated a PARAMETER — on sgola the
   *  builder lowers to a by-value `[]byte`, so the appends were silently lost
   *  and `writeStream` returned an empty stream; builders are function-local
   *  accumulators, now emitter-enforced). */
  def page(payload: Bytes, granule: Long, headerType: Int, seq: Int): Bytes =
    val pre = pageNoCrc(payload, granule, headerType, seq)
    val crc = Crc.crc32(pre)
    val out = new BytesBuilder
    out.addBytes(pre.slice(0, 22))
    out.addU32LE(crc.toInt)
    out.addBytes(pre.slice(26, pre.size))
    out.result()

  /** write a complete Ogg/Opus stream: OpusHead (BOS), OpusTags, one audio page
   *  per frame, then an empty EOS page. */
  def writeStream(frames: List[Bytes]): Bytes =
    val out = new BytesBuilder
    var seq = 0
    var granule = 0L
    out.addBytes(page(opusHead(), 0L, FLAG_BOS, seq))
    seq = seq + 1
    out.addBytes(page(opusTags(), 0L, 0, seq))
    seq = seq + 1
    var cur = frames
    var going = true
    while going do
      cur match
        case h :: t =>
          granule = granule + SAMPLES_PER_FRAME.toLong
          out.addBytes(page(h, granule, 0, seq))
          seq = seq + 1
          cur = t
        case Nil() => going = false
    out.addBytes(page(Bytes.empty, granule, FLAG_EOS, seq))
    out.result()

  // ---- reader ---------------------------------------------------------------

  /** does the 4-byte "OggS" capture pattern sit at `pos`? */
  def isOggS(data: Bytes, pos: Int): Boolean =
    if pos + 4 > data.size then false
    else data(pos) == 79 && data(pos + 1) == 103 && data(pos + 2) == 103 && data(pos + 3) == 83

  /** sum the lacing segment sizes for the page whose header starts at `pos`. */
  def payloadSize(data: Bytes, segStart: Int, nseg: Int): Int =
    var total = 0
    var i = 0
    while i < nseg do
      total = total + data(segStart + i)
      i += 1
    total

  /** extract every AUDIO frame (skipping BOS/OpusTags/empty pages, exactly as
   *  ogg.zig's `nextFrame`: BOS by flag, the SECOND page by count, empty by size). */
  def readFrames(data: Bytes): List[Bytes] =
    var acc: List[Bytes] = Nil[Bytes]()
    var pos = 0
    var pagesRead = 0
    var going = true
    while going do
      if pos + 27 > data.size then going = false
      else if !isOggS(data, pos) then going = false
      else
        val nseg = data(pos + 26)
        val segStart = pos + 27
        val segEnd = segStart + nseg
        if segEnd > data.size then going = false
        else
          val psize = payloadSize(data, segStart, nseg)
          val payloadStart = segEnd
          val payloadEnd = payloadStart + psize
          if payloadEnd > data.size then going = false
          else
            val headerType = data(pos + 5)
            pos = payloadEnd
            pagesRead = pagesRead + 1
            val isBos = (headerType & FLAG_BOS) != 0
            if isBos then ()
            else if pagesRead == 2 then ()
            else if psize == 0 then ()
            else acc = data.slice(payloadStart, payloadEnd) :: acc
    ListOps.reverse(acc)

  /** the count of decoded audio frames (a convenience over `readFrames`). */
  def frameCount(data: Bytes): Int =
    var cur = readFrames(data)
    var n = 0
    var going = true
    while going do
      cur match
        case c: ::[Bytes] => n = n + 1; cur = c.tail
        case Nil()        => going = false
    n
