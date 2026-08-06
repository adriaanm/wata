// Key translation scaffolding: macOS keyboard events into the five-key
// model wata's input layer speaks (wata-fb/src/main/scala/input.scala).
//
// The table is pure — NSEvent's keyCode is a hardware-independent macOS
// virtual key code (HIToolbox Events.h), so translation needs no AppKit
// call. Wiring keyDown:/keyUp: into it takes a synthesized NSView subclass
// that becomes first responder; that lives with the wata-mac shell
// (plan 0032 chunk 2), which owns press/release/repeat state as well.
//
// The device's key model, for reference: PTT (F1 on the case), up/down/
// left/right, enter (select), back (esc). The prev/next-applet side keys
// have no macOS binding yet.

package nativeui

// Key is one key of the walkie-talkie's key model.
type Key int

const (
	KeyNone Key = iota
	KeyUp
	KeyDown
	KeyLeft
	KeyRight
	KeyEnter // select / DPAD center
	KeyBack  // esc
	KeyPtt   // hold to talk — space on a Mac keyboard
)

// macOS virtual key codes (HIToolbox Events.h — kVK_*).
const (
	vkReturn      = 36
	vkKeypadEnter = 76
	vkSpace       = 49
	vkEscape      = 53
	vkLeftArrow   = 123
	vkRightArrow  = 124
	vkDownArrow   = 125
	vkUpArrow     = 126
)

// TranslateKeyCode maps an NSEvent keyCode to the five-key model. KeyNone
// means "not a wata key" — let AppKit have it.
func TranslateKeyCode(code uint16) Key {
	switch code {
	case vkUpArrow:
		return KeyUp
	case vkDownArrow:
		return KeyDown
	case vkLeftArrow:
		return KeyLeft
	case vkRightArrow:
		return KeyRight
	case vkReturn, vkKeypadEnter:
		return KeyEnter
	case vkEscape:
		return KeyBack
	case vkSpace:
		return KeyPtt
	}
	return KeyNone
}
