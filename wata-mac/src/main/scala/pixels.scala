/** The pixel pipeline: RGB565 (the device panel's format, and therefore the
 *  view algebra's) into what AppKit wants. Pure functions, no facade calls,
 *  so the conversions are covered by the pure arm of `wata-mac interptest`.
 *
 *  Channel widening is BIT REPLICATION, the same inverse of Color.rgb that
 *  the PNG golden encoder, the terminal sim and the fb painter use — a color
 *  that round-trips through any backend is comparable to a golden by
 *  construction. */
object MacPixels:

  /** one RGB565 channel set, widened to sRGB components in 0..1 — the three
   *  reads NSColor colorWithSRGBRed:green:blue:alpha: takes. */
  def red(c: Int): Double =
    val r5 = (c >> 11) & 0x1f
    (r5 << 3 | r5 >> 2).toDouble / 255.0

  def green(c: Int): Double =
    val g6 = (c >> 5) & 0x3f
    (g6 << 2 | g6 >> 4).toDouble / 255.0

  def blue(c: Int): Double =
    val b5 = c & 0x1f
    (b5 << 3 | b5 >> 2).toDouble / 255.0

  /** w*h RGB565 LITTLE-ENDIAN byte pairs — VImage's exact payload (core
   *  `Bytes`) — into opaque RGBA bytes (w*h*4, row-major), as the `go.Bytes`
   *  the glue's bitmap crossing takes. */
  def expandRGB565(src: Bytes, w: Int, h: Int): go.Bytes =
    val out = go.makeSlice[Byte](w * h * 4)
    var i = 0
    while i < w * h do
      val c = src(i * 2) | (src(i * 2 + 1) << 8)
      val r5 = (c >> 11) & 0x1f
      val g6 = (c >> 5) & 0x3f
      val b5 = c & 0x1f
      out(i * 4 + 0) = ((r5 << 3 | r5 >> 2) & 0xff).toByte
      out(i * 4 + 1) = ((g6 << 2 | g6 >> 4) & 0xff).toByte
      out(i * 4 + 2) = ((b5 << 3 | b5 >> 2) & 0xff).toByte
      out(i * 4 + 3) = (0xff).toByte
      i += 1
    out

  /** magnify RGBA bytes by an integer factor with nearest-neighbour
   *  sampling: every source pixel becomes an s*s block of the identical
   *  value. The panel's pixels are the design; the image handed to
   *  NSImageView is pre-scaled here so no interpolating scaler ever
   *  touches it. */
  def scaleRGBANearest(src: go.Bytes, w: Int, h: Int, s0: Int): go.Bytes =
    val s = if s0 < 1 then 1 else s0
    val out = go.makeSlice[Byte](w * s * h * s * 4)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        val o = (y * w + x) * 4
        var dy = 0
        while dy < s do
          val row = ((y * s + dy) * w * s + x * s) * 4
          var dx = 0
          while dx < s do
            var k = 0
            while k < 4 do
              out(row + dx * 4 + k) = src(o + k)
              k += 1
            dx += 1
          dy += 1
        x += 1
      y += 1
    out
