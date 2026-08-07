// The Devices window (plan 0037, slice 5): the admin surface wata-tui has
// always had, as something a parent can click. Pick a handset, look at what
// its radio can see, hand it a network and a password, drop its wifi to prove
// the cellular fallback works, and approve or deny a handset that has just
// announced itself.
//
// This file is PRESENTATION ONLY. Every one of those is a request the session
// already knows how to make — the device-command mailbox and the admin
// enrolment API — so the buttons push a command string onto the same queue
// the menu items use (menu.go's `NextCommand`) and the session does the work
// on its own goroutine. A button that blocked on a wifi scan would freeze the
// stage for the sixty seconds a handset may take to answer.
//
// THE PASSWORD IS THE EXCEPTION, and deliberately so. It never travels on the
// command queue, never appears in a command string, and is never logged: it
// lives in an NSSecureTextField and nowhere else, and the session collects it
// with TakePSK, which reads it and clears the field in the same breath. That
// mirrors wata-fb's own join helper, which passes the PSK over stdin because
// argv and the environment are world-readable (wata-fb/netexec.scala); a
// queue string that some future log line prints would undo the same
// reasoning.
//
// APPROVE AND DENY ARE IRREVERSIBLE from the user's side — denying a handset
// a parent has just unboxed sends them back to the box — so the window states
// the whole decision as a sentence before the click commits: which device,
// which account it will be bound to, or that denying drops it.
//
// LISTS ARE NSPopUpButtons, not NSTableViews. A table needs a data source
// whose delegate methods answer rows, and the generator refuses a good part
// of NSTableView besides; a popup is a native list you pick one thing out of,
// which is exactly what all three lists here are for. It also reads back
// trivially (`ItemTitles`, `IndexOfSelectedItem`), which is what makes this
// window assertable with no mouse and no screen-recording grant.
//
// NSButton and NSPopUpButton ARE generated (bindgen.md); NSSecureTextField
// cannot be — it declares no members of its own, so the generator has nothing
// to emit. Its class object is fetched raw and every message to it goes
// through the generated NSTextField/NSControl wrappers.

//go:build darwin

package macshell

import (
	"strconv"
	"strings"
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/ebitengine/purego/objc"
)

// The commands this window hands the session. Fields are tab-separated, like
// the login sheet's answer, so the facade stays strings-only.
const (
	CmdDevScan    = "dev:scan"    // \t<userId>
	CmdDevJoin    = "dev:join"    // \t<userId>\t<ssid>   — the PSK is NOT here
	CmdDevOff     = "dev:off"     // \t<userId>\t<minutes>
	CmdDevPending = "dev:pending" // refresh the enrolment list
	CmdDevApprove = "dev:approve" // \t<nodeId>\t<account>
	CmdDevDeny    = "dev:deny"    // \t<nodeId>
)

// how long `wifi off` drops the radio for. The device clamps and always
// auto-restores; ten minutes is wata-tui's default and long enough to watch a
// message go out over cellular.
const devOffMinutes = 10

const (
	devW       = 560.0
	devH       = 396.0
	devMargin  = 20.0
	devRowH    = 24.0
	devPopupH  = 26.0
	devBtnH    = 28.0
	devHeadGap = 6.0
)

type devHandset struct{ id, name string }
type devNetwork struct {
	ssid    string
	signal  string
	secured bool
}
type devPending struct{ nodeID, nonce string }

var (
	devMu       sync.Mutex
	devWin      objc.ID // 0 until first opened; reused after
	devContent  objc.ID // the filled content view (headless: a bare one)
	devHandsets []devHandset
	devNetworks []devNetwork
	devPendings []devPending
	devRoster   []string
	devStatus   string

	// the controls the session and the harness read back; retained, because a
	// factory-made control is autoreleased and this package outlives the pool
	ctlHandsets objc.ID
	ctlNetworks objc.ID
	ctlPendings objc.ID
	ctlAccount  objc.ID
	ctlPSK      objc.ID
	ctlDecision objc.ID

	selDevScan    = objc.RegisterName("wataDevScan:")
	selDevJoin    = objc.RegisterName("wataDevJoin:")
	selDevOff     = objc.RegisterName("wataDevOff:")
	selDevApprove = objc.RegisterName("wataDevApprove:")
	selDevDeny    = objc.RegisterName("wataDevDeny:")
	selDevRefresh = objc.RegisterName("wataDevRefresh:")
	selDevices    = objc.RegisterName("wataDevices:")
	selDevPick    = objc.RegisterName("wataDevPick:")
)

