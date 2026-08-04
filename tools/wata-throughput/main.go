// M7 chunk 6 — the wata-server throughput + long-poll-latency benchmark driver.
// Points at ANY Matrix homeserver on <baseURL> (Sgola or the TS reference), runs
// a FIXED workload, and prints req/s + wake-latency numbers. Comparative: run it
// once per server and tabulate.
//
//	go run . <baseURL> [reqsPerPhase]
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

var base string
var client = &http.Client{Timeout: 30 * time.Second}

func fail(m string) { fmt.Fprintln(os.Stderr, "wata-throughput: "+m); os.Exit(1) }

func req(method, url, token, body string) map[string]any {
	var r io.Reader
	if body != "" {
		r = bytes.NewReader([]byte(body))
	}
	rq, _ := http.NewRequest(method, base+url, r)
	if token != "" {
		rq.Header.Set("Authorization", "Bearer "+token)
	}
	if body != "" {
		rq.Header.Set("Content-Type", "application/json")
	}
	resp, err := client.Do(rq)
	if err != nil {
		fail(method + " " + url + ": " + err.Error())
	}
	defer resp.Body.Close()
	var m map[string]any
	json.NewDecoder(resp.Body).Decode(&m)
	return m
}

func login(user string) string {
	m := req("POST", "/_matrix/client/v3/login", "",
		fmt.Sprintf(`{"identifier":{"type":"m.id.user","user":%q},"password":"testpass123"}`, user))
	tok, _ := m["access_token"].(string)
	if tok == "" {
		fail("login " + user + ": no token")
	}
	return tok
}

// rate runs fn n times sequentially and returns requests/second.
func rate(n int, fn func(i int)) float64 {
	t0 := time.Now()
	for i := 0; i < n; i++ {
		fn(i)
	}
	d := time.Since(t0).Seconds()
	return float64(n) / d
}

// rateConc runs n calls across w concurrent workers.
func rateConc(n, w int, fn func(i int)) float64 {
	t0 := time.Now()
	var wg sync.WaitGroup
	ch := make(chan int, n)
	for i := 0; i < n; i++ {
		ch <- i
	}
	close(ch)
	for k := 0; k < w; k++ {
		wg.Add(1)
		go func() { defer wg.Done(); for i := range ch { fn(i) } }()
	}
	wg.Wait()
	return float64(n) / time.Since(t0).Seconds()
}

func main() {
	if len(os.Args) < 2 {
		fail("usage: wata-throughput <baseURL> [reqsPerPhase]")
	}
	base = os.Args[1]
	n := 3000
	if len(os.Args) > 2 {
		fmt.Sscan(os.Args[2], &n)
	}

	alice := login("alice")
	bob := login("bob")
	cr := req("POST", "/_matrix/client/v3/createRoom", alice, `{"is_direct":true,"invite":["@bob:localhost"]}`)
	room, _ := cr["room_id"].(string)
	if room == "" {
		fail("createRoom: no room_id")
	}
	req("POST", "/_matrix/client/v3/rooms/"+room+"/join", bob, "")

	fmt.Printf("=== %s  (n=%d/phase) ===\n", base, n)

	loginRate := rate(n, func(i int) { login("alice") })
	fmt.Printf("login    seq  %8.0f req/s\n", loginRate)

	sendRate := rate(n, func(i int) {
		req("PUT", fmt.Sprintf("/_matrix/client/v3/rooms/%s/send/m.room.message/thr%d", room, i), alice,
			`{"msgtype":"m.text","body":"x"}`)
	})
	fmt.Printf("send     seq  %8.0f req/s\n", sendRate)

	// initial sync ONCE to get a since-token; the hot client path is INCREMENTAL
	// sync (mostly-empty deltas), so throughput is measured on that, not on the
	// O(n) full-state rebuild (which grows with the timeline the send phase left).
	since, _ := req("GET", "/_matrix/client/v3/sync?timeout=0", alice, "")["next_batch"].(string)
	syncURL := "/_matrix/client/v3/sync?since=" + since + "&timeout=0"

	syncRate := rate(n, func(i int) { req("GET", syncURL, alice, "") })
	fmt.Printf("sync-inc seq  %8.0f req/s\n", syncRate)

	syncConc := rateConc(n, 16, func(i int) { req("GET", syncURL, alice, "") })
	fmt.Printf("sync-inc c16  %8.0f req/s\n", syncConc)

	initRate := rate(200, func(i int) { req("GET", "/_matrix/client/v3/sync?timeout=0", alice, "") })
	fmt.Printf("sync-full seq %8.0f req/s  (full-state rebuild, ~%d-event room)\n", initRate, n)

	// ---- long-poll wake latency: K rounds, each a fresh poller woken by a send.
	const K = 20
	var tot, mx time.Duration
	for k := 0; k < K; k++ {
		s2, _ := req("GET", "/_matrix/client/v3/sync?timeout=0", alice, "")["next_batch"].(string)
		var tWake time.Time
		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			req("GET", "/_matrix/client/v3/sync?since="+s2+"&timeout=5000", alice, "")
			tWake = time.Now() // wata-conc's method: t_wake - t_send
		}()
		time.Sleep(30 * time.Millisecond) // let the poller register as a waiter
		tSend := time.Now()
		req("PUT", fmt.Sprintf("/_matrix/client/v3/rooms/%s/send/m.room.message/wk%d", room, k), bob,
			`{"msgtype":"m.text","body":"wake"}`)
		wg.Wait()
		wake := tWake.Sub(tSend)
		tot += wake
		if wake > mx {
			mx = wake
		}
	}
	fmt.Printf("longpoll wake avg %.2fms  max %.2fms  (K=%d)\n",
		float64(tot.Microseconds())/float64(K)/1000.0, float64(mx.Microseconds())/1000.0, K)
}
