// The boot trace: on a real watch neither stdout nor exit codes reach the
// host (devicectl reported "exit code 0" for a SIGTRAP death, and console
// launches forward nothing), so when launch dies or hangs the only honest
// channels are crash reports and files pulled from the app container. A
// launch that HANGS leaves no crash report worth reading — the 0x8BADF00D
// watchdog record came back with an unwindable main thread — so the app
// writes its own progress: one line per launch stage, timestamped and
// fsynced, appended to $HOME/boot.log. Whichever stage is missing from the
// pulled file is where launch stopped. A few tiny writes per launch; it
// stays on permanently.

//go:build darwin

package watchshell

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

var (
	bootMu    sync.Mutex
	bootFirst = true
)

// bootDir is the container root: $HOME when launchd provides it, else
// derived from $TMPDIR (always <container>/tmp for an app process).
func bootDir() string {
	if h := os.Getenv("HOME"); h != "" {
		return h
	}
	if t := os.Getenv("TMPDIR"); t != "" {
		return filepath.Dir(filepath.Clean(t))
	}
	return ""
}

// BootMark appends one fsynced line to <container>/boot.log. The first
// line of a run records the env the path came from, so a missing or
// misplaced file diagnoses itself. Failures are swallowed: the trace must
// never be able to break the launch it observes.
func BootMark(stage string) {
	// The tripwire (device debugging): WATA_BOOT_ABORT=<stage> raises
	// SIGABRT after that stage's write, turning "did main even run?" into
	// a crash report — the one launch channel a real watch always honours.
	if os.Getenv("WATA_BOOT_ABORT") == stage {
		defer syscall.Kill(syscall.Getpid(), syscall.SIGABRT)
	}
	dir := bootDir()
	if dir == "" {
		return
	}
	bootMu.Lock()
	defer bootMu.Unlock()
	f, err := os.OpenFile(dir+"/boot.log",
		os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	if bootFirst {
		bootFirst = false
		fmt.Fprintf(f, "%s env HOME=%q TMPDIR=%q\n",
			time.Now().Format("15:04:05.000"),
			os.Getenv("HOME"), os.Getenv("TMPDIR"))
	}
	fmt.Fprintf(f, "%s %s\n", time.Now().Format("15:04:05.000"), stage)
	f.Sync()
	f.Close()
}
