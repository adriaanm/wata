// Struct arguments into Go callbacks on the PINNED purego (v0.10.2), which
// rejects reflect.Struct in NewCallback — by register decomposition: the
// callback is declared with the scalar parameters AAPCS64 assigns to the
// struct's registers, and the struct is reassembled in Go.
//
// The caller side is real Foundation/AppKit machinery, not our own msgSend:
// NSInvocation marshals arguments from the method's ObjC type encoding and
// performs the C call to the IMP, and the drawRect: leg has AppKit itself
// drive a synthesized NSView subclass through an offscreen render. If the
// decomposed signature misdeclared even one register, the pinned exact field
// values could not come through.
//
// AAPCS64 classes proven here (darwin/arm64):
//   - HFA (all-float aggregate, ≤4 members after flattening): one float
//     register per member, EVEN ABOVE 16 BYTES — CGRect (32 B) rides d0–d3.
//   - Non-HFA ≤16 B: 1–2 GPRs holding the memory image — NSRange (x2,x3
//     after self/_cmd), and a mixed {double,int64} whose double arrives as
//     raw bits in a GPR.
//   - Non-HFA >16 B: by reference — the IMP receives a pointer to the
//     caller's copy (NSOperatingSystemVersion-shaped, 24 B).
//
//go:build darwin && arm64

package decomp

