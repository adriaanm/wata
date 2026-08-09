package go

/** `appkit` — the generated ObjC bindings (`go-pkgs/appleptt/appkit`), bound
 *  as a facade. This is the slice of them `nativeui/interp.go` cannot avoid.
 *
 *  Everything the interpreter does with AppKit is geometry, and AppKit's
 *  geometry is C structs passed and returned BY VALUE: a view is initialised
 *  with a `CGRect`, its frame is read back as a `CGRect`, and a `CGRect` is
 *  two nested structs of two `float64` each. Three shapes are exercised:
 *
 *  1. **Constructing a Go struct.** `CGRect`/`CGPoint`/`CGSize` are values,
 *     not handles: the interpreter builds them from wataui coordinates. A
 *     facade `case class` is a Go value struct (IOP-6) — companion `apply`
 *     is a named-field composite literal.
 *  2. **A struct ARGUMENT and RESULT.** `InitWithFrame(CGRect)` takes one by
 *     value; `Frame()` returns one by value.
 *  3. **A handle that is itself a value struct.** Generated ObjC classes are
 *     `type NSView struct{ objc.ID }` — passed by value throughout the
 *     bindings. Spelled as a bound-subset case class (no fields bound: the
 *     `objc.ID` stays Go-side), constructor `private[go]` so only the
 *     bindings mint one.
 *
 *  Every facade case class carries the mandatory
 *  `override def toString: String = go.native` opt-in; without it the
 *  compiler walls the type.
 */
@go.bind("github.com/adriaanm/wata/go-pkgs/appleptt/appkit")
object appkit:

  /** Go `appkit.CGPoint{X, Y float64}`. */
  case class CGPoint(@go.name("X") x: scala.Double, @go.name("Y") y: scala.Double)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `appkit.CGSize{Width, Height float64}`. */
  case class CGSize(@go.name("Width") width: scala.Double, @go.name("Height") height: scala.Double)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `appkit.CGRect{Origin CGPoint; Size CGSize}` — a struct of structs. */
  case class CGRect(@go.name("Origin") origin: CGPoint, @go.name("Size") size: CGSize)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `appkit.NSView` — an ObjC instance handle, `struct{ objc.ID }`. */
  case class NSView private[go] ():
    override def toString: String = go.native
    /** `-[NSView initWithFrame:]` — a struct ARGUMENT. */
    @go.name("InitWithFrame") def initWithFrame(frameRect: CGRect): NSView = ???
    /** `-[NSView frame]` — a struct RESULT. */
    @go.name("Frame") def frame(): CGRect = ???

  /** Go `appkit.NSViewClass` — the class object. */
  case class NSViewClass private[go] ():
    override def toString: String = go.native
    @go.name("Alloc") def alloc(): NSView = ???

  @go.name("GetNSViewClass") def getNSViewClass(): NSViewClass = ???
