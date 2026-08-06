// The synthesized key view, driven headlessly: acceptsFirstResponder through
// the ObjC dispatcher, and keyDown:/keyUp: fed a stand-in event object — a
// second synthesized class answering keyCode/isARepeat — so the translation
// and the press/release/repeat phases are pinned without a window or a real
// event loop.

//go:build darwin

package nativeui

import (
	"sync"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/ebitengine/purego/objc"
)

// fakeKeyEvent synthesizes an object answering keyCode/isARepeat — the two
// messages dispatchKeyEvent sends — with canned values.
var fakeEventOnce sync.Once
var fakeEventClass objc.Class
var fakeMu sync.Mutex
var fakeCode uint16
var fakeRepeat bool

func fakeKeyEvent(code uint16, repeat bool) objc.ID {
	fakeEventOnce.Do(func() {
		cls, err := objc.RegisterClass("WataFakeKeyEvent", objc.GetClass("NSObject"), nil, nil,
			[]objc.MethodDef{
				{Cmd: selKeyCode, Fn: func(self objc.ID, _ objc.SEL) uint16 {
					return fakeCode
				}},
				{Cmd: selIsARepeat, Fn: func(self objc.ID, _ objc.SEL) bool {
					return fakeRepeat
				}},
			})
		if err != nil {
			t := err.Error()
			panic("registering WataFakeKeyEvent: " + t)
		}
		fakeEventClass = cls
	})
	fakeCode = code
	fakeRepeat = repeat
	return objc.ID(fakeEventClass).Send(selAllocKV).Send(objc.RegisterName("init"))
}

func TestKeyViewTranslatesAndPhases(t *testing.T) {
	onAppKit(t, func() {
		fakeMu.Lock()
		defer fakeMu.Unlock()
		type got struct {
			k     Key
			phase int
		}
		var events []got
		v := NewKeyView(appkit.CGRect{Size: appkit.CGSize{Width: 100, Height: 100}},
			func(k Key, phase int) { events = append(events, got{k, phase}) })

		if got := className(v.ID); got != "WataKeyView" {
			t.Fatalf("class = %q, want WataKeyView", got)
		}
		if !objc.Send[bool](v.ID, selAcceptsFirst) {
			t.Fatalf("acceptsFirstResponder answered NO")
		}

		// press, autorepeat, release of the space bar (PTT), then an arrow
		// press and a key the table does not know (must be swallowed).
		v.ID.Send(selKeyDown, fakeKeyEvent(49, false))
		v.ID.Send(selKeyDown, fakeKeyEvent(49, true))
		v.ID.Send(selKeyUp, fakeKeyEvent(49, false))
		v.ID.Send(selKeyDown, fakeKeyEvent(126, false))
		v.ID.Send(selKeyDown, fakeKeyEvent(12 /* kVK_ANSI_Q */, false))

		want := []got{
			{KeyPtt, PhasePress},
			{KeyPtt, PhaseRepeat},
			{KeyPtt, PhaseRelease},
			{KeyUp, PhasePress},
		}
		if len(events) != len(want) {
			t.Fatalf("events = %v, want %v", events, want)
		}
		for i := range want {
			if events[i] != want[i] {
				t.Fatalf("event %d = %v, want %v", i, events[i], want[i])
			}
		}
	})
}
