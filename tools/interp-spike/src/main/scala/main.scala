/** Plan 0038's interp gate: build an NSView from Sgola with a frame it
 *  constructed, and read the frame back.
 *
 *  The oracle is arithmetic, like the objc spike's: a view initialised with
 *  `{{0,0},{160,128}}` reports a frame 160 wide and 128 high. A struct
 *  marshalled wrong gives zeros or garbage — field order, nesting and the
 *  float ABI all have to be right at once, and none of them fails subtly.
 *
 *  Left spelled the way the interpreter wants it: `initWithFrame` takes a
 *  rect the caller built. The read-only half below it is the smaller
 *  question, and it fails first.
 */
object Main:

  def main(args: Array[String]): Unit =
    val f = go.appkit.getNSViewClass().alloc().frame()
    println("interp-spike: frame = " + f.size.width + "x" + f.size.height +
      " at " + f.origin.x + "," + f.origin.y)
