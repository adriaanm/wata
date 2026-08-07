// The Settings window (plan 0037, slice 3). Deliberately three lines and one
// button: who you are signed in as, which server, and a way out of both.
//
// It shows the account rather than editing it. Changing the server or the
// name IS signing out — the token is scoped to that pair, the Keychain items
// are keyed by it, and the running client is bound to it — so offering an
// editable field would be pretending a text edit could do what only a
// re-login can. The button says so: "Sign Out…" returns to the sheet, which
// is where a different server or a different name gets typed, prefilled with
// what was there. That is also the "switch account" path plan 0037 wanted.
//
// The account text comes from the Sgola side via SetAccount, because this
// package has no idea what is stored; the window is rebuilt from it every
// time it opens, which is cheaper than keeping labels in step.
//
// The notification mode this window is also supposed to hold belongs to slice
// 4, which is where the setting will exist at all.

//go:build darwin

package macshell

import (
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

const (
	prefsW      = 360.0
	prefsH      = 150.0
	prefsMargin = 20.0
)

var (
	prefsMu   sync.Mutex
	prefsWin  objc.ID // 0 until first opened; reused after
	acctUser  string
	acctHs    string
	acctReady bool
)

// SetAccount tells the chrome what the session is signed in as. Called from
// the Sgola side whenever that changes — at startup and after a re-login.
func SetAccount(hs, user string) {
	prefsMu.Lock()
	acctHs, acctUser, acctReady = hs, user, true
	prefsMu.Unlock()
}

// account reads it back, with a legible answer for "not signed in yet".
func account() (string, string, bool) {
	prefsMu.Lock()
	defer prefsMu.Unlock()
	return acctHs, acctUser, acctReady && acctUser != ""
}

// ShowPrefs opens the window from anywhere (the facade's entry point); the
// menu item calls showPrefs directly, being on the main thread already.
func ShowPrefs() { onStageSync(showPrefs) }

func showPrefs() {
	prefsMu.Lock()
	w := prefsWin
	prefsMu.Unlock()
	if w == 0 {
		w = buildPrefsWindow()
		prefsMu.Lock()
		prefsWin = w
		prefsMu.Unlock()
	}
	fillPrefs(w)
	w.Send(objc.RegisterName("makeKeyAndOrderFront:"), objc.ID(0))
	objc.ID(objc.GetClass("NSApplication")).
		Send(objc.RegisterName("sharedApplication")).
		Send(objc.RegisterName("activateIgnoringOtherApps:"), true)
}

func closePrefs() {
	prefsMu.Lock()
	w := prefsWin
	prefsMu.Unlock()
	if w != 0 {
		w.Send(objc.RegisterName("orderOut:"), objc.ID(0))
	}
}

// buildPrefsWindow makes the window and its permanent controls. Closable but
// NOT resizable: nothing in it reflows, and a resizable window that cannot
// use the space just looks broken.
func buildPrefsWindow() objc.ID {
	style := appkit.NSWindowStyleMaskTitled | appkit.NSWindowStyleMaskClosable
	w := appkit.NSWindow{ID: appkit.GetNSWindowClass().Alloc().ID}.
		InitWithContentRectStyleMaskBackingDefer(
			appkit.CGRect{Size: appkit.CGSize{Width: prefsW, Height: prefsH}},
			style, appkit.NSBackingStoreBuffered, false)
	w.SetTitle("Wata Settings")
	// closing must HIDE it: released, the next ⌘, would message freed memory
	w.ID.Send(objc.RegisterName("setReleasedWhenClosed:"), false)
	w.Center()
	return w.ID
}

// fillPrefs rebuilds the window's content from the current account.
func fillPrefs(w objc.ID) {
	fillPrefsView(appkit.NSView{ID: w.Send(objc.RegisterName("contentView"))})
}

// fillPrefsView is the content itself, taking the view rather than the window
// — an NSWindow may only be instantiated on the main thread, so the test
// builds a bare NSView and fills that. Same split as login.go's.
func fillPrefsView(content appkit.NSView) {
	hs, user, signedIn := account()

	// drop what a previous open left; NSArray copy first — removing from a
	// view mutates the subviews array being walked
	subs := content.Subviews()
	for i := int(subs.Count()) - 1; i >= 0; i-- {
		appkit.NSView{ID: subs.ObjectAtIndex(uint(i))}.
			ID.Send(objc.RegisterName("removeFromSuperview"))
	}

	row := func(i int) float64 { return prefsH - prefsMargin - float64(i+1)*rowH - float64(i)*rowGap }
	labelCol := 92.0
	valueW := prefsW - prefsMargin*2 - labelCol

	who := user
	where := hs
	if !signedIn {
		who, where = "not signed in", "—"
	}
	content.AddSubview(appkit.NSView{ID: newLabel("Signed in as", prefsMargin, row(0), labelCol, rowH)})
	content.AddSubview(appkit.NSView{ID: newLabel(who, prefsMargin+labelCol, row(0), valueW, rowH)})
	content.AddSubview(appkit.NSView{ID: newLabel("Server", prefsMargin, row(1), labelCol, rowH)})
	content.AddSubview(appkit.NSView{ID: newLabel(where, prefsMargin+labelCol, row(1), valueW, rowH)})

	note := newLabel("Signing out returns to the sign-in window, where you can "+
		"use a different server or name.", prefsMargin, row(3)-rowH, prefsW-prefsMargin*2, rowH*2)
	note.Send(objc.RegisterName("setFont:"),
		objc.ID(objc.GetClass("NSFont")).Send(objc.RegisterName("systemFontOfSize:"), 10.0))
	// two short lines rather than one clipped one
	note.Send(objc.RegisterName("setUsesSingleLineMode:"), false)
	note.Send(objc.RegisterName("cell")).
		Send(objc.RegisterName("setWraps:"), true)
	content.AddSubview(appkit.NSView{ID: note})

	btn := objc.ID(objc.GetClass("NSButton")).Send(selAlloc).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Origin: appkit.CGPoint{X: prefsW - prefsMargin - 110, Y: prefsMargin},
			Size:   appkit.CGSize{Width: 110, Height: 28},
		})
	btn.Send(objc.RegisterName("setTitle:"), objcrt.NSString("Sign Out…"))
	btn.Send(objc.RegisterName("setBezelStyle:"), nsBezelStyleRounded)
	btn.Send(objc.RegisterName("setTarget:"), menuTarget)
	btn.Send(objc.RegisterName("setAction:"), selSignOutCmd)
	btn.Send(objc.RegisterName("setEnabled:"), signedIn)
	content.AddSubview(appkit.NSView{ID: btn})
}

const (
	nsBezelStyleRounded      = 1

)
