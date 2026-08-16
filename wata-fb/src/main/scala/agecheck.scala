/** the age-column selfcheck (plan 0049): `ageStr` is pure, and the golden
 *  frames only ever exercise its "now" arm (the scripted harness clamps
 *  future timestamps there by design) — so the minute/hour/day arms and the
 *  boundaries are pinned here instead. Run by fb-smoke beside `exitfit`;
 *  prints each case and a final `agecheck: PASS`, or FAIL lines. */
object AgeCheck:

  def run(): Unit =
    var bad = 0
    bad += check("future", -5000L, "now")
    bad += check("zero", 0L, "now")
    bad += check("59s", 59999L, "now")
    bad += check("60s", 60000L, "1m")
    bad += check("59m", 3599999L, "59m")
    bad += check("60m", 3600000L, "1h")
    bad += check("23h", 86399999L, "23h")
    bad += check("24h", 86400000L, "1d")
    bad += check("98d", 8467200000L, "98d")
    bad += check("400d", 34560000000L, "99d")
    if bad == 0 then println("agecheck: PASS")
    else println("agecheck: FAIL (" + bad + " cases)")

  /** one case: a message `diff` ms old relative to a fixed now. Returns 1 on
   *  mismatch so the caller can count. */
  def check(label: String, diff: Long, want: String): scala.Int =
    val now = 1000000000000L
    val got = WataLogic.ageStr(now, now - diff)
    var bad = 0
    if got != want then
      println("agecheck: " + label + ": got \"" + got + "\" want \"" + want + "\"")
      bad = 1
    bad
