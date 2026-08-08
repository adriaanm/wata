//go:build darwin

package macshell

import (
	"fmt"
	"os"
	"runtime"
	"runtime/pprof"
	"strconv"
	"time"
)

// A heap profile on a timer, off unless asked for.
//
// The question this exists for is which objects grow while the app sits idle
// (wata-mac was found paused by macOS at 26 GB after days of running). RSS says
// the process grew and gctrace says the Go heap grew; neither names an
// allocation site, and naming it is the whole remaining question.
//
// It is in this package rather than the harness because the app that matters
// is the WINDOWED one — the owner's 26 GB was a windowed run, and a profile
// that can only be taken headless cannot settle it. An init() covers Start and
// StartHeadless alike, and any other entry point that may exist later.
//
//	WATA_MAC_HEAP_PROFILE=/tmp/wata-heap   # writes /tmp/wata-heap.<n>.pprof
//	WATA_MAC_HEAP_EVERY=30                 # seconds between dumps (default 60)
//
// Read two of them with:
//
//	go tool pprof -top -sample_index=inuse_space -base <early> <binary> <late>
//
// -base subtracts, so what is left is exactly what grew between them, which is
// the only thing worth reading — a single profile is dominated by the app's
// ordinary steady-state heap.
func init() {
	path := os.Getenv("WATA_MAC_HEAP_PROFILE")
	if path == "" {
		return
	}
	every := 60 * time.Second
	if s := os.Getenv("WATA_MAC_HEAP_EVERY"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n > 0 {
			every = time.Duration(n) * time.Second
		}
	}
	go func() {
		for i := 0; ; i++ {
			time.Sleep(every)
			// The profile is of what is LIVE, so the GC has to have run
			// first — without this the dump counts garbage that a
			// collection would have removed, and every site looks like it
			// is growing.
			runtime.GC()
			name := fmt.Sprintf("%s.%d.pprof", path, i)
			f, err := os.Create(name)
			if err != nil {
				fmt.Fprintln(os.Stderr, "macshell: heap profile:", err)
				return
			}
			if err := pprof.Lookup("heap").WriteTo(f, 0); err != nil {
				fmt.Fprintln(os.Stderr, "macshell: heap profile:", err)
			}
			f.Close()
			fmt.Fprintln(os.Stderr, "macshell: heap profile ->", name)
		}
	}()
}
