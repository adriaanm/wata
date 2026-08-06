// The dispatch seam, proven headless: MainQueue drains only under a
// runloop, so the tests exercise the identical Async code path on a private
// serial queue, which libdispatch drains on its own worker threads.

//go:build darwin

package nativeui

import (
	"testing"
	"time"
)

func TestSerialQueueRunsSubmissionsInOrder(t *testing.T) {
	q := NewSerialQueue("wata.nativeui.test")
	done := make(chan int, 3)
	for i := 0; i < 3; i++ {
		i := i
		q.Async(func() { done <- i })
	}
	for want := 0; want < 3; want++ {
		select {
		case got := <-done:
			if got != want {
				t.Fatalf("ran out of order: got %d, want %d", got, want)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("dispatch never ran the work item")
		}
	}
}

func TestMainQueueHandleResolves(t *testing.T) {
	// Headless, nothing drains the main queue — but the handle must resolve
	// and accept work without blocking the submitter.
	q := MainQueue()
	if q.q == 0 {
		t.Fatal("main queue symbol did not resolve")
	}
	doneSubmitting := make(chan struct{})
	go func() {
		q.Async(func() {})
		close(doneSubmitting)
	}()
	select {
	case <-doneSubmitting:
	case <-time.After(5 * time.Second):
		t.Fatal("Async on the main queue blocked the submitter")
	}
}
