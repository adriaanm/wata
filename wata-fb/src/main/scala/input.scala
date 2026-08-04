import language.experimental.saferExceptions

/** evdev input. Reads `struct input_event`s from `/dev/input/event{0,1,2}`
 *  (nonblocking) and maps the kernel key codes (linux/input-event-codes.h) to
 *  the app `Key` enum. The `Key`/`KeyState` "enums" are SEALED nullary
 *  case-class families, not Scala 3 `enum`s — matching the same style used
 *  for `ConnectionState` in the portable domain model. Device-layer app code
 *  over the `go.syscall` facade. */

sealed trait Key derives CanEqual
case class KUp() extends Key
case class KDown() extends Key
case class KLeft() extends Key
case class KRight() extends Key
case class KEnter() extends Key      // SELECT / DPAD_CENTER
case class KBack() extends Key       // BACK / ESC
case class KPtt() extends Key        // F1 — main PTT
case class KDot1() extends Key       // F3 — prev applet
case class KDot2() extends Key       // F10 — next applet
case class KF2() extends Key         // F2 — sim/script only; no such key on the case
case class KUnknown() extends Key

sealed trait KeyState derives CanEqual
case class Pressed() extends KeyState
case class Released() extends KeyState
case class Repeat() extends KeyState

case class KeyEvent(key: Key, state: KeyState)

object Evdev:
  // struct input_event field/type constants
  val EV_KEY: scala.Int = 0x01
  val EVENT_SIZE: scala.Int = 16   // 32-bit ARM: tv_sec(4) tv_usec(4) type(2) code(2) value(4)

  // key codes (linux/input-event-codes.h)
  val KEY_ESC: scala.Int = 1       // Matrix keypad: Back
  val KEY_ENTER: scala.Int = 28    // Matrix keypad: Center
  val KEY_F1: scala.Int = 59       // GPIO: Main PTT
  val KEY_F2: scala.Int = 60       // GPIO: headset PTT line (no key on the case)
  val KEY_F3: scala.Int = 61       // GPIO: Side button 3 (prev applet)
  val KEY_F10: scala.Int = 68      // GPIO: Side button 4 (next applet)
  val KEY_UP: scala.Int = 103
  val KEY_LEFT: scala.Int = 105
  val KEY_RIGHT: scala.Int = 106
  val KEY_DOWN: scala.Int = 108

  def mapKey(code: scala.Int): Key =
    if code == KEY_UP then KUp()
    else if code == KEY_DOWN then KDown()
    else if code == KEY_LEFT then KLeft()
    else if code == KEY_RIGHT then KRight()
    else if code == KEY_ENTER then KEnter()
    else if code == KEY_ESC then KBack()
    else if code == KEY_F1 then KPtt()
    else if code == KEY_F2 then KF2()
    else if code == KEY_F3 then KDot1()
    else if code == KEY_F10 then KDot2()
    else KUnknown()

  def mapState(value: scala.Int): KeyState =
    if value == 1 then Pressed()
    else if value == 2 then Repeat()
    else Released()

  def known(k: Key): Boolean = k match
    case KUnknown() => false
    case _          => true

  def keyName(k: Key): String = k match
    case KUp()      => "up"
    case KDown()    => "down"
    case KLeft()    => "left"
    case KRight()   => "right"
    case KEnter()   => "enter"
    case KBack()    => "back"
    case KPtt()     => "ptt"
    case KDot1()    => "dot1"
    case KDot2()    => "dot2"
    case KF2()      => "f2"
    case KUnknown() => "unknown"

  def stateName(s: KeyState): String = s match
    case Pressed()  => "pressed"
    case Released() => "released"
    case Repeat()   => "repeat"

  def u8(buf: go.Bytes, i: scala.Int): scala.Int = buf(i).toInt & 0xff

  /** open /dev/input/event0..2 nonblocking; skip any that fail to open (a dev
   *  host may have none). Returns the open fds. */
  def open(): List[scala.Int] =
    var acc: List[scala.Int] = Nil
    var i = 0
    while i < 3 do
      val path = "/dev/input/event" + i
      try
        val fd = go.syscall.open(path, go.syscall.O_RDONLY | go.syscall.O_NONBLOCK, 0)
        acc = fd :: acc
      catch case e: sgo.GoError => ()
      i += 1
    ListOps.reverse(acc)

  def count(fds: List[scala.Int]): scala.Int =
    var n = 0
    var cur = fds
    var going = true
    while going do
      cur match
        case f :: t => n = n + 1; cur = t
        case Nil  => going = false
    n

  def closeAll(fds: List[scala.Int]): Unit =
    var cur = fds
    var going = true
    while going do
      cur match
        case fd :: t => go.syscall.close(fd); cur = t
        case Nil   => going = false

  /** poll all fds, draining every pending input_event; map to KeyEvents. */
  def poll(fds: List[scala.Int]): List[KeyEvent] =
    var acc: List[KeyEvent] = Nil
    var cur = fds
    var going = true
    while going do
      cur match
        case fd :: t =>
          acc = drainFd(fd, acc)
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  /** drain one fd until EAGAIN (read throws) or a short read; prepend mapped
   *  key events onto `accIn`. */
  def drainFd(fd: scala.Int, accIn: List[KeyEvent]): List[KeyEvent] =
    var acc = accIn
    val buf = go.makeSlice[Byte](EVENT_SIZE)
    var reading = true
    while reading do
      try
        val n = go.syscall.read(fd, buf)
        if n != EVENT_SIZE then reading = false
        else
          val etype = u8(buf, 8) | (u8(buf, 9) << 8)
          if etype == EV_KEY then
            val code = u8(buf, 10) | (u8(buf, 11) << 8)
            val value = u8(buf, 12)
            val k = mapKey(code)
            if known(k) then acc = KeyEvent(k, mapState(value)) :: acc
      catch case e: sgo.GoError => reading = false
    acc