// ---- what the session tells the window ------------------------------------
//
// Each setter takes newline-separated rows of tab-separated fields — the same
// primitive-only discipline as the rest of the facade — and redraws the
// window if it is open. The session owns all of this state; the chrome keeps
// only enough to draw it.

// SetHandsets lists the accounts whose handsets can be administered:
// `<userId>\t<display name>` per line.
func SetHandsets(tsv string) {
	rows := make([]devHandset, 0, 8)
	for _, ln := range splitRows(tsv) {
		f := strings.Split(ln, "\t")
		rows = append(rows, devHandset{id: f[0], name: field(f, 1)})
	}
	devMu.Lock()
	devHandsets = rows
	devMu.Unlock()
	redrawDevices()
}

// SetNetworks is one handset's scan report: `<ssid>\t<signal dBm>\t<0|1>`.
func SetNetworks(tsv string) {
	rows := make([]devNetwork, 0, 8)
	for _, ln := range splitRows(tsv) {
		f := strings.Split(ln, "\t")
		rows = append(rows, devNetwork{ssid: f[0], signal: field(f, 1), secured: field(f, 2) == "1"})
	}
	devMu.Lock()
	devNetworks = rows
	devMu.Unlock()
	redrawDevices()
}

// SetPending lists the handsets waiting for a verdict: `<nodeId>\t<code>`.
func SetPending(tsv string) {
	rows := make([]devPending, 0, 4)
	for _, ln := range splitRows(tsv) {
		f := strings.Split(ln, "\t")
		rows = append(rows, devPending{nodeID: f[0], nonce: field(f, 1)})
	}
	devMu.Lock()
	devPendings = rows
	devMu.Unlock()
	redrawDevices()
}

// SetRoster is the accounts an approval may bind to, one per line. A name
// that is not on it creates the account, which is what plan 0027 chose: a
// casually minted name is renameable, an interrupted onboarding is not.
func SetRoster(tsv string) {
	devMu.Lock()
	devRoster = splitRows(tsv)
	devMu.Unlock()
	redrawDevices()
}

// SetDevStatus is the one line under the buttons that says what just
// happened. The session writes an OUTCOME here — "joined youbetcha",
// "approved … — bound to kid" — never a verb, for the reason plan 0027's
// field follow-up records: a terse success reads as an error.
func SetDevStatus(s string) {
	devMu.Lock()
	devStatus = s
	devMu.Unlock()
	redrawDevices()
}

// TakePSK answers what was typed into the secure field and CLEARS it in the
// same call. It is the only way the password leaves this package, and the
// clear is why: a PSK that outlived the join it was typed for would sit in a
// live view for the rest of the session.
func TakePSK() string {
	var out string
	onStageSync(func() {
		devMu.Lock()
		f := ctlPSK
		devMu.Unlock()
		if f == 0 {
			return
		}
		out = appkit.NSControl{ID: f}.StringValue()
		appkit.NSControl{ID: f}.SetStringValue("")
	})
	return out
}

// ShowDevices opens the window (the facade's entry point and the menu item's).
func ShowDevices() { onStageSync(showDevices) }

func showDevices() {
	registerMenuTarget()
	devMu.Lock()
	hl := headless
	w := devWin
	devMu.Unlock()
	if hl {
		// No NSApplication and no main thread: an NSWindow cannot be
		// instantiated here at all. The content view can, and it is the whole
		// assertable surface, so headless builds exactly that.
		fillDevicesView(appkit.NSView{ID: devContentView()})
		return
	}
	if w == 0 {
		w = buildDevicesWindow()
		devMu.Lock()
		devWin = w
		devMu.Unlock()
	}
	fillDevicesView(appkit.NSView{ID: w.Send(objc.RegisterName("contentView"))})
	w.Send(objc.RegisterName("makeKeyAndOrderFront:"), objc.ID(0))
	objc.ID(objc.GetClass("NSApplication")).
		Send(objc.RegisterName("sharedApplication")).
		Send(objc.RegisterName("activateIgnoringOtherApps:"), true)
}

