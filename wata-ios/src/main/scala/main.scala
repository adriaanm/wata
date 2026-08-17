import language.experimental.saferExceptions

/** wata-ios: the iOS client (plan 0044) — wata-mac's architecture with UIKit
 *  under it, simulator-first. Stage 3 holds the retained interpreter
 *  (iosstage.scala) and its test suite; the bodies — the screens, the pump,
 *  the client session — cross in stage 4, so the default path is a stub.
 *
 *  THE SHELL'S INVERSION (go-pkgs/iosshell): UIKit builds the UI inside its
 *  own launch callback and UIApplicationMain never returns, so main goes
 *  `iosshell.start()` → `iosshell.runApp(ready)`, and everything after — for
 *  now, running the interptest — happens inside `ready`, a `go.callback`
 *  trampoline the shell invokes on the main thread once the window is key
 *  and visible.
 *
 *  Argv modes (simctl launch passes arguments through to the process):
 *
 *    wata-ios interptest   — the retained interpreter's suite
 *                            (interptest.scala; tools/ios-interptest.py runs
 *                            it in the simulator and greps the verdict)
 *    wata-ios              — the stage-4 stub: prints one line and returns */
object Main:

  /** the ready trampoline, minted ONCE at module init (the registration
   *  contract) — the shell calls it on the main thread inside
   *  UIApplicationMain, which is the interptest's UIKit thread. */
  val interptestCb: go.Uintptr = go.callback(() => Main.runInterptest())

  def main(args: Array[String]): Unit =
    if args.length > 0 && args(0) == "interptest" then
      go.iosshell.start()
      go.iosshell.runApp(interptestCb) // never returns
    else
      // stage 4 brings the client; until then the launch is a visible stub
      println("wata-ios: stage-3 build — only the interptest argv mode runs")

  def runInterptest(): Unit =
    // PRUNE-DANGLING-MODULE-INIT: keep the frame-handoff seam linked. The
    // pruner drops `drainPending` when nothing reachable references `submit`
    // (stage 4's pump is its real caller), but the module init still emits
    // the `applyCb` callback literal that CALLS it — the emitted Go then
    // does not compile. This inline no-op submit is the reference that keeps
    // submit -> applyCb -> drainPending in the program until the pump lands.
    IosStage.submit(Nil)
    val failures = InterpTest.run()
    // The verdict travels by the printed line: launchd owns the process's
    // exit, so the harness greps `interptest: PASS`/`FAIL` and terminates
    // the app itself. Nothing to do here but stay in the runloop.
    ()
