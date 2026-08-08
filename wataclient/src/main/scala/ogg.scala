/** the Ogg/Opus container reader and writer. Pure byte work over the portable
 *  `Bytes`/`BytesBuilder` prelude — no `go` imports. The writer's output
 *  round-trips through the reader byte-exact, the page CRC checks out, and
 *  the golden CRC-32 vectors match an independent reference (computed by the
 *  same poly-0x04C11DB7/init-0/no-reflection algorithm).
 *
 *  CRC note: this uses a bit-serial MSB-first form rather than a lookup
 *  table (verified byte-for-byte against the golden vectors). The bit-serial
 *  form is a pure function with no module-level table state, and it sidesteps
 *  a 32-bit-Int-on-64-bit-Go width trap by carrying the CRC as a `Long` (Go
 *  int64) masked to 32 bits each step — a lookup table would hit the same
 *  trap at construction time.
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
  val FLAG_CONT: Int = 0x01
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
   *  Build-and-return, not mutate-a-parameter: a `BytesBuilder` parameter
   *  lowers to a by-value `[]byte` on this backend, so appending to it would
   *  be silently lost by the caller — builders must be function-local
   *  accumulators that are built and returned. */
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
        case Nil => going = false
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

  /** `a` followed by `b`, cheap when either is empty. Only page-spanning
   *  packets need it — a packet's segments are contiguous within one page, so
   *  the common case is a single slice. */
  def concat(a: Bytes, b: Bytes): Bytes =
    if a.size == 0 then b
    else if b.size == 0 then a
    else
      val out = new BytesBuilder
      out.addBytes(a)
      out.addBytes(b)
      out.result()

  /** the two Opus header packets, by their 8-byte magic. Identifying them by
   *  content rather than by page ordinal is what lets a foreign stream put
   *  them anywhere it likes — including a tags packet long enough to span
   *  pages, which arrives here already reassembled. */
  def isHeaderPacket(p: Bytes): Boolean =
    p.size >= 8 && p(0) == 79 && p(1) == 112 && p(2) == 117 && p(3) == 115 &&
      ((p(4) == 72 && p(5) == 101 && p(6) == 97 && p(7) == 100) ||  // OpusHead
       (p(4) == 84 && p(5) == 97 && p(6) == 103 && p(7) == 115))    // OpusTags

  /** extract every audio PACKET, skipping the Opus headers and empty packets.
   *
   *  A page is not a packet. The lacing table divides a page's payload into
   *  segments of at most 255 bytes, and a packet runs until a segment shorter
   *  than 255 ends it — so one page may carry many packets, and a packet whose
   *  final segment is a full 255 continues onto the next page (which flags
   *  itself `FLAG_CONT`). Our own writer emits exactly one packet per page and
   *  never spans, but a foreign encoder pages normally: reading a whole payload
   *  as one frame gives a decoder a run of concatenated packets it will treat
   *  as one, and the message plays as a fraction of itself.
   *
   *  A page that does not claim continuation abandons any unfinished packet:
   *  the stream was cut mid-packet, and half a packet is not decodable. */
  def readFrames(data: Bytes): List[Bytes] =
    var acc: List[Bytes] = Nil
    var pos = 0
    var carry = Bytes.empty  // an unfinished packet, continued from the previous page
    var sawAudio = false
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
            var prefix = if (headerType & FLAG_CONT) != 0 then carry else Bytes.empty
            var pktStart = payloadStart
            var off = payloadStart
            var i = 0
            while i < nseg do
              val seg = data(segStart + i)
              off = off + seg
              if seg < 255 then
                val pkt = concat(prefix, data.slice(pktStart, off))
                prefix = Bytes.empty
                pktStart = off
                if pkt.size == 0 then ()
                else if !sawAudio && isHeaderPacket(pkt) then ()
                else
                  acc = pkt :: acc
                  sawAudio = true
              i += 1
            // a trailing 255 segment means the last packet runs on; anything
            // else leaves nothing behind (pktStart has caught up with off).
            carry = concat(prefix, data.slice(pktStart, off))
            pos = payloadEnd
    ListOps.reverse(acc)

  /** the count of decoded audio frames (a convenience over `readFrames`). */
  def frameCount(data: Bytes): Int =
    var cur = readFrames(data)
    var n = 0
    var going = true
    while going do
      cur match
        case c: ::[Bytes] => n = n + 1; cur = c.tail
        case Nil        => going = false
    n
