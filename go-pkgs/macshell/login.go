// The login sheet: the one piece of chrome that has to exist before the app
// can be used by someone who does not know what an environment variable is
// (plan 0037, slice 2).
//
// NSAlert with an accessory view — homeserver, username, password, and a
// "remember me" checkbox. `runModal` is a SYNCHRONOUS call returning an
// NSInteger, so this needs no block, no delegate and no completion handler,
// which is what keeps the plan's named binding risk (struct/CGFloat returns
// from a callback are refused) away from this slice entirely. Slice 4's
// UNUserNotificationCenter authorization is where that risk actually lives.
//
// NSAlert, NSButton and NSSecureTextField are not in the bindgen allowlist,
// so they are reached with raw objc.Send — the same way nativeui/keyview.go
// synthesizes its key view. Eight selectors used in one file did not justify
// an SDK regeneration; if the chrome grows a menu bar and notifications
// (slices 3 and 4), a generated `macui` target becomes the right call.
//
// THREADING. A modal must run on the main thread, and the caller is the pump
// goroutine, so this dispatches and then BLOCKS on the result — `onStageSync`.
// Windowed, that main queue only drains once NSApplication.run is going, so a
// sheet requested before then simply waits, which is correct: there is no
// window to put it on yet either.

//go:build darwin

package macshell

import (
	"strings"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

const (
	// NSAlertFirstButtonReturn — the leftmost button, which is "Sign In".
	alertFirstButton = 1000
	// NSSwitchButton: the checkbox button type.
	buttonTypeSwitch = 3
	controlStateOn   = 1
)

// the accessory view's geometry, in points
const (
	sheetW  = 320.0
	rowH    = 24.0
	rowGap  = 8.0
	labelW  = 96.0
	fieldW  = sheetW - labelW
	numRows = 4 // homeserver, user, password, remember
	sheetH  = numRows*rowH + (numRows-1)*rowGap
)

// onStageSync lives in shell.go now: headless the caller IS the stage's
// thread (the locked main goroutine), so the work runs inline; windowed it
// hops to the main queue and waits.

func newTextField(x, y, w, h float64, secure bool) objc.ID {
	class := "NSTextField"
	if secure {
		class = "NSSecureTextField"
	}
	id := objc.ID(objc.GetClass(class)).Send(objc.RegisterName("alloc"))
	return id.Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
		Origin: appkit.CGPoint{X: x, Y: y},
		Size:   appkit.CGSize{Width: w, Height: h},
	})
}

// a non-editable, borderless, transparent NSTextField — an AppKit label.
func newLabel(text string, x, y, w, h float64) objc.ID {
	id := newTextField(x, y, w, h, false)
	appkit.NSControl{ID: id}.SetStringValue(text) // StringValue is NSControl's
	f := appkit.NSTextField{ID: id}
	f.SetEditable(false)
	f.SetSelectable(false)
	f.SetBordered(false)
	f.SetDrawsBackground(false)
	return id
}

func setPlaceholder(id objc.ID, s string) {
	id.Send(objc.RegisterName("setPlaceholderString:"), objcrt.NSString(s))
}

func stringValue(id objc.ID) string {
	return objcrt.GoString(id.Send(objc.RegisterName("stringValue")))
}

// Login shows the sheet and blocks until the user commits or cancels.
//
// The answer is one tab-separated line so the Sgola facade stays the
// strings-only surface the rest of macshell is:
//
//	""                                     the user cancelled
//	"<homeserver>\t<user>\t<pass>\t0|1"    committed; the flag is "remember me"
//
// `hs` and `user` prefill the fields — a re-authentication after a rejected
// token should not make anyone retype what the app already knows.
func Login(hs, user string) string {
	var out string
	onStageSync(func() { out = runLoginSheet(hs, user) })
	return out
}

// loginFields is the accessory view and the controls a caller reads back.
// Split out from the modal so it can be built and asserted WITHOUT spinning
// one — a test cannot click a button, and a machine with no screen-recording
// grant cannot even look at it.
type loginFields struct {
	box, hs, user, pass, remember objc.ID
}

func buildLoginFields(hs, user string) loginFields {
	box := objc.ID(objc.GetClass("NSView")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Size: appkit.CGSize{Width: sheetW, Height: sheetH},
		})
	view := appkit.NSView{ID: box}

	// rows are laid out from the TOP, and AppKit's origin is bottom-left
	row := func(i int) float64 { return sheetH - float64(i+1)*rowH - float64(i)*rowGap }

	hsField := newTextField(labelW, row(0), fieldW, rowH, false)
	setPlaceholder(hsField, "http://…")
	if hs != "" {
		appkit.NSControl{ID: hsField}.SetStringValue(hs)
	}
	userField := newTextField(labelW, row(1), fieldW, rowH, false)
	setPlaceholder(userField, "the name on the handset")
	if user != "" {
		appkit.NSControl{ID: userField}.SetStringValue(user)
	}
	passField := newTextField(labelW, row(2), fieldW, rowH, true)

	remember := objc.ID(objc.GetClass("NSButton")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Origin: appkit.CGPoint{X: labelW, Y: row(3)},
			Size:   appkit.CGSize{Width: fieldW, Height: rowH},
		})
	remember.Send(objc.RegisterName("setButtonType:"), buttonTypeSwitch)
	remember.Send(objc.RegisterName("setTitle:"), objcrt.NSString("Stay signed in"))
	remember.Send(objc.RegisterName("setState:"), controlStateOn)

	for _, sub := range []objc.ID{
		newLabel("Server", 0, row(0), labelW-8, rowH),
		newLabel("Name", 0, row(1), labelW-8, rowH),
		newLabel("Password", 0, row(2), labelW-8, rowH),
		hsField, userField, passField, remember,
	} {
		view.AddSubview(appkit.NSView{ID: sub})
	}
	return loginFields{box: box, hs: hsField, user: userField,
		pass: passField, remember: remember}
}

// firstField is where the cursor lands: the first EMPTY one. A fresh install
// starts at the server; a re-auth after a rejected token starts at the
// password, which is the only thing that has to be retyped.
func (f loginFields) firstField(hs, user string) objc.ID {
	if hs == "" {
		return f.hs
	}
	if user == "" {
		return f.user
	}
	return f.pass
}

// answer reads the controls back into the tab-separated line Login returns.
func (f loginFields) answer() string {
	flag := "0"
	if int(f.remember.Send(objc.RegisterName("state"))) == controlStateOn {
		flag = "1"
	}
	return strings.Join([]string{
		strings.TrimSpace(stringValue(f.hs)),
		strings.TrimSpace(stringValue(f.user)),
		stringValue(f.pass),
		flag,
	}, "\t")
}

func runLoginSheet(hs, user string) string {
	alert := objc.ID(objc.GetClass("NSAlert")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("init"))
	alert.Send(objc.RegisterName("setMessageText:"), objcrt.NSString("Sign in to Wata"))
	alert.Send(objc.RegisterName("setInformativeText:"),
		objcrt.NSString("Connect to your family's Wata server."))
	alert.Send(objc.RegisterName("addButtonWithTitle:"), objcrt.NSString("Sign In"))
	alert.Send(objc.RegisterName("addButtonWithTitle:"), objcrt.NSString("Quit"))

	f := buildLoginFields(hs, user)
	alert.Send(objc.RegisterName("setAccessoryView:"), f.box)
	alert.Send(objc.RegisterName("window")).
		Send(objc.RegisterName("setInitialFirstResponder:"), f.firstField(hs, user))

	if int(alert.Send(objc.RegisterName("runModal"))) != alertFirstButton {
		return ""
	}
	return f.answer()
}
