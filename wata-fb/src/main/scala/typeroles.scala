/** THE ROLE → STRIKE TABLE — the fb renderer's analogue of the watch's
 *  `TypeRoles.labelPoints`, and the ONE place a `TypeRole` becomes a pixel
 *  size (plan 0077 stage 1). Shared with wata-mac by symlink like
 *  `paint.scala`; the mac's retained backend draws `VLabel` natively and
 *  never reaches the strike path, but the table compiles there unchanged.
 *
 *  The FACE is SETTLED: Atkinson Hyperlegible, by the owner's on-panel A/B
 *  (2026-08-27 — legibility at this panel's sizes; Inter and its config
 *  switch are retired, plan 0077 records the verdict). `FACE` below is the
 *  one spelling the strike table carries.
 *
 *  `STATUS` — and any (role, face) combination the strike table cannot serve
 *  — resolves to NO strike (-1), and the painter keeps the 5x8 grid font for
 *  it, stated there so the gap is visible rather than silent.
 *
 *  `DISPLAY` is NOT a single size: the full-bleed name resolves per TEXT
 *  through `displayStrikeFor`'s fit-down ladder, so it never appears in
 *  `strikeFor` — the painter routes the DISPLAY role there itself. */
object FbTypeRoles:

  /** the settled face (owner verdict 2026-08-27). */
  val FACE: String = "atkinson"

  /** the strike for (role, weight), or -1 for the 5x8 fallback. Called per
   *  label per frame; the Go side is a small table scan.
   *  DISPLAY never arrives here (see `displayStrikeFor`).
   *
   *  EVERY small role resolves to BOLD (owner ruling 2026-08-27): classic
   *  Atkinson ships no Medium cut, so the "medium" rows silently rasterised
   *  Regular — which read too thin at 13/16 px on this panel. The flip lives
   *  HERE, at strike resolution: the bodies keep their medium/bold weight
   *  vocabulary (a native backend like wata-mac's may still honour it), and
   *  on this renderer both weights land on the Bold strike. */
  def strikeFor(role: scala.Int, weight: scala.Int): scala.Int =
    if role == TypeRole.STATUS then -1
    else if role == TypeRole.NAME then go.strikes.strike(FACE, 16, "bold")
    else if role == TypeRole.CAPTION then go.strikes.strike(FACE, 13, "bold")
    else -1

  /** the full-bleed DISPLAY ladder (plan 0077 tuning, owner 2026-08-27): the
   *  resting name is as big as its card can fit — try 38 px bold, step down
   *  to 30, floor at 24 (below the floor the painter clips at the box edge,
   *  as it does for any overlong run). Resolved per NAME by measurement, and
   *  called with the SAME box width by the rolodex body (to size the name
   *  box from the rung's line box) and the painter (to pick the strike), so
   *  the two cannot disagree. Deterministic and cheap: at most three
   *  measures, each a scan of the text's advances. */
  def displayStrikeFor(text: String, availW: scala.Int): scala.Int =
    val s1 = go.strikes.strike(FACE, 38, "bold")
    if go.strikes.measureText(s1, text) <= availW then s1
    else
      val s2 = go.strikes.strike(FACE, 30, "bold")
      if go.strikes.measureText(s2, text) <= availW then s2
      else go.strikes.strike(FACE, 24, "bold")
