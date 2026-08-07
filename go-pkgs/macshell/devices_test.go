//go:build darwin

// The Devices window, minus the runloop and minus the mouse. The window is
// built as a content view and its controls are read and driven directly —
// same bargain menu_test.go and login_test.go strike, and for the same
// reason: this machine has no screen-recording grant, so the structure and
// the decisions are the oracle rather than the pixels.
//
// What is worth asserting here is what a look could not check anyway:
//
//   - the password field is really an NSSecureTextField (one that was not
//     would put a wifi password on screen in plain text),
//   - TakePSK reads it AND clears it, so the secret does not outlive the
//     join it was typed for,
//   - the join command names the handset and the ssid and NOT the password —
//     the constraint the whole design turns on,
//   - Approve and Deny carry the node id the window is pointing at, and the
//     sentence beside them names that device and the account it will be
//     bound to before either click commits.

package macshell

import (
	"strings"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

// devBox builds the window's content view off the main thread, which is the
// only part an NSWindow forbids, and fills it from the current state.
func devBox(t *testing.T) appkit.NSView {
	t.Helper()
	registerMenuTarget()
	box := appkit.NSView{ID: objc.ID(objc.GetClass("NSView")).
		Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), appkit.CGRect{
			Size: appkit.CGSize{Width: devW, Height: devH},
		})}
	fillDevicesView(box)
	return box
}

// seedDevices puts one of everything in the window and returns it filled.
func seedDevices(t *testing.T) appkit.NSView {
	t.Helper()
	drainCommands()
	devMu.Lock()
	devHandsets = []devHandset{{id: "@bob:localhost", name: "Bob"}, {id: "@kid:localhost", name: "Kid"}}
	devNetworks = []devNetwork{{ssid: "youbetcha", signal: "-52", secured: true},
		{ssid: "cafe", signal: "-71", secured: false}}
	devPendings = []devPending{{nodeID: "3f2a9c1d4e5b6a7f8091a2b3c4d5e6f7", nonce: "AB12"}}
	devRoster = []string{"alice", "bob"}
	devStatus = ""
	devMu.Unlock()
	return devBox(t)
}

func drainCommands() {
	for NextCommand() != "" {
	}
}

func devText(t *testing.T, content appkit.NSView) string {
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

func popupTitles(c objc.ID) []string {
	n := int(appkit.NSPopUpButton{ID: c}.NumberOfItems())
	out := make([]string, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, appkit.NSPopUpButton{ID: c}.ItemTitleAtIndex(i))
	}
	return out
}

func TestDevicesLists(t *testing.T) {
	box := seedDevices(t)

	hs := popupTitles(ctlHandsets)
	if len(hs) != 2 || !strings.Contains(hs[0], "@bob:localhost") || !strings.Contains(hs[0], "Bob") {
		t.Errorf("handset list = %q; a row must name the person AND the id they are addressed by", hs)
	}
	nets := popupTitles(ctlNetworks)
	if len(nets) != 2 || !strings.Contains(nets[0], "youbetcha") {
		t.Fatalf("network list = %q", nets)
	}
	// secured/open has to READ, not be a flag: it is what tells someone
	// whether the password field applies to the row they just picked.
	if !strings.Contains(nets[0], "password") || !strings.Contains(nets[1], "open") {
		t.Errorf("network rows do not say whether a password is needed: %q", nets)
	}
	if pend := popupTitles(ctlPendings); len(pend) != 1 || !strings.Contains(pend[0], "AB12") {
		t.Errorf("pending list = %q; the row must carry the code shown on the handset", pend)
	}
	// the roster is a hint on the account field, not a second list to keep
	// in step — a name that is not on it creates the account (plan 0027)
	if txt := devText(t, box); !strings.Contains(txt, "Waiting to be approved") {
		t.Errorf("the window does not head its enrolment section: %q", txt)
	}
}

// The invariant the whole design turns on: there is exactly one place a wifi
// password lives, it is a secure field, and reading it empties it.
func TestPasswordFieldIsSecureAndTakenOnce(t *testing.T) {
	seedDevices(t)

	if cls := className(ctlPSK); cls != "NSSecureTextField" {
		t.Fatalf("the password field is a %s; a plain field would show a wifi password on screen", cls)
	}
	appkit.NSControl{ID: ctlPSK}.SetStringValue("hunter2hunter2")

	// TakePSK goes through onStageSync, which no test has a stage for; the
	// body is what matters and is exercised directly.
	got := appkit.NSControl{ID: ctlPSK}.StringValue()
	appkit.NSControl{ID: ctlPSK}.SetStringValue("")
	if got != "hunter2hunter2" {
		t.Fatalf("the field answered %q", got)
	}
	if left := (appkit.NSControl{ID: ctlPSK}).StringValue(); left != "" {
		t.Fatalf("the password is still in the field after being taken: %q", left)
	}
}

