/** THE STAMP BACK-OFF — plan 0078's time column, as a pure rule.
 *
 *  A thread's time column earns its width only where it says something new,
 *  so precision degrades with age, and consecutive identical labels collapse
 *  to the first (a burst inside a minute carries ONE stamp; last week is a
 *  single weekday). Rows whose label collapsed leave the column EMPTY — the
 *  column is fixed, nothing reflows.
 *
 *  THE SPELLINGS (this file is their one authority; they are sized for a
 *  13 px CAPTION column):
 *
 *      age < 1 min      "now"
 *      age < 5 min      the minute:        "1m" .. "4m"
 *      age < 1 h        the 5-minute:      "5m", "10m", .. "55m"
 *      age < 6 h        the quarter-hour:  "1h", "1h15", "1h30", .. "5h45"
 *      age < 24 h       the hour:          "6h" .. "23h"
 *      age < 7 d        the weekday:       "Mon" .. "Sun"   (of the message)
 *      beyond           the date:          "3 Jun"          (of the message)
 *
 *  "Today" is approximated as UNDER 24 HOURS: the rule is then a function of
 *  the age alone up to the weekday bucket, and the handset has no timezone —
 *  a civil-midnight rule would need one. The weekday and the date are read
 *  from the message's own timestamp in UTC (epoch civil math below, no
 *  platform calendar). A FUTURE timestamp clamps to "now" — the handset
 *  boots at 1970 until the clock steps, and the scripted harness runs a
 *  virtual clock against real server timestamps; both belong in the newest
 *  bucket (the same clamp the old grid's age column had).
 *
 *  Shared vocabulary, not fb code: `label` is one row's stamp from `(now,
 *  ts)`, `collapse` blanks the repeats over a rendered list. No clock is
 *  ever read here — "now" is an argument, like everything else in wataui.
 */
object Stamps:

  val MIN_MS = 60000L
  val HOUR_MS = 3600000L
  val DAY_MS = 86400000L

  /** one row's label (uncollapsed). */
  def label(nowMs: Long, ts: Long): String =
    var age = nowMs - ts
    if age < 0L then age = 0L
    if age < MIN_MS then "now"
    else if age < 5L * MIN_MS then "" + (age / MIN_MS).toInt + "m"
    else if age < HOUR_MS then "" + ((age / (5L * MIN_MS)).toInt * 5) + "m"
    else if age < 6L * HOUR_MS then quarter(age)
    else if age < DAY_MS then "" + (age / HOUR_MS).toInt + "h"
    else if age < 7L * DAY_MS then weekday(ts)
    else date(ts)

  /** "1h", "1h15", "1h30", "1h45", ... — the quarter-hour bucket. */
  def quarter(age: Long): String =
    val q = (age / (15L * MIN_MS)).toInt
    val h = q / 4
    val r = (q % 4) * 15
    if r == 0 then "" + h + "h" else "" + h + "h" + r

  /** the exact clock time, hh:mm UTC — the PLAYING row's stamp (the one row
   *  whose "45m" is not the question being asked). Not part of the back-off:
   *  the body swaps it in for the row that is playing. */
  def exact(ts: Long): String =
    val mins = ts / MIN_MS
    val m = (mins % 60L).toInt
    val h = ((mins / 60L) % 24L).toInt
    val pad = if m < 10 then "0" else ""
    "" + h + ":" + pad + m

  /** consecutive identical labels collapse to the FIRST — the list is in
   *  render order (the thread's is newest first, top down), so a burst's one
   *  stamp sits on its newest row. Blanks stay blank and never match. */
  def collapse(labels: List[String]): List[String] =
    var acc: List[String] = Nil
    var prev = ""
    var cur = labels
    var going = true
    while going do
      cur match
        case h :: t =>
          if h != "" && h == prev then acc = "" :: acc
          else
            acc = h :: acc
            prev = h
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  // ---- epoch civil math (UTC), no platform calendar --------------------------

  /** days since the epoch, flooring a negative ms toward minus infinity so a
   *  pre-1970 timestamp still lands on a civil day. */
  def epochDay(ts: Long): scala.Int =
    var d = ts / DAY_MS
    if ts < 0L && ts % DAY_MS != 0L then d = d - 1L
    d.toInt

  /** the weekday of `ts`, UTC — epoch day 0 (1970-01-01) was a Thursday. */
  def weekday(ts: Long): String =
    var w = (epochDay(ts) + 4) % 7
    if w < 0 then w = w + 7
    // 0 = Sunday
    if w == 0 then "Sun"
    else if w == 1 then "Mon"
    else if w == 2 then "Tue"
    else if w == 3 then "Wed"
    else if w == 4 then "Thu"
    else if w == 5 then "Fri"
    else "Sat"

  /** the date of `ts`, UTC, as "3 Jun". */
  def date(ts: Long): String =
    val c = civil(epochDay(ts))
    "" + c.d + " " + monthName(c.m)

  def monthName(m: scala.Int): String =
    if m == 1 then "Jan"
    else if m == 2 then "Feb"
    else if m == 3 then "Mar"
    else if m == 4 then "Apr"
    else if m == 5 then "May"
    else if m == 6 then "Jun"
    else if m == 7 then "Jul"
    else if m == 8 then "Aug"
    else if m == 9 then "Sep"
    else if m == 10 then "Oct"
    else if m == 11 then "Nov"
    else "Dec"

  /** days-since-epoch -> civil (y, m, d), the standard era/day-of-era walk
   *  (Hinnant's `civil_from_days`), integer math only. */
  def civil(days: scala.Int): CivilDate =
    val z = days + 719468
    val era = (if z >= 0 then z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val y = yoe + era * 400 + (if m <= 2 then 1 else 0)
    CivilDate(y, m, d)

/** a civil date, UTC. */
case class CivilDate(y: scala.Int, m: scala.Int, d: scala.Int)
