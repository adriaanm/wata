// The PushToTalk framework on the client side (plan 0065 tier 3): the channel
// manager the framework requires at launch, both delegates, join/leave against
// the family room, the per-join EPHEMERAL push token, and the system talk
// button (Dynamic Island, lock screen) driving the app's own record/send arc.
//
// This is tools/bindgen/hello/hellopt productized. The hello proved the whole
// arc on hardware (2026-08-17); what changes here is where the events GO:
// nothing in this file decides anything, exactly as push.go does not. Every
// callback appends a line to an event queue the Sgola pump drains once a frame
// (ptt.scala), which is where the POSTs to /_wata/v1/push/channel/{join,leave}
// and the record/send edges are decided — on the pump's own goroutine, with
// the rest of the app's decisions.
//
// TWO THINGS ARE NOT QUEUED, because both must be true the instant the
// framework says so and a 33 ms frame is too late:
//
//   - the AUDIO SESSION HANDOFF. The framework configures and activates the
//     session for a transmission and hands it over; macaudio must not activate
//     its own underneath (plan 0008 records that as incompatible). So
//     didActivate/didDeactivateAudioSession call macaudio.PTTSession* straight
//     from the callback. See go-pkgs/macaudio/session_ios.go.
//   - the INCOMING PUSH RESULT. incomingPushResultForChannelManager: is a
//     SYNCHRONOUS answer the system requires (a push it does not get an answer
//     for costs the app its channel), so the active speaker is read off the
//     payload and returned here.
//
// TALKING is a state, not an edge: recording may only start once the framework
// has BOTH begun the transmission and handed over an activated session, and
// the two callbacks arrive in either order. So the queue carries a derived
// `talk on <system|app>` / `talk off` pair rather than the raw callbacks, and
// the pump treats those as the PTT button's press and release.
//
// THE FRAMEWORK DOES NOT EXIST IN A SIMULATOR (tools/bindgen/hello/README.md).
// Everything here is therefore written to be inert when it is missing: dlopen
// failing, the class being absent, or the manager never arriving all end as a
// queued note and a `false` from PTTJoined, which keeps the on-screen PTT
// button on its direct path. That is also the state of a device build whose
// profile lacks the entitlement — the manager then fails to instantiate with
// `missingPushServerEnvironment`, which the log names.

//go:build darwin

package iosshell

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sync"

	pt "github.com/adriaanm/wata/go-pkgs/appleptt"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/adriaanm/wata/go-pkgs/macaudio"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// inPTTPool brackets a direct framework excursion in an autorelease pool —
// every alloc/init below is autoreleased by the framework's convention.
func inPTTPool(f func()) {
	p := iosui.PoolPush()
	defer iosui.PoolPop(p)
	f()
}

var (
	pttMu sync.Mutex
	// the channel manager, once the system has handed one over.
	pttManager pt.PTChannelManager
	// the joined channel's uuid string and the descriptor's name, remembered
	// for leave, transmit and the restoration delegate's descriptor.
	pttUUID string
	pttName string
	// is a channel joined right now? Drives the on-screen button's routing.
	pttJoined bool
	// the transmission state machine (see the header).
	pttTransmitting bool
	pttSessionOn    bool
	pttTalking      bool
	pttSource       string
	// every ephemeral push token the framework has minted (hex), oldest
	// first: one per JOIN, and the previous one is dead.
	pttTokens []string
	// the lines the pump drains and prints.
	pttEvents []string
	// foreground? A channel may only be joined from the foreground
	// (PTChannelError.appNotForeground), so the app tracks its own state
	// rather than asking UIApplication off the main thread.
	pttForeground bool

	pttStartOnce sync.Once
	pttReady     bool // the framework loaded and the class is there

	selAppDidBecomeActive = objc.RegisterName("applicationDidBecomeActive:")
	selAppWillResignAct   = objc.RegisterName("applicationWillResignActive:")
)

// pttMethods are the two lifecycle callbacks the join gate needs, installed on
// the same synthesized WataAppDelegate as the launch, URL and APNs ones.
func pttMethods() []objc.MethodDef {
	return []objc.MethodDef{
		{Cmd: selAppDidBecomeActive, Fn: func(self objc.ID, _ objc.SEL, app objc.ID) {
			pttMu.Lock()
			pttForeground = true
			pttMu.Unlock()
		}},
		{Cmd: selAppWillResignAct, Fn: func(self objc.ID, _ objc.SEL, app objc.ID) {
			pttMu.Lock()
			pttForeground = false
			pttMu.Unlock()
		}},
	}
}

