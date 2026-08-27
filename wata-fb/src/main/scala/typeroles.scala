/** THE ROLE → STRIKE TABLE — the fb renderer's analogue of the watch's
 *  `TypeRoles.labelPoints`, and the ONE place a `TypeRole` becomes a pixel
 *  size (plan 0077 stage 1). Shared with wata-mac by symlink like
 *  `paint.scala`; the mac's retained backend draws `VLabel` natively and
 *  never reaches the strike path, but the table compiles there unchanged.
 *
 *  The FACE is a runtime configuration, not a constant: `FbConfig.typeFace()`
 *  answers "atkinson" or "inter" (owner ruling 2026-08-27 — default Atkinson,
 *  and the on-panel A/B is a config edit plus a restart, never a rebuild).
 *  Both faces live in the go-pkgs/strikes table and rasterise lazily, so the
 *  one not configured costs nothing.
 *
 *  `STATUS` — and any (role, face) combination the strike table cannot serve
 *  — resolves to NO strike (-1), and the painter keeps the 5x8 grid font for
 *  it, stated there so the gap is visible rather than silent. */
object FbTypeRoles:

  /** the strike for (role, weight) under the configured face, or -1 for the
   *  5x8 fallback. Called per label per frame; the Go side is a scan of 8. */
  def strikeFor(role: scala.Int, weight: scala.Int): scala.Int =
    if role == TypeRole.STATUS then -1
    else
      val face = FbConfig.typeFace()
      if role == TypeRole.DISPLAY then go.strikes.strike(face, 30, "bold")
      else if role == TypeRole.NAME then
        val w = if weight == TypeWeight.BOLD then "bold" else "medium"
        go.strikes.strike(face, 16, w)
      else if role == TypeRole.CAPTION then go.strikes.strike(face, 13, "medium")
      else -1