// devContentView is the headless stand-in for the window's content view: one
// bare NSView, made once and refilled, so a harness reads the same hierarchy
// a window would show.
func devContentView() objc.ID {
	devMu.Lock()
	v := devContent
	devMu.Unlock()
	if v == 0 {
		v = objc.ID(objc.GetClass("NSView")).Send(selAlloc).
			Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
				Size: appkit.CGSize{Width: devW, Height: devH}})
		v.Send(objc.RegisterName("retain"))
		devMu.Lock()
		devContent = v
		devMu.Unlock()
	}
	return v
}

// redrawDevices refills an OPEN window in place. A setter called before the
// window has ever been shown just records the state; the next open draws it.
func redrawDevices() {
	devMu.Lock()
	open := devWin != 0 || devContent != 0
	devMu.Unlock()
	if !open {
		return
	}
	onStageSync(func() {
		devMu.Lock()
		w, c := devWin, devContent
		devMu.Unlock()
		if w != 0 {
			fillDevicesView(appkit.NSView{ID: w.Send(objc.RegisterName("contentView"))})
		} else if c != 0 {
			fillDevicesView(appkit.NSView{ID: c})
		}
	})
}

func buildDevicesWindow() objc.ID {
	style := appkit.NSWindowStyleMaskTitled | appkit.NSWindowStyleMaskClosable
	w := appkit.NSWindow{ID: appkit.GetNSWindowClass().Alloc().ID}.
		InitWithContentRectStyleMaskBackingDefer(
			appkit.CGRect{Size: appkit.CGSize{Width: devW, Height: devH}},
			style, appkit.NSBackingStoreBuffered, false)
	w.SetTitle("Wata Devices")
	// closing must HIDE it: released, the next open would message freed memory
	w.ID.Send(objc.RegisterName("setReleasedWhenClosed:"), false)
	w.Center()
	return w.ID
}

func closeDevices() {
	devMu.Lock()
	w := devWin
	devMu.Unlock()
	if w != 0 {
		w.Send(objc.RegisterName("orderOut:"), objc.ID(0))
	}
}

