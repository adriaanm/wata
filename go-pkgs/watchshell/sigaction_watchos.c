// WATCH-SIGACTION-FATAL: a real watch refuses the Go runtime's signal setup.
//
// watchOS's libsystem_c aborts any process that calls sigaction() for a
// fatal signal — the crash report's application-specific info reads
// "sigaction on fatal signals is not supported", SIGTRAP out of
// libsystem_c, before main. The Go runtime installs exactly those handlers
// (SIGSEGV, SIGBUS, ...) during startup, so an unmodified Go watch app dies
// on the wrist before any of its code runs. The simulator's libsystem_c
// permits the call, which is why every simulator gate passes without this
// file doing anything.
//
// The fix: define sigaction ourselves. The Go runtime's reference resolves
// to this definition at static link (it beats libSystem's), so we can lie —
// report success without installing anything. The cost, on device only: a
// fault the runtime would have turned into a panic (a nil-pointer
// dereference) is a hard crash with a system report instead of a Go
// traceback. The simulator keeps Go's full signal machinery: this whole
// file compiles away there, so the gates' semantics are untouched.

// WATCH-ASYNCPREEMPT-NULL-UCONTEXT: watchOS delivers signals to SA_SIGINFO
// handlers with a NULL ucontext argument. Go's signal handlers read the
// interrupted registers out of that ucontext — doSigPreempt's
// `ldr x4, [x4, #0x30]` is uc_mcontext off the NULL — and the fault
// re-raises inside the handler forever: ~20s of pure system time on an
// unwindable main thread until the 0x8BADF00D launch watchdog, the crash
// report reading KERN_INVALID_ADDRESS at 0x30. Seen live for SIGURG, the
// signal Go's async preemption rides (sysmon sends it at any goroutine
// running >10ms — a package initializer is enough); every other
// Go-installed handler (SIGPROF profiling, os/signal forwarding) has the
// same shape. Found by the boot-trace sampler (bootmark_ctor.c),
// 2026-08-22 on watchOS 26.6. GODEBUG=asyncpreemptoff=1 from a dyld
// constructor does NOT fix it: Go reads its environment from the original
// stack envp at rt0, so a constructor setenv is invisible to the runtime.
//
// So on a real watch NO Go handler may be installed at all: the
// interposer below reports success for EVERY install and installs
// nothing. SIGURG's default disposition is discard, so sysmon's preempt
// sends become harmless no-ops and preemption is cooperative only (the
// pre-Go-1.14 behavior); the cost is that a tight non-yielding loop can
// delay a GC stop-the-world, which none of our inits or frame work does.
// SIGPIPE's default would KILL, and Go's usual in-handler ignore is gone
// with the rest, so the constructor parks it on SIG_IGN — a disposition,
// not a handler, so no ucontext is ever read (Go sets SO_NOSIGPIPE on
// sockets; this covers stray pipe writes). os/signal.Notify can never
// fire on the wrist; nothing there uses it.

#include <TargetConditionals.h>
#if !TARGET_OS_SIMULATOR

#include <dlfcn.h>
#include <signal.h>
#include <string.h>

__attribute__((constructor)) static void wata_sigpipe_ignore(void) {
	signal(SIGPIPE, SIG_IGN);
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *oact) {
	static int (*real)(int, const struct sigaction *, struct sigaction *);
	if (act != NULL) {
		// Every install is swallowed: fatal signals because libsystem_c
		// aborts the process for asking (WATCH-SIGACTION-FATAL), the rest
		// because a handler that ever fired would die reading the NULL
		// ucontext (WATCH-ASYNCPREEMPT-NULL-UCONTEXT above).
		if (oact != NULL)
			memset(oact, 0, sizeof *oact);
		return 0;
	}
	if (real == NULL)
		real = (int (*)(int, const struct sigaction *, struct sigaction *))
		    dlsym(RTLD_NEXT, "sigaction");
	return real(sig, act, oact);
}

#endif /* !TARGET_OS_SIMULATOR */
