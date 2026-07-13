import language.experimental.saferExceptions

/** M8 chunk 3 — the app-side fixture-oracle driver (`wata-fb syncfix`). Reads
 *  captured /sync fixture FILES (go.sys.readFile — the app layer may use `go.*`
 *  freely; only wataclient is portable) and feeds them to the PORTABLE
 *  `SyncDescribe.fixtureReport`. Arg format: `<selfUserId>=<path>` per fixture,
 *  in order; the path doubles as the fixture name in the report (ci passes
 *  spike-relative paths, so the output is deterministic). */
object SyncFixDriver:
  def run(args: Array[String]): Unit =
    var fixtures: List[(String, String, String)] = Nil[(String, String, String)]()
    var i = 1
    while i < args.length do
      val arg = args(i)
      val eq = arg.indexOf("=")
      if eq > 0 then
        val uid = arg.substring(0, eq)
        val path = arg.substring(eq + 1)
        var body = ""
        try
          val raw = go.sys.readFile(path)
          body = go.string(raw)
        catch case e: sgo.GoError => body = ""
        if body == "" then println("MISSING FIXTURE " + path)
        else fixtures = (uid, path, body) :: fixtures
      else println("BAD ARG " + arg + " (want <selfUserId>=<path>)")
      i += 1
    Main.printReport(SyncDescribe.fixtureReport(ListOps.reverse(fixtures)))
