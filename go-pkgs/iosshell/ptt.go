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
//     payload and returned here. The room and event the push names ARE queued
//     — fetching and playing that message is the pump's job (ptt.scala).
//
// JOINING HANDS THE AUDIO SESSION OVER FOR GOOD, not just for an episode: a
// joined app's own -setActive: is refused by iOS with 'ent?' (missing
// entitlement). So the join and leave delegates tell macaudio directly — a
// restoration join lands before the audio thread has even started, and macaudio
// has to know before it builds its engine.
//
// AN EPISODE IS ONE RAISED SPEAKER, NOT ONE PUSH, AND IT ENDS WHEN THE APP SAYS
// SO. Reporting an active remote participant is what makes the system show
// "<name> speaking" and hold the audio session activated, and the framework
// holds both until the app clears the participant again (PTTSpeakerStopped).
// Nothing times out on its own: on hardware 2026-08-18 five incoming pushes
// produced five `audio session activated` and not one `deactivated`, because
// this file never cleared the speaker.
//
// A push arriving while that speaker is still up therefore JOINS the episode —
// no second speaker, no second activation — which is why pttEpisodeSeq, not
// pttPushSeq, is the identity the app ends things by, and why PTTPlayReady
// asks about the episode's handover rather than a per-push one. Four messages
// in a row is what a walkie-talkie does; on hardware 2026-08-18 the per-push
// version of both answers played the first and gave up on the other three.
// So the receive half owns the whole burst — answer every push, play them in
// order on the one session, then clear the speaker — and macaudio is told the
// episode is over at the same moment, so its ownership flag cannot latch on a
// callback that may never arrive (go-pkgs/macaudio/session_ios.go).
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
	"strconv"
	"sync"
	"time"

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
	// the messages incoming `pushtotalk` pushes named, oldest first, and the
	// one TakePTTPlay last handed out (only the pump drains, so "the last
	// taken" is unambiguous — push.go's pushCur idiom).
	pttPlays   []pttPlay
	pttPlayCur pttPlay
	// EPISODE IDENTITY. An EPISODE is one raised speaker, not one push: the
	// first push raises it, every push arriving while it is still up joins it,
	// and it comes down when the app says the whole burst is done. So
	// pttPushSeq only numbers pushes for the log, while pttEpisodeSeq is the
	// identity that matters — the id the app names when it ends an episode,
	// and the thing a stale end is checked against.
	//
	// pttEpisodeLive is what makes "joins it" expressible: it is true from the
	// push that raised the speaker until PTTSpeakerStopped clears it.
	// pttActivations counts didActivateAudioSession callbacks and
	// pttEpisodeGen is that count as the episode opened, which together answer
	// the question a rapid-fire arrival makes real: has THIS episode's session
	// handover landed? A push joining a live episode inherits the answer,
	// because the session it will play on is the one already under it and no
	// activation boundary is crossed.
	pttPushSeq     uint64
	pttEpisodeSeq  uint64
	pttEpisodeLive bool
	pttEpisodeGen  uint64
	pttActivations uint64
	// foreground? A channel may only be joined from the foreground
	// (PTChannelError.appNotForeground), so the app tracks its own state
	// rather than asking UIApplication off the main thread.
	pttForeground bool

	pttStartOnce sync.Once
	pttReady     bool // the framework loaded and the class is there

	selAppDidBecomeActive = objc.RegisterName("applicationDidBecomeActive:")
	selAppWillResignAct   = objc.RegisterName("applicationWillResignActive:")
)

