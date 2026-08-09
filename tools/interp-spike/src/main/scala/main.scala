/** Plan 0038's interp gate: build an NSView from Sgola with a frame it
 *  constructed, and read the frame back.
 *
 *  The oracle is arithmetic, like the objc spike's: a view initialised with
 *  `{{0,0},{160,128}}` reports a frame 160 wide and 128 high. A struct
 *  marshalled wrong gives zeros or garbage — field order, nesting and the
 *  float ABI all have to be right at once, and none of them fails subtly.
 */
object Main:

  def main(args: Array[String]): Unit =
    val rect = go.appkit.CGRect(go.appkit.CGPoint(0.0, 0.0), go.appkit.CGSize(160.0, 128.0))
    val view = go.appkit.getNSViewClass().alloc().initWithFrame(rect)
    val f = view.frame()
    println("interp-spike: frame = " + f.size.width + "x" + f.size.height +
      " at " + f.origin.x + "," + f.origin.y)
    if f == rect then println("interp-spike: PASS")
    else println("interp-spike: FAIL")