// fillDevicesView rebuilds the content from the state the setters recorded.
// Rebuilding wholesale rather than mutating controls in place is the same
// bargain prefs.go strikes: this window changes rarely and keeping a dozen
// labels in step with a snapshot costs more than redrawing them.
//
// Taking the VIEW rather than the window is what lets a test — and the
// headless driver — have one at all: an NSWindow may only be instantiated on
// the main thread.
func fillDevicesView(content appkit.NSView) {
	devMu.Lock()
	handsets, nets, pend, roster, status := devHandsets, devNetworks, devPendings, devRoster, devStatus
	devMu.Unlock()

	// selections survive the redraw — a scan result arriving must not move the
	// handset the user picked out from under them
	keepH := selectedIndex(ctlHandsets)
	keepN := selectedIndex(ctlNetworks)
	keepP := selectedIndex(ctlPendings)
	keepAcct := ""
	if ctlAccount != 0 {
		keepAcct = appkit.NSControl{ID: ctlAccount}.StringValue()
	}

	subs := content.Subviews()
	for i := int(subs.Count()) - 1; i >= 0; i-- {
		appkit.NSView{ID: subs.ObjectAtIndex(uint(i))}.
			ID.Send(objc.RegisterName("removeFromSuperview"))
	}

	add := func(id objc.ID) { content.AddSubview(appkit.NSView{ID: id}) }
	y := devH - devMargin
	full := devW - devMargin*2

	// ---- the handset, and what can be done to it ---------------------------
	y -= devRowH
	add(heading("Handsets", devMargin, y, full))
	y -= devHeadGap + devPopupH
	ctlHandsets = newPopup(devMargin, y, 240)
	for _, h := range handsets {
		appkit.NSPopUpButton{ID: ctlHandsets}.AddItemWithTitle(handsetTitle(h))
	}
	selectIndex(ctlHandsets, keepH)
	add(ctlHandsets)
	add(pushButton("Scan Wi-Fi", devMargin+248, y-1, 120, selDevScan))
	add(pushButton("Test cellular", devMargin+376, y-1, 124, selDevOff))

	// ---- what the radio saw, and the network to join -----------------------
	y -= devRowH
	add(note("\"Test cellular\" drops the handset's wifi for "+
		strconv.Itoa(devOffMinutes)+" minutes so you can watch a message go out "+
		"over the mobile network. It comes back on its own.",
		devMargin, y-14, full, 28))

	y -= devRowH + devHeadGap
	add(heading("Networks", devMargin, y, full))
	y -= devHeadGap + devPopupH
	ctlNetworks = newPopup(devMargin, y, 300)
	for _, n := range nets {
		appkit.NSPopUpButton{ID: ctlNetworks}.AddItemWithTitle(networkTitle(n))
	}
	selectIndex(ctlNetworks, keepN)
	add(ctlNetworks)

	y -= devHeadGap + devRowH
	add(newLabel("Password", devMargin, y, 70, devRowH))
	// The one control in this window that holds a secret. NSSecureTextField
	// declares no members of its own, so it is fetched raw and driven through
	// the generated NSTextField/NSControl wrappers.
	ctlPSK = newTextField(devMargin+76, y, 224, devRowH, true)
	setPlaceholder(ctlPSK, "leave empty for an open network")
	add(ctlPSK)
	add(pushButton("Join", devMargin+308, y-2, 90, selDevJoin))

	// ---- the handsets asking to be let in ----------------------------------
	y -= devRowH + devHeadGap*2
	add(heading("Waiting to be approved", devMargin, y, full))
	y -= devHeadGap + devPopupH
	ctlPendings = newPopup(devMargin, y, 300)
	for _, p := range pend {
		appkit.NSPopUpButton{ID: ctlPendings}.AddItemWithTitle(pendingTitle(p))
	}
	selectIndex(ctlPendings, keepP)
	// A pick has to redraw the decision sentence, or it would describe the
	// device that WAS selected while the buttons act on the one that is.
	ctlPendings.Send(selSetTarget, menuTarget)
	ctlPendings.Send(objc.RegisterName("setAction:"), selDevPick)
	add(ctlPendings)
	add(pushButton("Refresh", devMargin+308, y-1, 90, selDevRefresh))

	y -= devHeadGap + devRowH
	add(newLabel("Account", devMargin, y, 70, devRowH))
	ctlAccount = newTextField(devMargin+76, y, 224, devRowH, false)
	setPlaceholder(ctlAccount, rosterHint(roster))
	appkit.NSControl{ID: ctlAccount}.SetStringValue(keepAcct)
	ctlAccount.Send(selSetTarget, menuTarget)
	ctlAccount.Send(objc.RegisterName("setAction:"), selDevPick)
	add(ctlAccount)

	y -= devHeadGap + devBtnH
	add(pushButton("Approve", devMargin, y, 100, selDevApprove))
	add(pushButton("Deny", devMargin+108, y, 100, selDevDeny))

	// The whole decision, spelled out, next to the two buttons that commit it.
	ctlDecision = note(decisionText(), devMargin+216, y-10, full-216, 40)
	add(ctlDecision)

	if status != "" {
		add(note(status, devMargin, devMargin-4, full, devRowH))
	}
}

// ---- the sentence the Approve/Deny buttons commit --------------------------

// decisionText names the device and the account BEFORE the click, because
// both verdicts are irreversible from here: an approval binds a handset to an
// account and lets it in, a denial drops the row and sends whoever is holding
// the handset back to the enrolment screen.
func decisionText() string {
	p, ok := selectedPending()
	if !ok {
		return "No handset is waiting."
	}
	who := "—"
	if ctlAccount != 0 {
		who = strings.TrimSpace(appkit.NSControl{ID: ctlAccount}.StringValue())
	}
	if who == "" || who == "—" {
		return "Approve lets " + shortNode(p.nodeID) + " in — type the account it " +
			"belongs to first. Deny drops it; the handset has to be enrolled again."
	}
	return "Approve lets " + shortNode(p.nodeID) + " in as " + who + ". Deny drops it; " +
		"the handset has to be enrolled again."
}

// shortNode is a node id a person can compare against a handset's screen: the
// enrolment screen shows the same leading digits.
func shortNode(id string) string {
	if len(id) > 12 {
		return id[:12] + "…"
	}
	return id
}

func rosterHint(roster []string) string {
	if len(roster) == 0 {
		return "a new name creates the account"
	}
	return strings.Join(roster, ", ")
}