func pttNote(format string, args ...any) {
	line := format
	if len(args) > 0 {
		line = fmt.Sprintf(format, args...)
	}
	pttMu.Lock()
	pttEvents = append(pttEvents, line)
	pttMu.Unlock()
}

// PTTStart loads PushToTalk and asks the system for a channel manager. The
// framework requires this at LAUNCH — without a manager the system tears down
// any channel the app holds and its ability to receive pushes — so the app
// calls it from the `ready` hop whether or not it will ever join.
//
// Any thread, once (a second call is a no-op: the delegate classes are ObjC
// classes and the runtime cannot unregister one). The manager arrives
// asynchronously in the completion handler; PTTJoin before then is a queued
// note, not a failure.
func PTTStart() {
	pttStartOnce.Do(pttStart)
}

func pttStart() {
	if _, err := purego.Dlopen(
		"/System/Library/Frameworks/PushToTalk.framework/PushToTalk",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		pttNote("unavailable: %s", err.Error())
		return
	}
	if pt.GetPTChannelManagerClass().Class == 0 {
		pttNote("unavailable: no PTChannelManager class")
		return
	}
	pttMu.Lock()
	pttReady = true
	pttMu.Unlock()

	delegate := pt.NewPTChannelManagerDelegate(pt.PTChannelManagerDelegate{
		ChannelManagerDidJoinChannelWithUUIDReason: func(
			_ pt.PTChannelManager, _ pt.NSUUID, reason pt.PTChannelJoinReason) {
			pttMu.Lock()
			pttJoined = true
			pttMu.Unlock()
			why := "request"
			if reason == pt.PTChannelJoinReasonChannelRestoration {
				why = "restoration"
			}
			pttNote("joined (%s)", why)
		},
		ChannelManagerDidLeaveChannelWithUUIDReason: func(
			_ pt.PTChannelManager, _ pt.NSUUID, _ pt.PTChannelLeaveReason) {
			pttMu.Lock()
			pttJoined = false
			pttTransmitting = false
			pttSessionOn = false
			talking := pttTalking
			pttTalking = false
			pttMu.Unlock()
			if talking {
				pttNote("talk off")
			}
			pttNote("left")
		},
		ChannelManagerFailedToJoinChannelWithUUIDError: func(
			_ pt.PTChannelManager, _ pt.NSUUID, err error) {
			pttNote("join failed: %s", errText(err))
		},
		ChannelManagerFailedToLeaveChannelWithUUIDError: func(
			_ pt.PTChannelManager, _ pt.NSUUID, err error) {
			pttNote("leave failed: %s", errText(err))
		},
		ChannelManagerChannelUUIDDidBeginTransmittingFromSource: func(
			_ pt.PTChannelManager, _ pt.NSUUID, source pt.PTChannelTransmitRequestSource) {
			src := "system"
			if source == pt.PTChannelTransmitRequestSourceDeveloperRequest {
				src = "app"
			}
			pttMu.Lock()
			pttTransmitting = true
			pttSource = src
			pttMu.Unlock()
			pttNote("transmit begin (%s)", src)
			pttMaybeTalkOn()
		},
		ChannelManagerChannelUUIDDidEndTransmittingFromSource: func(
			_ pt.PTChannelManager, _ pt.NSUUID, _ pt.PTChannelTransmitRequestSource) {
			pttMu.Lock()
			pttTransmitting = false
			talking := pttTalking
			pttTalking = false
			pttMu.Unlock()
			if talking {
				pttNote("talk off")
			}
			pttNote("transmit end")
		},
		ChannelManagerFailedToBeginTransmittingInChannelWithUUIDError: func(
			_ pt.PTChannelManager, _ pt.NSUUID, err error) {
			pttNote("transmit failed: %s", errText(err))
		},
		ChannelManagerFailedToStopTransmittingInChannelWithUUIDError: func(
			_ pt.PTChannelManager, _ pt.NSUUID, err error) {
			pttNote("transmit stop failed: %s", errText(err))
		},
		// The session handoff — straight through to macaudio, see the header.
		ChannelManagerDidActivateAudioSession: func(_ pt.PTChannelManager, _ pt.AVAudioSession) {
			macaudio.PTTSessionActivated()
			pttMu.Lock()
			pttSessionOn = true
			pttMu.Unlock()
			pttNote("audio session activated")
			pttMaybeTalkOn()
		},
		ChannelManagerDidDeactivateAudioSession: func(pt.PTChannelManager, pt.AVAudioSession) {
			pttMu.Lock()
			pttSessionOn = false
			pttMu.Unlock()
			macaudio.PTTSessionDeactivated()
			pttNote("audio session deactivated")
		},
		// One token per JOIN, dead after the leave: the pump POSTs it to
		// /_wata/v1/push/channel/join, which replaces whatever row the
		// server held for this device.
		ChannelManagerReceivedEphemeralPushToken: func(_ pt.PTChannelManager, token pt.NSData) {
			tok := hex.EncodeToString(objcrt.GoBytes(token.ID))
			pttMu.Lock()
			pttTokens = append(pttTokens, tok)
			pttMu.Unlock()
			pttNote("ephemeral token (%d hex chars)", len(tok))
		},
		// A `pushtotalk` push arriving for this channel. The answer is
		// synchronous and mandatory; the speaker's name is the server's
		// (apns.PTTPayload's activeSpeaker) and the system draws it.
		IncomingPushResultForChannelManagerChannelUUIDPushPayload: func(
			_ pt.PTChannelManager, _ pt.NSUUID, payload pt.NSDictionary) pt.PTPushResult {
			who := dictString(objc.ID(payload.ID), "activeSpeaker")
			if who == "" {
				who = "wata" // -initWithName: rejects an empty name
			}
			pttNote("incoming push (%s)", who)
			p := pt.GetPTParticipantClass().Alloc().InitWithNameImage(who, pt.UIImage{})
			return pt.GetPTPushResultClass().PushResultForActiveRemoteParticipant(p)
		},
	})

	restoration := pt.NewPTChannelRestorationDelegate(pt.PTChannelRestorationDelegate{
		// The framework keeps the channel across app termination and asks for
		// its descriptor again on the next launch. The name is whatever this
		// process last joined with, or a neutral one when it has not joined
		// yet — the pump re-joins with the real family name once it syncs.
		ChannelDescriptorForRestoredChannelUUID: func(u pt.NSUUID) pt.PTChannelDescriptor {
			pttMu.Lock()
			name := pttName
			if name == "" {
				name = "wata"
			}
			pttName = name
			pttUUID = u.UUIDString()
			pttMu.Unlock()
			pttNote("restoring the channel")
			return pt.GetPTChannelDescriptorClass().Alloc().InitWithNameImage(name, pt.UIImage{})
		},
	})

	pt.GetPTChannelManagerClass().ChannelManagerWithDelegateRestorationDelegateCompletionHandler(
		delegate, restoration, func(m pt.PTChannelManager, err error) {
			if err != nil {
				// error 3 (missingPushServerEnvironment) is the build with no
				// aps-environment entitlement; error 1 is a simulator.
				pttNote("no channel manager: %s", errText(err))
				return
			}
			pttMu.Lock()
			pttManager = m
			pttMu.Unlock()
			pttNote("channel manager ready")
		})
}

