// Package watamobile is the gomobile BIND SURFACE of the phone spike
// (plan 0023 M1): a hand-written Go shim over the sgola-emitted wataclient
// core, exposing only what gobind can carry across the ObjC/Java boundary.
//
// gobind's supported types are: signed integers, float32/64, bool, string,
// []byte, and interfaces/structs built from those. The emitted core's surface
// is nothing of the sort — `MatrixClient` holds channels, `StateSnapshot`
// holds sgola `List` cons cells, and every ADT case is a pointer-shaped
// struct behind a marker interface. So the boundary is strings, and the
// rendering happens in Sgola (Bind.reportOf), where the domain types live.
//
// SHAPE (plan 0025). The client is started, observed and stopped — never
// "run". `Start` hands back a *Client whose Sgola-side handle owns a
// goroutine; `Watch` drains the handle's bounded dirty-flag channel into an
// `EventSink` the host implements (an interface with one method is the gobind
// idiom — Swift and Kotlin conform to it directly); `Live`, `HasSelf` and
// `Report` read the current state when a flag says to; `Stop` winds it down
// and joins its goroutine. No thread is parked and nothing sleeps to fake a
// lifetime, so a UIKit app can hold a *Client across callbacks.
package watamobile

import (
	"time"

	watacore "github.com/adriaanm/wata/tools/phone-spike/watacore"
)

// Hello returns a build-identity line from the sgola-emitted package. It needs
// no server, so it is the smallest possible "the bound framework is really
// running our Go" check.
func Hello() string {
	return watacore.Bind_hello()
}

// EventSink receives the client's dirty-flag topics: "conn", "snapshot",
// "outbox", and finally "stopped". A topic carries no data — it means "read
// the current state" — so a host implements this with a UI refresh, not with
// a payload decode.
type EventSink interface {
	OnEvent(topic string)
}

// Client is a running client: the Sgola-side handle, plus whatever goroutine
// Watch has pumping its events. Created by Start, ended by Stop.
type Client struct {
	h watacore.Handle
}

// Start logs the client in on its own goroutine and returns immediately.
func Start(homeserver, user, password string) *Client {
	return &Client{h: watacore.Bind_start(homeserver, user, password)}
}

// Watch drains the handle's event channel into sink on its own goroutine,
// until the client stops — "stopped" is the last topic delivered, so the pump
// always ends (the channel is never closed under it).
func (c *Client) Watch(sink EventSink) {
	ch := watacore.Bind_events(c.h)
	go func() {
		for {
			topic := watacore.ClientHandle_topicName(<-ch)
			sink.OnEvent(topic)
			if topic == "stopped" {
				return
			}
		}
	}()
}

// Live reports whether the client is connected/syncing right now.
func (c *Client) Live() bool { return watacore.Bind_live(c.h) }

// HasSelf reports whether the current snapshot carries the account — the
// first snapshot worth showing.
func (c *Client) HasSelf() bool { return watacore.Bind_hasSelf(c.h) }

// Report renders the current snapshot (self / contacts / conversations /
// family); the rendering itself happens in Sgola, where the domain types are.
func (c *Client) Report() string { return watacore.Bind_reportOf(c.h) }

// Stop winds the client down and waits for its goroutine.
func (c *Client) Stop() { watacore.Bind_stop(c.h) }

// Probe is the smoke's one-call driver, and the demonstration that a HOST can
// own the loop: it starts a client, pumps its events through an EventSink of
// its own, and returns the first snapshot carrying the account, rendered.
// Everything it does is what a Swift view controller would do across
// callbacks instead of inside one function. It blocks for up to
// timeoutMillis; call it off the UI thread.
func Probe(homeserver, user, password string, timeoutMillis int) string {
	c := Start(homeserver, user, password)
	defer c.Stop()
	topics := make(chan string, 16)
	c.Watch(&chanSink{topics})

	deadline := time.After(time.Duration(timeoutMillis) * time.Millisecond)
	live := false
	for {
		select {
		case topic := <-topics:
			switch topic {
			case "conn":
				live = live || c.Live()
			case "snapshot":
				if c.HasSelf() {
					return c.Report()
				}
			}
		case <-deadline:
			if !live {
				return "error unreachable-or-rejected"
			}
			return "error no-snapshot"
		}
	}
}

// chanSink is Probe's own EventSink: it forwards topics to a buffered channel
// and DROPS when that is full, for the same reason the Sgola side does — a
// dirty flag is worth nothing once a later one of the same topic exists, and
// a sink that blocks would stall the pump.
type chanSink struct{ ch chan string }

func (s *chanSink) OnEvent(topic string) {
	select {
	case s.ch <- topic:
	default:
	}
}