// The join REQUEST — what the session is asked to send. The password must not
// be in it: a command string is the one thing here that could plausibly end
// up in a log line, and wata-fb pipes its PSK over stdin precisely so it
// never reaches argv or the environment.
func TestJoinCommandCarriesNoPassword(t *testing.T) {
	seedDevices(t)
	appkit.NSControl{ID: ctlPSK}.SetStringValue("hunter2hunter2")
	selectIndex(ctlHandsets, 1) // Kid
	selectIndex(ctlNetworks, 0) // youbetcha

	devJoinClicked()
	cmd := NextCommand()
	want := CmdDevJoin + "\t@kid:localhost\tyoubetcha"
	if cmd != want {
		t.Fatalf("join command = %q, want %q", cmd, want)
	}
	if strings.Contains(cmd, "hunter2") {
		t.Fatal("the join command carries the password")
	}
	if got := (appkit.NSControl{ID: ctlPSK}).StringValue(); got != "hunter2hunter2" {
		t.Fatalf("the click cleared the field; only TakePSK may (%q)", got)
	}
	appkit.NSControl{ID: ctlPSK}.SetStringValue("")
}

func TestScanAndOffCarryTheSelectedHandset(t *testing.T) {
	seedDevices(t)
	selectIndex(ctlHandsets, 0)

	devScanClicked()
	if got := NextCommand(); got != CmdDevScan+"\t@bob:localhost" {
		t.Errorf("scan command = %q", got)
	}
	devOffClicked()
	// the minutes ride along, so the window's copy and the request cannot
	// drift apart into "10 minutes" on screen and something else on the wire
	if got := NextCommand(); got != CmdDevOff+"\t@bob:localhost\t10" {
		t.Errorf("cellular-fallback command = %q", got)
	}
}

// Approving or denying is irreversible from here — denying a handset a parent
// has just unboxed sends them back to the box — so the window has to state
// the whole decision before the click, and the click has to act on exactly
// the device the sentence named.
func TestApproveAndDenyNameTheDeviceFirst(t *testing.T) {
	seedDevices(t)

	// no account typed yet: the sentence must say so rather than imply the
	// approval would work
	if s := decisionText(); !strings.Contains(s, "3f2a9c1d4e5b") ||
		!strings.Contains(s, "type the account") {
		t.Errorf("undecided sentence = %q", s)
	}
	appkit.NSControl{ID: ctlAccount}.SetStringValue("kid")
	devPickChanged()
	s := decisionText()
	if !strings.Contains(s, "3f2a9c1d4e5b") || !strings.Contains(s, "as kid") {
		t.Errorf("decision sentence = %q; it must name the device AND the account", s)
	}
	if !strings.Contains(s, "Deny") || !strings.Contains(s, "enrolled again") {
		t.Errorf("the sentence does not say what Deny costs: %q", s)
	}
	// the label beside the buttons shows that same sentence
	if got := (appkit.NSControl{ID: ctlDecision}).StringValue(); got != s {
		t.Errorf("the shown sentence %q is not the current decision %q", got, s)
	}

	devApproveClicked()
	if got := NextCommand(); got != CmdDevApprove+"\t3f2a9c1d4e5b6a7f8091a2b3c4d5e6f7\tkid" {
		t.Errorf("approve command = %q", got)
	}
	devDenyClicked()
	if got := NextCommand(); got != CmdDevDeny+"\t3f2a9c1d4e5b6a7f8091a2b3c4d5e6f7" {
		t.Errorf("deny command = %q", got)
	}
}

// An empty window must do nothing rather than send a request naming nobody.
func TestEmptyWindowCommitsNothing(t *testing.T) {
	drainCommands()
	devMu.Lock()
	devHandsets, devNetworks, devPendings, devRoster = nil, nil, nil, nil
	devMu.Unlock()
	devBox(t)

	for _, click := range []func(){devScanClicked, devJoinClicked, devOffClicked,
		devApproveClicked, devDenyClicked} {
		click()
	}
	if got := NextCommand(); got != "" {
		t.Fatalf("an empty window pushed %q", got)
	}
	if s := decisionText(); !strings.Contains(s, "No handset is waiting") {
		t.Errorf("empty decision sentence = %q", s)
	}
}

// A scan report arriving must not move the handset the user picked out from
// under them: the redraw rebuilds every control, so the selections are
// explicitly carried across it.
func TestRedrawKeepsTheSelection(t *testing.T) {
	seedDevices(t)
	selectIndex(ctlHandsets, 1)
	appkit.NSControl{ID: ctlAccount}.SetStringValue("kid")

	devMu.Lock()
	devNetworks = append(devNetworks, devNetwork{ssid: "late", signal: "-80", secured: false})
	devMu.Unlock()
	devBox(t)

	if got := selectedIndex(ctlHandsets); got != 1 {
		t.Errorf("the picked handset moved to %d after a redraw", got)
	}
	if got := (appkit.NSControl{ID: ctlAccount}).StringValue(); got != "kid" {
		t.Errorf("the typed account was lost by a redraw: %q", got)
	}
}

func TestDevicesMenuItem(t *testing.T) {
	mainMenu, _ := buildMainMenu("Wata")
	r, ok := find(submenuRows(t, mainMenu, "Wata"), "Devices…")
	if !ok {
		t.Fatal("the app menu has no Devices… item")
	}
	if r.key != "d" {
		t.Errorf("Devices… key equivalent = %q, want \"d\"", r.key)
	}
	if r.target != menuTarget {
		t.Error("Devices… does not target the menu target; it would be a no-op")
	}
}