// ---- the button actions ----------------------------------------------------
//
// Every one of these only reads controls and pushes a command; the session
// does the work. They are package functions rather than closures so the tests
// (and the headless driver) take the SAME path a click takes.

func devScanClicked() {
	if h, ok := selectedHandset(); ok {
		pushCommand(CmdDevScan + "\t" + h.id)
	}
}

func devOffClicked() {
	if h, ok := selectedHandset(); ok {
		pushCommand(CmdDevOff + "\t" + h.id + "\t" + strconv.Itoa(devOffMinutes))
	}
}

// devJoinClicked names the handset and the network. The PSK is deliberately
// absent: the session reads it with TakePSK, straight out of the secure
// field, so it never becomes part of a string anything might print.
func devJoinClicked() {
	h, okH := selectedHandset()
	n, okN := selectedNetwork()
	if okH && okN {
		pushCommand(CmdDevJoin + "\t" + h.id + "\t" + n.ssid)
	}
}

func devApproveClicked() {
	p, ok := selectedPending()
	if !ok {
		return
	}
	acct := ""
	if ctlAccount != 0 {
		acct = strings.TrimSpace(appkit.NSControl{ID: ctlAccount}.StringValue())
	}
	pushCommand(CmdDevApprove + "\t" + p.nodeID + "\t" + acct)
}

func devDenyClicked() {
	if p, ok := selectedPending(); ok {
		pushCommand(CmdDevDeny + "\t" + p.nodeID)
	}
}

func devRefreshClicked() { pushCommand(CmdDevPending) }

// devPickChanged redraws the decision sentence in place — the popup and the
// account field both point at it.
func devPickChanged() {
	if ctlDecision != 0 {
		appkit.NSControl{ID: ctlDecision}.SetStringValue(decisionText())
	}
}

// ---- reading the controls back ---------------------------------------------

func selectedIndex(c objc.ID) int {
	if c == 0 {
		return 0
	}
	return int(appkit.NSPopUpButton{ID: c}.IndexOfSelectedItem())
}

func selectIndex(c objc.ID, i int) {
	n := int(appkit.NSPopUpButton{ID: c}.NumberOfItems())
	if i >= 0 && i < n {
		appkit.NSPopUpButton{ID: c}.SelectItemAtIndex(int(i))
	}
}

func selectedHandset() (devHandset, bool) {
	devMu.Lock()
	rows := devHandsets
	devMu.Unlock()
	i := selectedIndex(ctlHandsets)
	if i < 0 || i >= len(rows) {
		return devHandset{}, false
	}
	return rows[i], true
}

func selectedNetwork() (devNetwork, bool) {
	devMu.Lock()
	rows := devNetworks
	devMu.Unlock()
	i := selectedIndex(ctlNetworks)
	if i < 0 || i >= len(rows) {
		return devNetwork{}, false
	}
	return rows[i], true
}

func selectedPending() (devPending, bool) {
	devMu.Lock()
	rows := devPendings
	devMu.Unlock()
	i := selectedIndex(ctlPendings)
	if i < 0 || i >= len(rows) {
		return devPending{}, false
	}
	return rows[i], true
}

// ---- driving it without a mouse --------------------------------------------
//
// The headless smoke's way in. Each of these is exactly what a click or a
// keystroke does and nothing more: DevClick calls the same function the
// button's action calls, so a test that passes here is a test of the real
// path rather than of a parallel one.

// DevSelect picks row `i` of "handset", "network" or "pending".
func DevSelect(kind string, i int) {
	onStageSync(func() {
		switch kind {
		case "handset":
			selectIndex(ctlHandsets, i)
		case "network":
			selectIndex(ctlNetworks, i)
		case "pending":
			selectIndex(ctlPendings, i)
		}
		devPickChanged()
	})
}

// DevType puts text in "psk" (the secure field) or "account". This is the
// only way a PSK gets in without a keyboard, and it goes to the SAME field a
// keyboard would reach — there is exactly one place a password lives.
func DevType(field, s string) {
	onStageSync(func() {
		switch field {
		case "psk":
			if ctlPSK != 0 {
				appkit.NSControl{ID: ctlPSK}.SetStringValue(s)
			}
		case "account":
			if ctlAccount != 0 {
				appkit.NSControl{ID: ctlAccount}.SetStringValue(s)
			}
		}
		devPickChanged()
	})
}

