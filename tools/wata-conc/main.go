// M7 chunk 4 — the /sync long-poll concurrency driver (THE CONC SHOWCASE test).
//
// N goroutine clients (all as user alice, distinct implicit sessions on one
// access token — waiters are keyed by userId, so one notifyUser wakes them all)
// long-poll GET /sync with a since-token that yields no data; a separate client
// (bob) sends ONE message to a room they all observe. Every client must wake
// WITH the event, and the client-measured wake latency (t_wake - t_send) must be
// well under the long-poll timeout. Then a timeout-expiry case: one client
// long-polls with no event and must return an empty sync after ~its timeout.
//
// Nondeterministic timing => this driver asserts BOUNDS and prints a summary, it
// is not byte-compared. It exits non-zero on any failure. Run against a `-race`
// wata binary so the whole exercise is race-checked (wata-smoke.sh wires it).
//
//	go run . <baseURL>
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"
	"time"
)

const (
	nClients      = 8
	longPollMs    = 5000 // clients block up to this
	wakeBoundMs   = 1500 // a woken client must return well under the timeout
	rampUpMs      = 500  // let all clients register as waiters before the send
	expiryMs      = 800  // the timeout-expiry case's short timeout
	expiryLoMs    = 700  // it must not return appreciably before its timeout
	expiryHiMs    = 3000 // …nor hang past it
)

var base string

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "wata-conc: FAIL: "+msg)
	os.Exit(1)
}

func login(user string) string {
	body := fmt.Sprintf(`{"identifier":{"type":"m.id.user","user":%q},"password":"testpass123"}`, user)
	resp, err := http.Post(base+"/_matrix/client/v3/login", "application/json", bytes.NewReader([]byte(body)))
	if err != nil {
		fail("login " + user + ": " + err.Error())
	}
	defer resp.Body.Close()
	var m map[string]any
	json.NewDecoder(resp.Body).Decode(&m)
	tok, _ := m["access_token"].(string)
	if tok == "" {
		fail("login " + user + ": no access_token")
	}
	return tok
}

func do(method, url, token, body string) map[string]any {
	var r io.Reader
	if body != "" {
		r = bytes.NewReader([]byte(body))
	}
	req, _ := http.NewRequest(method, base+url, r)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		fail(method + " " + url + ": " + err.Error())
	}
	defer resp.Body.Close()
	var m map[string]any
	json.NewDecoder(resp.Body).Decode(&m)
	return m
}

// syncCall returns (nextBatch, #timeline events across join rooms).
func syncCall(token, since string, timeoutMs int) (string, int) {
	url := fmt.Sprintf("/_matrix/client/v3/sync?since=%s&timeout=%d", since, timeoutMs)
	m := do("GET", url, token, "")
	nb, _ := m["next_batch"].(string)
	events := 0
	if rooms, ok := m["rooms"].(map[string]any); ok {
		if join, ok := rooms["join"].(map[string]any); ok {
			for _, v := range join {
				if room, ok := v.(map[string]any); ok {
					if tl, ok := room["timeline"].(map[string]any); ok {
						if evs, ok := tl["events"].([]any); ok {
							events += len(evs)
						}
					}
				}
			}
		}
	}
	return nb, events
}

func main() {
	if len(os.Args) < 2 {
		fail("usage: wata-conc <baseURL>")
	}
	base = os.Args[1]

	// ---- setup: alice + bob in a DM room -----------------------------------
	alice := login("alice")
	bob := login("bob")
	cr := do("POST", "/_matrix/client/v3/createRoom", alice, `{"is_direct":true,"invite":["@bob:localhost"]}`)
	room, _ := cr["room_id"].(string)
	if room == "" {
		fail("createRoom: no room_id")
	}
	do("POST", "/_matrix/client/v3/rooms/"+room+"/join", bob, "")

	// initial sync → the since token all long-pollers share.
	since, _ := syncCall(alice, "", 0)
	if since == "" {
		fail("initial sync: no next_batch")
	}

	// ---- N clients long-poll; bob sends one message ------------------------
	type result struct {
		events  int
		latency time.Duration
	}
	results := make([]result, nClients)
	var tSend time.Time
	var sendOnce sync.Once
	sendMarked := make(chan struct{})

	var wg sync.WaitGroup
	for i := 0; i < nClients; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, ev := syncCall(alice, since, longPollMs)
			tWake := time.Now()
			<-sendMarked // ensure tSend is set before we read it
			results[idx] = result{events: ev, latency: tWake.Sub(tSend)}
		}(i)
	}

	// give every client time to register as a waiter, then send.
	time.Sleep(time.Duration(rampUpMs) * time.Millisecond)
	sendOnce.Do(func() {
		tSend = time.Now()
		do("PUT", "/_matrix/client/v3/rooms/"+room+"/send/m.room.message/ct1", bob, `{"msgtype":"m.text","body":"broadcast"}`)
		close(sendMarked)
	})
	wg.Wait()

	// ---- assertions --------------------------------------------------------
	woke := 0
	var maxLat time.Duration
	for i, r := range results {
		if r.events >= 1 {
			woke++
		} else {
			fmt.Fprintf(os.Stderr, "wata-conc: client %d woke with NO events\n", i)
		}
		if r.latency > maxLat {
			maxLat = r.latency
		}
		if r.latency < 0 {
			fail(fmt.Sprintf("client %d woke BEFORE the send (lost/misordered wake)", i))
		}
	}
	maxMs := maxLat.Milliseconds()
	fmt.Printf("wake: %d/%d clients woke with the event, maxWakeLatency=%dms (bound %dms, timeout %dms)\n",
		woke, nClients, maxMs, wakeBoundMs, longPollMs)
	if woke != nClients {
		fail(fmt.Sprintf("only %d/%d clients woke with the event", woke, nClients))
	}
	if maxMs > wakeBoundMs {
		fail(fmt.Sprintf("max wake latency %dms exceeds bound %dms", maxMs, wakeBoundMs))
	}

	// ---- timeout-expiry case ----------------------------------------------
	after, _ := syncCall(alice, since, 0) // fresh token past the message
	t0 := time.Now()
	_, ev := syncCall(alice, after, expiryMs)
	elapsed := time.Since(t0).Milliseconds()
	fmt.Printf("timeout-expiry: returned after %dms (timeout %dms), emptyTimeline=%v\n", elapsed, expiryMs, ev == 0)
	if ev != 0 {
		fail("timeout-expiry case returned events (expected empty)")
	}
	if elapsed < expiryLoMs || elapsed > expiryHiMs {
		fail(fmt.Sprintf("timeout-expiry elapsed %dms outside [%d,%d]ms", elapsed, expiryLoMs, expiryHiMs))
	}

	fmt.Println("wata-conc: PASS")
}
