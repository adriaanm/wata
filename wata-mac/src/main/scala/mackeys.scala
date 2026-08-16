/** Key translation: macOS virtual key codes (HIToolbox Events.h, kVK_*) into
 *  the five-key model wata's input layer speaks (input.scala). The seam
 *  carries RAW codes — the synthesized key view and `pushKeyCode` queue
 *  `code*4 + phase` untranslated — and this table runs where the events are
 *  drained, so the window path and the headless harness path go through the
 *  SAME translation.
 *
 *  The device's key model, for reference: PTT (F1 on the case), up/down/
 *  left/right, enter (select), back (esc). The prev/next-applet side keys
 *  have no macOS binding yet. */
object MacKeys:
  // the numbering `translate` answers in: 0 = not a wata key.
  val NONE = 0
  val UP = 1
  val DOWN = 2
  val LEFT = 3
  val RIGHT = 4
  val ENTER = 5 // select / DPAD center
  val BACK = 6  // esc
  val PTT = 7   // hold to talk — space on a Mac keyboard

  /** map an NSEvent keyCode to the model; NONE means "not a wata key" —
   *  the drain drops it. */
  def translate(code: Int): Int =
    if code == 126 then UP          // kVK_UpArrow
    else if code == 125 then DOWN   // kVK_DownArrow
    else if code == 123 then LEFT   // kVK_LeftArrow
    else if code == 124 then RIGHT  // kVK_RightArrow
    else if code == 36 then ENTER   // kVK_Return
    else if code == 76 then ENTER   // kVK_ANSI_KeypadEnter
    else if code == 53 then BACK    // kVK_Escape
    else if code == 49 then PTT     // kVK_Space
    else NONE
