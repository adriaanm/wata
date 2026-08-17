/** The touch keypad's key numbering — the CONTRACT with go-pkgs/iosshell's
 *  keypad (keypad.go declares the same constants): the shell's on-screen
 *  buttons queue `code*4 + phase` in these codes, and the pump drains them
 *  into the shared `Key` model (input.scala). Unlike the mac there is no
 *  raw-platform-code translation table: the buttons ARE the model, one per
 *  key, so the codes cross the seam already translated. */
object IosKeys:
  val NONE = 0
  val UP = 1
  val DOWN = 2
  val LEFT = 3
  val RIGHT = 4
  val ENTER = 5 // the OK button
  val BACK = 6
  val PTT = 7   // hold to talk — press/release are the button's touch edges
