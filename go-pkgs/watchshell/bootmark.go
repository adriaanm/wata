// The boot trace: on a real watch neither stdout nor exit codes reach the
// host (devicectl reported "exit code 0" for a SIGTRAP death, and console
// launches forward nothing), so when launch dies or hangs the only honest
// channels are crash reports and files pulled from the app container. A
// launch that HANGS leaves no crash report worth reading — the 0x8BADF00D
// watchdog record came back with an unwindable main thread — so the app
// writes its own progress: one line per launch stage, timestamped and
// fsynced, appended to the container's boot.log — $HOME when writable, else
// $TMPDIR (a real watch's container root is not writable, so the file lands
// in tmp/ there). Whichever stage is missing from the pulled file is where
// launch stopped. A few tiny writes per launch; it stays on permanently.

//go:build darwin

package watchshell

// void wata_boot_mark_go_alive(void);
import "C"

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

// A mark from package init: it runs after the Go runtime is fully up but
// before main, so its presence or absence splits "runtime never came up"
// from "runtime up, stuck before or inside main".
func init() { BootMark("goinit") }

// openBoot opens the trace file, trying the same directories the dyld
// constructor tries, in the same order — on a real watch the container
// root ($HOME) is NOT writable and the file lands in $TMPDIR, so writing
// only $HOME/boot.log would silently drop every Go-side mark.
func openBoot() *os.File {
	var dirs []string
	if h := os.Getenv("HOME"); h != "" {
		dirs = append(dirs, h)
	}
	if t := os.Getenv("TMPDIR"); t != "" {
		dirs = append(dirs, filepath.Dir(filepath.Clean(t)), t)
	}
	for _, dir := range dirs {
		f, err := os.OpenFile(filepath.Join(dir, "boot.log"),
			os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
		if err == nil {
			return f
		}
	}
	return nil
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
	C.wata_boot_mark_go_alive()
	bootMu.Lock()
	defer bootMu.Unlock()
	f := openBoot()
	if f == nil {
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
