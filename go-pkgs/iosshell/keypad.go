// The minimal viable touch input (plan 0044 stage 4): the handset's key
// model as on-screen buttons. wata's applets speak five keys plus PTT and
// arrows; a phone has none of them, so the shell draws a button per key and
// queues touch edges as key events — target-action through a synthesized
// ObjC class, the same runtime-class machinery the app delegate uses. Real
// interaction design (gestures, layout, a PTT worth holding) is
// ADULT-UX-NONHAPPY's; these buttons exist so the shared applet logic is
// drivable at all.
//
// The queue's contract with the Sgola side (ioskeys.scala's IosKeys): each
// entry is `code*4 + phase` with code 1..7 (UP DOWN LEFT RIGHT ENTER BACK
// PTT) and phase 0 release / 1 press — ALREADY the app's key model, no
// platform translation table (macshell queues raw kVK codes because a
// keyboard has scancodes; a button we drew has no raw code to preserve).
// PTT's press/release are the button's touch-down and touch-up/cancel edges,
// which is what makes hold-to-talk work.
//
// Retention: buttons are retained by the container (addSubview); the target
// instance is owned (+alloc) and kept in a package global. UIButton does NOT
// retain its target.

//go:build darwin

package iosshell

import (
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/ebitengine/purego/objc"
)

// the key codes (the Sgola contract; keep in step with ioskeys.scala).
const (
	KeyNone  = 0
	KeyUp    = 1
	KeyDown  = 2
	KeyLeft  = 3
	KeyRight = 4
	KeyEnter = 5
	KeyBack  = 6
	KeyPtt   = 7
)

// UIControlEvents masks (UIControl.h).
const (
	touchDown      = 1 << 0
	touchUpInside  = 1 << 6
	touchUpOutside = 1 << 7
	touchCancel    = 1 << 8
)

const keypadTargetClass = "WataKeypadTarget"

var (
	keyMu    sync.Mutex
	keyQueue []int
	target   objc.ID // the owned action target (never released)
	buttons  []objc.ID

	selButtonWithType   = objc.RegisterName("buttonWithType:")
	selSetTitleForState = objc.RegisterName("setTitle:forState:")
	selAddTarget        = objc.RegisterName("addTarget:action:forControlEvents:")
	selSetTag           = objc.RegisterName("setTag:")
	selTag              = objc.RegisterName("tag")
	selKeyDown          = objc.RegisterName("wataKeyDown:")
	selKeyUp            = objc.RegisterName("wataKeyUp:")
)

// PushKey queues one key edge (phase 0 release / 1 press / 2 repeat) — the
// buttons' own path, and a harness's injection seam.
func PushKey(code, phase int) {
	keyMu.Lock()
	keyQueue = append(keyQueue, code*4+phase)
	keyMu.Unlock()
}

// NextKey pops the oldest queued `code*4 + phase`, or -1 — never blocks.
func NextKey() int {
	keyMu.Lock()
	defer keyMu.Unlock()
	if len(keyQueue) == 0 {
		return -1
	}
	k := keyQueue[0]
	keyQueue = keyQueue[1:]
	return k
}

func keypadTarget() objc.ID {
	if target != 0 {
		return target
	}
	cls, err := objc.RegisterClass(keypadTargetClass, objc.GetClass("NSObject"), nil, nil,
		[]objc.MethodDef{
			{Cmd: selKeyDown, Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
				PushKey(int(sender.Send(selTag)), 1)
			}},
			{Cmd: selKeyUp, Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
				PushKey(int(sender.Send(selTag)), 0)
			}},
		})
	if err != nil {
		panic("iosshell: RegisterClass " + keypadTargetClass + ": " + err.Error())
	}
	target = objc.ID(cls).Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
	return target
}

func addButton(cv uikit.UIView, title string, code int, frame uikit.CGRect) {
	// UIButtonTypeSystem = 1; the answer is autoreleased — the container's
	// addSubview retains it.
	btn := objc.ID(objc.GetClass("UIButton")).Send(selButtonWithType, 1)
	btn.Send(selSetTitleForState, objcrt.NSString(title), 0)
	btn.Send(selSetTag, code)
	t := keypadTarget()
	btn.Send(selAddTarget, t, selKeyDown, touchDown)
	btn.Send(selAddTarget, t, selKeyUp, touchUpInside|touchUpOutside|touchCancel)
	v := uikit.UIView{ID: btn}
	v.SetFrame(frame)
	v.SetBackgroundColor(uikit.GetUIColorClass().SystemGray5Color())
	cv.AddSubview(v)
	buttons = append(buttons, btn)
}

// AddKeypad lays the key buttons into the window's container: two rows at
// the bottom — UP DOWN LEFT RIGHT, then BACK OK PTT. Main thread, after
// `ready` (it reads the container's bounds).
func AddKeypad() {
	mu.Lock()
	cv := container
	mu.Unlock()
	pool := iosui.PoolPush()
	defer iosui.PoolPop(pool)
	b := cv.Bounds()
	w, h := b.Size.Width, b.Size.Height
	const btnH, gap = 52.0, 8.0
	row := func(y float64, keys []int, titles []string) {
		n := float64(len(keys))
		bw := (w - gap*(n+1)) / n
		for i, code := range keys {
			addButton(cv, titles[i], code, uikit.CGRect{
				Origin: uikit.CGPoint{X: gap + float64(i)*(bw+gap), Y: y},
				Size:   uikit.CGSize{Width: bw, Height: btnH}})
		}
	}
	row(h-2*(btnH+gap)-24, []int{KeyUp, KeyDown, KeyLeft, KeyRight},
		[]string{"▲", "▼", "◀", "▶"})
	row(h-(btnH+gap)-24, []int{KeyBack, KeyEnter, KeyPtt},
		[]string{"BACK", "OK", "PTT"})
}
