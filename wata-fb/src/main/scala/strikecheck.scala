/** the strike selfcheck (plan 0077 stage 1): `wata-fb striketest`, run by
 *  fb-smoke beside `pngtest`/`agecheck`. The goldens do not exercise the
 *  strike path yet (no fb body emits `VLabel` into a rendered frame until
 *  stage 3), so this is what holds the rasteriser green: every strike both
 *  faces carry resolves and measures a known string inside an expected band,
 *  a glyph's coverage is non-trivial (real antialiased ink, not a blank or
 *  1-bit box), and ONE strike's full digest is pinned to 16 hex digits.
 *
 *  The pinned digest is also the portability assertion: pure-Go
 *  rasterisation is byte-deterministic across darwin/linux (integer
 *  fixed-point, no platform text stack), so the same digest must come out of
 *  every host — which is what will let stage-4 goldens drawn with these
 *  strikes be regenerated anywhere. A digest change means the rasteriser's
 *  output moved (an x/image bump, a ttf swap): loud here, decided by a
 *  human, never discovered as a mysterious golden diff. */
object StrikeCheck:

  /** the byte-determinism witness: atkinson bold 30 (the DISPLAY ladder's
   *  middle rung), metrics + all 95 coverage bitmaps, FNV-1a 64. */
  val PINNED_DIGEST: String = "f956822f72281f15"

  def run(): Unit =
    var bad = 0
    // every (face, size, weight) the role table can ask for resolves, and
    // measures the same known string within a band — wide enough to survive
    // nothing, tight enough that a wrong size or a collapsed advance fails.
    bad += band("atkinson", 38, "bold", 220, 260)
    bad += band("atkinson", 30, "bold", 170, 210)
    bad += band("atkinson", 24, "bold", 138, 168)
    bad += band("atkinson", 16, "bold", 90, 115)
    bad += band("atkinson", 16, "medium", 82, 106)
    bad += band("atkinson", 13, "medium", 66, 86)
    bad += band("inter", 38, "bold", 230, 272)
    bad += band("inter", 30, "bold", 178, 220)
    bad += band("inter", 24, "bold", 144, 176)
    bad += band("inter", 16, "bold", 95, 120)
    bad += band("inter", 16, "medium", 92, 117)
    bad += band("inter", 13, "medium", 74, 94)
    // the role table routes as stated: STATUS has no strike at all, and the
    // DISPLAY fit-down ladder (38 → 30 → 24 against the box width, floor 24)
    // steps exactly where the measured widths say it must. The widths under
    // the default face (atkinson — no config loaded here): "Bob" 69 px at 38
    // (fits 156); "Gabriella" 164 at 38, 129 at 30 (steps once);
    // "Ada Lovelace" 189 at 30, 152 at 24 (steps twice); at 100 px avail
    // even 24 overflows and the floor holds — the painter clips, the ladder
    // never goes lower.
    if FbTypeRoles.strikeFor(TypeRole.STATUS, TypeWeight.REGULAR) != -1 then
      println("striketest: STATUS resolved to a strike (must stay the 5x8 fallback)")
      bad += 1
    bad += rung("Bob", 156, 38)
    bad += rung("Gabriella", 156, 30)
    bad += rung("Ada Lovelace", 156, 24)
    bad += rung("Ada Lovelace", 100, 24)
    // coverage is real antialiased ink: 'A' at DISPLAY size has fully-opaque
    // pixels AND a substantial lit area.
    val cid = go.strikes.strike("atkinson", 30, "bold")
    val cov = go.strikes.cover(cid, 65)
    var maxC = 0
    var lit = 0
    var i = 0
    while i < cov.length do
      val c = cov(i).toInt & 0xff
      if c > maxC then maxC = c
      if c > 0 then lit += 1
      i += 1
    if maxC <= 200 then
      println("striketest: 'A' coverage peaks at " + maxC + " (want > 200)")
      bad += 1
    if lit <= 50 then
      println("striketest: 'A' has only " + lit + " lit pixels (want > 50)")
      bad += 1
    // the pinned digest (header comment owns why).
    val dg = go.strikes.digest(cid)
    if dg != PINNED_DIGEST then
      println("striketest: digest " + dg + " != pinned " + PINNED_DIGEST
        + " — the rasteriser's output changed; re-pin only on purpose")
      bad += 1
    if bad == 0 then println("striketest: PASS")
    else println("striketest: FAIL (" + bad + " checks)")

  /** the DISPLAY ladder picks the expected rung for `text` in `availW`. */
  def rung(text: String, availW: scala.Int, wantPx: scala.Int): scala.Int =
    val got = FbTypeRoles.displayStrikeFor(text, availW)
    if got != go.strikes.strike("atkinson", wantPx, "bold") then
      println("striketest: ladder(" + text + ", " + availW + ") != atkinson "
        + wantPx + " bold")
      1
    else 0

  /** one strike: it exists, and "Ada Lovelace" measures within [lo, hi]. */
  def band(face: String, px: scala.Int, weight: String,
      lo: scala.Int, hi: scala.Int): scala.Int =
    val id = go.strikes.strike(face, px, weight)
    var bad = 0
    if id < 0 then
      println("striketest: no strike for " + face + " " + px + " " + weight)
      bad = 1
    else
      val w = go.strikes.measureText(id, "Ada Lovelace")
      if w < lo || w > hi then
        println("striketest: " + face + " " + px + " " + weight
          + ": width " + w + " outside [" + lo + ", " + hi + "]")
        bad = 1
    bad
