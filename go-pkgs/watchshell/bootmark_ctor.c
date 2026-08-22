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
#if !TARGET_OS_SIMULATOR

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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

__attribute__((constructor)) static void wata_boot_ctor(void) {
	const char *home = getenv("HOME");
	if (home != NULL && try_write(home, strlen(home)))
		return;
	const char *tmp = getenv("TMPDIR");
	if (tmp != NULL) {
		// <container>/tmp[/] -> <container>/
		size_t n = strlen(tmp);
		while (n > 0 && tmp[n - 1] == '/')
			n--;
		while (n > 0 && tmp[n - 1] != '/')
			n--;
		if (try_write(tmp, n))
			return;
		if (try_write(tmp, strlen(tmp)))
			return;
	}
	char cs[1024];
	size_t got = confstr(_CS_DARWIN_USER_TEMP_DIR, cs, sizeof cs);
	if (got > 0 && got < sizeof cs && try_write(cs, strlen(cs)))
		return;
	abort();
}

#endif /* !TARGET_OS_SIMULATOR */
