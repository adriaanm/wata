package go

/** `go.uikit` — the generated ObjC bindings (`go-pkgs/appleptt/uikit`),
 *  bound as a facade: the slice the retained interpreter (`iosstage.scala`)
 *  and its tests need, in exactly wata-mac's appkit.scala shapes (the
 *  ratified FACADE-VALUE-STRUCT spelling; tools/interp-spike is the worked
 *  example).
 *
 *  Three shapes, one rule each:
 *
 *  1. **Geometry is value structs.** `CGRect`/`CGPoint`/`CGSize` are facade
 *     case classes — a Go value struct, constructed as a named-field
 *     composite literal, crossing by value both ways.
 *  2. **ObjC handles are one-field value structs.** Generated classes are
 *     `type UIView struct{ objc.ID }`, spelled as bound-subset case classes
 *     (no fields bound — the `objc.ID` stays Go-side), constructor
 *     `private[go]` so only the bindings mint one.
 *  3. **Defined scalar types are opaque types over the ground scalar** — the
 *     interpreter's UIKit slice needs none today (no enum reaches a bound
 *     method here: subview placement is by index/sibling, and UIImageView's
 *     default scale-to-fill is exactly right for pre-scaled pixels).
 *
 *  Every facade case class carries the mandatory
 *  `override def toString: String = go.native` opt-in (a facade case class
 *  without it walls loudly).
 */
@go.bind("github.com/adriaanm/wata/go-pkgs/appleptt/uikit")
object uikit:

  /** Go `uikit.CGPoint{X, Y float64}`. */
  case class CGPoint(@go.name("X") x: scala.Double, @go.name("Y") y: scala.Double)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `uikit.CGSize{Width, Height float64}`. */
  case class CGSize(@go.name("Width") width: scala.Double, @go.name("Height") height: scala.Double)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `uikit.CGRect{Origin CGPoint; Size CGSize}` — a struct of structs. */
  case class CGRect(@go.name("Origin") origin: CGPoint, @go.name("Size") size: CGSize)
    derives CanEqual:
    override def toString: String = go.native

  /** Go `uikit.UIView` — an ObjC instance handle, `struct{ objc.ID }`.
   *  UIKit has no replaceSubview:with:, so the interpreter's replace is
   *  insertSubviewBelowSubview + removeFromSuperview (iosstage.scala). */
  case class UIView private[go] ():
    override def toString: String = go.native
    @go.name("InitWithFrame") def initWithFrame(frame: CGRect): UIView = ???
    @go.name("Frame") def frame(): CGRect = ???
    @go.name("SetFrame") def setFrame(v: CGRect): Unit = ???
    @go.name("Bounds") def bounds(): CGRect = ???
    @go.name("AddSubview") def addSubview(view: UIView): Unit = ???
    @go.name("RemoveFromSuperview") def removeFromSuperview(): Unit = ???
    @go.name("InsertSubviewBelowSubview")
    def insertSubviewBelowSubview(view: UIView, siblingSubview: UIView): Unit = ???
    @go.name("SetBackgroundColor") def setBackgroundColor(v: UIColor): Unit = ???

  /** Go `uikit.UIViewClass` — the class object. */
  case class UIViewClass private[go] ():
    override def toString: String = go.native
    @go.name("Alloc") def alloc(): UIView = ???

  @go.name("GetUIViewClass") def getUIViewClass(): UIViewClass = ???

  /** Go `uikit.UIFont`. Handed straight to a label inside the same pool it
   *  was minted in — the label retains it; the stage never stores one (iOS
   *  has no retain glue, so nothing autoreleased may outlive its pool). */
  case class UIFont private[go] ():
    override def toString: String = go.native

  case class UIFontClass private[go] ():
    override def toString: String = go.native
    @go.name("MonospacedSystemFontOfSizeWeight")
    def monospacedSystemFontOfSizeWeight(fontSize: scala.Double, weight: scala.Double): UIFont = ???
    /** the PROPORTIONAL system face, for a `VLabel`: grid text is monospaced
     *  because its columns must line up with the cells a body counted, but a
     *  name on a card is read rather than tabulated. */
    @go.name("SystemFontOfSizeWeight")
    def systemFontOfSizeWeight(fontSize: scala.Double, weight: scala.Double): UIFont = ???

  @go.name("GetUIFontClass") def getUIFontClass(): UIFontClass = ???

  /** Go `uikit.UIColor`. NO component reads on iOS (getRed:… takes CGFloat*
   *  out-params, refused) — colour assertions go through `go.iosui`'s
   *  offscreen render probe instead. */
  case class UIColor private[go] ():
    override def toString: String = go.native

  case class UIColorClass private[go] ():
    override def toString: String = go.native
    @go.name("ColorWithRedGreenBlueAlpha")
    def colorWithRedGreenBlueAlpha(red: scala.Double, green: scala.Double,
      blue: scala.Double, alpha: scala.Double): UIColor = ???

  @go.name("GetUIColorClass") def getUIColorClass(): UIColorClass = ???

  /** Go `uikit.UILabel` — the VText/VGlyph element's class, reached from the
   *  UIView the interpreter holds via `go.iosui.asLabel` (the cast is Go's);
   *  unlike AppKit's NSTextField, text/font/colour then bind DIRECTLY. */
  case class UILabel private[go] ():
    override def toString: String = go.native
    @go.name("Text") def text(): String = ???
    @go.name("SetText") def setText(v: String): Unit = ???
    @go.name("SetFont") def setFont(v: UIFont): Unit = ???
    @go.name("SetTextColor") def setTextColor(v: UIColor): Unit = ???

  /** Go `uikit.UIImageView` — the VImage element's class, reached via
   *  `go.iosui.asImageView`. Its default scale-to-fill never interpolates
   *  visibly here: the pixels arrive pre-scaled to the frame's size. */
  case class UIImageView private[go] ():
    override def toString: String = go.native
    @go.name("SetImage") def setImage(v: UIImage): Unit = ???

  /** Go `uikit.UIImage` — held only to hand to an image view (`go.iosui`
   *  glue mints one from raw RGBA; every CGImageRef shape is refused). */
  case class UIImage private[go] ():
    override def toString: String = go.native