// pttPlay is one message an incoming `pushtotalk` push named: the room and
// event ids off the payload (apns.PTTPayload's room_id/event_id) and WHEN the
// push arrived. The arrival time matters because the app may be suspended or
// cold when the push lands — the pump can start frames much later, and a
// message is only worth playing live inside the window the framework gives the
// woken app. The pump reads the age and decides; nothing here does.
type pttPlay struct {
	roomID  string
	eventID string
	at      time.Time
	// seq numbers the push (for the log); episode is the raised speaker it
	// belongs to, which is what the app names when it ends the episode. Every
	// push of one burst carries the SAME episode.
	seq     uint64
	episode uint64
}

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
	// macaudio cannot reach the channel manager (this package imports it, not
	// the other way round), so it asks for the episode a joined app needs
	// before it can make any sound through these hooks. Registered before the
	// framework is even loaded: a nil hook is a diagnosable refusal, and there
	// is no window in which one is missing for a manager that exists.
	macaudio.PTTBeginEpisode = pttBeginPlaybackEpisode
	macaudio.PTTEndEpisode = PTTSpeakerStopped
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
			// From here the framework, not the app, activates the audio
			// session — including on a RESTORATION join, which lands before
			// the audio thread has started. macaudio must know before it
			// builds its engine, so this is a direct call like the handoff.
			macaudio.PTTChannelJoined(true)
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
			pttEpisodeLive = false
			talking := pttTalking
			pttTalking = false
			pttMu.Unlock()
			if talking {
				pttNote("talk off")
			}
			// No channel, no episode: whatever the framework still held it is
			// not coming back through a delegate now, and self-activation is
			// the app's again.
			macaudio.PTTEpisodeEnded("the channel was left")
			macaudio.PTTChannelJoined(false)
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
			} else {
				// The transmission never became a press: the framework accepted
				// it but the session handover had not arrived by the time the
				// user let go. A brief tap on the system talk button that
				// LAUNCHES the app does this — the launch outlasts the press —
				// and it recorded nothing. Said plainly here because the
				// alternative is a log with a begin, an end, and no explanation
				// between them (device logs 2026-08-19, twice).
				pttNote("nothing was recorded: the transmission ended before the " +
					"audio session was handed over")
			}
			// The transmission is over as far as the framework is concerned,
			// so its hand-back is now owed; macaudio stops waiting for it
			// after a grace period rather than forever.
			macaudio.PTTEpisodeEnded("the transmission ended")
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
			pttActivations++
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
		//
		// Reporting a participant here is what activates the audio session and
		// puts the speaker on screen, and BOTH stay until the app clears the
		// participant again — so the same payload's room and event ids are
		// queued for the pump, which fetches that message, plays it on the
		// session the framework just handed over, and then calls
		// PTTSpeakerStopped to end the episode.
		IncomingPushResultForChannelManagerChannelUUIDPushPayload: func(
			_ pt.PTChannelManager, u pt.NSUUID, payload pt.NSDictionary) pt.PTPushResult {
			who := dictString(objc.ID(payload.ID), "activeSpeaker")
			if who == "" {
				who = "wata" // -initWithName: rejects an empty name
			}
			pttMu.Lock()
			pttPushSeq++
			// A speaker that is already up stays up and its session stays
			// activated, so this push JOINS that episode; only a push that
			// finds the speaker DOWN opens a new one, and only a new one has
			// its own session handover to wait for.
			if !pttEpisodeLive {
				pttEpisodeLive = true
				pttEpisodeSeq++
				pttEpisodeGen = pttActivations
			}
			play := pttPlay{
				roomID:  dictString(objc.ID(payload.ID), "room_id"),
				eventID: dictString(objc.ID(payload.ID), "event_id"),
				at:      time.Now(),
				seq:     pttPushSeq,
				episode: pttEpisodeSeq,
			}
			pttMu.Unlock()
			// The push names the channel it is for, and on a launch the push
			// itself woke that is the first time this process learns the uuid
			// — PTTSpeakerStopped needs it.
			inPTTPool(func() {
				if s := u.UUIDString(); s != "" {
					pttMu.Lock()
					pttUUID = s
					pttMu.Unlock()
				}
			})
			pttMu.Lock()
			pttPlays = append(pttPlays, play)
			pttMu.Unlock()
			pttNote("incoming push #%d ep=%d (%s) room=%s event=%s",
				play.seq, play.episode, who, play.roomID, play.eventID)
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

// pttBeginPlaybackEpisode opens an episode so the APP can play something —
// a message the user tapped, or one the arrival policy auto-plays. A joined app
// has no audio session of its own (plan 0066), and Apple's sanctioned way to
// ask for one outside a transmission is to report an active remote participant:
// the framework then activates a session, hands it over, and puts the speaker on
// the system UI. That is honest here — a wata message IS a remote participant
// talking — and it is the same episode machinery an incoming push opens, so
// PTTSpeakerStopped ends it and the burst rules apply unchanged.
//
// Three answers, because "who ends this episode" has three cases:
//   - a positive id: this call opened the episode, and whoever asked for it
//     ends it when the sound is over;
//   - -1: an episode was ALREADY live (a push-woken burst, a transmission).
//     Play on it and do not end it — it belongs to the pump, which ends it when
//     the whole burst has drained;
//   - 0: no manager, no channel, or no join. Nothing can play, and macaudio
//     says so rather than playing silently into a session it does not have.
//
// The participant is named after the CHANNEL rather than the message's sender:
// this is called from the audio thread, which knows a PCM buffer and nothing
// else. Naming the actual speaker is the target work queued as PTT-CHANNEL-TARGET.
func pttBeginPlaybackEpisode(reason string) int {
	m, why := pttCurrent()
	if why != "" {
		pttNote("no episode for %s: %s", reason, why)
		return 0
	}
	pttMu.Lock()
	if !pttJoined {
		pttMu.Unlock()
		return 0
	}
	// A live episode is joined rather than replaced, exactly as a second push
	// joins the burst already playing: same speaker, same session, nothing to
	// raise. Crucially it is not ours to END either — lowering the speaker under
	// a draining burst would cut off the message in the air.
	if pttEpisodeLive {
		pttMu.Unlock()
		return -1
	}
	pttEpisodeLive = true
	pttEpisodeSeq++
	pttEpisodeGen = pttActivations
	ep := pttEpisodeSeq
	name := pttName
	pttMu.Unlock()
	if name == "" {
		name = "wata" // -initWithName: rejects an empty name
	}
	inPTTPool(func() {
		u, ok := pttUUIDObj()
		if !ok {
			return
		}
		p := pt.GetPTParticipantClass().Alloc().InitWithNameImage(name, pt.UIImage{})
		m.SetActiveRemoteParticipantForChannelUUIDCompletionHandler(p, u, func(err error) {
			if err != nil {
				pttNote("raising the speaker for %s failed: %s", reason, errText(err))
			}
		})
	})
	pttNote("speaker up #%d for %s", ep, reason)
	return int(ep)
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

// PTTDescriptor renames the joined channel, which is how the system UI says
// WHERE a press will go (plan 0067). iOS gives an app one channel and no
// recipient picker, so the descriptor's name is the only place the target can
// be seen — and in the default mode the target follows the most recent
// interaction, i.e. it moves without the user doing anything. Showing it is
// what makes that safe.
//
// Same UUID, no leave, no new ephemeral token: the channel identifies this
// DEVICE's one channel, not the target. The name is also remembered for the
// restoration delegate, so a relaunch comes back with what the user last saw
// rather than whatever this process first joined with.
func PTTDescriptor(name string) {
	if name == "" {
		return
	}
	pttMu.Lock()
	pttName = name
	pttMu.Unlock()
	m, why := pttCurrent()
	if why != "" {
		return
	}
	inPTTPool(func() {
		u, ok := pttUUIDObj()
		if !ok {
			return
		}
		d := pt.GetPTChannelDescriptorClass().Alloc().InitWithNameImage(name, pt.UIImage{})
		m.SetChannelDescriptorForChannelUUIDCompletionHandler(d, u, func(err error) {
			if err != nil {
				pttNote("renaming the channel to %q failed: %s", name, errText(err))
			}
		})
	})
	pttNote("channel is %q", name)
}

// PTTServiceStatus tells the system UI whether wata's own service is reachable,
// so the pill and the lock screen stop claiming a healthy channel while the app
// knows its connection is down. `ready`, `connecting`, `unavailable` — the pump
// feeds it from the same session state it prints as `net:`, one call per change
// (the framework's default is `ready`, which is a lie the moment a sync drops).
//
// Nothing about audio depends on this: it is what the user sees when they raise
// the phone and wonder whether a press would go anywhere.
func PTTServiceStatus(state string) {
	var st pt.PTServiceStatus
	switch state {
	case "ready":
		st = pt.PTServiceStatusReady
	case "connecting":
		st = pt.PTServiceStatusConnecting
	default:
		st = pt.PTServiceStatusUnavailable
	}
	m, why := pttCurrent()
	if why != "" {
		return
	}
	inPTTPool(func() {
		u, ok := pttUUIDObj()
		if !ok {
			return
		}
		m.SetServiceStatusForChannelUUIDCompletionHandler(st, u, func(err error) {
			if err != nil {
				pttNote("service status %s failed: %s", state, errText(err))
			}
		})
	})
	pttNote("service %s", state)
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

// TakePTTPlay pops the oldest message an incoming `pushtotalk` push named,
// described for the pump to print (`play room=… event=…`), or "" when none is
// pending. PTTPlayRoom, PTTPlayEvent and PTTPlayAgeMs answer for the one it
// just handed out.
func TakePTTPlay() string {
	pttMu.Lock()
	defer pttMu.Unlock()
	if len(pttPlays) == 0 {
		return ""
	}
	pttPlayCur = pttPlays[0]
	pttPlays = pttPlays[1:]
	return "play #" + strconv.FormatUint(pttPlayCur.seq, 10) +
		" ep=" + strconv.FormatUint(pttPlayCur.episode, 10) +
		" room=" + pttPlayCur.roomID + " event=" + pttPlayCur.eventID
}

// PTTPlayPending reports whether any pushes are still waiting for the pump to
// drain them. The pump asks before it ends an episode: a push that landed
// after this frame's drain belongs to the episode about to be closed, and
// closing it would strand that message with no speaker on screen and no
// session under it.
func PTTPlayPending() bool {
	pttMu.Lock()
	defer pttMu.Unlock()
	return len(pttPlays) > 0
}

// PTTPlayEpisode is the episode id of the push TakePTTPlay last returned —
// the raised speaker it belongs to, shared with every other push of the same
// burst. It goes back into PTTSpeakerStopped, so an episode can only ever end
// itself.
func PTTPlayEpisode() int {
	pttMu.Lock()
	defer pttMu.Unlock()
	return int(pttPlayCur.episode)
}

// PTTPlayReady reports whether the audio session that is active right now is
// THIS EPISODE's: activated, and activated after the episode opened.
//
// The gate is per EPISODE, not per push, and the difference is the whole
// rapid-fire story. Per push it was wrong in both directions:
//
//   - "is a session active" answers yes to a push answered while the PREVIOUS
//     episode's session is still up. It plays on it, and is killed a moment
//     later when that older episode's deactivation lands — device log
//     2026-08-18: `ptt: playing $kyO5…`, `PushToTalk released the audio
//     session`, `the audio session changed under the playback`.
//   - "an activation attributable to THIS PUSH" answers no to every push after
//     the first of a burst, because a speaker that is already up produces no
//     new activation. Device log 2026-08-18: four messages, one played, three
//     waited out their window and gave up with "no audio session for this
//     push".
//
// The episode is what the session actually belongs to: one raised speaker, one
// activation, however many pushes join it. A push that opens a NEW episode
// waits for its own fresh activation — the speaker came down, the framework
// took the session back, and playing across that boundary is what the first
// bullet describes.
func PTTPlayReady() bool {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttEpisodeLive && pttSessionOn && pttActivations > pttEpisodeGen
}

// PTTPlayRoom is the room id of the push TakePTTPlay last returned.
func PTTPlayRoom() string {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttPlayCur.roomID
}

// PTTPlayEvent is the event id of the push TakePTTPlay last returned.
func PTTPlayEvent() string {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttPlayCur.eventID
}

// PTTPlayAgeMs is how long ago the push TakePTTPlay last returned ARRIVED —
// not how long ago it was taken. A suspended or cold app runs no frames, so
// the pump can drain a push seconds after the framework delivered it, and the
// live-audio window is measured from the push.
func PTTPlayAgeMs() int {
	pttMu.Lock()
	at := pttPlayCur.at
	pttMu.Unlock()
	if at.IsZero() {
		return 0
	}
	ms := time.Since(at).Milliseconds()
	// A day is past every window there is, and keeps the value small enough
	// that no arithmetic on the other side of the facade can surprise anyone.
	if ms > 24*3600*1000 {
		ms = 24 * 3600 * 1000
	}
	if ms < 0 {
		ms = 0
	}
	return int(ms)
}

// PTTSpeakerStopped ends a RECEIVE episode: it clears the channel's active
// remote participant, which takes the speaker off the system UI, unblocks
// transmitting and lets the framework deactivate the audio session it handed
// over. The pump calls it once the woken message has played, or once it has
// given up on playing it — an episode nobody ends never ends, which is the
// hardware behaviour recorded in this file's header.
//
// AN EPISODE MAY ONLY END ITSELF. `episode` is the id PTTPlayEpisode handed
// out — the raised speaker, not the push. If the app has since raised a NEW
// speaker (this one came down and another push arrived), the speaker on screen
// and the session in play belong to that episode and this call does nothing.
// Without the check a failed or finished playback tears down the episode that
// succeeded it.
//
// macaudio is told in the same breath and unconditionally, including when
// there is no manager at all: its ownership flag must come back even if the
// framework's own callback does not.
func PTTSpeakerStopped(episode int) {
	pttMu.Lock()
	cur := pttEpisodeSeq
	if episode > 0 && uint64(episode) != cur {
		pttMu.Unlock()
		pttNote("episode #%d is over, but #%d owns the speaker now — leaving it",
			episode, cur)
		return
	}
	// The speaker is coming down, so the NEXT push opens a fresh episode and
	// waits for its own session handover.
	pttEpisodeLive = false
	pttMu.Unlock()
	m, why := pttCurrent()
	if why == "" {
		inPTTPool(func() {
			u, ok := pttUUIDObj()
			if !ok {
				return
			}
			m.SetActiveRemoteParticipantForChannelUUIDCompletionHandler(
				pt.PTParticipant{}, u, func(err error) {
					if err != nil {
						pttNote("clearing the speaker failed: %s", errText(err))
					}
				})
		})
	}
	macaudio.PTTEpisodeEnded("the incoming message finished")
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
