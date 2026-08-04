import language.experimental.saferExceptions

/** wata-tui: the terminal client and admin interface (plan 0016).
 *
 *    WATA_TUI_USER=alice WATA_TUI_PASS=testpass123 wata-tui
 *    wata-tui <homeserver> <user> <password>
 *
 *  Login is from the environment — `WATA_TUI_HS` (default
 *  `http://127.0.0.1:8008`), `WATA_TUI_USER`, `WATA_TUI_PASS` — with positional
 *  arguments overriding, so a script and a shell reach it the same way. There
 *  is no stored session file: an admin tool logs in each run.
 *
 *  The process is one `Runtime` in one supervised scope: the sync loop and the
 *  action loop are forked goroutines, the REPL is the main one, and the scope
 *  is the join point. The client is HEADLESS (`Runtime.make`) — playback goes
 *  to an external player process, not an audio thread.
 *
 *  Startup prints exactly one line: `ready <userId>` once the sync loop
 *  reaches Syncing, or `login failed`. */
object Main:
  def main(args: Array[String]): Unit =
    val hs = pick(args, 0, "WATA_TUI_HS", "http://127.0.0.1:8008")
    val user = pick(args, 1, "WATA_TUI_USER", "")
    val pass = pick(args, 2, "WATA_TUI_PASS", "")
    if user == "" || pass == "" then
      println("wata-tui: set WATA_TUI_USER and WATA_TUI_PASS, or pass <homeserver> <user> <password>")
    else run(hs, user, pass)

  /** positional argument `i` wins, else the env var, else the default. */
  def pick(args: Array[String], i: scala.Int, env: String, dflt: String): String =
    var out = go.sys.getenv(env)
    if out == "" then out = dflt
    if args.length > i && args(i) != "" then out = args(i)
    out

  def run(hs: String, user: String, pass: String): Unit =
    val cfg = ClientConfig(hs, user, pass, 1000, Session("", "", "", "", ""))
    val c = Runtime.make(cfg, TuiCaps.httpDo(), TuiCaps.clock())
    sgo.supervised {
      Runtime.start(c)
      if Runtime.waitForConnection(c, Syncing(), 30000L) then
        println("ready " + Runtime.lastAuth.userId)
        Repl.loop(c)
      else println("login failed")
      Runtime.stopClient(c)
    }
