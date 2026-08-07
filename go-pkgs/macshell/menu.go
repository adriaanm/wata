// The menu bar (plan 0037, slice 3). Without one, a mac app has no About, no
// ⌘Q, no ⌘V — the window's two-step Back quit is a handset idiom nobody will
// guess, and a login sheet you cannot paste into defeats every password
// manager. Most of what this installs is therefore not "features" but the
// things macOS assumes are present.
//
// Almost every item targets NIL, which sends the action down the responder
// chain: `terminate:`, `hide:`, `paste:`, `performMiniaturize:` are all
// AppKit's own, and nil-targeting is how they reach whatever is focused —
// crucially, the Edit menu then works inside the login sheet's text fields
// without this package knowing they exist.
//
// The two items that are OURS — Preferences and Sign Out — cannot work that
// way: they belong to the Sgola session, which owns the credential stores and
// the client lifecycle. They push a string onto a command queue that the pump
// polls (`NextCommand`), the same shape as the key queue. A menu action must
// not block the main thread waiting for the pump, and this way it does not.
//
// NSMenu/NSMenuItem are not in the bindgen allowlist and are reached with raw
// objc.Send, like login.go. Every selector here takes an object or nothing —
// no struct returns, so no decomposed trampolines (bindgen.md).

//go:build darwin

package macshell

import (
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

// Commands the chrome hands the session. Strings, because the facade is
// strings — and because a command that means nothing to this package should
// not be an enum it has to keep in step.
const (
	CmdPrefs   = "prefs"
	CmdSignOut = "signout"
)

const cmdQueueCap = 8

var (
	cmds     = make(chan string, cmdQueueCap)
	menuOnce sync.Once
	// the one object menu items target; retained for the process's life
	menuTarget objc.ID

	selAlloc      = objc.RegisterName("alloc")
	selInit       = objc.RegisterName("init")
	selAddItem    = objc.RegisterName("addItem:")
	selSetSubmenu = objc.RegisterName("setSubmenu:")
	selSetTarget  = objc.RegisterName("setTarget:")
	selPrefsCmd   = objc.RegisterName("wataPrefs:")
	selSignOutCmd = objc.RegisterName("wataSignOut:")
)

func pushCommand(c string) {
	select {
	case cmds <- c:
	default: // a session that stopped polling is already ending; drop, not block
	}
}

// NextCommand pops one pending chrome command, or "" — never blocks. The pump
// calls it once a frame, exactly as it calls NextKey.
func NextCommand() string {
	select {
	case c := <-cmds:
		return c
	default:
		return ""
	}
}

func registerMenuTarget() {
	menuOnce.Do(func() {
		cls, err := objc.RegisterClass("WataMenuTarget", objc.GetClass("NSObject"), nil, nil,
			[]objc.MethodDef{
				{Cmd: selPrefsCmd, Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
					showPrefs()
				}},
				{Cmd: selSignOutCmd, Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
					closePrefs() // the window names an account that is about to go
					pushCommand(CmdSignOut)
				}},
			})
		if err != nil {
			panic("macshell: registering WataMenuTarget: " + err.Error())
		}
		menuTarget = objc.ID(cls).Send(selAlloc).Send(selInit)
		menuTarget.Send(objc.RegisterName("retain"))
	})
}

// newMenu is an NSMenu with a title (the title shows in the menu bar for
// every submenu except the app's, which macOS replaces with the app name).
func newMenu(title string) objc.ID {
	return objc.ID(objc.GetClass("NSMenu")).Send(selAlloc).
		Send(objc.RegisterName("initWithTitle:"), objcrt.NSString(title))
}

// item appends one item. `target` nil (0) sends the action down the responder
// chain; a non-nil target is ours. An empty selector name makes it disabled
// text, which nothing here wants, so `sel` is always real.
func item(menu objc.ID, title, sel, key string, target objc.ID) objc.ID {
	it := menu.Send(objc.RegisterName("addItemWithTitle:action:keyEquivalent:"),
		objcrt.NSString(title), objc.RegisterName(sel), objcrt.NSString(key))
	if target != 0 {
		it.Send(selSetTarget, target)
	}
	return it
}