import (
	"math"
	"runtime"
	"testing"
	"unsafe"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// --- the struct vocabulary under test -------------------------------------

type cgPoint struct{ x, y float64 }
type cgSize struct{ w, h float64 }
type cgRect struct {
	origin cgPoint
	size   cgSize
}
type nsRange struct{ loc, length uint64 }
type fMix struct { // non-HFA, 16 B: double + int64
	f float64
	n int64
}
type osVer struct{ major, minor, patch int64 } // non-HFA, 24 B: by reference

// --- raw ObjC runtime plumbing (custom type encodings) --------------------
//
// objc.RegisterClass derives the encoding from the Go signature, which cannot
// spell a struct here; NSInvocation reads the encoding to marshal the call,
// so the method must be added with the true struct encoding while the IMP is
// the decomposed callback. Hence class_addMethod directly.

var (
	allocClassPair    func(super objc.Class, name string, extra uintptr) objc.Class
	registerClassPair func(cls objc.Class)
	classAddMethod    func(cls objc.Class, sel objc.SEL, imp uintptr, types string) bool
	poolPush          func() uintptr
	poolPop           func(p uintptr)
)

func initRuntime(t *testing.T) {
	t.Helper()
	objcLib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
	if err != nil {
		t.Fatalf("dlopen libobjc: %v", err)
	}
	if _, err := purego.Dlopen("/System/Library/Frameworks/Foundation.framework/Foundation", purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		t.Fatalf("dlopen Foundation: %v", err)
	}
	purego.RegisterLibFunc(&allocClassPair, objcLib, "objc_allocateClassPair")
	purego.RegisterLibFunc(&registerClassPair, objcLib, "objc_registerClassPair")
	purego.RegisterLibFunc(&classAddMethod, objcLib, "class_addMethod")
	purego.RegisterLibFunc(&poolPush, objcLib, "objc_autoreleasePoolPush")
	purego.RegisterLibFunc(&poolPop, objcLib, "objc_autoreleasePoolPop")
}

// defineClass registers a fresh subclass with one method whose ObjC type
// encoding is enc and whose IMP is the (decomposed) Go callback fn.
func defineClass(t *testing.T, name, super, sel, enc string, fn any) objc.ID {
	t.Helper()
	cls := allocClassPair(objc.GetClass(super), name, 0)
	if cls == 0 {
		t.Fatalf("objc_allocateClassPair(%s) failed", name)
	}
	if !classAddMethod(cls, objc.RegisterName(sel), purego.NewCallback(fn), enc) {
		t.Fatalf("class_addMethod(%s %s) failed", name, sel)
	}
	registerClassPair(cls)
	return objc.ID(cls).Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
}

// invoke drives [obj sel] through NSInvocation — Foundation reads the type
// encoding, marshals each argument from memory, and performs the real C call.
func invoke(t *testing.T, obj objc.ID, sel string, args ...unsafe.Pointer) {
	t.Helper()
	selID := objc.RegisterName(sel)
	sig := objc.ID(obj.Class()).Send(objc.RegisterName("instanceMethodSignatureForSelector:"), selID)
	if sig == 0 {
		t.Fatalf("no method signature for %s", sel)
	}
	inv := objc.ID(objc.GetClass("NSInvocation")).Send(objc.RegisterName("invocationWithMethodSignature:"), sig)
	inv.Send(objc.RegisterName("setSelector:"), selID)
	inv.Send(objc.RegisterName("setTarget:"), obj)
	for i, a := range args {
		inv.Send(objc.RegisterName("setArgument:atIndex:"), a, i+2)
	}
	inv.Send(objc.RegisterName("invoke"))
	runtime.KeepAlive(args)
}

// --- the tests ------------------------------------------------------------

func TestHFARect32BytesInFloatRegisters(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// CGRect flattens to 4 doubles: an HFA, so despite being 32 bytes it is
	// passed in d0–d3 — the decomposed IMP declares 4 float64 parameters.
	var got cgRect
	obj := defineClass(t, "SpikeRectArg", "NSObject", "takeRect:",
		"v@:{CGRect={CGPoint=dd}{CGSize=dd}}",
		func(self, cmd uintptr, x, y, w, h float64) {
			got = cgRect{cgPoint{x, y}, cgSize{w, h}}
		})

	want := cgRect{cgPoint{10.5, -2.25}, cgSize{300.125, 44.75}}
	arg := want
	invoke(t, obj, "takeRect:", unsafe.Pointer(&arg))
	if got != want {
		t.Errorf("CGRect arrived as %+v, want %+v", got, want)
	}
}

func TestNonHFARange16BytesInGPRPair(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// NSRange (2×uint64, non-HFA, exactly 16 B) rides x2/x3 after self/_cmd:
	// the decomposed IMP declares two uintptr parameters.
	var got nsRange
	obj := defineClass(t, "SpikeRangeArg", "NSObject", "takeRange:",
		"v@:{_NSRange=QQ}",
		func(self, cmd, loc, length uintptr) {
			got = nsRange{uint64(loc), uint64(length)}
		})

	want := nsRange{7, 1234567890123}
	arg := want
	invoke(t, obj, "takeRange:", unsafe.Pointer(&arg))
	if got != want {
		t.Errorf("NSRange arrived as %+v, want %+v", got, want)
	}
}

func TestMixedStructDoubleBitsInGPR(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// {double,int64} is 16 B but NOT an HFA (mixed classes), so both words go
	// in GPRs: the double arrives as raw bits in an integer register and is
	// reassembled with math.Float64frombits.
	var got fMix
	obj := defineClass(t, "SpikeMixArg", "NSObject", "takeMix:",
		"v@:{FMix=dq}",
		func(self, cmd, fbits, n uintptr) {
			got = fMix{math.Float64frombits(uint64(fbits)), int64(n)}
		})

	want := fMix{3.1415926535, -987654321}
	arg := want
	invoke(t, obj, "takeMix:", unsafe.Pointer(&arg))
	if got != want {
		t.Errorf("mixed struct arrived as %+v, want %+v", got, want)
	}
}

func TestNonHFA24BytesByReference(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// A 24-byte non-HFA composite is passed by reference: the IMP receives a
	// pointer to the caller's copy in a GPR and copies the fields out. The
	// callee does not own the memory past the call, so it must copy eagerly.
	var got osVer
	obj := defineClass(t, "SpikeVerArg", "NSObject", "takeVer:",
		"v@:{_NSOperatingSystemVersion=qqq}",
		func(self, cmd, p uintptr) {
			got = *(*osVer)(unsafe.Pointer(p))
		})

	want := osVer{26, 4, 1}
	arg := want
	invoke(t, obj, "takeVer:", unsafe.Pointer(&arg))
	if got != want {
		t.Errorf("24-byte struct arrived as %+v, want %+v", got, want)
	}
}

func TestObjectThenRectInterleavesClasses(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// The delegate shape that matters: object arguments and a struct in one
	// signature. GPR and float-register counters advance independently —
	// (self, _cmd, id, NSRange, CGPoint) is x0,x1,x2,(x3,x4),(d0,d1).
	var gotObj uintptr
	var gotRange nsRange
	var gotPt cgPoint
	obj := defineClass(t, "SpikeInterleave", "NSObject", "takeObj:range:point:",
		"v@:@{_NSRange=QQ}{CGPoint=dd}",
		func(self, cmd, o, loc, length uintptr, x, y float64) {
			gotObj = o
			gotRange = nsRange{uint64(loc), uint64(length)}
			gotPt = cgPoint{x, y}
		})

	marker := objc.ID(objc.GetClass("NSObject")).Send(objc.RegisterName("class"))
	rng := nsRange{3, 9}
	pt := cgPoint{-1.5, 2.5}
	invoke(t, obj, "takeObj:range:point:",
		unsafe.Pointer(&marker), unsafe.Pointer(&rng), unsafe.Pointer(&pt))
	if gotObj != uintptr(marker) || gotRange != rng || gotPt != pt {
		t.Errorf("got (obj %#x, %+v, %+v), want (%#x, %+v, %+v)",
			gotObj, gotRange, gotPt, uintptr(marker), rng, pt)
	}
}

func TestAppKitDrivesDrawRect(t *testing.T) {
	initRuntime(t)
	if _, err := purego.Dlopen("/System/Library/Frameworks/AppKit.framework/AppKit", purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		t.Fatalf("dlopen AppKit: %v", err)
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	pool := poolPush()
	defer poolPop(pool)

	// The real consumer shape: AppKit itself calls -[NSView drawRect:] on a
	// synthesized subclass during an offscreen render, passing the dirty rect
	// (the view's bounds here) as a 32-byte HFA in d0–d3.
	var got cgRect
	calls := 0
	cls := allocClassPair(objc.GetClass("NSView"), "SpikeDrawView", 0)
	if cls == 0 {
		t.Fatal("objc_allocateClassPair(SpikeDrawView) failed")
	}
	if !classAddMethod(cls, objc.RegisterName("drawRect:"),
		purego.NewCallback(func(self, cmd uintptr, x, y, w, h float64) {
			got = cgRect{cgPoint{x, y}, cgSize{w, h}}
			calls++
		}),
		"v@:{CGRect={CGPoint=dd}{CGSize=dd}}") {
		t.Fatal("class_addMethod(drawRect:) failed")
	}
	registerClassPair(cls)

	frame := cgRect{cgPoint{0, 0}, cgSize{64, 32}}
	view := objc.ID(cls).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), frame)
	if view == 0 {
		t.Fatal("initWithFrame: returned nil")
	}
	rep := view.Send(objc.RegisterName("bitmapImageRepForCachingDisplayInRect:"), frame)
	if rep == 0 {
		t.Fatal("bitmapImageRepForCachingDisplayInRect: returned nil")
	}
	view.Send(objc.RegisterName("cacheDisplayInRect:toBitmapImageRep:"), frame, rep)

	if calls == 0 {
		t.Fatal("AppKit never called drawRect:")
	}
	if got != frame {
		t.Errorf("drawRect: saw %+v, want %+v", got, frame)
	}
}
