//go:build darwin

package mackeychain

import (
	"errors"
	"os"
	"testing"
)

// A service name nothing else uses, so the test never touches a real
// credential and leaves nothing behind.
const svc = "wata-mackeychain-test"

func TestRoundTrip(t *testing.T) {
	if !Available() {
		t.Skip("Security.framework unavailable")
	}
	acct := "alice@" + os.Getenv("USER")
	defer Delete(svc, acct)

	if _, err := Get(svc, acct); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a fresh account should miss cleanly, got %v", err)
	}
	if err := Set(svc, acct, "syt_first"); err != nil {
		t.Fatalf("Set: %v", err)
	}
	got, err := Get(svc, acct)
	if err != nil || got != "syt_first" {
		t.Fatalf("Get after Set = %q, %v", got, err)
	}
	// Overwriting must go through the update path, not leave a duplicate.
	if err := Set(svc, acct, "syt_second"); err != nil {
		t.Fatalf("Set (overwrite): %v", err)
	}
	got, err = Get(svc, acct)
	if err != nil || got != "syt_second" {
		t.Fatalf("Get after overwrite = %q, %v", got, err)
	}
	if err := Delete(svc, acct); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := Get(svc, acct); !errors.Is(err, ErrNotFound) {
		t.Fatalf("after Delete, want ErrNotFound, got %v", err)
	}
	// Deleting what is already gone is the caller's intent either way.
	if err := Delete(svc, acct); err != nil {
		t.Fatalf("Delete (absent): %v", err)
	}
}

// Empty secrets and long ones both round-trip: a token is neither.
func TestSizes(t *testing.T) {
	if !Available() {
		t.Skip("Security.framework unavailable")
	}
	acct := "sizes@" + os.Getenv("USER")
	defer Delete(svc, acct)
	long := make([]byte, 4096)
	for i := range long {
		long[i] = byte('a' + i%26)
	}
	for _, s := range []string{"", "x", string(long)} {
		if err := Set(svc, acct, s); err != nil {
			t.Fatalf("Set(%d bytes): %v", len(s), err)
		}
		got, err := Get(svc, acct)
		if err != nil || got != s {
			t.Fatalf("round trip of %d bytes: got %d bytes, %v", len(s), len(got), err)
		}
	}
}