// DevClick presses "scan", "join", "off", "approve", "deny" or "refresh".
func DevClick(name string) {
	onStageSync(func() {
		switch name {
		case "scan":
			devScanClicked()
		case "join":
			devJoinClicked()
		case "off":
			devOffClicked()
		case "approve":
			devApproveClicked()
		case "deny":
			devDenyClicked()
		case "refresh":
			devRefreshClicked()
		}
	})
}

// DevDecision is the sentence shown beside Approve and Deny — what the user
// is told before an irreversible click, and therefore worth asserting.
func DevDecision() string {
	var out string
	onStageSync(func() { out = decisionText() })
	return out
}

// ---- small AppKit helpers --------------------------------------------------

// pushButton is a standard rounded push button, built with -alloc plus
// -initWithFrame:.
//
// NOT with +[NSButton buttonWithTitle:target:action:], which the generator
// emits and which BLOCKS FOREVER off the main thread: the convenience
// factories configure the control through the appearance machinery, and that
// waits on the main runloop, which headless does not have and a test
// goroutine is not on. -initWithFrame: does not. `setBezelStyle:` is a raw
// send because NSBezelStyle has no Go mapping (bindgen.md), which is the same
// place login.go and prefs.go ended up.
func pushButton(title string, x, y, w float64, action objc.SEL) objc.ID {
	b := objc.ID(objc.GetClass("NSButton")).Send(selAlloc).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Origin: appkit.CGPoint{X: x, Y: y},
			Size:   appkit.CGSize{Width: w, Height: devBtnH},
		})
	btn := appkit.NSButton{ID: b}
	btn.SetTitle(title)
	b.Send(objc.RegisterName("setBezelStyle:"), nsBezelStyleRounded)
	b.Send(selSetTarget, menuTarget)
	b.Send(objc.RegisterName("setAction:"), action)
	return b
}

func newPopup(x, y, w float64) objc.ID {
	p := appkit.NSPopUpButton{ID: objc.ID(objc.GetClass("NSPopUpButton")).Send(selAlloc)}.
		InitWithFramePullsDown(appkit.CGRect{
			Origin: appkit.CGPoint{X: x, Y: y},
			Size:   appkit.CGSize{Width: w, Height: devPopupH},
		}, false)
	return p.ID
}

func heading(text string, x, y, w float64) objc.ID {
	id := newLabel(text, x, y, w, devRowH)
	id.Send(objc.RegisterName("setFont:"),
		objc.ID(objc.GetClass("NSFont")).Send(objc.RegisterName("boldSystemFontOfSize:"), 13.0))
	return id
}

// note is small wrapped explanatory text — laid out by its own frame, not on
// a row ladder, because three wrapped 10pt lines are not one 24pt row.
func note(text string, x, y, w, h float64) objc.ID {
	id := newLabel(text, x, y, w, h)
	id.Send(objc.RegisterName("setFont:"),
		objc.ID(objc.GetClass("NSFont")).Send(objc.RegisterName("systemFontOfSize:"), 10.0))
	id.Send(objc.RegisterName("setUsesSingleLineMode:"), false)
	id.Send(objc.RegisterName("cell")).Send(objc.RegisterName("setWraps:"), true)
	return id
}

func handsetTitle(h devHandset) string {
	if h.name == "" || h.name == h.id {
		return h.id
	}
	return h.name + "  (" + h.id + ")"
}

func networkTitle(n devNetwork) string {
	out := n.ssid
	if n.signal != "" {
		out += "   " + n.signal + " dBm"
	}
	if n.secured {
		return out + "   needs a password"
	}
	return out + "   open"
}

func pendingTitle(p devPending) string {
	if p.nonce == "" {
		return shortNode(p.nodeID)
	}
	return shortNode(p.nodeID) + "   code " + p.nonce
}

func splitRows(tsv string) []string {
	out := make([]string, 0, 8)
	for _, ln := range strings.Split(tsv, "\n") {
		if strings.TrimSpace(ln) != "" {
			out = append(out, ln)
		}
	}
	return out
}

func field(f []string, i int) string {
	if i < len(f) {
		return f[i]
	}
	return ""
}
