// The incoming-block handle against the live ObjC runtime.
//
// The block under test is built with objc.NewBlock — a real heap block whose
// invoke pointer purego minted — so Invoke exercises the exact machinery a
// framework-created block needs: the copied pointer as argument 0, the
// declared arguments in integer and FP registers, cross-goroutine calls, and
// the release semantics.
//
//go:build darwin && objcruntime

package objcrt

import (
	"testing"

	"github.com/ebitengine/purego/objc"
)

// receive plays the framework side: it hands the raw block to CopyBlock (what
// a generated trampoline does at entry) and releases its own reference, so
// the handle's copy is the only thing keeping the block alive.
func receive(t *testing.T, raw objc.Block) *BlockHandle {
	t.Helper()
	h := CopyBlock(raw)
	if h == nil {
		t.Fatal("CopyBlock returned nil for a live block")
	}
	raw.Release()
	return h
}

func TestInvokeCarriesArgumentsIntact(t *testing.T) {
	type got struct {
		f float64
		n int
		o objc.ID
	}
	ch := make(chan got, 1)
	h := receive(t, objc.NewBlock(func(_ objc.Block, f float64, n int, o objc.ID) {
		ch <- got{f, n, o}
	}))
	defer h.Release()

	obj := objc.ID(objc.GetClass("NSObject")).Send(objc.RegisterName("class"))
	go h.Invoke(3.5, -42, obj) // any goroutine, after the "callback" returned

	g := <-ch
	if g.f != 3.5 || g.n != -42 || g.o != obj {
		t.Errorf("block saw (%v, %v, %#x), want (3.5, -42, %#x)", g.f, g.n, g.o, obj)
	}
}

func TestInvokeMayRepeatWhileLive(t *testing.T) {
	calls := 0
	h := receive(t, objc.NewBlock(func(_ objc.Block) { calls++ }))
	h.Invoke()
	h.Invoke()
	h.Release()
	if calls != 2 {
		t.Errorf("block ran %d times, want 2", calls)
	}
}

func TestDoubleReleasePanics(t *testing.T) {
	h := receive(t, objc.NewBlock(func(_ objc.Block) {}))
	h.Release()
	defer func() {
		if recover() == nil {
			t.Error("second Release did not panic")
		}
	}()
	h.Release()
}

func TestInvokeAfterReleasePanics(t *testing.T) {
	h := receive(t, objc.NewBlock(func(_ objc.Block) {}))
	h.Release()
	defer func() {
		if recover() == nil {
			t.Error("Invoke after Release did not panic")
		}
	}()
	h.Invoke()
}

func TestNilBlockYieldsNilHandleAndNilHandlePanics(t *testing.T) {
	if CopyBlock(0) != nil {
		t.Fatal("CopyBlock(0) != nil")
	}
	var h *BlockHandle
	defer func() {
		if recover() == nil {
			t.Error("Invoke on a nil handle did not panic")
		}
	}()
	h.Invoke()
}
