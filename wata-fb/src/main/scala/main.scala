import language.experimental.saferExceptions

/** wata-fb: the framebuffer device client's entry point.
 *
 *  On the device (linux/arm, cgo opus + tinyalsa) the default (no-subcommand)
 *  mode prints the opus/alsa constants and encodes ONE generated 440Hz tone
 *  frame through Opus — no speaker, no capture, device state stays clean. On
 *  the darwin host the audio stub errors loudly on `newEncoder()`; we catch it
 *  and report, so a native `sgo build`/`sgo run` stays green without the
 *  device or the C toolchain. */
object Main:
  def main(args: Array[String]): Unit =
    // the sync-engine oracle driver subcommands:
    //   wata-fb synctest              print the unit oracle (the ported sync tests)
    //   wata-fb syncfix f1 f2 …       feed captured /sync fixtures (files named
    //                                 <localpart>__<step>.json; self user id =
    //                                 line 1 of the paired .self file) — see
    //                                 tools/wataclient-tests.sh + test-fixtures/.
    // The driver is APP-side (go.sys file reads); the engine + describers it
    // calls live in the PORTABLE wataclient module.
    // the LIVE integration oracle (tools/wataclient-integ.sh):
    //   wata-fb integ <scenario> <baseUrl>  run one integration scenario against
    //                                       a live wata-server; prints INTEG
    //                                       PASS/FAIL <scenario>.
    if args.length > 0 && args(0) == "synctest" then
      printReport(SyncOracle.report())
    // the sgola-side Ogg/CRC byte oracle: the SAME OggOracle.report() the JVM
    // conformance seed runs, exercised on the Go-emitted side too (this catches
    // codegen-only drift, since the writer bug that motivated this oracle only
    // showed up on the JVM path). Byte-diffed against
    // tools/wataclient-ogg.expected.txt.
    else if args.length > 0 && args(0) == "oggtest" then
      printReport(OggOracle.report())
    // the FOREIGN-CONTAINER fixture: a pinned Ogg produced by a different
    // encoder, through the portable reader (oggforeign.scala; the decode half
    // is go-pkgs/audio's linux/arm Go test).
    else if args.length > 1 && args(0) == "oggforeign" then
      OggForeign.run(args)
    // the framebuffer golden-frame oracle + smoke.
    //   wata-fb fbdump      draw the deterministic test pattern, encode PNG, and
    //                       write the raw PNG bytes to stdout (fd 1) — the host
    //                       golden oracle (tools/fb-golden.sh).
    //   wata-fb fbsmoke     ON-DEVICE: mmap /dev/fb0, draw + present the pattern,
    //                       blink the LEDs, poll evdev echoing keys ~20s, clear.
    else if args.length > 0 && args(0) == "fbdump" then FbTest.dump()
    else if args.length > 0 && args(0) == "fbsmoke" then FbTest.smoke()
    // the PNG stored-block selfcheck (png.scala PngCheck header): exercises
    // Png.zlib past the 65535-byte stored-block cap; run by fb-smoke.
    else if args.length > 0 && args(0) == "pngtest" then PngCheck.run()
    else if args.length > 1 && args(0) == "syncfix" then
      SyncFixDriver.run(args)
    else if args.length > 2 && args(0) == "integ" then
      Integ.run(args)
    // device layer II (the shell drivers):
    //   wata-fb --selftest [echo|play|all]              the on-device audio selftest
    //   wata-fb login|voicesend|voiceplay|audiosoak …   scripted runtime+audio
    //                                                   actions against a live
    //                                                   server (devcli.scala header)
    else if args.length > 0 && args(0) == "--selftest" then
      var stage = "all"
      if args.length > 1 then stage = args(1)
      Selftest.run(stage)
    else if args.length > 0 && (args(0) == "login" || args(0) == "voicesend"
        || args(0) == "voiceplay" || args(0) == "audiosoak") then
      DevCli.run(args)
    // the full on-device fbclient: shell + wata/settings applets + the sync
    // runtime + audio thread, driving the framebuffer/input/LED layer against a
    // live wata-server (ui.scala header). Device-only (mmaps /dev/fb0); the
    // host build compiles it but reports "device unavailable".
    else if args.length > 0 && args(0) == "ui" then
      Ui.run(args)
    // the HOST front ends over the same frame loop (no framebuffer, no evdev,
    // no audio hardware — only a live wata-server):
    //   wata-fb sim <base> <user> <pass> [--once]  interactive ANSI half-block
    //                                              terminal client (sim.scala)
    //   wata-fb uitest <script> <base> <user> <pass> <outdir>
    //                                              the deterministic scripted
    //                                              driver + PNG checkpoints
    //                                              (uiscript.scala)
    //   wata-fb gio <base> <user> <pass> [--scale N] [--frames N]
    //                                              the Gio window: the same
    //                                              frame loop blitted as a
    //                                              scaled texture with touch
    //                                              buttons (gio.scala). Needs
    //                                              a `-tags gioshell` build.
    else if args.length > 0 && args(0) == "sim" then
      Sim.run(args)
    else if args.length > 0 && args(0) == "gio" then
      Gio.run(args)
    else if args.length > 0 && args(0) == "uitest" then
      UiScript.run(args)
    else
      skeleton()

  /** print a report that already ends in '\n' (there is no bare `print`
   *  mapping; drop the trailing newline and let println re-add it). */
  def printReport(s: String): Unit =
    if s.length > 0 then println(s.substring(0, s.length - 1))

  def skeleton(): Unit =
    println("wata-fb skeleton: audio constants + one encoded frame")
    println("audio: " + go.audio.SampleRate + "Hz " + go.audio.Channels + "ch S16_LE")
    println("frame: " + go.audio.FrameSamples + " samples, maxEncoded " + go.audio.MaxFrameByte + " bytes")
    println("ring: " + go.audio.PlaybackPeriods + " periods x " + go.audio.FramesPerPeriod + " frames")
    println("state " + go.audio.StateRunning + " = " + go.audio.stateName(go.audio.StateRunning))
    try
      val enc = go.audio.newEncoder()
      val frame = go.audio.tone(440, go.audio.FrameSamples)
      val out = go.makeSlice[Byte](go.audio.MaxFrameByte)
      val n = enc.encode(frame, out)
      enc.close()
      println("encoded 440Hz tone frame -> " + n + " bytes")
    catch case e: sgo.GoError => println("audio unavailable: " + e.message + " [expected on host stub]")
