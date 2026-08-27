/** the thread rules' oracle: `report()` returns a deterministic multi-line
 *  String that is byte-diffed against a pinned expectation
 *  (`tools/wataui-rules.expected.txt`, run by `tools/wataui-tests.sh` as
 *  `wata-fb ruletest` — wata-fb is only the driver, like the differ's).
 *
 *  Two pure rules are pinned, the DiffOracle discipline applied to each:
 *
 *   - the STAMP BACK-OFF (`Stamps`): every age boundary, both sides — the
 *     minute, the 5-minute, the quarter-hour, the hour, the weekday, the
 *     date — plus the future-timestamp clamp, the exact-time spelling, the
 *     SCRUB CHIP's spelling (`Stamps.scrub`: exact time under 24h, day
 *     count under two weeks, week count beyond — every boundary both
 *     sides), and
 *     the collapse (a burst inside a minute carries one stamp; blanks never
 *     match; an interleaved run does not collapse across the interloper).
 *   - the DELIVERY DOTS (`Delivery`): all five states, both slots.
 *
 *  The fixed `NOW` is 2026-08-27 12:00:00 UTC (a Thursday), so the weekday
 *  and date lines are checkable by hand against a real calendar.
 *
 *  Builders are function-local in this dialect (DATA-10), so every helper
 *  RETURNS its line and `report` concatenates. */
object ThreadRulesOracle:

  /** 2026-08-27T12:00:00Z. */
  val NOW = 1787832000000L

  def report(): String =
    val b = new StringBuilder
    b.append("== stamps: the back-off, per age ==\n")
    b.append(stampLine("future +5s", 5000L))
    b.append(stampLine("0s", 0L))
    b.append(stampLine("59s", -59000L))
    b.append(stampLine("60s", -60000L))
    b.append(stampLine("4m59s", -299000L))
    b.append(stampLine("5m", -300000L))
    b.append(stampLine("9m59s", -599000L))
    b.append(stampLine("10m", -600000L))
    b.append(stampLine("55m", -3300000L))
    b.append(stampLine("59m59s", -3599000L))
    b.append(stampLine("1h", -3600000L))
    b.append(stampLine("1h14m", -4440000L))
    b.append(stampLine("1h15m", -4500000L))
    b.append(stampLine("1h30m", -5400000L))
    b.append(stampLine("1h45m", -6300000L))
    b.append(stampLine("2h", -7200000L))
    b.append(stampLine("5h59m", -21540000L))
    b.append(stampLine("6h", -21600000L))
    b.append(stampLine("23h59m", -86340000L))
    b.append(stampLine("24h", -86400000L))
    b.append(stampLine("2d", -172800000L))
    b.append(stampLine("6d23h", -601200000L))
    b.append(stampLine("7d", -604800000L))
    b.append(stampLine("30d", -2592000000L))
    b.append(stampLine("400d", -34560000000L))
    b.append("== stamps: the exact time (the playing row) ==\n")
    b.append("exact 12:00:00Z -> \"" + Stamps.exact(NOW) + "\"\n")
    b.append("exact 09:05:00Z -> \"" + Stamps.exact(NOW - 10500000L) + "\"\n")
    b.append("== stamps: the collapse ==\n")
    b.append(collapseLine("burst within a minute",
      "now" :: ("now" :: ("now" :: ("5m" :: Nil)))))
    b.append(collapseLine("identical 5-minute run",
      "25m" :: ("25m" :: ("30m" :: ("30m" :: ("30m" :: Nil))))))
    b.append(collapseLine("interloper breaks the run",
      "2h" :: ("Mon" :: ("2h" :: Nil))))
    b.append(collapseLine("blanks never match",
      "" :: ("" :: ("3h" :: ("3h" :: Nil)))))
    b.append(collapseLine("single weekday for last week",
      "Fri" :: ("Fri" :: ("Fri" :: Nil))))
    b.append("== stamps: the scrub chip (exact time + coarse age) ==\n")
    b.append(scrubLine("future +5s", 5000L))
    b.append(scrubLine("0s", 0L))
    b.append(scrubLine("2h", -7200000L))
    b.append(scrubLine("23h59m", -86340000L))
    b.append(scrubLine("24h", -86400000L))
    b.append(scrubLine("3d", -259200000L))
    b.append(scrubLine("13d23h", -1206000000L))
    b.append(scrubLine("14d", -1209600000L))
    b.append(scrubLine("20d", -1728000000L))
    b.append(scrubLine("400d", -34560000000L))
    b.append("== delivery: the slots ==\n")
    b.append(deliveryLine(Delivery.NONE))
    b.append(deliveryLine(Delivery.QUEUED))
    b.append(deliveryLine(Delivery.SERVER))
    b.append(deliveryLine(Delivery.PLAYED))
    b.append(deliveryLine(Delivery.REFUSED))
    b.toString

  def stampLine(name: String, offset: Long): String =
    "age " + name + " -> \"" + Stamps.label(NOW, NOW + offset) + "\"\n"

  def scrubLine(name: String, offset: Long): String =
    "scrub " + name + " -> \"" + Stamps.scrub(NOW, NOW + offset) + "\"\n"

  def collapseLine(name: String, labels: List[String]): String =
    name + ": [" + joinQuoted(Stamps.collapse(labels)) + "]\n"

  def joinQuoted(xs: List[String]): String =
    var out = ""
    var first = true
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          if !first then out = out + ","
          out = out + "\"" + h + "\""
          first = false
          cur = t
        case Nil => going = false
    out

  def deliveryLine(state: scala.Int): String =
    Delivery.showState(state) + " -> " +
      Delivery.showSlot(Delivery.slotOne(state)) + "," +
      Delivery.showSlot(Delivery.slotTwo(state)) + "\n"
