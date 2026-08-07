package go

/** `appkit` — the generated ObjC bindings (`go-pkgs/appleptt/appkit`), bound
 *  as a facade. This is the slice of them `nativeui/interp.go` cannot avoid.
 *
 *  Everything the interpreter does with AppKit is geometry, and AppKit's
 *  geometry is C structs passed and returned BY VALUE: a view is initialised
 *  with a `CGRect`, its frame is read back as a `CGRect`, and a `CGRect` is
 *  two nested structs of two `float64` each. Every facade in this repo and in
 *  sgola's own generated `go.*` binds either an opaque handle obtained from a
 *  function or a scalar — a Go struct that Sgola CONSTRUCTS and reads fields
 *  off has no precedent. Three shapes here are the experiment:
 *
 *  1. **Constructing a Go struct.** `CGRect`/`CGPoint`/`CGSize` are values,
 *     not handles: the interpreter builds them from wataui coordinates.
 *  2. **A struct RESULT.** `Frame()` returns a `CGRect` by value.
 *  3. **A handle that is itself a value struct.** Generated ObjC classes are
 *     `type NSView struct{ objc.ID }` — passed by value throughout the
 *     bindings, where every facade class stands for a pointer.
 *
 *  The structs are spelled opaque here — obtained only from `Frame()`, never
 *  built — which is the SMALLEST version of the question: it removes
 *  construction from the picture so the value-vs-pointer mapping fails on its
 *  own. `main.scala` says what the interpreter actually wants instead.
 */
@go.bind("github.com/adriaanm/wata/go-pkgs/appleptt/appkit")
object appkit:

  /** Go `appkit.CGPoint{X, Y float64}`. */
  final class CGPoint private[go] ():
    @go.name("X") def x: scala.Double = ???
    @go.name("Y") def y: scala.Double = ???

  /** Go `appkit.CGSize{Width, Height float64}`. */
  final class CGSize private[go] ():
    @go.name("Width") def width: scala.Double = ???
    @go.name("Height") def height: scala.Double = ???

  /** Go `appkit.CGRect{Origin CGPoint; Size CGSize}` — a struct of structs. */
  final class CGRect private[go] ():
    @go.name("Origin") def origin: CGPoint = ???
    @go.name("Size") def size: CGSize = ???

  /** Go `appkit.NSView` — an ObjC instance handle. */
  final class NSView private[go] ():
    /** `-[NSView frame]` — a struct RESULT. */
    @go.name("Frame") def frame(): CGRect = ???

  /** Go `appkit.NSViewClass` — the class object. */
  final class NSViewClass private[go] ():
    @go.name("Alloc") def alloc(): NSView = ???

  @go.name("GetNSViewClass") def getNSViewClass(): NSViewClass = ???
