// Who is allowed to do what to the audio session right now — the one decision
// every session call in this package asks first. It lives here, platform-free
// and pure, because it is a RULE rather than a mechanism: it is what hardware
// taught us about PushToTalk, and a table test is the only place that lesson
// can be pinned (the framework does not exist in a simulator, so no gate on
// this machine ever executes the iOS side).
//
// THE RULE. A joined PushToTalk app does not own its audio session — at all,
// not merely during an episode. Apple's documentation is explicit ("the
// activation of your audio session MUST be triggered by the PushToTalk system,
// NOT your app") and so is the hardware, which refuses both halves of what
// this package used to do (wata-sgola plan 0066, device logs 2026-08-19):
//
//	AVAudioEngine start: … 1701737535   'ent?'  at launch, joined by restoration
//	engine restart:      … 2003329396   'what'  after an episode handed back
//
// 'ent?' is AVAudioSessionErrorCodeMissingEntitlement and 'what' is
// kAudioUnitErr_CannotDoInCurrentContext: the same wall, hit from before and
// after an episode. Note WHICH call was refused — not setActive:, which this
// package already knew was the framework's, but the ENGINE's own start. The
// belief that AVFAudio would activate on the app's behalf where the app may
// not is what left wata-ios with no audio outside an episode for a day.
//
// Why it ever looked fine: Apple DTS, forum thread 804205 — "setActive will
// work in the foreground (because the foreground app always has control over
// the audio system) but will NOT work correctly in the background". A
// walkie-talkie's whole job happens in the background.
package macaudio

// sessionPolicy is what the app may do to the audio session, and what it must
// ask the framework for instead.
type sessionPolicy struct {
	// may the app set its own category? (PlayAndRecord + DefaultToSpeaker)
	setCategory bool
	// may the app call setActive:? Never true while a channel is joined.
	setActive bool
	// may the engine be STARTED right now? A start implicitly activates the
	// session, so it is refused wherever setActive would be — which makes "no
	// running engine" the normal state of a joined app between episodes, not a
	// failure to report.
	startEngine bool
	// must playback first ask the framework for an episode
	// (setActiveRemoteParticipant, then play on the handover)?
	needEpisode bool
}

// policyFor decides from the only two facts that matter: is a PushToTalk
// channel joined, and has the framework handed an activated session over for a
// live episode (a transmission, or an incoming message).
//
// `episode` implies `joined` — the framework cannot hand over a session for a
// channel the app has not joined — and is treated as authoritative if the two
// ever disagree: a live handover means the session is configured and started
// for us, whatever the join flag says.
func policyFor(joined, episode bool) sessionPolicy {
	switch {
	case episode:
		// The framework's session is live and configured for this episode.
		// Touch nothing; the engine runs on what we were handed.
		return sessionPolicy{startEngine: true}
	case joined:
		// Joined and idle: hands off entirely. Nothing the app can do here
		// succeeds, and anything it tries corrupts the framework's own audio
		// path (DTS, above). A sound now means asking for an episode.
		return sessionPolicy{needEpisode: true}
	default:
		// No PushToTalk in the picture: macOS, the simulator, the handset, and
		// a phone that has left the channel. The app owns its session.
		return sessionPolicy{setCategory: true, setActive: true, startEngine: true}
	}
}

// The framework hooks. A joined app's only way to make a sound is to ask the
// framework for an episode, and only iosshell can do that (it owns the channel
// manager). It registers these at startup; they are nil everywhere else, and a
// nil hook is a diagnosable refusal rather than silence.
//
// PTTBeginEpisode raises the speaker for `reason` and returns the episode id it
// opened, or 0 when it could not. PTTEndEpisode lowers that episode's speaker,
// which is what lets the framework deactivate the session it handed over. Only
// an episode this package OPENED is ended here: a push-woken episode belongs to
// the pump, which ends it after the whole burst has drained.
var (
	PTTBeginEpisode func(reason string) int
	PTTEndEpisode   func(episode int)
)
