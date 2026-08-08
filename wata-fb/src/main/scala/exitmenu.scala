/** the exit menu (plan 0040): what the confirmed exit edge opens instead of
 *  quitting.
 *
 *  Five actions, in the order a person escalates. The interesting thing here is
 *  the confirmation, which is NOT graded by how destructive an action is — a
 *  reboot and a power-off are both fine, the handset comes back — but by who
 *  can undo it. Restart, reboot and power-off all end with a device its owner
 *  can use again by pressing a button. Fastboot and EDL end with a device that
 *  shows nothing, responds to nothing, and stays that way until somebody with a
 *  cable and a host machine intervenes, so they take a second confirmation: a
 *  kid exploring the menu must not reach that state by pressing OK twice.
 *
 *  The state is a small counter rather than Settings' `armed` Boolean, because
 *  a second step needs a third value. Everything else follows the shapes
 *  `SettingsLogic` already established — pure transition functions over an
 *  immutable record, the view algebra for drawing, and the detail block
 *  doubling as the confirmation prompt.
 */

/** an open exit menu: the row the selection is on, and how many times OK has
 *  been pressed on it (0 unarmed, 1 armed, 2 armed again). `msg` carries what
 *  an action reported — off-device every action is a logged no-op, and saying
 *  so beats a menu that appears to do nothing. */
case class ExitMenuState(selected: scala.Int, confirm: scala.Int, armLeft: scala.Double, msg: String) extends Shareable

