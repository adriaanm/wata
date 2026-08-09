package go

/** `go.nativeui` — the Go glue under the Sgola interpreter
 *  (go-pkgs/nativeui/glue.go, whose header states why each function cannot
 *  be a facade binding on the generated appkit surface): the main-queue
 *  seam and the pool brackets (the dispatch machinery sgola-ffi.md keeps
 *  Go), cross-class casts, methods whose Go signatures carry defined scalar
 *  types (FACADE-GO-NAMED-SCALAR), the raw-RGBA bitmap crossing the
 *  bindings refuse, and the ObjC-runtime reads the tests assert with. */
@go.bind("github.com/adriaanm/wata/go-pkgs/nativeui")
object nativeui:

  // ---- pools + the windowed main-queue seam ---------------------------------
  /** open an autorelease pool (bracket every direct AppKit excursion). */
  @go.name("PoolPush") def poolPush(): go.Uintptr = ???
  @go.name("PoolPop") def poolPop(p: go.Uintptr): Unit = ???
  /** enqueue a `go.callback` trampoline on the MAIN queue, pool-wrapped —
   *  the windowed frame hop. */
  @go.name("OnMain") def onMain(fn: go.Uintptr): Unit = ???

  // ---- cross-class casts ----------------------------------------------------
  @go.name("AllocBoxAsView") def allocBoxAsView(): appkit.NSView = ???
  @go.name("AllocImageViewAsView") def allocImageViewAsView(): appkit.NSView = ???
  /** adopt the interpreter's NSView as its concrete class — the cast is
   *  Go's; everything driven THROUGH the facet is the facade's own binding. */
  @go.name("AsBox") def asBox(v: appkit.NSView): appkit.NSBox = ???
  @go.name("AsImageView") def asImageView(v: appkit.NSView): appkit.NSImageView = ???
  @go.name("NewLabel") def newLabel(text: String): appkit.NSView = ???
  @go.name("SetLabelFont") def setLabelFont(v: appkit.NSView, f: appkit.NSFont): Unit = ???
  @go.name("SetLabelText") def setLabelText(v: appkit.NSView, s: String): Unit = ???
  @go.name("SetLabelColor") def setLabelColor(v: appkit.NSView, c: appkit.NSColor): Unit = ???
  @go.name("LabelText") def labelText(v: appkit.NSView): String = ???
  /** retain a factory-made font the stage keeps across pools. */
  @go.name("RetainFont") def retainFont(f: appkit.NSFont): Unit = ???

  // ---- the raw-pointer crossing the bindings refuse -------------------------
  /** an NSImage over one bitmap rep holding a COPY of rgba (w*h*4, meshed
   *  RGBA, opaque). */
  @go.name("ImageFromRGBA") def imageFromRGBA(rgba: go.Bytes, w: scala.Int, h: scala.Int): appkit.NSImage = ???

  // ---- ObjC-runtime reads (the interp tests' assertion surface) -------------
  @go.name("ViewClassName") def viewClassName(v: appkit.NSView): String = ???
  /** the SAME native object? (the zero-field facade's `==` cannot say) */
  @go.name("SameView") def sameView(a: appkit.NSView, b: appkit.NSView): Boolean = ???
  @go.name("SubviewCount") def subviewCount(v: appkit.NSView): scala.Int = ???
  @go.name("SubviewAt") def subviewAt(v: appkit.NSView, i: scala.Int): appkit.NSView = ???
  @go.name("RepPixelsWide") def repPixelsWide(rep: appkit.NSBitmapImageRep): scala.Int = ???
  @go.name("RepPixelsHigh") def repPixelsHigh(rep: appkit.NSBitmapImageRep): scala.Int = ???
