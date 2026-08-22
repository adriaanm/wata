// The earliest boot mark: a dyld constructor, running before the Go
// runtime's first instruction. On a real watch the Go runtime has been
// seen to die between signal init and main with no crash report and no
// output, so the trace needs a stamp that does not depend on Go being
// alive: "cinit" in the container's boot.log says the binary loaded and
// dyld ran our initializers; whatever is missing after it is the runtime's.
//
// Three candidate directories, because a watch app's launchd environment
// is not trusted to carry HOME or TMPDIR: $HOME, the parent of $TMPDIR,
// and confstr(_CS_DARWIN_USER_TEMP_DIR), which asks libc directly and
// needs no environment at all. If none is writable the constructor
// abort()s — turning "nowhere to write" into a crash report, the one
// launch channel a real watch always honours. Device-only, like the
// sigaction interposer beside it.

#include <TargetConditionals.h>

// Set by Go the first time BootMark runs; read by the sampler thread so a
// healthy launch stops being profiled the moment Go is provably alive.
// Defined unconditionally so the Go side links on the simulator too.
volatile int wata_boot_go_alive;

void wata_boot_mark_go_alive(void) { wata_boot_go_alive = 1; }

#if !TARGET_OS_SIMULATOR

#include <dlfcn.h>
#include <fcntl.h>
#include <mach/mach.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// The boot.log path the constructor resolved, kept for the sampler thread.
static char wata_boot_path[1024];

static int try_write(const char *dir, size_t dirlen) {
	char path[1024];
	if (dirlen == 0 || dirlen + 10 >= sizeof path)
		return 0;
	memcpy(path, dir, dirlen);
	if (path[dirlen - 1] != '/')
		path[dirlen++] = '/';
	strcpy(path + dirlen, "boot.log");
	int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
	if (fd < 0)
		return 0;
	strcpy(wata_boot_path, path);
	const char *home = getenv("HOME");
	const char *tmp = getenv("TMPDIR");
	char line[2048];
	int n = snprintf(line, sizeof line, "cinit HOME=%s TMPDIR=%s\n",
	    home ? home : "(unset)", tmp ? tmp : "(unset)");
	if (n > 0)
		write(fd, line, (size_t)n);
	fsync(fd);
	close(fd);
	return 1;
}

// The pre-main sampler (WATCH-DEVICE-PREMAIN): on a real watch the app has
// been seen to spin ~20s of system time between dyld initializers and Go
// main, with a main thread the crash reporter cannot unwind. The one code
// we know runs is this constructor, so it profiles the hang itself: a
// detached thread samples the main thread's PC/LR and frame-pointer chain
// via thread_get_state every 150ms and appends the addresses to boot.log
// (with the image base, so `atos -l` symbolicates them offline). It stops
// as soon as Go marks itself alive, so a healthy launch pays a few lines.
// The frame walk reads memory with vm_read_overwrite — a garbage fp must
// not crash the profiler that exists to observe a crash.

#define WATA_SAMPLES 100
#define WATA_FRAMES 16

static thread_act_t wata_main_thread;

// The watchOS SDK annotates the thread_* mach calls __API_UNAVAILABLE —
// a compile-time gate, not a missing symbol (the traps are in the shared
// kernel). Resolve them at runtime; if any is genuinely absent the
// sampler says so in the trace and stands down instead of crashing.
typedef kern_return_t (*wata_thr_op_t)(thread_act_t);
typedef kern_return_t (*wata_thr_get_t)(thread_act_t, int,
    thread_state_t, mach_msg_type_number_t *);

