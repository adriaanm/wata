// M7 chunk 6 — allocs/op on the EMITTED serve path (the M5 harness idea in Go's
// native shape: testing.B.ReportAllocs, == the runtime.ReadMemStats Mallocs delta
// the sgola in-source harness uses). Copied into .sgo/wata (package main) by
// tools/wata-bench.sh and run with `go test -bench`; it drives the emitted
// WataHandler through the real Go-1.22 ServeMux via httptest — so every op pays
// the actual mux match + PathValue + store HashMap hits + json-module write, the
// map-bound handler path the CHAMP gate (ROADMAP M7 decision 6) evaluates.
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

var benchMux *http.ServeMux

func benchCall(method, path, token, body string) *httptest.ResponseRecorder {
	var req *http.Request
	if body != "" {
		req = httptest.NewRequest(method, path, strings.NewReader(body))
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	benchMux.ServeHTTP(rec, req)
	return rec
}

func benchField(bodyStr, key string) string {
	var m map[string]any
	_ = json.Unmarshal([]byte(bodyStr), &m)
	s, _ := m[key].(string)
	return s
}

// setup: a fresh store + mux, alice logged in, one DM room with bob joined.
func benchSetup() (token, room string) {
	Store_init()
	benchMux = http.NewServeMux()
	Server_registerRoutes(benchMux, &WataHandler{})
	benchMux.Handle("/", &NotFound{})
	login := benchCall("POST", "/_matrix/client/v3/login", "",
		`{"identifier":{"type":"m.id.user","user":"alice"},"password":"testpass123"}`)
	token = benchField(login.Body.String(), "access_token")
	bob := benchCall("POST", "/_matrix/client/v3/login", "",
		`{"identifier":{"type":"m.id.user","user":"bob"},"password":"testpass123"}`)
	bobTok := benchField(bob.Body.String(), "access_token")
	cr := benchCall("POST", "/_matrix/client/v3/createRoom", token, `{"is_direct":true,"invite":["@bob:localhost"]}`)
	room = benchField(cr.Body.String(), "room_id")
	benchCall("POST", "/_matrix/client/v3/rooms/"+room+"/join", bobTok, "")
	return
}

func BenchmarkLogin(b *testing.B) {
	benchSetup()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		benchCall("POST", "/_matrix/client/v3/login", "",
			`{"identifier":{"type":"m.id.user","user":"alice"},"password":"testpass123"}`)
	}
}

func BenchmarkSend(b *testing.B) {
	token, room := benchSetup()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		txn := fmt.Sprintf("bt%d", i)
		benchCall("PUT", "/_matrix/client/v3/rooms/"+room+"/send/m.room.message/"+txn, token,
			`{"msgtype":"m.text","body":"hi"}`)
	}
}

func BenchmarkSyncInitial(b *testing.B) {
	token, _ := benchSetup()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		benchCall("GET", "/_matrix/client/v3/sync?timeout=0", token, "")
	}
}

func BenchmarkWhoami(b *testing.B) {
	token, _ := benchSetup()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		benchCall("GET", "/_matrix/client/v3/account/whoami", token, "")
	}
}