func errText(err error) string {
	if err == nil {
		return "<nil>"
	}
	return err.Error()
}

// pttMaybeTalkOn queues the `talk on` the pump turns into a PTT press, once
// the framework has both begun transmitting AND handed over an activated
// session — recording before that has no session to record on.
func pttMaybeTalkOn() {
	pttMu.Lock()
	on := pttTransmitting && pttSessionOn && !pttTalking
	if on {
		pttTalking = true
	}
	src := pttSource
	pttMu.Unlock()
	if on {
		pttNote("talk on %s", src)
	}
}

// PTTJoin joins (or re-joins) the one channel this app has: `name` is what the
// system UI shows (the family room's name) and `key` is what its UUID is
// derived from (the family room id), so every launch and every device lands on
// the same channel for the same family.
//
// Returns "" when the request went out, else why it did not — the caller
// retries on its own clock. Joining requires the FOREGROUND: no server can
// conscript a phone into a channel, and after a reboot or an explicit leave
// the user has to open the app once. That rule is the framework's; this
// function reports it rather than working around it.
func PTTJoin(name, key string) string {
	m, why := pttCurrent()
	if why != "" {
		return why
	}
	pttMu.Lock()
	fg := pttForeground
	joined := pttJoined
	pttMu.Unlock()
	if joined {
		return "already joined"
	}
	if !fg {
		return "not foreground"
	}
	u := pttChannelUUID(key)
	pttMu.Lock()
	pttUUID = u
	pttName = name
	pttMu.Unlock()
	var out string
	inPTTPool(func() {
		uuid := pt.GetNSUUIDClass().Alloc().InitWithUUIDString(u)
		if uuid.ID == 0 {
			out = "bad channel uuid"
			return
		}
		d := pt.GetPTChannelDescriptorClass().Alloc().InitWithNameImage(name, pt.UIImage{})
		m.RequestJoinChannelWithUUIDDescriptor(uuid, d)
	})
	return out
}