static void *wata_boot_sampler(void *arg) {
	(void)arg;
	int fd = open(wata_boot_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
	if (fd < 0)
		return NULL;
	// A crash-looping install relaunches every few seconds forever; do
	// not let the trace file grow without bound.
	struct stat st_;
	if (fstat(fd, &st_) == 0 && st_.st_size > 256 * 1024) {
		close(fd);
		return NULL;
	}
	char line[1024];
	Dl_info di;
	unsigned long base = 0;
	if (dladdr((void *)&wata_boot_sampler, &di))
		base = (unsigned long)di.dli_fbase;
	wata_thr_op_t p_suspend =
	    (wata_thr_op_t)dlsym(RTLD_DEFAULT, "thread_suspend");
	wata_thr_op_t p_resume =
	    (wata_thr_op_t)dlsym(RTLD_DEFAULT, "thread_resume");
	wata_thr_get_t p_get =
	    (wata_thr_get_t)dlsym(RTLD_DEFAULT, "thread_get_state");
	int n = snprintf(line, sizeof line, "sampler base=0x%lx thrapi=%d\n",
	    base, p_suspend && p_resume && p_get);
	write(fd, line, (size_t)n);
	fsync(fd);
	if (!(p_suspend && p_resume && p_get)) {
		close(fd);
		return NULL;
	}
	uint64_t prev_pc = 0;
	int reps = 0;
	for (int i = 0; i < WATA_SAMPLES && !wata_boot_go_alive; i++) {
		usleep(150000);
		arm_thread_state64_t st;
		mach_msg_type_number_t cnt = ARM_THREAD_STATE64_COUNT;
		if (p_suspend(wata_main_thread) != KERN_SUCCESS)
			break;
		kern_return_t kr = p_get(wata_main_thread,
		    ARM_THREAD_STATE64, (thread_state_t)&st, &cnt);
		uint64_t frames[WATA_FRAMES];
		int nf = 0;
		if (kr == KERN_SUCCESS) {
			uint64_t fp = st.__fp;
			while (nf < WATA_FRAMES && fp != 0 && (fp & 7) == 0) {
				uint64_t pair[2];
				vm_size_t got = 0;
				if (vm_read_overwrite(mach_task_self(),
				        (vm_address_t)fp, sizeof pair,
				        (vm_address_t)pair, &got) != KERN_SUCCESS ||
				    got != sizeof pair)
					break;
				frames[nf++] = pair[1];
				if (pair[0] <= fp)
					break;
				fp = pair[0];
			}
		}
		p_resume(wata_main_thread);
		if (kr != KERN_SUCCESS)
			continue;
		if (st.__pc == prev_pc && nf == 0) {
			reps++;
			continue;
		}
		int off = snprintf(line, sizeof line,
		    "s%03d pc=0x%llx lr=0x%llx fp=0x%llx sp=0x%llx x0=0x%llx",
		    i, (unsigned long long)st.__pc, (unsigned long long)st.__lr,
		    (unsigned long long)st.__fp, (unsigned long long)st.__sp,
		    (unsigned long long)st.__x[0]);
		if (reps > 0 && off < (int)sizeof line)
			off += snprintf(line + off, sizeof line - off,
			    " (prev pc x%d)", reps);
		for (int f = 0; f < nf && off < (int)sizeof line; f++)
			off += snprintf(line + off, sizeof line - off,
			    " %llx", (unsigned long long)frames[f]);
		if (off < (int)sizeof line)
			line[off++] = '\n';
		write(fd, line, (size_t)off);
		fsync(fd);
		prev_pc = st.__pc;
		reps = 0;
	}
	n = snprintf(line, sizeof line, "sampler done alive=%d reps=%d\n",
	    wata_boot_go_alive, reps);
	write(fd, line, (size_t)n);
	fsync(fd);
	close(fd);
	return NULL;
}

__attribute__((constructor)) static void wata_boot_ctor(void) {
	const char *home = getenv("HOME");
	int ok = home != NULL && try_write(home, strlen(home));
	const char *tmp = getenv("TMPDIR");
	if (!ok && tmp != NULL) {
		// <container>/tmp[/] -> <container>/
		size_t n = strlen(tmp);
		while (n > 0 && tmp[n - 1] == '/')
			n--;
		while (n > 0 && tmp[n - 1] != '/')
			n--;
		ok = try_write(tmp, n) || try_write(tmp, strlen(tmp));
	}
	if (!ok) {
		char cs[1024];
		size_t got = confstr(_CS_DARWIN_USER_TEMP_DIR, cs, sizeof cs);
		ok = got > 0 && got < sizeof cs && try_write(cs, strlen(cs));
	}
	if (!ok)
		abort();
	// dyld runs initializers on the main thread, so this port names the
	// thread the launch will live or die on.
	wata_main_thread = mach_thread_self();
	pthread_t t;
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
	pthread_create(&t, &attr, wata_boot_sampler, NULL);
	pthread_attr_destroy(&attr);
}

#endif /* !TARGET_OS_SIMULATOR */