func separator(menu objc.ID) {
	menu.Send(selAddItem,
		objc.ID(objc.GetClass("NSMenuItem")).Send(objc.RegisterName("separatorItem")))
}

// submenu hangs a titled menu off the main menu bar.
func submenu(mainMenu objc.ID, title string) objc.ID {
	holder := objc.ID(objc.GetClass("NSMenuItem")).Send(selAlloc).Send(selInit)
	mainMenu.Send(selAddItem, holder)
	m := newMenu(title)
	holder.Send(selSetSubmenu, m)
	return m
}

// installMenuBar builds the whole bar and hands it to NSApp. MAIN THREAD:
// AppKit throws on a setMainMenu: from anywhere else. Called from Start
// before the window comes up.
func installMenuBar(app objc.ID, appName string) {
	mainMenu, windowMenu := buildMainMenu(appName)
	app.Send(objc.RegisterName("setMainMenu:"), mainMenu)
	app.Send(objc.RegisterName("setWindowsMenu:"), windowMenu)
}

// buildMainMenu assembles the bar and answers (main menu, Window menu). Split
// from the install so it can be walked by a test — `setMainMenu:` is the one
// step that demands the main thread, and building is the part worth
// asserting.
func buildMainMenu(appName string) (objc.ID, objc.ID) {
	registerMenuTarget()
	mainMenu := newMenu("")

	// ---- the app menu -------------------------------------------------------
	appMenu := submenu(mainMenu, appName)
	item(appMenu, "About "+appName, "orderFrontStandardAboutPanel:", "", 0)
	separator(appMenu)
	// ⌘, is where every mac user looks for settings, so it is the ONE binding
	// here that has to be exactly right.
	item(appMenu, "Settings…", "wataPrefs:", ",", menuTarget)
	item(appMenu, "Sign Out…", "wataSignOut:", "", menuTarget)
	separator(appMenu)
	item(appMenu, "Hide "+appName, "hide:", "h", 0)
	hideOthers := item(appMenu, "Hide Others", "hideOtherApplications:", "h", 0)
	// ⌥⌘H, the standard: command is implied by a key equivalent, option is not
	hideOthers.Send(objc.RegisterName("setKeyEquivalentModifierMask:"),
		uint(nsEventModifierFlagOption|nsEventModifierFlagCommand))
	item(appMenu, "Show All", "unhideAllApplications:", "", 0)
	separator(appMenu)
	item(appMenu, "Quit "+appName, "terminate:", "q", 0)

	// ---- Edit: the reason the login sheet can be pasted into ----------------
	editMenu := submenu(mainMenu, "Edit")
	item(editMenu, "Undo", "undo:", "z", 0)
	redo := item(editMenu, "Redo", "redo:", "z", 0)
	redo.Send(objc.RegisterName("setKeyEquivalentModifierMask:"),
		uint(nsEventModifierFlagShift|nsEventModifierFlagCommand))
	separator(editMenu)
	item(editMenu, "Cut", "cut:", "x", 0)
	item(editMenu, "Copy", "copy:", "c", 0)
	item(editMenu, "Paste", "paste:", "v", 0)
	item(editMenu, "Select All", "selectAll:", "a", 0)

	// ---- Window -------------------------------------------------------------
	windowMenu := submenu(mainMenu, "Window")
	item(windowMenu, "Minimize", "performMiniaturize:", "m", 0)
	item(windowMenu, "Zoom", "performZoom:", "", 0)
	separator(windowMenu)
	item(windowMenu, "Bring All to Front", "arrangeInFront:", "", 0)

	return mainMenu, windowMenu
}

// NSEventModifierFlags, the bits this file needs.
const (
	nsEventModifierFlagShift   = 1 << 17
	nsEventModifierFlagOption  = 1 << 19
	nsEventModifierFlagCommand = 1 << 20
)
