// The persistent app log (plan 0064): on a physical iPhone the process's
// stdout/stderr are readable only through a tethered `devicectl ... launch
// --console`, so an icon-tap launch logs into the void. TeeLog splices a tee
// under both fds — every line still reaches the original console (tethered
// launches and the simulator harnesses keep their output unchanged) AND a
// file in the app's own sandbox, which `just ios-log` copies off the phone
// afterwards.
//
// This lives in Go because raw fd work (dup/dup2/pipes) is not expressible
// in the Sgola dialect; iosshell is the platform-glue home. The file is a
// debug surface, not a log system: truncated at every open (one run's log),
// growth capped at logCap — past the cap the console copy continues and the
// file copy stops.

//go:build darwin

package watchshell

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
)

// logCap bounds one run's file growth (4 MiB — weeks of wata's line rate).
const logCap = 4 << 20

var (
	logFileMu  sync.Mutex
	logWritten int64
)

// TeeLog redirects fds 1 and 2 (stdout, stderr) through pipes whose reader
// goroutines copy every chunk to both the original fd and a log file
// (created/truncated, 0600). Call FIRST in main, before any output. Returns
// "" on success, else the error text; on failure the fds are left usable (at
// worst one stream is already teed).
//
// The file is `path` where that is creatable, else `$TMPDIR/<basename>` —
// the same fallback boot.log needs and for the same reason: a real watch's
// container root ($HOME) is not writable, so $HOME/Documents cannot even be
// created there and a tee bound to it would drop every line an icon-tap
// launch produces. The first teed line names where the log landed, so a
// pulled file identifies itself and a puller knows which path to try.
func TeeLog(path string) string {
	candidates := []string{path}
	if t := os.Getenv("TMPDIR"); t != "" {
		candidates = append(candidates, filepath.Join(t, filepath.Base(path)))
	}
	var f *os.File
	var err error
	var landed string
	for _, p := range candidates {
		// A watch container starts without Documents/ (an iPhone's has it) —
		// create the parent rather than losing the whole log to ENOENT.
		if dir := filepath.Dir(p); dir != "." && dir != "/" {
			if e := os.MkdirAll(dir, 0o700); e != nil {
				err = e
				continue
			}
		}
		f, err = os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
		if err == nil {
			landed = p
			break
		}
	}
	if f == nil {
		return err.Error()
	}
	if err := teeFd(1, f); err != nil {
		f.Close()
		return err.Error()
	}
	if err := teeFd(2, f); err != nil {
		return err.Error()
	}
	fmt.Fprintf(os.Stdout, "log: tee -> %s\n", landed)
	return ""
}

// teeFd replaces `fd` with a pipe's write end and forks the copier. The
// original fd is dup'd first so the console keeps receiving everything.
func teeFd(fd int, f *os.File) error {
	orig, err := syscall.Dup(fd)
	if err != nil {
		return err
	}
	syscall.CloseOnExec(orig)
	r, w, err := os.Pipe()
	if err != nil {
		syscall.Close(orig)
		return err
	}
	if err := syscall.Dup2(int(w.Fd()), fd); err != nil {
		syscall.Close(orig)
		r.Close()
		w.Close()
		return err
	}
	w.Close() // fd itself now holds the write end
	go copyTee(r, os.NewFile(uintptr(orig), "console"), f)
	return nil
}

// copyTee pumps one stream: console always, the file while under the cap
// (both streams share the counter — the cap is per run, not per fd).
func copyTee(r, console, f *os.File) {
	buf := make([]byte, 4096)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			console.Write(buf[:n])
			logFileMu.Lock()
			if logWritten < logCap {
				f.Write(buf[:n])
				logWritten += int64(n)
			}
			logFileMu.Unlock()
		}
		if err != nil {
			return
		}
	}
}
