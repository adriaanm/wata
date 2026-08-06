// Struct arguments AND returns in Go callbacks on purego v0.11.0-alpha.8,
// where NewCallback accepts reflect.Struct directly (upstream issue #225,
// milestone v0.11.0; unreleased in any stable tag as of 2026-08). The
// callback is declared with the Go struct types themselves — no
// decomposition — and struct RETURNS work too, which the pinned v0.10.2
// cannot express at all (its callback result travels only through x0).
//
// Same proof shape as ../decomp: the caller side is real Foundation/AppKit
// (NSInvocation marshalling from the ObjC type encoding; AppKit driving
// drawRect: in an offscreen render), field values pinned exactly.
//
//go:build darwin && arm64

package upstream

import (
	"runtime"
	"testing"
	"unsafe"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// --- the struct vocabulary under test -------------------------------------

type cgPoint struct{ X, Y float64 }
type cgSize struct{ W, H float64 }
type cgRect struct {
	Origin cgPoint
	Size   cgSize
}
type nsRange struct{ Loc, Len uint64 }
type osVer struct{ Major, Minor, Patch int64 } // non-HFA, 24 B

const rectEnc = "{CGRect={CGPoint=dd}{CGSize=dd}}"
const rangeEnc = "{_NSRange=QQ}"
const verEnc = "{_NSOperatingSystemVersion=qqq}"

// --- raw ObjC runtime plumbing --------------------------------------------

var (
	allocClassPair    func(super objc.Class, name string, extra uintptr) objc.Class
	registerClassPair func(cls objc.Class)
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
	purego.RegisterLibFunc(&poolPush, objcLib, "objc_autoreleasePoolPush")
	purego.RegisterLibFunc(&poolPop, objcLib, "objc_autoreleasePoolPop")
}

func defineClass(t *testing.T, name, super, sel, enc string, fn any) objc.ID {
	t.Helper()
	cls := allocClassPair(objc.GetClass(super), name, 0)
	if cls == 0 {
		t.Fatalf("objc_allocateClassPair(%s) failed", name)
	}
	if !cls.AddMethod(objc.RegisterName(sel), objc.IMP(purego.NewCallback(fn)), enc) {
		t.Fatalf("class_addMethod(%s %s) failed", name, sel)
	}
	registerClassPair(cls)
	return objc.ID(cls).Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
}

// invocationFor builds an NSInvocation for [obj sel] with the given argument
// bytes; Foundation marshals from the type encoding and performs the C call.
func invocationFor(t *testing.T, obj objc.ID, sel string, args ...unsafe.Pointer) objc.ID {
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
	return inv
}

// --- arguments: the same three AAPCS64 classes, struct-typed directly ------

func TestStructArgsArriveTyped(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	var gotRect cgRect
	var gotRange nsRange
	var gotVer osVer
	obj := defineClass(t, "UpSpikeArgs", "NSObject", "takeRect:range:ver:",
		"v@:"+rectEnc+rangeEnc+verEnc,
		func(self, cmd uintptr, r cgRect, rng nsRange, v osVer) {
			gotRect, gotRange, gotVer = r, rng, v
		})

	rect := cgRect{cgPoint{10.5, -2.25}, cgSize{300.125, 44.75}} // HFA, d0–d3
	rng := nsRange{7, 1234567890123}                             // 16 B, GPR pair
	ver := osVer{26, 4, 1}                                       // 24 B, by reference
	invocationFor(t, obj, "takeRect:range:ver:",
		unsafe.Pointer(&rect), unsafe.Pointer(&rng), unsafe.Pointer(&ver))
	if gotRect != rect || gotRange != rng || gotVer != ver {
		t.Errorf("got (%+v, %+v, %+v), want (%+v, %+v, %+v)",
			gotRect, gotRange, gotVer, rect, rng, ver)
	}
}

// --- returns: all three return conventions, impossible on v0.10.2 ---------

func TestRangeReturnInGPRPair(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	want := nsRange{42, 99887766}
	obj := defineClass(t, "UpSpikeRangeRet", "NSObject", "giveRange",
		rangeEnc+"@:",
		func(self, cmd uintptr) nsRange { return want })

	inv := invocationFor(t, obj, "giveRange")
	var got nsRange
	inv.Send(objc.RegisterName("getReturnValue:"), unsafe.Pointer(&got))
	if got != want {
		t.Errorf("NSRange return read back as %+v, want %+v", got, want)
	}
}

func TestHFARectReturnInFloatRegisters(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	want := cgRect{cgPoint{1.5, 2.5}, cgSize{640.25, 480.75}}
	obj := defineClass(t, "UpSpikeRectRet", "NSObject", "giveRect",
		rectEnc+"@:",
		func(self, cmd uintptr) cgRect { return want })

	inv := invocationFor(t, obj, "giveRect")
	var got cgRect
	inv.Send(objc.RegisterName("getReturnValue:"), unsafe.Pointer(&got))
	if got != want {
		t.Errorf("CGRect return read back as %+v, want %+v", got, want)
	}
}

func TestIndirectReturnViaX8(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// 24-byte non-HFA return: the caller passes the result address in x8 and
	// the callback writes the struct there.
	want := osVer{26, 4, 1}
	obj := defineClass(t, "UpSpikeVerRet", "NSObject", "giveVer",
		verEnc+"@:",
		func(self, cmd uintptr) osVer { return want })

	inv := invocationFor(t, obj, "giveVer")
	var got osVer
	inv.Send(objc.RegisterName("getReturnValue:"), unsafe.Pointer(&got))
	if got != want {
		t.Errorf("24-byte return read back as %+v, want %+v", got, want)
	}
}

func TestCGFloatReturnViaOneFieldStruct(t *testing.T) {
	initRuntime(t)
	pool := poolPush()
	defer poolPop(pool)

	// Even on alpha.8 a callback cannot return a plain float64 (not in
	// compileCallback's return-kind list), but a one-field struct is an HFA
	// of one member — returned in d0, ABI-identical to returning CGFloat.
	// This is the shape for CGFloat-returning delegate methods
	// (tableView:heightOfRow: and friends); the method encoding stays "d".
	type cgFloatRet struct{ V float64 }
	obj := defineClass(t, "UpSpikeFloatRet", "NSObject", "giveHeight",
		"d@:",
		func(self, cmd uintptr) cgFloatRet { return cgFloatRet{44.5} })

	inv := invocationFor(t, obj, "giveHeight")
	var got float64
	inv.Send(objc.RegisterName("getReturnValue:"), unsafe.Pointer(&got))
	if got != 44.5 {
		t.Errorf("CGFloat return read back as %v, want 44.5", got)
	}
}

// --- the real consumer shape ----------------------------------------------

func TestAppKitDrivesDrawRectTyped(t *testing.T) {
	initRuntime(t)
	if _, err := purego.Dlopen("/System/Library/Frameworks/AppKit.framework/AppKit", purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		t.Fatalf("dlopen AppKit: %v", err)
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	pool := poolPush()
	defer poolPop(pool)

	var got cgRect
	calls := 0
	cls := allocClassPair(objc.GetClass("NSView"), "UpSpikeDrawView", 0)
	if cls == 0 {
		t.Fatal("objc_allocateClassPair(UpSpikeDrawView) failed")
	}
	if !cls.AddMethod(objc.RegisterName("drawRect:"),
		objc.IMP(purego.NewCallback(func(self, cmd uintptr, dirty cgRect) {
			got = dirty
			calls++
		})),
		"v@:"+rectEnc) {
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
