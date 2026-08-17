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

/** `go.iosshell` — the UIKit shell (go-pkgs/iosshell): UIApplicationMain
 *  ownership, the synthesized delegate, the window and the root container.
 *
 *  THREADING (the shell's structural inversion vs macshell): UIKit builds
 *  the UI inside its own launch callback, and UIApplicationMain never
 *  returns — so the Sgola main goes `start()` → `runApp(ready)`, and
 *  everything after (stage creation, adoptRoot, forking the pump) happens
 *  inside `ready`, a `go.callback` trampoline the shell invokes on the main
 *  thread once the window is key and visible. */
@go.bind("github.com/adriaanm/wata/go-pkgs/iosshell")
object iosshell:
  /** load UIKit, synthesize the delegate — no UI yet. Main goroutine, first. */
  @go.name("Start") def start(): Unit = ???
  /** UIApplicationMain — never returns; `ready` runs once the window is up. */
  @go.name("RunApp") def runApp(ready: go.Uintptr): Unit = ???
  /** the root container's bounds (points) — valid once `ready` has run. */
  @go.name("ContainerBounds") def containerBounds(): uikit.CGRect = ???
  /** splice the stage's root into the window's container (main thread). */
  @go.name("AdoptRoot") def adoptRoot(v: uikit.UIView): Unit = ???

  // ---- the touch keypad (keypad.go — the minimal viable input mapping) ------
  /** lay the key buttons into the container (main thread, inside `ready`). */
  @go.name("AddKeypad") def addKeypad(): Unit = ???
  /** one pending key event as `code*4 + phase` (IosKeys codes, phase 0
   *  release / 1 press), or -1 — never blocks. */
  @go.name("NextKey") def nextKey(): scala.Int = ???
  /** inject a key edge into the same queue the buttons feed. */
  @go.name("PushKey") def pushKey(code: scala.Int, phase: scala.Int): Unit = ???
