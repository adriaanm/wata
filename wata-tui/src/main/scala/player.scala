import language.experimental.saferExceptions

/** The external audio player: the tui has no audio hardware layer, so
 *  playback is "write the Ogg out and run a player on it".
 *
 *  `$WATA_TUI_PLAYER` is a whole command line (`ffplay -nodisp -autoexit`);
 *  the file is appended as the last argument. Unset, the first of `mpv` /
 *  `ffplay` on `$PATH` wins. */
object Player:

  def spec(): List[String] =
    val env = go.sys.getenv("WATA_TUI_PLAYER")
    if env != "" then Str.splitWs(env)
    else if have("mpv") then Str.splitWs("mpv --really-quiet")
    else if have("ffplay") then Str.splitWs("ffplay -nodisp -autoexit -loglevel quiet")
    else Nil

  def have(name: String): Boolean =
    var ok = false
    try
      val p = go.exec.lookPath(name)
      ok = p != ""
    catch case e: sgo.GoError => ok = false
    ok

  /** run the player on `file`; "" = it exited zero. */
  def run(file: String): String =
    val sp = spec()
    val name = Str.nth(sp, 0)
    if name == "" then "no player on $PATH (set WATA_TUI_PLAYER)"
    else runCmd(name, Str.appended(Str.restOf(sp), file))

  def runCmd(name: String, a: List[String]): String =
    val cmd = cmdFor(name, a)
    var err = ""
    try cmd.run()
    catch case e: sgo.GoError => err = e.message
    err

  /** `exec.Command` is variadic and this dialect has no slice spread, so the
   *  arity is chosen here (facades.scala binds one overload each). */
  def cmdFor(name: String, a: List[String]): go.exec.Cmd =
    val n = Str.len(a)
    if n == 0 then go.exec.command0(name)
    else if n == 1 then go.exec.command1(name, Str.nth(a, 0))
    else if n == 2 then go.exec.command2(name, Str.nth(a, 0), Str.nth(a, 1))
    else if n == 3 then go.exec.command3(name, Str.nth(a, 0), Str.nth(a, 1), Str.nth(a, 2))
    else go.exec.command4(name, Str.nth(a, 0), Str.nth(a, 1), Str.nth(a, 2), Str.nth(a, 3))

