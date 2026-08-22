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
// report success for the fatal set without installing anything, and pass
// every other signal (Go's async preemption rides SIGURG) to the real
// libSystem function. The cost, on device only: a fault the runtime would
// have turned into a panic (a nil-pointer dereference) is a hard crash with
// a system report instead of a Go traceback. The simulator keeps Go's full
// signal machinery: this whole file compiles away there, so the gates'
// semantics are untouched.

#include <TargetConditionals.h>
#if !TARGET_OS_SIMULATOR

#include <dlfcn.h>
#include <signal.h>
#include <string.h>

static int wata_fatal_signal(int sig) {
	switch (sig) {
	case SIGILL:
	case SIGTRAP:
	case SIGABRT:
	case SIGFPE:
	case SIGBUS:
	case SIGSEGV:
	case SIGSYS:
		return 1;
	default:
		return 0;
	}
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *oact) {
	static int (*real)(int, const struct sigaction *, struct sigaction *);
	if (act != NULL && wata_fatal_signal(sig)) {
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