// PTTLeave ends the channel. The ephemeral token dies with it, which is why
// the pump POSTs /_wata/v1/push/channel/leave when the delegate confirms.
func PTTLeave() {
	m, why := pttCurrent()
	if why != "" {
		return
	}
	inPTTPool(func() {
		if u, ok := pttUUIDObj(); ok {
			m.LeaveChannelWithUUID(u)
		}
	})
}

// PTTTransmit requests the framework to begin/stop transmitting. The app's own
// PTT button routes through here whenever a channel is joined, so a press on
// screen and a press on the system talk button take the SAME path: the request
// comes back as the didBeginTransmitting/didActivateAudioSession pair, and
// only then does anything record.
func PTTTransmit(on bool) {
	m, why := pttCurrent()
	if why != "" {
		pttNote("transmit ignored: %s", why)
		return
	}
	inPTTPool(func() {
		u, ok := pttUUIDObj()
		if !ok {
			return
		}
		if on {
			m.RequestBeginTransmittingWithChannelUUID(u)
		} else {
			m.StopTransmittingWithChannelUUID(u)
		}
	})
}

// PTTJoined reports whether a channel is joined right now — what decides
// whether the on-screen PTT button goes through the framework or drives the
// audio thread directly (no channel, no system UI to keep in step).
func PTTJoined() bool {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttJoined
}

// TakePTTEvent pops the oldest thing the framework told the app, or "" — the
// pump prints each as `ptt: …` and acts on the `talk on|off` and `left` ones.
func TakePTTEvent() string {
	pttMu.Lock()
	defer pttMu.Unlock()
	if len(pttEvents) == 0 {
		return ""
	}
	e := pttEvents[0]
	pttEvents = pttEvents[1:]
	return e
}

// TakePTTToken pops the oldest ephemeral push token (hex), or "". One per
// join; the previous one is dead the moment a new one is minted, so the pump
// posts every value it sees and the server replaces the row wholesale.
func TakePTTToken() string {
	pttMu.Lock()
	defer pttMu.Unlock()
	if len(pttTokens) == 0 {
		return ""
	}
	t := pttTokens[0]
	pttTokens = pttTokens[1:]
	return t
}

// pttCurrent answers the manager, or why there is none.
func pttCurrent() (pt.PTChannelManager, string) {
	pttMu.Lock()
	defer pttMu.Unlock()
	if !pttReady {
		return pttManager, "no PushToTalk framework"
	}
	if pttManager.ID == 0 {
		return pttManager, "no channel manager yet"
	}
	return pttManager, ""
}

// pttUUIDObj mints the NSUUID of the channel this process last joined.
// Caller holds no lock; the string is stable once a join has been requested.
func pttUUIDObj() (pt.NSUUID, bool) {
	pttMu.Lock()
	s := pttUUID
	pttMu.Unlock()
	if s == "" {
		return pt.NSUUID{}, false
	}
	u := pt.GetNSUUIDClass().Alloc().InitWithUUIDString(s)
	return u, u.ID != 0
}

// pttChannelUUID derives the channel's UUID from a wata room id: SHA-256
// truncated to 16 bytes with RFC 4122's version and variant bits stamped in,
// i.e. a name-based UUID in the shape NSUUID accepts. Deterministic, so every
// device of a family joins the same channel and a restored channel matches.
func pttChannelUUID(key string) string {
	sum := sha256.Sum256([]byte("wata-ptt-channel:" + key))
	var b [16]byte
	copy(b[:], sum[:16])
	b[6] = (b[6] & 0x0f) | 0x50 // version 5-shaped
	b[8] = (b[8] & 0x3f) | 0x80 // RFC 4122 variant
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}
