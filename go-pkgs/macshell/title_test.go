//go:build darwin

// The title seam (plan 0045 slice 3), minus the window: headless SetTitle
// records the words and Title reads them back — which is exactly what the
// mac failure-scenario harness asserts on, so the seam itself is pinned.

package macshell

import "testing"

func TestTitleSeam(t *testing.T) {
	StartHeadless()
	if got := Title(); got != "" {
		t.Fatalf("title before any SetTitle = %q, want empty", got)
	}
	SetTitle("Wata — reconnecting…")
	if got := Title(); got != "Wata — reconnecting…" {
		t.Fatalf("title = %q", got)
	}
	SetTitle("Wata — reconnecting…") // the per-frame call: same words, a no-op
	SetTitle("Wata")
	if got := Title(); got != "Wata" {
		t.Fatalf("title after change = %q", got)
	}
}
