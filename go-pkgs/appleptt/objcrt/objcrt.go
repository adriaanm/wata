// Package objcrt is the hand-written runtime the generated Apple bindings sit
// on: string and error bridging, the NSError** out-parameter slot, and the
// synthesized delegate classes.
//
// Everything here is small, hand-reviewed, and framework-agnostic. The
// generated code (tools/bindgen) contains no logic of its own — it maps
// selectors onto these primitives, so a bug in the bridging is fixed in one
// place rather than in thousands of generated lines.
package objcrt

import (
	"errors"
	"reflect"
	"runtime"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

var (
	selAlloc               = objc.RegisterName("alloc")
	selInit                = objc.RegisterName("init")
	selStringWithUTF8      = objc.RegisterName("stringWithUTF8String:")
	selUTF8String          = objc.RegisterName("UTF8String")
	selLocalizedDesc       = objc.RegisterName("localizedDescription")
	selCode                = objc.RegisterName("code")
	selDomain              = objc.RegisterName("domain")
	selErrorWithDomainCode = objc.RegisterName("errorWithDomain:code:userInfo:")
	selDataWithBytes       = objc.RegisterName("dataWithBytes:length:")
	selGetBytesLength      = objc.RegisterName("getBytes:length:")
	selLength              = objc.RegisterName("length")
)

func init() {
	// Foundation is where NSString and NSError live; the ObjC runtime itself is
	// already loaded by purego/objc. Loading is idempotent and cheap.
	_, _ = purego.Dlopen("/System/Library/Frameworks/Foundation.framework/Foundation",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY)
}

// NSString returns an autoreleased NSString holding s.
func NSString(s string) objc.ID {
	return objc.ID(objc.GetClass("NSString")).Send(selStringWithUTF8, s)
}

// GoString copies an NSString into a Go string. A nil id is the empty string.
func GoString(id objc.ID) string {
	if id == 0 {
		return ""
	}
	return objc.Send[string](id, selUTF8String)
}

// NSData returns an autoreleased NSData holding a copy of b.
//
// The generated bindings refuse every `void *`+length method (a raw pointer
// pair is not a Go type), so byte payloads — push tokens, audio frames —
// cross here instead.
func NSData(b []byte) objc.ID {
	var p unsafe.Pointer
	if len(b) > 0 {
		p = unsafe.Pointer(&b[0])
	}
	d := objc.ID(objc.GetClass("NSData")).Send(selDataWithBytes, p, len(b))
	runtime.KeepAlive(b)
	return d
}

// GoBytes copies an NSData into a fresh Go slice. A nil id is a nil slice.
func GoBytes(id objc.ID) []byte {
	if id == 0 {
		return nil
	}
	n := objc.Send[int](id, selLength)
	if n == 0 {
		return nil
	}
	out := make([]byte, n)
	id.Send(selGetBytesLength, unsafe.Pointer(&out[0]), n)
	runtime.KeepAlive(out)
	return out
}

// NSError is the error an ObjC method reported: its domain, code and localized
// description, kept so a caller can tell them apart.
type NSError struct {
	Domain      string
	Code        int
	Description string
}

func (e *NSError) Error() string {
	if e.Description != "" {
		return e.Description
	}
	return e.Domain + " " + itoa(e.Code)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [24]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// GoError converts an NSError * to a Go error. A nil id is a nil error.
func GoError(id objc.ID) error {
	if id == 0 {
		return nil
	}
	return &NSError{
		Domain:      GoString(id.Send(selDomain)),
		Code:        objc.Send[int](id, selCode),
		Description: GoString(id.Send(selLocalizedDesc)),
	}
}

// NSErrorID converts a Go error back to an NSError *, so an error can be
// handed to a callback that expects one. A nil error is a nil id; an error
// this package produced round-trips its domain and code.
func NSErrorID(err error) objc.ID {
	if err == nil {
		return 0
	}
	domain, code := "WataGoError", 1
	var ns *NSError
	if errors.As(err, &ns) {
		domain, code = ns.Domain, ns.Code
	}
	return objc.ID(objc.GetClass("NSError")).Send(
		selErrorWithDomainCode, NSString(domain), code, objc.ID(0))
}

// ErrOut is an NSError ** out-parameter slot.
//
// The slot holds an objc.ID (a uintptr), never a Go pointer, so the callee
// writing through it cannot store a pointer the Go collector must see.
type ErrOut struct {
	id objc.ID
}

// Ptr is the &error argument to pass as the method's trailing parameter.
func (e *ErrOut) Ptr() unsafe.Pointer { return unsafe.Pointer(&e.id) }

// Err is the error the callee reported, or nil.
func (e *ErrOut) Err() error { return GoError(e.id) }

// BlockHandle is an ObjC block received in a callback, copied so it can be
// invoked after the callback returns.
//
// The block pointer a delegate trampoline receives is only valid for the
// duration of the call; CopyBlock (_Block_copy) is what lets the handle
// outlive it. The generated bindings wrap a handle in a per-signature type
// with a typed Call — Invoke is the untyped core under that.
//
// The contract, from any goroutine:
//   - Invoke may be called any number of times while the handle is live
//     (some ObjC blocks are legitimately repeat-callable; a completion
//     handler is called once by convention, which the framework, not this
//     type, defines).
//   - Release must be called exactly once when done. A second Release
//     panics; Invoke after Release panics — either would otherwise be a
//     use-after-free on the ObjC heap.
type BlockHandle struct {
	mu    sync.RWMutex
	block objc.Block // 0 once released
}

// CopyBlock copies (retains) an incoming block so it survives the callback
// that delivered it. A nil (0) block yields a nil handle: calling through a
// nil handle panics, which is the loud version of "the framework passed no
// block".
func CopyBlock(b objc.Block) *BlockHandle {
	if b == 0 {
		return nil
	}
	return &BlockHandle{block: b.Copy()}
}

// Invoke calls the block through its own invoke pointer.
//
// args are ABI values (objc.ID, integers, floats, bool) — the generated
// per-signature Call methods do the Go-to-ObjC conversions. Per the ObjC
// block convention the block pointer itself rides as argument 0, ahead of
// args. purego.RegisterFunc builds the call, so floats are passed in FP
// registers and the ABI holds for every mappable parameter kind. Blocks
// with a non-void return are not mapped, so Invoke returns nothing.
func (h *BlockHandle) Invoke(args ...any) {
	if h == nil {
		panic("objcrt: Invoke on a nil block handle (the callback received no block)")
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	if h.block == 0 {
		panic("objcrt: incoming block invoked after Release")
	}
	in := make([]reflect.Type, len(args)+1)
	vals := make([]reflect.Value, len(args)+1)
	in[0] = reflect.TypeOf(uintptr(0))
	vals[0] = reflect.ValueOf(uintptr(h.block))
	for i, a := range args {
		in[i+1] = reflect.TypeOf(a)
		vals[i+1] = reflect.ValueOf(a)
	}
	fn := reflect.New(reflect.FuncOf(in, nil, false))
	purego.RegisterFunc(fn.Interface(), h.invokePtr())
	fn.Elem().Call(vals)
}

// Release frees the copied block (_Block_release), exactly once.
func (h *BlockHandle) Release() {
	if h == nil {
		panic("objcrt: Release on a nil block handle (the callback received no block)")
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.block == 0 {
		panic("objcrt: incoming block released twice")
	}
	h.block.Release()
	h.block = 0
}

// blockLayout is the head of the ObjC block ABI's Block_literal
// (https://clang.llvm.org/docs/Block-ABI-Apple.html): isa (8) + flags (4) +
// reserved (4) put invoke at offset 16 on every 64-bit Apple platform.
type blockLayout struct {
	_      uintptr
	_      uint32
	_      uint32
	invoke uintptr
}

// invokePtr reads the function pointer out of the block layout. The word
// held in h.block is an ObjC heap pointer, never a Go pointer, so the
// reinterpretation is invisible to (and safe from) the Go collector.
func (h *BlockHandle) invokePtr() uintptr {
	return (*blockLayout)(*(*unsafe.Pointer)(unsafe.Pointer(&h.block))).invoke
}

// Method is one selector of a synthesized delegate class.
//
// Fn must be func(self objc.ID, cmd objc.SEL, args...). With Enc empty,
// purego derives the ObjC type encoding from Fn's signature. A method whose
// signature carries a C struct by value (or a CGFloatRet return) must instead
// set Enc to the TRUE ObjC type encoding — the frameworks and NSInvocation
// marshal the call from it, and an encoding derived from the Go spelling
// (a struct tag purego invents, "{CGFloatRet=d}" for what is really "d")
// would be silently wrong. Such methods are registered via class_addMethod
// with Enc verbatim.
type Method struct {
	Sel string
	Fn  any
	Enc string
}

// CGFloatRet spells a CGFloat return from a delegate callback. purego's
// NewCallback rejects a plain float64 return, but a one-field struct is an
// HFA of one member — returned in d0, ABI-identical to returning double —
// while the registered method encoding stays the true "d". Generated
// trampolines wrap the user func's float64 into it; user code never sees it.
type CGFloatRet struct{ V float64 }

var delegateSeq atomic.Uint64

// keepAlive holds every synthesized delegate instance. Nothing else references
// them from Go, and the ObjC side holds delegates weakly (PushToTalk) or by
// unowned reference (NSXMLParser), so dropping them would be a use-after-free.
var keepAlive []objc.ID

// NewDelegate registers a fresh ObjC class named after base, conforming to the
// named protocols, with one method per entry of methods, and returns an
// instance of it.
//
// A class per call, because the methods close over Go state. Class
// registration is permanent — the ObjC runtime has no unregister — so
// delegates are meant to be created a bounded number of times (one per channel
// manager, per parser), not per event.
func NewDelegate(base string, protocols []string, methods []Method) objc.ID {
	defs := make([]objc.MethodDef, 0, len(methods))
	for _, m := range methods {
		if m.Enc == "" {
			defs = append(defs, objc.MethodDef{Cmd: objc.RegisterName(m.Sel), Fn: m.Fn})
		}
	}
	protos := make([]*objc.Protocol, 0, len(protocols))
	for _, name := range protocols {
		if p := objc.GetProtocol(name); p != nil {
			protos = append(protos, p)
		}
	}
	name := base + "_" + itoa(int(delegateSeq.Add(1)))
	class, err := objc.RegisterClass(name, objc.GetClass("NSObject"), protos, nil, defs)
	if err != nil {
		panic("objcrt: registering " + name + ": " + err.Error())
	}
	// Methods carrying an explicit encoding go through class_addMethod so the
	// runtime records the TRUE ObjC types (struct encodings, "d" for a
	// CGFloatRet return) rather than an encoding derived from the Go
	// signature. Adding to a registered class is ordinary ObjC (categories do
	// the same), and these methods are only ever called after New returns.
	for _, m := range methods {
		if m.Enc == "" {
			continue
		}
		if !class.AddMethod(objc.RegisterName(m.Sel), objc.IMP(purego.NewCallback(m.Fn)), m.Enc) {
			panic("objcrt: adding " + m.Sel + " (" + m.Enc + ") to " + name)
		}
	}
	obj := objc.ID(class).Send(selAlloc).Send(selInit)
	keepAlive = append(keepAlive, obj)
	return obj
}
