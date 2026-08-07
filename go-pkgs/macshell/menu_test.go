//go:build darwin

// The menu bar and the Settings window, minus the runloop. Menus need no
// NSApplication.run to exist — they are built, handed to NSApp, and can be
// walked — so everything a user would check by pulling a menu down is checked
// here: that the items are present, that the key equivalents are the ones
// muscle memory expects, and that the two items which are OURS actually
// target our object rather than falling into the responder chain and doing
// nothing.
//
// This is the same bargain login_test.go strikes: the machine has no
// screen-recording grant, so the structure is asserted instead of the pixels.

package macshell

import (
	"strings"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

// menuItems returns (title, keyEquivalent, hasTarget) for one submenu of the
// main menu, found by its title.
type menuRow struct {
	title, key string
	target     objc.ID
	action     objc.SEL
}

func submenuRows(t *testing.T, mainMenu objc.ID, want string) []menuRow {
	t.Helper()
	n := int(mainMenu.Send(objc.RegisterName("numberOfItems")))
	for i := 0; i < n; i++ {
		it := mainMenu.Send(objc.RegisterName("itemAtIndex:"), i)
		sub := it.Send(objc.RegisterName("submenu"))
		if sub == 0 {
			continue
		}
		title := objcrt.GoString(sub.Send(objc.RegisterName("title")))
		if title != want {
			continue
		}
		var rows []menuRow
		m := int(sub.Send(objc.RegisterName("numberOfItems")))
		for j := 0; j < m; j++ {
			si := sub.Send(objc.RegisterName("itemAtIndex:"), j)
			rows = append(rows, menuRow{
				title:  objcrt.GoString(si.Send(objc.RegisterName("title"))),
				key:    objcrt.GoString(si.Send(objc.RegisterName("keyEquivalent"))),
				target: si.Send(objc.RegisterName("target")),
				action: objc.SEL(si.Send(objc.RegisterName("action"))),
			})
		}
		return rows
	}
	t.Fatalf("no %q menu on the bar", want)
	return nil
}

func find(rows []menuRow, title string) (menuRow, bool) {
	for _, r := range rows {
		if r.title == title {
			return r, true
		}
	}
	return menuRow{}, false
}

func TestMenuBar(t *testing.T) {
	// buildMainMenu, not installMenuBar: handing the bar to NSApp is the one
	// step AppKit insists happens on the main thread, and a test goroutine is
	// never on it. Everything worth asserting is in the built tree.
	mainMenu, windowMenu := buildMainMenu("Wata")

	app := submenuRows(t, mainMenu, "Wata")
	for _, want := range []struct{ title, key string }{
		{"About Wata", ""},
		{"Settings…", ","}, // ⌘, — where every mac user looks
		{"Sign Out…", ""},
		{"Hide Wata", "h"},
		{"Quit Wata", "q"}, // the whole reason ⌘Q does anything
	} {
		r, ok := find(app, want.title)
		if !ok {
			t.Errorf("the app menu has no %q", want.title)
			continue
		}
		if r.key != want.key {
			t.Errorf("%q key equivalent = %q, want %q", want.title, r.key, want.key)
		}
	}

	// Quit must reach NSApp's own terminate: through the responder chain — a
	// target of our own here would mean ⌘Q did nothing.
	if q, _ := find(app, "Quit Wata"); q.target != 0 {
		t.Error("Quit has an explicit target; it must go down the responder chain")
	}
	if q, _ := find(app, "Quit Wata"); q.action != objc.RegisterName("terminate:") {
		t.Error("Quit's action is not terminate:")
	}
	// ...and ours must NOT: nothing in the chain answers wataSignOut:.
	for _, title := range []string{"Settings…", "Sign Out…"} {
		r, _ := find(app, title)
		if r.target != menuTarget {
			t.Errorf("%q does not target the menu target; it would be a no-op", title)
		}
	}

	// Edit exists for the login sheet: without ⌘V a password manager is
	// useless, which is most of why anyone would have a strong password.
	edit := submenuRows(t, mainMenu, "Edit")
	for _, want := range []struct{ title, key string }{
		{"Cut", "x"}, {"Copy", "c"}, {"Paste", "v"}, {"Select All", "a"},
	} {
		r, ok := find(edit, want.title)
		if !ok {
			t.Errorf("the Edit menu has no %q", want.title)
			continue
		}
		if r.key != want.key {
			t.Errorf("Edit %q key = %q, want %q", want.title, r.key, want.key)
		}
		if r.target != 0 {
			t.Errorf("Edit %q must target nil so it reaches the focused field", want.title)
		}
	}

	if _, ok := find(submenuRows(t, mainMenu, "Window"), "Minimize"); !ok {
		t.Error("the Window menu has no Minimize")
	}
	// the same object install hands to setWindowsMenu:, so AppKit's own
	// window list lands in the menu the user sees
	if objcrt.GoString(windowMenu.Send(objc.RegisterName("title"))) != "Window" {
		t.Error("buildMainMenu's second answer is not the Window menu")
	}
}

func TestCommandQueue(t *testing.T) {
	for NextCommand() != "" { // drain whatever an earlier test left
	}
	pushCommand(CmdSignOut)
	if got := NextCommand(); got != "signout" {
		t.Fatalf("NextCommand = %q, want %q", got, CmdSignOut)
	}
	if got := NextCommand(); got != "" {
		t.Fatalf("an empty queue answered %q, not \"\"", got)
	}
	// The queue must never block a menu click, whatever the pump is doing.
	for i := 0; i < cmdQueueCap*4; i++ {
		pushCommand(CmdSignOut)
	}
}

// prefsText is every label the Settings window shows, joined.
func prefsText(t *testing.T, content appkit.NSView) string {
	t.Helper()
	subs := content.Subviews()
	var b strings.Builder
	for i := 0; i < int(subs.Count()); i++ {
		id := subs.ObjectAtIndex(uint(i))
		b.WriteString(objcrt.GoString(id.Send(objc.RegisterName("stringValue"))))
		b.WriteString("\n")
	}
	return b.String()
}

// prefsButton finds a button by its title: the window holds two (the
// walkie-talkie checkbox and Sign Out), so position is not an identity.
func prefsButton(t *testing.T, content appkit.NSView, title string) objc.ID {
	t.Helper()
	subs := content.Subviews()
	for i := 0; i < int(subs.Count()); i++ {
		id := subs.ObjectAtIndex(uint(i))
		if className(id) == "NSButton" &&
			objcrt.GoString(id.Send(objc.RegisterName("title"))) == title {
			return id
		}
	}
	t.Fatalf("the Settings window has no %q button", title)
	return 0
}

func TestPrefsWindow(t *testing.T) {
	registerMenuTarget()
	// the content view, not the window: NSWindow refuses to be instantiated
	// off the main thread, and every assertion below is about the content.
	box := appkit.NSView{ID: objc.ID(objc.GetClass("NSView")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Size: appkit.CGSize{Width: prefsW, Height: prefsH},
		})}

	// signed in: the window names the account, and Sign Out is live
	SetAccount("http://pi.local:8008", "alice")
	fillPrefsView(box)
	txt := prefsText(t, box)
	for _, want := range []string{"alice", "http://pi.local:8008", "Signed in as", "Server"} {
		if !strings.Contains(txt, want) {
			t.Errorf("the Settings window does not show %q; it shows:\n%s", want, txt)
		}
	}
	btn := prefsButton(t, box, "Sign Out…")
	if btn.Send(objc.RegisterName("target")) != menuTarget {
		t.Error("the Sign Out button does not target the menu target")
	}
	if int(btn.Send(objc.RegisterName("isEnabled"))) == 0 {
		t.Error("Sign Out is disabled while signed in")
	}

	// signed out: no stale account text, and nothing to sign out OF
	SetAccount("", "")
	fillPrefsView(box)
	txt = prefsText(t, box)
	if strings.Contains(txt, "alice") {
		t.Errorf("the previous account is still on screen:\n%s", txt)
	}
	if !strings.Contains(txt, "not signed in") {
		t.Errorf("no signed-out state shown:\n%s", txt)
	}
	if int(prefsButton(t, box, "Sign Out…").Send(objc.RegisterName("isEnabled"))) != 0 {
		t.Error("Sign Out is enabled with nobody signed in")
	}

	// reopening must not stack a second copy of every label
	n1 := int(box.Subviews().Count())
	fillPrefsView(box)
	n2 := int(box.Subviews().Count())
	if n1 != n2 {
		t.Fatalf("refilling grew the content view from %d to %d subviews", n1, n2)
	}
}

