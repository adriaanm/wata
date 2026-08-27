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
   *  middle rung), metrics + all 95 glyphs' FOUR quarter-pixel phase rasters
   *  (subpixel positioning, plan 0077 polish), FNV-1a 64. Re-pinned when the
   *  phases landed — the digest deliberately covers every phase, so phases
   *  collapsing onto phase 0 moves it. */
  val PINNED_DIGEST: String = "a7bea23e9ff6a869"

  def run(): Unit =
    var bad = 0
    // every (size, weight) the role table can ask for resolves, and measures
    // the same known string within a band — wide enough to survive nothing,
    // tight enough that a wrong size or a collapsed advance fails. One face
    // (Atkinson, the settled verdict) and one weight (BOLD — the small-roles
    // flip, owner 2026-08-27): plan 0077 records both rulings.
    bad += band("atkinson", 38, "bold", 220, 260)
    bad += band("atkinson", 30, "bold", 170, 210)
    bad += band("atkinson", 24, "bold", 138, 168)
    bad += band("atkinson", 16, "bold", 90, 115)
    // the 13-bold band is the BOLD-SMALLS DISCRIMINATOR: "Ada Lovelace"
    // measures 82 at 13 bold and 76 at the retired 13 Regular, so the 79
    // floor is what makes "small roles draw Bold" a claim this check can
    // fail — seen red with CAPTION forced back onto a Regular row.
    bad += band("atkinson", 13, "bold", 79, 90)
    // the role table's flip: BOTH weights of the small roles land on the
    // Bold strike (classic Atkinson has no Medium; Regular read too thin
    // on the panel).
    if FbTypeRoles.strikeFor(TypeRole.NAME, TypeWeight.MEDIUM)
        != go.strikes.strike("atkinson", 16, "bold") then
      println("striketest: NAME medium did not resolve to the 16 bold strike")
      bad += 1
    val capId = FbTypeRoles.strikeFor(TypeRole.CAPTION, TypeWeight.MEDIUM)
    if capId != go.strikes.strike("atkinson", 13, "bold") then
      println("striketest: CAPTION did not resolve to the 13 bold strike")
      bad += 1
    else
      val capW = go.strikes.measureText(capId, "Ada Lovelace")
      if capW < 79 || capW > 90 then
        println("striketest: CAPTION measures " + capW
          + " — outside the 13-bold band [79, 90] (Regular measures 76)")
        bad += 1
    // the role table routes as stated: STATUS has no strike at all, and the
    // DISPLAY fit-down ladder (38 → 30 → 24 against the box width, floor 24)
    // steps exactly where the measured widths say it must. The widths:
    // "Bob" 69 px at 38
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
    val cov = go.strikes.cover(cid, 65, 0)
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
    // SUBPIXEL PHASE COVERAGE: the strikes carry four quarter-pixel rasters
    // per glyph, and a phase must actually be a different raster — phase 1
    // (¼ px) of 'A' differs from phase 0 in ink or in box. A rasteriser
    // that quietly renders every phase at the integer dot fails here (the
    // seen-red run: the phase offset zeroed in go-pkgs/strikes).
    bad += phaseDiffers(cid, 65)
    // the pinned digest (header comment owns why).
    val dg = go.strikes.digest(cid)
    if dg != PINNED_DIGEST then
      println("striketest: digest " + dg + " != pinned " + PINNED_DIGEST
        + " — the rasteriser's output changed; re-pin only on purpose")
      bad += 1
    if bad == 0 then println("striketest: PASS")
    else println("striketest: FAIL (" + bad + " checks)")

  /** 0 when ch's phase-1 raster differs from its phase-0 one (box or ink) —
   *  the subpixel-positioning witness. */
  def phaseDiffers(id: scala.Int, ch: scala.Int): scala.Int =
    var differs = go.strikes.glyphW(id, ch, 1) != go.strikes.glyphW(id, ch, 0) ||
      go.strikes.glyphLeft(id, ch, 1) != go.strikes.glyphLeft(id, ch, 0)
    if !differs then
      val c0 = go.strikes.cover(id, ch, 0)
      val c1 = go.strikes.cover(id, ch, 1)
      if c0.length != c1.length then differs = true
      else
        var i = 0
        while i < c0.length && !differs do
          if c0(i) != c1(i) then differs = true
          i += 1
    if differs then 0
    else
      println("striketest: phase 1 of 'A' is byte-identical to phase 0 — the subpixel rasters collapsed")
      1

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
