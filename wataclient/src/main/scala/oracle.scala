/** the portable byte oracle. `report()` returns a deterministic multi-line
 *  String exercising CRC-32, the Ogg writer/reader round-trip, and the
 *  computed-Byte op surface. It is PORTABLE (Bytes/List/StringBuilder only,
 *  zero `go`), so the SAME code runs on the sgola backend AND on plain
 *  scalac/JVM — the two reports must be byte-identical. Multi-byte values
 *  render as their unsigned LE byte tuples: string concatenation here has no
 *  `Long` operand, and the byte view is the sharper test. */
object OggOracle:

  /** a `Bytes` from a String's UTF-8/ASCII bytes (magic markers, CRC inputs). */
  def bytesOf(s: String): Bytes =
    val b = new BytesBuilder
    b.addAscii(s)
    b.result()

  /** a `Bytes` of `n` copies of the byte `v` (a distinct fake-Opus frame). */
  def fill(n: Int, v: Int): Bytes =
    val b = new BytesBuilder
    var i = 0
    while i < n do
      b.addByte(v)
      i += 1
    b.result()

  /** the 4 little-endian bytes of a u32 as "a.b.c.d" (renders a CRC without a
   *  Long->String). Returns a String — a builder never crosses a def
   *  boundary; builders are function-local accumulators, build-and-return. */
  def u32Str(x: Long): String =
    val bb = new BytesBuilder
    bb.addU32LE(x.toInt)
    val bs = bb.result()
    val b = new StringBuilder
    b.append(bs(0)); b.append('.'); b.append(bs(1)); b.append('.')
    b.append(bs(2)); b.append('.'); b.append(bs(3))
    b.toString

  def boolStr(x: Boolean): String = if x then "true" else "false"

  /** `mk(i).toByte` on a param forces the general narrowing path (truncation). */
  def mk(i: Int): Byte = i.toByte

  def report(): String =
    val b = new StringBuilder

    // ---- CRC-32 golden vectors (independent poly-0x04C11DB7/init-0 reference) --
    b.append("crc empty ")
    b.append(u32Str(Crc.crc32(Bytes.empty)))
    b.append('\n')
    b.append("crc 123456789 ")
    b.append(u32Str(Crc.crc32(bytesOf("123456789")))) // 0x89A1897F = 137.24.161.137
    b.append('\n')
    b.append("crc hello ")
    b.append(u32Str(Crc.crc32(bytesOf("Hello, Ogg!")))) // 0xD3968642
    b.append('\n')
    b.append("crc abc-ne-def ")
    b.append(boolStr(Crc.crc32(bytesOf("abc")) != Crc.crc32(bytesOf("def"))))
    b.append('\n')
    // incremental == single-pass
    val d = bytesOf("Hello, Ogg!")
    val single = Crc.crc32(d)
    val incr = Crc.crc32Update(Crc.crc32(d.slice(0, 6)), d.slice(6, d.size))
    b.append("crc incremental ")
    b.append(boolStr(single == incr))
    b.append('\n')

    // ---- Ogg structure: OpusHead / OpusTags payloads --------------------------
    val head = Ogg.opusHead()
    b.append("opushead size ")
    b.append(head.size) // 19
    b.append('\n')
    b.append("opushead magic ")
    b.append(boolStr(head.slice(0, 8) == bytesOf("OpusHead")))
    b.append('\n')
    b.append("opushead ver-ch ")
    b.append(head(8)); b.append('.'); b.append(head(9)) // 1.1
    b.append('\n')
    b.append("opushead preskip ")
    b.append(head(10)); b.append('.'); b.append(head(11)) // 312 LE = 56.1
    b.append('\n')
    val tags = Ogg.opusTags()
    b.append("opustags size ")
    b.append(tags.size) // 20
    b.append('\n')
    b.append("opustags vendor ")
    b.append(boolStr(tags.slice(12, 16) == bytesOf("wata")))
    b.append('\n')

    // ---- write a stream, verify page CRC + BOS/EOS ----------------------------
    var frames: List[Bytes] = Nil
    frames = fill(50, 5) :: frames
    // exactly 2*255: the length that needs a TRAILING ZERO segment. Without
    // one the lacing reads as an unfinished packet and a table-walking reader
    // drops the frame entirely, which is invisible to any other length.
    frames = fill(510, 4) :: frames
    frames = fill(600, 3) :: frames // >255 -> multi-segment (3 lacing segments)
    frames = fill(50, 2) :: frames
    frames = fill(50, 1) :: frames
    val ordered = ListOps.reverse(frames)
    val stream = Ogg.writeStream(ordered)
    b.append("stream magic ")
    b.append(boolStr(Ogg.isOggS(stream, 0)))
    b.append('\n')
    b.append("stream bos ")
    b.append(boolStr((stream(5) & Ogg.FLAG_BOS) != 0)) // first page is BOS
    b.append('\n')
    // recompute the first page's CRC with the field zeroed, compare to stored.
    b.append("stream crc-valid ")
    b.append(boolStr(firstPageCrcValid(stream)))
    b.append('\n')

    // ---- read back: frame count + content round-trip --------------------------
    val got = Ogg.readFrames(stream)
    b.append("roundtrip frames ")
    b.append(Ogg.frameCount(stream)) // 5
    b.append('\n')
    b.append("roundtrip exact ")
    b.append(boolStr(sameFrames(ordered, got)))
    b.append('\n')
    b.append("roundtrip bigframe ")
    b.append(nthFrameSize(got, 2)) // the 600-byte frame survives multi-segment
    b.append('\n')
    b.append("reader empty ")
    b.append(Ogg.frameCount(Bytes.empty)) // 0
    b.append('\n')

    // ---- a page is not a packet ----------------------------------------------
    // Our writer emits one packet per page and never spans, so nothing it
    // produces distinguishes a page-reading reader from a packet-reading one.
    // These streams do: the same five frames, paged the way a foreign encoder
    // pages them. Built here rather than pinned as a fixture so the shapes are
    // visible, and so a spanning packet — which real encoders emit only for
    // very large packets — can be exercised at all.
    val packed = packedStream(ordered)
    b.append("packed frames ")
    b.append(Ogg.frameCount(packed)) // 5, all on ONE audio page
    b.append('\n')
    b.append("packed exact ")
    b.append(boolStr(sameFrames(ordered, Ogg.readFrames(packed))))
    b.append('\n')
    val spanned = spannedStream(ordered)
    b.append("spanning frames ")
    b.append(Ogg.frameCount(spanned)) // 5, the 600-byte one split across pages
    b.append('\n')
    b.append("spanning exact ")
    b.append(boolStr(sameFrames(ordered, Ogg.readFrames(spanned))))
    b.append('\n')
    // a stream cut mid-packet: the last page's lacing ends in 255 and no
    // continuation page follows, so the half packet is dropped rather than
    // handed to the decoder as if it were whole.
    b.append("truncated dropped ")
    b.append(Ogg.frameCount(truncatedStream(ordered))) // 2 — the whole ones only
    b.append('\n')

    // ---- computed-Byte op surface ----------------------------------------------
    b.append("byte narrow ")
    b.append(mk(300).toInt) // 44
    b.append('\n')
    b.append("byte widen ")
    b.append(mk(200).toInt) // -56
    b.append('\n')
    b.append("byte cmp ")
    b.append(boolStr(mk(200) < mk(10))) // -56 < 10 -> true (signed int8)
    b.append('\n')

    // ---- raw-byte String bridge (the transport body currency): Bytes ->
    //      String -> Bytes must be byte-exact over the full 0..255 range (Go:
    //      verbatim string bytes; JVM reference: ISO-8859-1).
    val rb = rawProbe()
    val rs = rb.rawString
    b.append("rawstr len ")
    b.append(rs.length) // 6
    b.append('\n')
    b.append("rawstr roundtrip ")
    b.append(boolStr(Bytes.fromRawString(rs) == rb))
    b.append('\n')

    // ---- freeze / toBytes / IArray. Portable (Bytes/BytesBuilder/Array/IArray
    //      only), so the SAME code runs on plain scalac/JVM AND on the sgola
    //      backend — byte-identical. The freeze-then-append-more edge is the
    //      soundness pin (the frozen view never changes).
    b.append(freezeReport())

    b.toString

  /** freeze / toBytes / IArray conformance lines. Kept portable so both the
   *  JVM reference and the sgola emitter must agree byte-for-byte. */
  def freezeReport(): String =
    val b = new StringBuilder
    // freeze: zero-copy snapshot; freeze-then-append-more (both windows) must not
    // change the frozen view.
    val bb = new BytesBuilder
    bb.addByte(1); bb.addByte(2); bb.addByte(3)
    val frozen = bb.freeze()
    b.append("freeze size ")
    b.append(frozen.size) // 3
    b.append('\n')
    bb.addByte(4) // WINDOW A: in-place-capable append
    var k = 0
    while k < 1000 do // WINDOW B: forces a growth reallocation
      bb.addByte(k & 0xff)
      k += 1
    b.append("freeze stable ")
    b.append(boolStr(frozen.size == 3 && frozen(0) == 1 && frozen(1) == 2 && frozen(2) == 3))
    b.append('\n')
    b.append("freeze builder ")
    b.append(bb.size) // 1004
    b.append('\n')
    // a fresh freeze after growth sees the new length.
    val frozen2 = bb.freeze()
    b.append("freeze2 ")
    b.append(boolStr(frozen2.size == 1004 && frozen2(0) == 1 && frozen2(3) == 4 && frozen2(4) == 0))
    b.append('\n')
    // toBytes: an explicit copy; mutating the source array does not affect it.
    val arr = new Array[Byte](3)
    arr(0) = 7.toByte; arr(1) = 8.toByte; arr(2) = 9.toByte
    val copied = arr.toBytes
    arr(0) = 99.toByte
    b.append("toBytes copy ")
    b.append(boolStr(copied.size == 3 && copied(0) == 7 && copied(1) == 8 && copied(2) == 9))
    b.append('\n')
    // IArray: immutable slice; apply/length/tabulate.
    val ia: IArray[Int] = IArray(10, 20, 30)
    val sq = IArray.tabulate(3)(i => i * i)
    b.append("iarray sum ")
    b.append(ia(0) + ia(1) + ia(2)) // 60
    b.append('\n')
    b.append("iarray tab ")
    b.append(sq(0) + sq(1) + sq(2)) // 0+1+4 = 5
    b.append('\n')
    b.toString

  /** 0x00,0x01,0x7F,0x80,0xFF,'A' — both edges of the signed/unsigned divide. */
  def rawProbe(): Bytes =
    val bb = new BytesBuilder
    bb.addByte(0)
    bb.addByte(1)
    bb.addByte(127)
    bb.addByte(128)
    bb.addByte(255)
    bb.addByte(65)
    bb.result()

  /** recompute the first page's CRC over (header with CRC=0) ++ segtable ++
   *  payload and compare to the stored value at bytes 22..26. */
  def firstPageCrcValid(stream: Bytes): Boolean =
    val nseg = stream(26)
    val segStart = 27
    val payStart = segStart + nseg
    val paySize = Ogg.payloadSize(stream, segStart, nseg)
    val pageEnd = payStart + paySize
    // build the pre-image: header[0:22] ++ 0000 ++ (header[26] ++ segtable ++ payload)
    val pre = new BytesBuilder
    pre.addBytes(stream.slice(0, 22))
    pre.addU32LE(0)
    pre.addBytes(stream.slice(26, pageEnd))
    val recomputed = Crc.crc32(pre.result())
    // the recomputed CRC's LE bytes must equal the stored field (stream[22:26]).
    val rb = new BytesBuilder
    rb.addU32LE(recomputed.toInt)
    rb.result() == stream.slice(22, 26)

  // list helpers on List[Bytes] (single-match defs — the json flattening idiom).
  def isEmpty(xs: List[Bytes]): Boolean = xs match
    case _: ::[Bytes] => false
    case Nil        => true
  def headOf(xs: List[Bytes]): Bytes = xs match
    case c: ::[Bytes] => c.head
    case Nil        => Bytes.empty
  def tailOf(xs: List[Bytes]): List[Bytes] = xs match
    case c: ::[Bytes] => c.tail
    case Nil        => xs

  /** structural frame-content equality of two `List[Bytes]`. */
  def sameFrames(a: List[Bytes], b: List[Bytes]): Boolean =
    var ca = a
    var cb = b
    var ok = true
    var going = true
    while going do
      val ea = isEmpty(ca)
      val eb = isEmpty(cb)
      if ea && eb then going = false
      else if ea || eb then
        ok = false
        going = false
      else
        if headOf(ca) != headOf(cb) then ok = false
        ca = tailOf(ca)
        cb = tailOf(cb)
    ok

  /** the size of the nth (0-based) frame, or -1. */
  def nthFrameSize(xs: List[Bytes], n: Int): Int =
    var cur = xs
    var i = 0
    var result = -1
    var going = true
    while going do
      if isEmpty(cur) then going = false
      else
        if i == n then result = headOf(cur).size
        i += 1
        cur = tailOf(cur)
    result

  // ---- the FOREIGN-CONTAINER fixture report -----------------------------------
  // Guards the container-parsing side of a past playback-failure regression
  // class in ci (host-side; the opus-decode half is the linux/arm Go test
  // go-pkgs/audio/foreign_decode_test.go over the SAME pinned fixture).
  // The fixture is TUI-shaped Ogg/Opus (60ms@16kHz packets = TOC config 11,
  // random serial, EOS page CARRIES audio — everything our own writer does
  // differently). Pinned bytes: go-pkgs/audio/testdata/tui-foreign.ogg;
  // regenerating it is a designer-reviewed manual re-pin step, never run at
  // ci time. Expected output pinned at tools/wataclient-foreign.expected.txt.

  /** little-endian u64 at `pos` (the Ogg granule field). */
  def u64LE(d: Bytes, pos: Int): Long =
    var v = 0L
    var k = 0
    while k < 8 do
      v = v | (d(pos + k).toLong << (8 * k))
      k += 1
    v

  /** little-endian u32 at `pos`, carried as Long (serial/seq fields). */
  def u32LEat(d: Bytes, pos: Int): Long =
    var v = 0L
    var k = 0
    while k < 4 do
      v = v | (d(pos + k).toLong << (8 * k))
      k += 1
    v

  // ---- foreign paging, built rather than pinned --------------------------------
  // Our writer puts one packet on a page and never spans, so its output cannot
  // tell a page-reading reader from a packet-reading one. These builders make
  // the shapes a foreign encoder produces, out of the same frames, so the
  // reader's contract is checked against something that disagrees with it when
  // it is wrong.

  /** the nth (0-based) frame, or empty. */
  def nthFrame(xs: List[Bytes], n: Int): Bytes =
    var cur = xs
    var i = 0
    var r = Bytes.empty
    var going = true
    while going do
      if isEmpty(cur) then going = false
      else if i == n then
        r = headOf(cur)
        going = false
      else
        cur = tailOf(cur)
        i += 1
    r

  /** the first `n` frames, in order. */
  def takeFrames(xs: List[Bytes], n: Int): List[Bytes] =
    var cur = xs
    var i = 0
    var acc: List[Bytes] = Nil
    var going = true
    while going do
      if i >= n then going = false
      else if isEmpty(cur) then going = false
      else
        acc = headOf(cur) :: acc
        cur = tailOf(cur)
        i += 1
    ListOps.reverse(acc)

  /** everything after the first `n` frames. */
  def dropFrames(xs: List[Bytes], n: Int): List[Bytes] =
    var cur = xs
    var i = 0
    var going = true
    while going do
      if i >= n then going = false
      else if isEmpty(cur) then going = false
      else
        cur = tailOf(cur)
        i += 1
    cur

  /** the lacing segments a page needs to carry `packets` in order: each packet
   *  contributes full 255s then a shorter segment that ends it — so a packet
   *  whose length is a multiple of 255 contributes a trailing 0. */
  def lacingFor(packets: List[Bytes]): Bytes =
    val b = new BytesBuilder
    var cur = packets
    var going = true
    while going do
      cur match
        case h :: t =>
          var rem = h.size
          var more = true
          while more do
            if rem >= 255 then
              b.addByte(255)
              rem = rem - 255
            else
              b.addByte(rem)
              more = false
          cur = t
        case Nil => going = false
    b.result()

  /** the concatenated payload of `packets`. */
  def bodyFor(packets: List[Bytes]): Bytes =
    val b = new BytesBuilder
    var cur = packets
    var going = true
    while going do
      cur match
        case h :: t =>
          b.addBytes(h)
          cur = t
        case Nil => going = false
    b.result()

  /** one page with an EXPLICIT lacing table — the shape `Ogg.page` cannot
   *  express, since it derives its lacing from a single payload. */
  def pageOf(lacing: Bytes, payload: Bytes, granule: Long, headerType: Int, seq: Int): Bytes =
    val pre = new BytesBuilder
    pre.addAscii("OggS")
    pre.addByte(0)
    pre.addByte(headerType)
    pre.addU64LE(granule)
    pre.addU32LE(Ogg.SERIAL)
    pre.addU32LE(seq)
    pre.addU32LE(0)          // CRC placeholder
    pre.addByte(lacing.size)
    pre.addBytes(lacing)
    pre.addBytes(payload)
    val img = pre.result()
    val crc = Crc.crc32(img)
    val out = new BytesBuilder
    out.addBytes(img.slice(0, 22))
    out.addU32LE(crc.toInt)
    out.addBytes(img.slice(26, img.size))
    out.result()

  /** the OpusHead/OpusTags pages every stream starts with. */
  def headerPages(): Bytes =
    val b = new BytesBuilder
    b.addBytes(Ogg.page(Ogg.opusHead(), 0L, Ogg.FLAG_BOS, 0))
    b.addBytes(Ogg.page(Ogg.opusTags(), 0L, 0, 1))
    b.result()

  /** every frame on ONE audio page — the ffmpeg-default shape, and the one
   *  that read back as a single oversized "frame" before the reader walked
   *  the lacing table. */
  def packedStream(frames: List[Bytes]): Bytes =
    val b = new BytesBuilder
    b.addBytes(headerPages())
    b.addBytes(pageOf(lacingFor(frames), bodyFor(frames), 4800L, 0, 2))
    b.addBytes(Ogg.page(Bytes.empty, 4800L, Ogg.FLAG_EOS, 3))
    b.result()

  /** page A of the spanning stream: frames 0 and 1 whole, then the first 510
   *  bytes of the 600-byte frame as two full 255 segments — which is how a
   *  page says "this packet is not finished". */
  def spanPageA(frames: List[Bytes]): Bytes =
    val big = nthFrame(frames, 2)
    val lace = new BytesBuilder
    lace.addBytes(lacingFor(takeFrames(frames, 2)))
    lace.addByte(255)
    lace.addByte(255)
    val body = new BytesBuilder
    body.addBytes(bodyFor(takeFrames(frames, 2)))
    body.addBytes(big.slice(0, 510))
    pageOf(lace.result(), body.result(), 2880L, 0, 2)

  /** the same frames with one packet SPLIT across a page boundary: page B is
   *  flagged continued and its first segment finishes the big packet. */
  def spannedStream(frames: List[Bytes]): Bytes =
    val big = nthFrame(frames, 2)
    val rest = big.slice(510, big.size)
    val after = dropFrames(frames, 3)
    val lace = new BytesBuilder
    lace.addByte(rest.size)
    lace.addBytes(lacingFor(after))
    val body = new BytesBuilder
    body.addBytes(rest)
    body.addBytes(bodyFor(after))
    val b = new BytesBuilder
    b.addBytes(headerPages())
    b.addBytes(spanPageA(frames))
    b.addBytes(pageOf(lace.result(), body.result(), 4800L, Ogg.FLAG_CONT, 3))
    b.addBytes(Ogg.page(Bytes.empty, 4800L, Ogg.FLAG_EOS, 4))
    b.result()

  /** the spanning stream cut after page A: the big packet is left unfinished
   *  and nothing claims to continue it, so only the two whole packets before
   *  it are frames. */
  def truncatedStream(frames: List[Bytes]): Bytes =
    val b = new BytesBuilder
    b.addBytes(headerPages())
    b.addBytes(spanPageA(frames))
    b.addBytes(Ogg.page(Bytes.empty, 2880L, Ogg.FLAG_EOS, 3))
    b.result()

  /** decoded samples at 48kHz for one frame of an Opus packet with TOC config
   *  `cfg` (RFC 6716 §3.1): SILK 10/20/40/60ms, hybrid 10/20ms, CELT
   *  2.5/5/10/20ms. Opus always decodes at the decoder rate (48k here). */
  def silkSamples(r: Int): Int =
    if r == 0 then 480 else if r == 1 then 960 else if r == 2 then 1920 else 2880
  def hybridSamples(r: Int): Int = if r == 0 then 480 else 960
  def celtSamples(r: Int): Int =
    if r == 0 then 120 else if r == 1 then 240 else if r == 2 then 480 else 960
  def cfgSamples48(cfg: Int): Int =
    if cfg < 12 then silkSamples(cfg % 4)
    else if cfg < 16 then hybridSamples(cfg % 2)
    else celtSamples(cfg % 4)

  /** page-level walk of a foreign Ogg/Opus container + the reader's own
   *  extraction, rendered for byte-diffing against the pinned expected. */
  def foreignReport(data: Bytes): String =
    val b = new StringBuilder
    b.append("== foreign Ogg/Opus container (TUI-shaped fixture) ==\n")
    b.append("bytes ")
    b.append(data.size)
    b.append('\n')
    var pos = 0
    var page = 0
    var audio = 0
    var samples48 = 0L
    var lastGranule = 0L
    var lastFlags = 0
    var lastPsize = 0
    var serial = 0L
    var pendSize = 0   // bytes of a packet still being laced (spans pages)
    var pendToc = 0    // its first byte, read where the packet started
    var going = true
    while going do
      if pos + 27 > data.size then going = false
      else if !Ogg.isOggS(data, pos) then going = false
      else
        val flags = data(pos + 5)
        val granule = u64LE(data, pos + 6)
        val ser = u32LEat(data, pos + 14)
        val nseg = data(pos + 26)
        val segStart = pos + 27
        val psize = Ogg.payloadSize(data, segStart, nseg)
        val payloadStart = segStart + nseg
        if payloadStart + psize > data.size then going = false
        else
          if page == 0 then serial = ser
          val isBos = (flags & Ogg.FLAG_BOS) != 0
          // Walk the lacing table, not the page: a page carries as many
          // packets as it has segments shorter than 255, and a packet whose
          // last segment is a full 255 runs on to the next page. Header pages
          // are skipped structurally (BOS flag, then the tags page), which
          // keeps this independent of the reader's magic-based rule.
          if isBos || page == 1 then
            pendSize = 0
          else
            if (flags & Ogg.FLAG_CONT) == 0 then pendSize = 0
            var off = payloadStart
            var i = 0
            while i < nseg do
              val seg = data(segStart + i)
              if pendSize == 0 && seg > 0 then pendToc = data(off)
              pendSize = pendSize + seg
              off = off + seg
              if seg < 255 then
                if pendSize > 0 then
                  audio += 1
                  val cfg = pendToc >> 3
                  b.append("packet ")
                  b.append(audio)
                  b.append(": ")
                  b.append(pendSize)
                  b.append("B toc-cfg ")
                  b.append(cfg)
                  b.append(" -> ")
                  b.append(cfgSamples48(cfg))
                  b.append(" samples@48k c=")
                  b.append(pendToc & 3)
                  b.append('\n')
                  samples48 = samples48 + cfgSamples48(cfg).toLong
                pendSize = 0
              i += 1
          lastGranule = granule
          lastFlags = flags
          lastPsize = psize
          page += 1
          pos = payloadStart + psize
    b.append("pages ")
    b.append(page)
    b.append(" audio-packets ")
    b.append(audio)
    b.append('\n')
    b.append("serial-is-foreign ") // the TUI muxer randomizes; ours is "wata"
    b.append(boolStr(serial != Ogg.SERIAL.toLong)) // ours is 0x77617461 "wata"
    b.append('\n')
    b.append("eos-carries-audio ")
    b.append(boolStr((lastFlags & Ogg.FLAG_EOS) != 0 && lastPsize > 0))
    b.append('\n')
    b.append("final-granule ")
    b.append("" + lastGranule) // STRINGBUILDER-APPEND-LONG: append(Long) emits strconv.Itoa,
    // which takes an int; string concat rides FormatInt and compiles. Drop the
    // "" + once that lands.
    b.append('\n')
    b.append("toc-total-samples48 ")
    b.append("" + samples48)
    b.append('\n')
    b.append("granule-matches-toc ")
    b.append(boolStr(lastGranule == samples48))
    b.append('\n')
    b.append("readFrames-count ")
    b.append(Ogg.frameCount(data))
    b.append('\n')
    b.append("reader-sees-all-packets ")
    b.append(boolStr(Ogg.frameCount(data) == audio))
    b.append('\n')
    b.toString
