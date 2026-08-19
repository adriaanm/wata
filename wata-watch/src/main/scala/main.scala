import language.experimental.saferExceptions

/** wata-watch: the standalone Apple Watch client (plan 0069).
 *
 *  The architecture is wata-ios's, for a reason established by running
 *  rather than reading: watchOS's objc runtime has a WORKING UIKit —
 *  UIView, UIWindow, UILabel, UIImageView all alloc, init, nest and render
 *  — even though the watch SDK's headers mark every one of them
 *  `API_UNAVAILABLE(watchos)`. So the watch is a small phone above the
 *  pixel, and the element table, the differ and the screen bodies are the
 *  phone's.
 *
 *  What differs is the entry point. watchOS refuses UIApplicationMain (it
 *  blocks forever and never delivers a launch callback), so
 *  `go.watchshell` owns WKApplicationMain, a synthesized
 *  WKExtensionDelegate and a window joined to watchOS's own scene. The
 *  inversion that follows is iosshell's exactly: main goes `start()` ->
 *  `runApp(ready)`, and everything the app builds happens inside `ready`.
 *
 *  THE ASSERTABLE LINES (tools/watch-smoke.py's surface — WatchKit owns the
 *  process exit, so printed lines are the verdict, the interptest rule):
 *    watch: ready <w>x<h>          — the shell reached a screen
 *    watch: painted <n> views      — a Sgola-built tree is on the panel
 *    watch: probe <hex>            — an offscreen render read that tree's
 *                                    own pixels back
 *
 *  A screenshot, not that probe, is what proves anything is VISIBLE: the
 *  probe renders offscreen and passes just as happily when the window never
 *  reached the compositor. */
object Main:

  /** the trampolines, minted ONCE at module init (the registration
   *  contract) — the shell invokes them on the main thread. */
  val readyCb: go.Uintptr = go.callback(() => Main.onReady())

  def main(args: Array[String]): Unit =
    go.watchshell.start()
    go.watchshell.runApp(readyCb) // never returns

  /** runs on the main thread once watchshell's window is on the scene. */
  def onReady(): Unit =
    val pool = go.iosui.poolPush()
    val b = go.watchshell.containerBounds()
    val w = b.size.width
    val h = b.size.height
    println("watch: ready " + fmt(w) + "x" + fmt(h))

    // A root the whole panel, and two bands pinning the row orientation the
    // way every other wata backend's first frame does: red TOP, blue BOTTOM.
    val root = rect(cgRect(0.0, 0.0, w, h), colour(0.1, 0.1, 0.1))
    val band = h / 3.0
    root.addSubview(rect(cgRect(0.0, 0.0, w, band), colour(1.0, 0.0, 0.0)))
    root.addSubview(rect(cgRect(0.0, h - band, w, band), colour(0.0, 0.0, 1.0)))

    val label = go.iosui.allocLabelAsView().initWithFrame(
      cgRect(8.0, band + 8.0, w - 16.0, 28.0))
    go.iosui.asLabel(label).setText("wata watch")
    go.iosui.asLabel(label).setTextColor(colour(1.0, 1.0, 1.0))
    root.addSubview(label)

    go.watchshell.adoptRoot(root)
    println("watch: painted " + go.iosui.subviewCount(root).toString + " views")

    // The same offscreen probe wata-ios asserts with, on a platform whose
    // headers say this view's class does not exist here.
    val top = go.iosui.renderPixel(root, (w / 2.0).toInt, (band / 2.0).toInt)
    println("watch: probe " + hex(top))
    println("watch: all checks passed")
    go.iosui.poolPop(pool)

  // ---- small helpers over the facade ---------------------------------------

  def cgRect(x: Double, y: Double, w: Double, h: Double): go.uikit.CGRect =
    go.uikit.CGRect(go.uikit.CGPoint(x, y), go.uikit.CGSize(w, h))

  def colour(r: Double, g: Double, b: Double): go.uikit.UIColor =
    go.uikit.getUIColorClass().colorWithRedGreenBlueAlpha(r, g, b, 1.0)

  def rect(f: go.uikit.CGRect, c: go.uikit.UIColor): go.uikit.UIView =
    val v = go.uikit.getUIViewClass().alloc().initWithFrame(f)
    v.setBackgroundColor(c)
    v

  /** a Double as a whole number, for the assertable lines. */
  def fmt(d: Double): String = d.toInt.toString

  def hex(v: Int): String =
    if v < 0 then "-1"
    else
      val digits = "0123456789abcdef"
      var out = ""
      var i = 5
      while i >= 0 do
        out = out + digits.charAt((v >> (i * 4)) & 0xf).toString
        i -= 1
      out
