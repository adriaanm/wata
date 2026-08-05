// irohnet-keygen prints a fresh node key as JSON:
//
//	{"secretKey":"<hex64>","id":"<hex64>"}
//
// The tunnel-smoke harness uses it to provision the client key and the
// server allowlist. Build with the real transport: `go build -tags iroh`.
//
// With -config it does NOT print a secret at all:
//
//	irohnet-keygen -config /etc/wata/iroh.json     ->  {"id":"<hex64>"}
//
// which is the DEVICE path (plan 0014 milestone 1): irohnet.EnsureKey mints a
// key into that config file if it has none — 0600, every other field preserved
// — and reports only the public id. wata-fb makes the same call on boot; this
// flag exists so a harness can drive the identical code path instead of
// hand-minting a key and pasting it into a file.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"github.com/adriaanm/wata/go-pkgs/irohnet"
)

func main() {
	cfg := flag.String("config", "", "mint a node key into this iroh config if it has none; print only its id")
	flag.Parse()
	if *cfg != "" {
		id, e := irohnet.EnsureKey(*cfg)
		if e != nil {
			fmt.Fprintln(os.Stderr, e)
			os.Exit(1)
		}
		blob, _ := json.Marshal(map[string]string{"id": id})
		fmt.Println(string(blob))
		return
	}
	secret, id, e := irohnet.GenKey()
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	blob, _ := json.Marshal(map[string]string{"secretKey": secret, "id": id})
	fmt.Println(string(blob))
}
