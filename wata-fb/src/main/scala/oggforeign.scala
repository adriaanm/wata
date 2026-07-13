import language.experimental.saferExceptions

/** M8 chunk 7 follow-up — the FOREIGN-CONTAINER fixture driver (ci step 14,
 *  wataclient-tests check 7/7):
 *
 *    wata-fb oggforeign <fixture.ogg>
 *
 *  Reads the pinned TUI-shaped Ogg/Opus fixture
 *  (go-pkgs/audio/testdata/tui-foreign.ogg) and prints the portable
 *  `OggOracle.foreignReport` — byte-diffed against
 *  tools/wataclient-foreign.expected.txt. This guards the container-parsing
 *  half of the chunk-6 VOICEPLAY-FAIL regression class host-side; the
 *  opus-decode half (the OPUS_BUFFER_TOO_SMALL fix itself) is the linux/arm
 *  Go test in go-pkgs/audio over the SAME fixture bytes (cross-COMPILED by ci
 *  step 13; run on-device — the device is never a ci dependency). */
object OggForeign:
  def run(args: Array[String]): Unit =
    try
      val raw = go.sys.readFile(args(1))
      Main.printReport(OggOracle.foreignReport(GoBytes.toPortable(raw)))
    catch case e: sgo.GoError =>
      println("oggforeign: cannot read " + args(1) + ": " + e.message)
