// APNs on the client side (plan 0065 tier 2): asking for permission,
// registering for remote notifications, and observing the pushes that come
// back. The server half — the registration endpoint and the fan-out — is
// wata-server's push.scala; the pusher is go-pkgs/apns.
//
// Nothing here DECIDES anything. The delegate callbacks queue what iOS
// handed us and the Sgola pump drains the queue once per frame, exactly as
// it drains the keypad and the wata:// URLs — so the printing, the POST of
// the token to the server, and the "a tap opens that conversation" edge all
// live where the rest of the app's decisions live, on the pump's own
// goroutine. This file is the platform's side of the seam and nothing else.
//
// FOUR CALLBACKS, all installed on the SAME synthesized WataAppDelegate
// class (shell.go's Start registers them together — one class, so the
// UNUserNotificationCenter delegate and the UIApplication delegate are the
// same object, which is what Apple's own template does):
//
//	application:didRegisterForRemoteNotificationsWithDeviceToken:
//	  the token, as NSData. It goes up to the server as HEX, and it is
//	  re-issued (a restore, a reinstall, occasionally on its own), so the
//	  token is QUEUED rather than latched: TakeDeviceToken hands back every
//	  value the system ever issues, and a stale token means silence.
//	application:didFailToRegisterForRemoteNotificationsWithError:
//	  logged and survivable, never a wedge. A build with no aps-environment
//	  entitlement — every hand-bundled simulator build in this repo — lands
//	  here, and the app must go on being a working walkie-talkie.
//	userNotificationCenter:willPresentNotification:withCompletionHandler:
//	  a push arriving while the app is FOREGROUND. Answering
//	  banner|list|sound|badge is what makes it present at all: without the
//	  delegate iOS suppresses a foreground notification entirely.
//	userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:
//	  the TAP. Same queue, flagged, so the pump opens that conversation.
//
// Each completion handler is an incoming block, copied (objcrt.CopyBlock)
// only so the invoke pointer is read through one code path; each is called
// and released inside its own callback, because both of these are called
// once by the framework's own contract and holding one longer would stall
// the notification pipeline.
//
// Retention: the notification objects are the framework's, read and dropped
// inside the callback — only Go strings escape.

//go:build darwin

package iosshell

import (
	"encoding/hex"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/usernotifications"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/ebitengine/purego/objc"
)

var (
	selDidRegisterToken = objc.RegisterName(
		"application:didRegisterForRemoteNotificationsWithDeviceToken:")
	selDidFailRegister = objc.RegisterName(
		"application:didFailToRegisterForRemoteNotificationsWithError:")
	selWillPresent = objc.RegisterName(
		"userNotificationCenter:willPresentNotification:withCompletionHandler:")
	selDidReceiveResponse = objc.RegisterName(
		"userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:")
	selObjectForKey = objc.RegisterName("objectForKey:")

	// the app delegate instance, remembered at launch so RegisterForPush can
	// make it the notification centre's delegate too.
	delegateSelf objc.ID

	// every device token the system has issued and the app has not taken yet
	// (hex), oldest first — see the re-issue note above.
	tokenQueue []string
	// what went wrong, for the pump to print: a registration failure, or a
	// denied authorization. Taken and cleared.
	pushNotes []string
	// observed pushes, oldest first.
	pushQueue []pushEvent
	// the one TakePush last handed out — what PushRoom and PushTapped answer
	// for. Only the pump drains, so "the last taken" is unambiguous.
	pushCur pushEvent
)

// pushEvent is one notification the app saw: what it was, and the room and
// event ids wata's own payload keys carry (apns.Payload).
type pushEvent struct {
	kind    string // "present" (foreground) or "tap"
	roomID  string
	eventID string
}

// pushMethods are the delegate methods shell.go's Start installs alongside
// the launch and openURL ones. Kept here so the whole APNs surface is one
// file.
func pushMethods() []objc.MethodDef {
	return []objc.MethodDef{
		{Cmd: selDidRegisterToken, Fn: func(self objc.ID, _ objc.SEL, app, token objc.ID) {
			hexTok := hex.EncodeToString(objcrt.GoBytes(token))
			mu.Lock()
			tokenQueue = append(tokenQueue, hexTok)
			mu.Unlock()
		}},
		{Cmd: selDidFailRegister, Fn: func(self objc.ID, _ objc.SEL, app, err objc.ID) {
			note := "remote registration failed"
			if e := objcrt.GoError(err); e != nil {
				note += ": " + e.Error()
			}
			mu.Lock()
			pushNotes = append(pushNotes, note)
			mu.Unlock()
		}},
		{Cmd: selWillPresent, Fn: func(self objc.ID, _ objc.SEL, center, note, done objc.ID) {
			queuePush("present", usernotifications.UNNotification{ID: note})
			// Present it: banner + list + sound + badge. A foreground push
			// iOS is not told to present is simply dropped.
			call(done, uint64(usernotifications.UNNotificationPresentationOptionBanner|
				usernotifications.UNNotificationPresentationOptionList|
				usernotifications.UNNotificationPresentationOptionSound|
				usernotifications.UNNotificationPresentationOptionBadge))
		}},
		{Cmd: selDidReceiveResponse, Fn: func(self objc.ID, _ objc.SEL, center, resp, done objc.ID) {
			queuePush("tap", usernotifications.UNNotificationResponse{ID: resp}.Notification())
			call(done)
		}},
	}
}

