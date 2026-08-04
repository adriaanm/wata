// irohnet-keygen prints a fresh node key as JSON:
//
//	{"secretKey":"<hex64>","id":"<hex64>"}
//
// The tunnel-smoke harness uses it to provision the client key and the
// server allowlist. Build with the real transport: `go build -tags iroh`.
package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/adriaanm/wata/go-pkgs/irohnet"
)

func main() {
	secret, id, e := irohnet.GenKey()
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	blob, _ := json.Marshal(map[string]string{"secretKey": secret, "id": id})
	fmt.Println(string(blob))
}