object ExitMenu:
  val RESTART_APP = 0
  val REBOOT = 1
  val POWER_OFF = 2
  val REBOOT_BL = 3
  val REBOOT_EDL = 4
  val N_ITEMS = 5

  /** seconds an arming lasts. An armed menu left alone must not sit one
   *  keypress away from EDL until the screensaver takes the panel. */
  val ARM_S: scala.Double = 4.0

  /** the grid row the menu's first item draws on, and the detail block's. */
  val FIRST_ROW = 2
  val DETAIL_ROW = 13

  def initial(): ExitMenuState = ExitMenuState(RESTART_APP, 0, 0.0, "")

  // ---- the confirmation rule -------------------------------------------------

  /** does this action end with a device only a cable can recover? Those are the
   *  two that take a second confirmation. */
  def needsTwo(i: scala.Int): Boolean = i == REBOOT_BL || i == REBOOT_EDL

  /** how many OK presses this action takes. */
  def confirmsFor(i: scala.Int): scala.Int = if needsTwo(i) then 2 else 1

  /** is the selected action one more OK away from running? */
  def ready(s: ExitMenuState): Boolean = s.confirm >= confirmsFor(s.selected)

  // ---- transitions -----------------------------------------------------------

  def withSelected(s: ExitMenuState, i: scala.Int): ExitMenuState =
    ExitMenuState(i, s.confirm, s.armLeft, s.msg)
  def withConfirm(s: ExitMenuState, c: scala.Int): ExitMenuState =
    ExitMenuState(s.selected, c, s.armLeft, s.msg)
  def withArm(s: ExitMenuState, t: scala.Double): ExitMenuState =
    ExitMenuState(s.selected, s.confirm, t, s.msg)
  def withMsg(s: ExitMenuState, m: String): ExitMenuState =
    ExitMenuState(s.selected, s.confirm, s.armLeft, m)

  /** any key other than OK drops the confirmation — the same rule Settings
   *  applies, and the one the menu's own prompt promises. */
  def disarmed(s: ExitMenuState): ExitMenuState =
    ExitMenuState(s.selected, 0, 0.0, "")

  /** the arming ages out; nothing else about the menu ticks. */
  def update(s: ExitMenuState, dt: scala.Double): ExitMenuState =
    if s.confirm <= 0 then s
    else
      val left = s.armLeft - dt
      if left <= 0.0 then disarmed(s) else withArm(s, left)

  def moveUp(s: ExitMenuState): ExitMenuState =
    var i = s.selected - 1
    if i < 0 then i = N_ITEMS - 1
    withSelected(disarmed(s), i)

  def moveDown(s: ExitMenuState): ExitMenuState =
    var i = s.selected + 1
    if i >= N_ITEMS then i = 0
    withSelected(disarmed(s), i)

  /** OK: one more confirmation, or — when the last one lands — run it.
   *  `Restart app` is the only action with no `Diag` call behind it: quitting
   *  IS the restart, since inittab respawns the app, so it answers `true` and
   *  the frame loop ends. */
  def onOk(s: ExitMenuState): ExitMenuState =
    val next = withArm(withConfirm(s, s.confirm + 1), ARM_S)
    if !ready(next) then next
    else ExitMenuState(s.selected, 0, 0.0, run(s.selected))

  /** does an OK on this state quit the loop (the restart action, fully
   *  confirmed)? Asked BEFORE `onOk`, since running it clears the counter. */
  def quitsOnOk(s: ExitMenuState): Boolean =
    s.selected == RESTART_APP && s.confirm + 1 >= confirmsFor(RESTART_APP)

  /** fire the action. Everything but the restart goes through `Diag`, which
   *  already degrades to a logged no-op off the handset — so this returns the
   *  line the menu shows rather than assuming the machine is going down. */
  def run(i: scala.Int): String =
    if i == RESTART_APP then ""
    else if i == REBOOT then
      Diag.reboot(); ranMsg()
    else if i == POWER_OFF then
      Diag.powerOff(); ranMsg()
    else if i == REBOOT_BL then
      Diag.rebootBootloader(); ranMsg()
    else
      Diag.rebootEdl(); ranMsg()

  /** on the handset the machine is going down and nothing reads this; off it,
   *  the action was a no-op and the menu should say so rather than sit there
   *  looking broken. */
  def ranMsg(): String = if Diag.onDevice() then "" else "not on device"

  def handleInput(s: ExitMenuState, k: Key, ks: KeyState, ctx: FrameCtx): ExitMenuState =
    if !Shell.isPressed(ks) then s
    else k match
      case _: KUp    => moveUp(s)
      case _: KDown  => moveDown(s)
      case _: KEnter => onOk(s)
      case _         => disarmed(s)

  /** BACK closes the menu — the escape route from every row, including an
   *  armed one. */
  def closes(k: Key, ks: KeyState): Boolean =
    Shell.isPressed(ks) && (k match
      case _: KBack => true
      case _        => false)

  // ---- rendering ---------------------------------------------------------------

  def render(s: ExitMenuState, px: go.Bytes, ctx: FrameCtx): Unit =
    FbPaint.draw(px, body(s))

  def body(s: ExitMenuState): View =
    VGroup(Keyed("title", VText(0, 0, "EXIT", Color.cyan)) ::
      (Keyed("rows", rowsView(s)) ::
        (Keyed("detail", detailView(s)) :: Nil)))

  def rowsView(s: ExitMenuState): View =
    var acc: List[Keyed] = Nil
    var i = 0
    while i < N_ITEMS do
      acc = Keyed("item" + i, itemView(s, i, FIRST_ROW + i * 2, i == s.selected)) :: acc
      i += 1
    VGroup(ListOps.reverse(acc))

  /** a row: the selection highlight first (children paint in list order), then
   *  the label. The two cable-only actions are drawn in red even unselected —
   *  the menu should look different where it IS different, before anyone
   *  presses anything. */
  def itemView(s: ExitMenuState, i: scala.Int, row: scala.Int, sel: Boolean): View =
    var fg = Color.green
    if needsTwo(i) then fg = Color.red
    if sel then fg = Color.black
    var kids: List[Keyed] = Nil
    if sel then
      var hl = Color.green
      if needsTwo(i) then hl = Color.red
      kids = Keyed("hl", VRect(0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, hl)) :: kids
    kids = Keyed("label", VText(0, row, itemLabel(i), fg)) :: kids
    VGroup(ListOps.reverse(kids))

  def itemLabel(i: scala.Int): String =
    if i == RESTART_APP then "Restart app"
    else if i == REBOOT then "Reboot"
    else if i == POWER_OFF then "Power off"
    else if i == REBOOT_BL then "Reboot to fastboot"
    else "Reboot to EDL"

  /** what the NEXT OK does. Unarmed it names the action; part-way through a
   *  two-step it says so in red, which is the whole point of the second step;
   *  and a reported message replaces both. */
  def detailView(s: ExitMenuState): View =
    if s.msg != "" then lines(s.msg, Color.red, "nothing happened", Color.midGray)
    else if s.confirm <= 0 then
      lines("OK: " + verb(s.selected), Color.midGray, hint(s.selected), Color.midGray)
    else lines("OK again: " + verb(s.selected), Color.red, "other keys cancel", Color.midGray)

  /** The panel is 26 columns (`Font.COLS`), and the longest thing this screen
   *  draws is not a label but the ARMED prompt — `"OK again: " + verb`, ten
   *  characters of prefix. So a verb has 16 columns, and the two cable rows
   *  drop the "reboot to" the highlighted label directly above already says.
   *  `drawText` stops at the panel edge without a word about it, so an
   *  overlong verb does not look broken — it looks like a shorter sentence,
   *  on the confirmation for the actions that most need reading. */
  def verb(i: scala.Int): String =
    if i == RESTART_APP then "restart app"
    else if i == REBOOT then "reboot"
    else if i == POWER_OFF then "power off"
    else if i == REBOOT_BL then "fastboot"
    else "EDL"

  /** the unarmed second line: for the two cable-only rows, what the person is
   *  actually choosing — not "are you sure" but what the device will be. */
  def hint(i: scala.Int): String =
    if i == REBOOT_BL then "needs a USB cable"
    else if i == REBOOT_EDL then "needs a USB cable"
    else if i == RESTART_APP then "the app comes back"
    else ""

  /** Every string this screen can draw, checked against the panel's 26
   *  columns (`wata-fb exitfit`, run by the fb smoke).
   *
   *  This exists because the failure is silent: `Font.drawText` stops at the
   *  panel edge, so an overlong prompt renders as a shorter sentence rather
   *  than as anything visibly wrong. "OK again: reboot to fastboot" shipped
   *  reading "OK again: reboot to fastbo" past six goldens, because none of
   *  them armed that row — a golden only pins the frames somebody thought to
   *  produce, and the longest string is rarely the one a script walks to. A
   *  check that enumerates the strings does not depend on being walked to. */
  def fitCheck(): Unit =
    var ok = true
    var i = 0
    while i <= REBOOT_EDL do
      if !fits("label", itemLabel(i)) then ok = false
      if !fits("unarmed", "OK: " + verb(i)) then ok = false
      if !fits("armed", "OK again: " + verb(i)) then ok = false
      if !fits("hint", hint(i)) then ok = false
      i += 1
    if !fits("cancel", "other keys cancel") then ok = false
    if !fits("nothing", "nothing happened") then ok = false
    if ok then println("exitfit: PASS")
    else println("exitfit: FAIL")

  def fits(what: String, s: String): Boolean =
    val n = go.bytes(s).length
    if n > Font.COLS then
      println("exitfit " + what + " FAIL: " + n + " > " + Font.COLS + " cols: " + s)
      false
    else true

  def lines(l1: String, c1: scala.Int, l2: String, c2: scala.Int): View =
    var kids: List[Keyed] = Nil
    if l2 != "" then kids = Keyed("d2", VText(0, DETAIL_ROW + 1, l2, c2)) :: kids
    if l1 != "" then kids = Keyed("d1", VText(0, DETAIL_ROW, l1, c1)) :: kids
    VGroup(kids)
