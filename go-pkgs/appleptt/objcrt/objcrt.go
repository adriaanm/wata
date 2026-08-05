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
	"runtime"
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

// Method is one selector of a synthesized delegate class.
//
// Fn must be func(self objc.ID, cmd objc.SEL, args...) — purego derives the
// ObjC type encoding from its signature.
type Method struct {
	Sel string
	Fn  any
}

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
		defs = append(defs, objc.MethodDef{Cmd: objc.RegisterName(m.Sel), Fn: m.Fn})
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
	obj := objc.ID(class).Send(selAlloc).Send(selInit)
	keepAlive = append(keepAlive, obj)
	return obj
}
