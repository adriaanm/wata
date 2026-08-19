package go

/** `go.iosui` — the Go glue under the Sgola interpreter
 *  (go-pkgs/iosui, whose headers state why each function cannot be a facade
 *  binding on the generated uikit surface): the main-queue seam and the pool
 *  brackets (the dispatch machinery sgola-ffi.md keeps Go), cross-class
 *  casts, the raw-RGBA bitmap crossing the bindings refuse, the offscreen
 *  render probe (iOS UIColor cannot answer for its own components), and the
 *  ObjC-runtime reads the tests assert with. */
@go.bind("github.com/adriaanm/wata/go-pkgs/iosui")
object iosui:

  // ---- pools + the windowed main-queue seam ---------------------------------
  /** open an autorelease pool (bracket every direct UIKit excursion). */
  @go.name("PoolPush") def poolPush(): go.Uintptr = ???
  @go.name("PoolPop") def poolPop(p: go.Uintptr): Unit = ???
  /** enqueue a `go.callback` trampoline on the MAIN queue, pool-wrapped —
   *  the windowed frame hop. */
  @go.name("OnMain") def onMain(fn: go.Uintptr): Unit = ???

  // ---- cross-class casts ----------------------------------------------------
  @go.name("AllocLabelAsView") def allocLabelAsView(): uikit.UIView = ???
  @go.name("AllocImageViewAsView") def allocImageViewAsView(): uikit.UIView = ???
  /** adopt the interpreter's UIView as its concrete class — the cast is
   *  Go's; everything driven THROUGH the facet is the facade's own binding. */
  @go.name("AsLabel") def asLabel(v: uikit.UIView): uikit.UILabel = ???
  @go.name("AsImageView") def asImageView(v: uikit.UIView): uikit.UIImageView = ???

  // ---- the raw-pointer crossing the bindings refuse -------------------------
  /** a UIImage over a CGImage holding a COPY of rgba (w*h*4, meshed RGBA,
   *  opaque, row 0 = top). */
  @go.name("ImageFromRGBA") def imageFromRGBA(rgba: go.Bytes, w: scala.Int, h: scala.Int): uikit.UIImage = ???

  // ---- the offscreen render probe -------------------------------------------
  /** render the view's layer tree offscreen (one pixel per point, row 0 =
   *  the view's TOP row — the hello's band probes pinned the orientation)
   *  and read pixel (x, y) back packed 0xRRGGBB; -1 out of bounds. Needs no
   *  window and no screen. */
  @go.name("RenderPixel") def renderPixel(v: uikit.UIView, x: scala.Int, y: scala.Int): scala.Int = ???

  // ---- ObjC-runtime reads (the interp tests' assertion surface) -------------
  @go.name("ViewClassName") def viewClassName(v: uikit.UIView): String = ???
  /** the SAME native object? (the zero-field facade's `==` cannot say) */
  @go.name("SameView") def sameView(a: uikit.UIView, b: uikit.UIView): Boolean = ???
  @go.name("SubviewCount") def subviewCount(v: uikit.UIView): scala.Int = ???
  @go.name("SubviewAt") def subviewAt(v: uikit.UIView, i: scala.Int): uikit.UIView = ???

/** `go.watchshell` — the WatchKit shell (go-pkgs/watchshell): ownership of
 *  WKApplicationMain, the synthesized WKExtensionDelegate and root interface
 *  controller, and the scene-joined window the app's view tree hangs from.
 *
 *  THREADING is iosshell's, for the same structural reason: the platform
 *  builds the app inside its own callback and WKApplicationMain never
 *  returns — so the Sgola main goes `start()` -> `runApp(ready)`, and
 *  everything after (stage creation, adoptRoot, forking the pump) happens
 *  inside `ready`, a `go.callback` trampoline the shell invokes on the main
 *  thread once a screen exists.
 *
 *  What differs from the phone is ONE fact, and it is invisible from here:
 *  watchOS refuses UIApplicationMain outright, so the lifecycle is
 *  WatchKit's while every pixel above it is ordinary UIKit. */
@go.bind("github.com/adriaanm/wata/go-pkgs/watchshell")
object watchshell:
  /** load the frameworks, synthesize the delegate and root controller — no
   *  UI yet. Main goroutine, first. */
  @go.name("Start") def start(): Unit = ???
  /** WKApplicationMain — never returns; `ready` runs once a screen exists. */
  @go.name("RunApp") def runApp(ready: go.Uintptr): Unit = ???
  /** the root container's bounds (points) — valid once `ready` has run.
   *  Spans the whole panel, not the safe area. */
  @go.name("ContainerBounds") def containerBounds(): uikit.CGRect = ???
  /** splice the stage's root into the window's container (main thread). */
  @go.name("AdoptRoot") def adoptRoot(v: uikit.UIView): Unit = ???

  /** attach the watch's input to a view — crown, tap, swipe, long press.
   *  The queue below then speaks ioskeys.scala's codes, so the shared
   *  applets never learn that the watch has no keypad. */
  @go.name("AddGestures") def addGestures(v: uikit.UIView): Unit = ???

  /** pop `code*4 + phase`, or -1 — never blocks (iosshell's contract). */
  @go.name("NextKey") def nextKey(): scala.Int = ???

  /** tee stdout+stderr into a file, so an icon-tap launch's lines survive
   *  to be pulled off the watch. Answers "" or the error. */
  @go.name("TeeLog") def teeLog(path: String): String = ???

  /** always "" today — the watch declares no URL scheme (see input.go). */
  @go.name("TakeURL") def takeURL(): String = ???

  /** logs and ignores: watchOS has no browser to open (see input.go). */
  @go.name("OpenURL") def openURL(url: String): Unit = ???
