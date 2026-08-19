// The `net` mode: does Go's own network stack work on watchOS?
//
// Everything wata's client core does above the socket — the sync long
// poll, the media fetch, the login POST — is `net/http` over Go's
// resolver and TLS. None of that is Apple's, so none of it is covered by
// anything the UIKit probes settled, and a watch is the first wata target
// where the OS might reasonably refuse an app a socket at all.
//
// Three things get asked, cheapest first: a raw TCP dial to the host's
// loopback (the simulator shares it, exactly as the iOS harnesses assume),
// an HTTP round trip over it, and a TLS handshake against a real name,
// which is also the only probe here that needs the system clock and the
// trust store to be sane — the failure mode the BQ268 taught us to check
// for by name.
package main

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

func runNet() {
	fmt.Println("watchspike: net mode")
	fmt.Printf("watchspike: net clock %s\n", time.Now().UTC().Format(time.RFC3339))

	base := os.Getenv("WATA_WATCH_PROBE_URL")
	if base == "" && len(os.Args) > 2 {
		base = os.Args[2]
	}

	if base != "" {
		host := base
		if u := hostOf(base); u != "" {
			host = u
		}
		c, err := net.DialTimeout("tcp", host, 10*time.Second)
		if err != nil {
			fmt.Printf("watchspike: net dial %s FAILED %v\n", host, err)
		} else {
			fmt.Printf("watchspike: net dial %s ok\n", host)
			c.Close()
		}
		cl := &http.Client{Timeout: 15 * time.Second}
		resp, err := cl.Get(base)
		if err != nil {
			fmt.Printf("watchspike: net http FAILED %v\n", err)
		} else {
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 256))
			resp.Body.Close()
			fmt.Printf("watchspike: net http %d %d bytes\n",
				resp.StatusCode, len(b))
		}
	} else {
		fmt.Println("watchspike: net no probe url — skipping loopback legs")
	}

	// TLS against a real name: DNS, the system trust store and a clock that
	// is not 1970. A watch has no battery-backed RTC problem the way the
	// BQ268 does, but the failure looks identical, so it is named.
	d := &net.Dialer{Timeout: 10 * time.Second}
	tc, err := tls.DialWithDialer(d, "tcp", "www.apple.com:443",
		&tls.Config{ServerName: "www.apple.com"})
	if err != nil {
		fmt.Printf("watchspike: net tls FAILED %v\n", err)
	} else {
		st := tc.ConnectionState()
		fmt.Printf("watchspike: net tls ok version=%x cipher=%x\n",
			st.Version, st.CipherSuite)
		tc.Close()
	}

	fmt.Println("watchspike: all checks passed")
}

// hostOf pulls host:port out of an http URL without importing net/url for
// one field.
func hostOf(raw string) string {
	s := raw
	for _, p := range []string{"http://", "https://"} {
		if len(s) > len(p) && s[:len(p)] == p {
			s = s[len(p):]
			break
		}
	}
	for i := 0; i < len(s); i++ {
		if s[i] == '/' {
			return s[:i]
		}
	}
	return s
}
