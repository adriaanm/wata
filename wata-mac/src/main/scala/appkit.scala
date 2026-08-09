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
 *  3. **Defined scalar types are opaque types over the ground scalar.**
 *     `@go.name("NSBoxType") opaque type NSBoxType = Int` — the emitter
 *     mints the boundary conversions, and the Go consts bind as
 *     parameterless defs. Only cross-class casts (`NSView{ID: box.ID}`)
 *     still need `go.nativeui`'s Go glue (the cast facets).
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
    @go.name("AddSubviewPositionedRelativeTo")
    def addSubviewPositionedRelativeTo(view: NSView, place: NSWindowOrderingMode, otherView: NSView): Unit = ???
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

  // ---- defined scalar types (the enum-newtype shape): an opaque type over
  // the ground scalar, `@go.name` pinning the Go defined type; the consts
  // bind as parameterless defs. The Go grounds here are uint/int — both land
  // on the Int ground, and the emitter's minted conversions are ordinary Go
  // numeric conversions.

  @go.name("NSBoxType") opaque type NSBoxType = scala.Int
  @go.name("NSTitlePosition") opaque type NSTitlePosition = scala.Int
  @go.name("NSImageScaling") opaque type NSImageScaling = scala.Int
  @go.name("NSWindowOrderingMode") opaque type NSWindowOrderingMode = scala.Int

  @go.name("NSBoxCustom") def nsBoxCustom: NSBoxType = ???
  @go.name("NSNoTitle") def nsNoTitle: NSTitlePosition = ???
  @go.name("NSImageScaleAxesIndependently") def nsImageScaleAxesIndependently: NSImageScaling = ???
  @go.name("NSWindowBelow") def nsWindowBelow: NSWindowOrderingMode = ???

  /** Go `appkit.NSBox` — the VRect element's class, reached from the NSView
   *  the interpreter holds via `go.nativeui.asBox` (the cast is Go's). */
  case class NSBox private[go] ():
    override def toString: String = go.native
    @go.name("SetBoxType") def setBoxType(v: NSBoxType): Unit = ???
    @go.name("SetTitlePosition") def setTitlePosition(v: NSTitlePosition): Unit = ???
    @go.name("SetBorderWidth") def setBorderWidth(v: scala.Double): Unit = ???
    @go.name("SetFillColor") def setFillColor(v: NSColor): Unit = ???

  /** Go `appkit.NSImageView` — the VImage element's class, reached via
   *  `go.nativeui.asImageView`. */
  case class NSImageView private[go] ():
    override def toString: String = go.native
    @go.name("SetImageScaling") def setImageScaling(v: NSImageScaling): Unit = ???
    @go.name("SetImage") def setImage(v: NSImage): Unit = ???

  /** Go `appkit.NSImage` — held only to hand to an image view (`go.nativeui`
   *  glue mints one from raw RGBA). */
  case class NSImage private[go] ():
    override def toString: String = go.native

  /** Go `appkit.NSBitmapImageRep` — the offscreen render probes' surface. */
  case class NSBitmapImageRep private[go] ():
    override def toString: String = go.native
    @go.name("ColorAtXY") def colorAtXY(x: scala.Int, y: scala.Int): NSColor = ???
