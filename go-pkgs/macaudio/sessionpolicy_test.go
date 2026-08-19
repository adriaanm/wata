package macaudio

import "testing"

// The rule hardware taught us (plan 0066), asserted where it can actually run:
// a joined PushToTalk app touches nothing until the framework hands a session
// over, and asks for an episode when it wants to play. The iOS side of this is
// ungateable on any machine without a phone, so the DECISION is pinned here and
// session_ios.go only obeys it.
func TestPolicyFor(t *testing.T) {
	for _, c := range []struct {
		name           string
		joined, episode bool
		want           sessionPolicy
	}{{
		// macOS, the simulator, the handset, or a phone that left the channel.
		name: "not joined: the session is the app's own",
		want: sessionPolicy{setCategory: true, setActive: true, startEngine: true},
	}, {
		// The state that had wata-ios silent: at launch (joined by channel
		// restoration) and again after every episode handed back.
		name:   "joined and idle: hands off, and a sound needs an episode",
		joined: true,
		want:   sessionPolicy{needEpisode: true},
	}, {
		// The framework handed over an ACTIVATED session configured for this
		// episode: run the engine on it, set nothing.
		name:    "episode live: run the engine, touch the session never",
		joined:  true,
		episode: true,
		want:    sessionPolicy{startEngine: true},
	}, {
		// Should not happen (an episode implies a join), and if it ever does
		// the handover is what to believe: a session we were handed is active.
		name:    "episode without a join: the handover wins",
		episode: true,
		want:    sessionPolicy{startEngine: true},
	}} {
		t.Run(c.name, func(t *testing.T) {
			if got := policyFor(c.joined, c.episode); got != c.want {
				t.Errorf("policyFor(joined=%v, episode=%v) = %+v, want %+v",
					c.joined, c.episode, got, c.want)
			}
		})
	}
}

// setActive: is the call iOS refuses with 'ent?', and a start implicitly makes
// it. Neither may be reachable while a channel is joined — stated separately
// from the table because it is the whole point of the plan and a future edit
// that breaks it should fail on a sentence, not on a struct literal.
func TestJoinedNeverTouchesTheSession(t *testing.T) {
	for _, episode := range []bool{false, true} {
		p := policyFor(true, episode)
		if p.setActive {
			t.Errorf("joined (episode=%v): setActive must never be allowed", episode)
		}
		if p.setCategory {
			t.Errorf("joined (episode=%v): setCategory must never be allowed", episode)
		}
	}
	if p := policyFor(true, false); p.startEngine {
		t.Error("joined and idle: the engine start is refused by iOS ('ent?'/'what'), " +
			"so it must not be attempted")
	}
}