// The walkie-talkie toggle (slice 4). The checkbox must SHOW the mode the
// session is in — a control that always draws the default silently lies about
// what a restart restored — and clicking it must report the new state onto the
// command queue rather than acting on it, because persisting the choice and
// changing what an arrival does belong to the session.
func TestNotifyToggle(t *testing.T) {
	registerMenuTarget()
	box := appkit.NSView{ID: objc.ID(objc.GetClass("NSView")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Size: appkit.CGSize{Width: prefsW, Height: prefsH},
		})}
	SetAccount("http://pi.local:8008", "alice")

	for _, tc := range []struct {
		play bool
		want int
	}{{false, 0}, {true, controlStateOn}} {
		SetNotifyPlay(tc.play)
		fillPrefsView(box)
		cb := prefsButton(t, box, "Play right away")
		if got := int(cb.Send(objc.RegisterName("state"))); got != tc.want {
			t.Errorf("SetNotifyPlay(%v): checkbox state = %d, want %d", tc.play, got, tc.want)
		}
		if cb.Send(objc.RegisterName("target")) != menuTarget {
			t.Error("the toggle does not target the menu target")
		}
		if objc.SEL(cb.Send(objc.RegisterName("action"))) != selNotifyCmd {
			t.Error("the toggle's action is not wataNotifyMode:")
		}
	}

	// clicking it: the action reads the control's NEW state, so drive it the
	// way AppKit does — set the state, then send the action.
	for NextCommand() != "" {
	}
	cb := prefsButton(t, box, "Play right away")
	for _, tc := range []struct {
		state int
		want  string
	}{{controlStateOn, CmdNotifyPlay}, {0, CmdNotifyQuiet}} {
		cb.Send(objc.RegisterName("setState:"), tc.state)
		menuTarget.Send(selNotifyCmd, cb)
		if got := NextCommand(); got != tc.want {
			t.Errorf("state %d queued %q, want %q", tc.state, got, tc.want)
		}
		// and the chrome now remembers it, so reopening shows what was clicked
		if notifyPlaying() != (tc.state == controlStateOn) {
			t.Errorf("state %d: notifyPlaying() = %v", tc.state, notifyPlaying())
		}
	}
}

// Frontmost is what decides whether an arrival banners at all, and the
// headless answer is a harness control rather than an AppKit question — there
// is no NSApplication to ask.
func TestFrontmostHeadless(t *testing.T) {
	mu.Lock()
	saved := headless
	headless = true
	mu.Unlock()
	defer func() { mu.Lock(); headless = saved; mu.Unlock() }()

	SetFrontmost(true)
	if !Frontmost() {
		t.Error("SetFrontmost(true) did not take")
	}
	SetFrontmost(false)
	if Frontmost() {
		t.Error("SetFrontmost(false) did not take")
	}
	// And neither notifications nor the Dock tile may touch AppKit here: no
	// bundle and no NSApplication, so both must decline rather than crash.
	if NotifyAvailable() {
		t.Error("NotifyAvailable() is true with no bundle")
	}
	if got := Notify("Bob", "sent you a voice message"); got == "" {
		t.Error("Notify() claimed to have posted a banner with no bundle")
	}
	SetBadge(3)
	SetBadge(0)
}
