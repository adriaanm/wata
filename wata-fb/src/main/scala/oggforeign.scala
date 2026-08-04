import language.experimental.saferExceptions

/** The FOREIGN-CONTAINER fixture driver:
 *
 *    wata-fb oggforeign <fixture.ogg>
 *
 *  Reads a pinned Ogg/Opus fixture produced by a different (foreign) encoder
 *  (go-pkgs/audio/testdata/tui-foreign.ogg) and prints the portable
 *  `OggOracle.foreignReport` — byte-diffed against
 *  tools/wataclient-foreign.expected.txt. This guards the container-parsing
 *  half of a VOICEPLAY-FAIL regression class host-side; the opus-decode half
 *  (the OPUS_BUFFER_TOO_SMALL fix itself) is the linux/arm Go test in
 *  go-pkgs/audio over the SAME fixture bytes, run on-device. */
object OggForeign:
  def run(args: Array[String]): Unit =
    try
      val raw = go.sys.readFile(args(1))
      Main.printReport(OggOracle.foreignReport(GoBytes.toPortable(raw)))
    catch case e: sgo.GoError =>
      println("oggforeign: cannot read " + args(1) + ": " + e.message)
