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

  /** spread through the varargs bind (toolchain `4cbea19`): an
   *  `Array[String]` is the one legal `xs*` vehicle, so the List is copied
   *  into one — arbitrary arg counts, no arity ceiling. */
  def cmdFor(name: String, a: List[String]): go.exec.Cmd =
    val n = Str.len(a)
    val arr = new Array[String](n)
    var cur = a
    var i = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          arr(i) = h
          i = i + 1
          cur = t
        case Nil => going = false
    go.exec.command(name, arr*)