// call invokes an incoming completion block once and releases it.
func call(block objc.ID, args ...any) {
	h := objcrt.CopyBlock(objc.Block(block))
	if h == nil {
		return
	}
	h.Invoke(args...)
	h.Release()
}

// queuePush records one observed notification, reading wata's own payload
// keys off its userInfo. NSDictionary is opaque in the generated bindings
// (nothing of it is on the allowlist beyond the handle), so the two lookups
// go through a raw objectForKey: — the same shell-glue category as shell.go's
// UIViewController alloc/init.
func queuePush(kind string, n usernotifications.UNNotification) {
	pool := iosui.PoolPush()
	info := objc.ID(n.Request().Content().UserInfo().ID)
	ev := pushEvent{
		kind:    kind,
		roomID:  dictString(info, "room_id"),
		eventID: dictString(info, "event_id"),
	}
	iosui.PoolPop(pool)
	mu.Lock()
	pushQueue = append(pushQueue, ev)
	mu.Unlock()
}

func dictString(dict objc.ID, key string) string {
	if dict == 0 {
		return ""
	}
	return objcrt.GoString(dict.Send(selObjectForKey, objcrt.NSString(key)))
}

// RegisterForPush asks for notification permission, makes the app delegate
// the notification centre's delegate, and registers for remote
// notifications. Any thread — the UIKit work hops to the main queue.
//
// Called from the app's `ready` hop rather than from didFinishLaunching, so
// the interptest mode (which never becomes a client) raises no permission
// prompt. The authorization answer arrives asynchronously and is only
// RECORDED: registration does not wait on it, because the device token and
// the user's permission are independent — a denied user still registers, and
// the pushes simply do not display.
func RegisterForPush() {
	iosui.MainQueue().Async(func() {
		pool := iosui.PoolPush()
		defer iosui.PoolPop(pool)
		c := usernotifications.GetUNUserNotificationCenterClass().CurrentNotificationCenter()
		mu.Lock()
		self := delegateSelf
		mu.Unlock()
		if self != 0 {
			c.SetDelegate(self)
		}
		c.RequestAuthorizationWithOptionsCompletionHandler(
			usernotifications.UNAuthorizationOptionAlert|
				usernotifications.UNAuthorizationOptionBadge|
				usernotifications.UNAuthorizationOptionSound,
			func(granted bool, err error) {
				note := ""
				switch {
				case err != nil:
					note = "authorization failed: " + err.Error()
				case !granted:
					note = "notifications not permitted"
				default:
					note = "authorized"
				}
				mu.Lock()
				pushNotes = append(pushNotes, note)
				mu.Unlock()
			})
		uikit.GetUIApplicationClass().SharedApplication().RegisterForRemoteNotifications()
	})
}

// TakeDeviceToken pops the oldest device token the system has issued (hex),
// or "" when none is pending. Any-thread safe; the pump polls once per frame
// and re-registers with the server for every value it sees.
func TakeDeviceToken() string {
	mu.Lock()
	defer mu.Unlock()
	if len(tokenQueue) == 0 {
		return ""
	}
	t := tokenQueue[0]
	tokenQueue = tokenQueue[1:]
	return t
}

// TakePushNote pops the oldest thing that went wrong (or the authorization
// answer), or "" — what the pump prints so a phone that is silently
// unregistered says so in the log.
func TakePushNote() string {
	mu.Lock()
	defer mu.Unlock()
	if len(pushNotes) == 0 {
		return ""
	}
	n := pushNotes[0]
	pushNotes = pushNotes[1:]
	return n
}

// TakePush pops the oldest observed notification, described for the pump to
// print (`present room=… event=…`), or "" when none is pending. PushRoom and
// PushTapped answer for the one it just handed out.
func TakePush() string {
	mu.Lock()
	defer mu.Unlock()
	if len(pushQueue) == 0 {
		return ""
	}
	pushCur = pushQueue[0]
	pushQueue = pushQueue[1:]
	return pushCur.kind + " room=" + pushCur.roomID + " event=" + pushCur.eventID
}

// PushRoom is the room id of the push TakePush last returned ("" if that
// payload carried none).
func PushRoom() string {
	mu.Lock()
	defer mu.Unlock()
	return pushCur.roomID
}

// PushTapped reports whether the push TakePush last returned was a TAP — the
// one that should open its conversation.
func PushTapped() bool {
	mu.Lock()
	defer mu.Unlock()
	return pushCur.kind == "tap"
}
