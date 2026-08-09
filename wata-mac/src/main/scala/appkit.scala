package go

/** `go.appkit` — the generated ObjC bindings (`go-pkgs/appleptt/appkit`),
 *  bound as a facade: the slice the retained interpreter (`interp.scala`)
 *  and its tests need, in the ratified FACADE-VALUE-STRUCT spelling
 *  (tools/interp-spike is the worked example; the upstream fixture is
 *  sgola's facadevalue-demo scenario).
 *
 *  Three shapes, one rule each:
 *
 *  1. **Geometry is value structs.** `CGRect`/`CGPoint`/`CGSize` are facade
 *     case classes — a Go value struct, constructed as a named-field
 *     composite literal, crossing by value both ways.
 *  2. **ObjC handles are one-field value structs.** Generated classes are
 *     `type NSView struct{ objc.ID }`, spelled as bound-subset case classes
 *     (no fields bound — the `objc.ID` stays Go-side), constructor
 *     `private[go]` so only the bindings mint one.
 *  3. **Only plain-typed, value-receiver methods are bound.** A method whose
 *     Go signature carries a defined scalar type (`NSBoxType`,
 *     `NSWindowOrderingMode`, …) or that needs a cross-class cast
 *     (`NSView{ID: box.ID}`) goes through `go.nativeui`'s Go glue instead —
 *     see FACADE-GO-NAMED-SCALAR there.
 *
 *  Every facade case class carries the mandatory
 *  `override def toString: String = go.native` opt-in (a facade case class
 *  without it walls loudly).
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
    @go.name("InitWithFrame") def initWithFrame(frameRect: CGRect): NSView = ???
    @go.name("Frame") def frame(): CGRect = ???
    @go.name("SetFrame") def setFrame(v: CGRect): Unit = ???
    @go.name("Bounds") def bounds(): CGRect = ???
    @go.name("AddSubview") def addSubview(view: NSView): Unit = ???
    @go.name("RemoveFromSuperview") def removeFromSuperview(): Unit = ???
    @go.name("ReplaceSubviewWith") def replaceSubviewWith(oldView: NSView, newView: NSView): Unit = ???
    @go.name("BitmapImageRepForCachingDisplayInRect")
    def bitmapImageRepForCachingDisplayInRect(rect: CGRect): NSBitmapImageRep = ???
    @go.name("CacheDisplayInRectToBitmapImageRep")
    def cacheDisplayInRectToBitmapImageRep(rect: CGRect, rep: NSBitmapImageRep): Unit = ???

  /** Go `appkit.NSViewClass` — the class object. */
  case class NSViewClass private[go] ():
    override def toString: String = go.native
    @go.name("Alloc") def alloc(): NSView = ???

  @go.name("GetNSViewClass") def getNSViewClass(): NSViewClass = ???

  /** Go `appkit.NSFont`. */
  case class NSFont private[go] ():
    override def toString: String = go.native

  case class NSFontClass private[go] ():
    override def toString: String = go.native
    @go.name("MonospacedSystemFontOfSizeWeight")
    def monospacedSystemFontOfSizeWeight(fontSize: scala.Double, weight: scala.Double): NSFont = ???

  @go.name("GetNSFontClass") def getNSFontClass(): NSFontClass = ???

  /** Go `appkit.NSColor`. The component reads are the render tests' probe. */
  case class NSColor private[go] ():
    override def toString: String = go.native
    @go.name("RedComponent") def redComponent(): scala.Double = ???
    @go.name("GreenComponent") def greenComponent(): scala.Double = ???
    @go.name("BlueComponent") def blueComponent(): scala.Double = ???

  case class NSColorClass private[go] ():
    override def toString: String = go.native
    @go.name("ColorWithSRGBRedGreenBlueAlpha")
    def colorWithSRGBRedGreenBlueAlpha(red: scala.Double, green: scala.Double,
      blue: scala.Double, alpha: scala.Double): NSColor = ???

  @go.name("GetNSColorClass") def getNSColorClass(): NSColorClass = ???

  /** Go `appkit.NSImage` — held only to hand to an image view (`go.nativeui`
   *  glue mints one from raw RGBA). */
  case class NSImage private[go] ():
    override def toString: String = go.native

  /** Go `appkit.NSBitmapImageRep` — the offscreen render probes' surface. */
  case class NSBitmapImageRep private[go] ():
    override def toString: String = go.native
    @go.name("ColorAtXY") def colorAtXY(x: scala.Int, y: scala.Int): NSColor = ???
