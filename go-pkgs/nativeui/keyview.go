// The first-responder key view: a synthesized NSView subclass whose
// keyDown:/keyUp: feed the pure key table (keys.go) and hand the shell one
// (key, phase) pair per event.
//
// Synthesis follows bindgen.md "Structs into callbacks": a method's ObjC
// type encoding must be the TRUE one. keyDown:/keyUp: take only an object
// and acceptsFirstResponder returns BOOL, so the encodings purego derives
// from the Go signatures (v@:@ and c@:) ARE the true ones — no struct
// decomposition, no hand-written class_addMethod. The moment a synthesized
// method takes a struct by value, it must go the decomposed-trampoline way
// the spike pinned; nothing here does.
//
// Repeat handling: a held key autorepeats as keyDown: events whose isARepeat
// answers YES. Those arrive as PhaseRepeat, distinct from the PhasePress of
// the first press — the walkie-talkie key model (PTT press/release, tap vs
// hold) needs the edges, and a repeat is not an edge.
//
// Keys the table does not know (KeyNone) are swallowed, not forwarded: the
// stage is the whole UI, and there is no text field for AppKit to want them.

//go:build darwin

package nativeui

import (
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/ebitengine/purego/objc"
)

// Key phases, matching the device input layer's Pressed/Released/Repeat.
const (
	PhaseRelease = 0
	PhasePress   = 1
	PhaseRepeat  = 2
)

var (
	keyViewOnce  sync.Once
	keyViewClass objc.Class

	keyHandlersMu sync.Mutex
	keyHandlers   = map[objc.ID]func(k Key, phase int){}

	selKeyCode      = objc.RegisterName("keyCode")
	selIsARepeat    = objc.RegisterName("isARepeat")
	selAllocKV      = objc.RegisterName("alloc")
	selKeyDown      = objc.RegisterName("keyDown:")
	selKeyUp        = objc.RegisterName("keyUp:")
	selAcceptsFirst = objc.RegisterName("acceptsFirstResponder")
	selRetain       = objc.RegisterName("retain")
)

func registerKeyViewClass() {
	keyViewOnce.Do(func() {
		cls, err := objc.RegisterClass("WataKeyView", objc.GetClass("NSView"), nil, nil,
			[]objc.MethodDef{
				{Cmd: selAcceptsFirst, Fn: func(self objc.ID, _ objc.SEL) bool { return true }},
				{Cmd: selKeyDown, Fn: func(self objc.ID, _ objc.SEL, event objc.ID) {
					dispatchKeyEvent(self, event, true)
				}},
				{Cmd: selKeyUp, Fn: func(self objc.ID, _ objc.SEL, event objc.ID) {
					dispatchKeyEvent(self, event, false)
				}},
			})
		if err != nil {
			panic("nativeui: registering WataKeyView: " + err.Error())
		}
		keyViewClass = cls
	})
}

func dispatchKeyEvent(self, event objc.ID, down bool) {
	k := TranslateKeyCode(objc.Send[uint16](event, selKeyCode))
	if k == KeyNone {
		return
	}
	phase := PhaseRelease
	if down {
		phase = PhasePress
		if objc.Send[bool](event, selIsARepeat) {
			phase = PhaseRepeat
		}
	}
	keyHandlersMu.Lock()
	h := keyHandlers[self]
	keyHandlersMu.Unlock()
	if h != nil {
		h(k, phase)
	}
}

// NewKeyView creates a WataKeyView spanning frame. onKey is called on the
// AppKit thread for every translated key event; it must not block (push to a
// queue and return — the shell's frame loop drains it). Make the view the
// window's initialFirstResponder (or send makeFirstResponder:) so keyDown:
// reaches it. AppKit thread only.
func NewKeyView(frame appkit.CGRect, onKey func(k Key, phase int)) appkit.NSView {
	registerKeyViewClass()
	// -init may return a different object than -alloc did; adopt the return.
	v := appkit.NSView{ID: objc.ID(keyViewClass).Send(selAllocKV)}.InitWithFrame(frame)
	keyHandlersMu.Lock()
	keyHandlers[v.ID] = onKey
	keyHandlersMu.Unlock()
	return v
}
