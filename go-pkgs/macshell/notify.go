// Arrival notifications and the Dock badge (plan 0037, slice 4).
//
// The DECISION — is this arrival worth announcing, who is it from, what does
// the badge read — belongs to the Sgola side (wataclient's Notify), which
// both clients share. This file is only the macOS presentation of it: a
// UNUserNotificationCenter banner, a Dock tile badge, and the one fact the
// decision needs from AppKit — whether the app is frontmost.
//
// BOTH ARE GATED ON A BUNDLE. UNUserNotificationCenter reads the running
// process's bundle proxy on its very first call and raises
// NSInternalInconsistencyException when there is none, so an unbundled build
// (`just mac-build`, and every headless harness) must never touch it —
// `bundleID() == ""` is the gate, checked before the class is even looked up.
// The Dock tile is the same story for a different reason: headless brings up
// no NSApplication at all, and asking for `sharedApplication` there would
// create one behind the runloop's back. So both calls answer a REASON string
// instead of doing nothing silently, and the pump logs it.
//
// The bindings are generated, not raw objc.Send: `usernotifications` is a
// bindgen target of its own and NSDockTile/NSBundle joined `appkit`
// (bindgen.json). The risk plan 0037 named did not bite — the two calls that
// take blocks (requestAuthorizationWithOptions:completionHandler: and
// addNotificationRequest:withCompletionHandler:) are OUTGOING blocks, which
// the emitter has always mapped (objc.NewBlock); the refused shape is a
// struct or CGFloat RETURN from a callback, and neither of these returns
// anything.

//go:build darwin

package macshell

import (
	"strconv"
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/usernotifications"
)

var (
	notifyMu sync.Mutex
	// what the checkbox in Settings shows and what the pump last set.
	notifyPlay bool
	// headless: what Frontmost() answers, so a harness can drive both sides
	// of the "only when we are not frontmost" rule. Windowed, [NSApp isActive]
	// is the answer and this is ignored.
	frontTest bool
	// the last badge label pushed, so an unchanged count costs no main-queue
	// turn (the pump reads the count every frame).
	badgeNow = -1
	// authorization is asked for once, and its answer only ever logged: a
	// denial is the user's decision, not an error to retry every arrival.
	authOnce  sync.Once
	authNote  string
	postNote  string
	notifySeq int
)

// bundleID is the gate for everything in this file. "" for a bare binary run
// out of .sgo — which is what every harness and `just mac` do.
func bundleID() string {
	return appkit.GetNSBundleClass().MainBundle().BundleIdentifier()
}

// NotifyAvailable reports whether a banner can be posted at all: a windowed
// run inside a bundle. Exposed so the Sgola side can say so once at startup
// rather than once per arrival.
func NotifyAvailable() bool {
	mu.Lock()
	hl := headless
	mu.Unlock()
	return !hl && bundleID() != ""
}

// RequestNotifyAuth asks for alert+badge permission, once. Called from Start;
// the answer arrives on a system queue and is only recorded — the first
// banner is posted whether or not it has landed, which is what Apple's own
// apps do and what makes a launch-and-immediately-receive run behave.
func RequestNotifyAuth() {
	if !NotifyAvailable() {
		return
	}
	authOnce.Do(func() {
		c := usernotifications.GetUNUserNotificationCenterClass().CurrentNotificationCenter()
		c.RequestAuthorizationWithOptionsCompletionHandler(
			usernotifications.UNAuthorizationOptionAlert|usernotifications.UNAuthorizationOptionBadge|
				usernotifications.UNAuthorizationOptionSound,
			func(granted bool, err error) {
				notifyMu.Lock()
				defer notifyMu.Unlock()
				switch {
				case err != nil:
					authNote = "authorization failed: " + err.Error()
				case !granted:
					authNote = "notifications not permitted"
				default:
					authNote = ""
				}
			})
	})
}

// Notify posts one banner. It answers the standing REASON banners are not
// appearing — no bundle, authorization denied, or the last post's error — or
// "" when nothing is known to be wrong. It cannot answer for this call: the
// completion handler is asynchronous. That is the right granularity anyway,
// because a client that silently stops announcing is indistinguishable from
// one with nothing to announce, and the standing cause is what a log needs.
func Notify(title, body string) string {
	if !NotifyAvailable() {
		return "no bundle"
	}
	notifyMu.Lock()
	notifySeq++
	id := "wata-" + strconv.Itoa(notifySeq)
	notifyMu.Unlock()

	content := usernotifications.UNMutableNotificationContent{
		ID: usernotifications.GetUNMutableNotificationContentClass().Alloc().ID}
	content.ID.Send(selInit)
	content.SetTitle(title)
	content.SetBody(body)
	// nil trigger = deliver now. UNNotificationTrigger is opaque here, which
	// is exactly enough to pass a nil one.
	req := usernotifications.GetUNNotificationRequestClass().RequestWithIdentifierContentTrigger(
		id, usernotifications.UNNotificationContent{ID: content.ID},
		usernotifications.UNNotificationTrigger{})
	usernotifications.GetUNUserNotificationCenterClass().CurrentNotificationCenter().
		AddNotificationRequestWithCompletionHandler(req, func(err error) {
			note := ""
			if err != nil {
				note = "post failed: " + err.Error()
			}
			// set, not appended: a post that succeeds CLEARS the note, so an
			// old failure cannot be reported for the rest of the session.
			notifyMu.Lock()
			postNote = note
			notifyMu.Unlock()
		})
	notifyMu.Lock()
	defer notifyMu.Unlock()
	if authNote != "" {
		return authNote
	}
	return postNote
}

// SetBadge puts the unplayed count on the Dock tile; 0 clears it. Runs on the
// main queue like every other AppKit mutation, and only when the number moved.
func SetBadge(n int) {
	mu.Lock()
	hl := headless
	mu.Unlock()
	notifyMu.Lock()
	if n == badgeNow {
		notifyMu.Unlock()
		return
	}
	badgeNow = n
	notifyMu.Unlock()
	if hl {
		return // no NSApplication to hang a tile off
	}
	label := ""
	if n > 0 {
		label = strconv.Itoa(n)
	}
	onStage(func() {
		appkit.GetNSApplicationClass().SharedApplication().DockTile().SetBadgeLabel(label)
	})
}

// Frontmost answers whether the user is looking at this app — the one thing
// that decides whether an arrival banners at all. Windowed it is
// [NSApp isActive], a plain accessor read from the pump goroutine (nothing is
// mutated, and hopping to the main queue could not return a value anyway);
// headless it is whatever SetFrontmost was last told, which is how the smoke
// drives both sides of the rule.
func Frontmost() bool {
	mu.Lock()
	hl := headless
	mu.Unlock()
	if hl {
		notifyMu.Lock()
		defer notifyMu.Unlock()
		return frontTest
	}
	return appkit.GetNSApplicationClass().SharedApplication().Active()
}

// SetFrontmost is the headless override. A no-op windowed: there, being
// frontmost is a fact about the user, not a setting.
func SetFrontmost(on bool) {
	notifyMu.Lock()
	frontTest = on
	notifyMu.Unlock()
}

// SetNotifyPlay tells the chrome which mode the session is in, so the
// Settings checkbox renders the stored answer rather than a default.
func SetNotifyPlay(on bool) {
	notifyMu.Lock()
	notifyPlay = on
	notifyMu.Unlock()
}

func notifyPlaying() bool {
	notifyMu.Lock()
	defer notifyMu.Unlock()
	return notifyPlay
}
