// The macOS Keychain seam: Security.framework's SecItem API over purego.
//
// wata-mac's credentials are a token it may rewrite on any login and a
// password it should never have had in a shell history. Both are generic
// passwords in the login keychain, keyed (service, account) — the caller
// picks the account so two users, or one user against two homeservers, do
// not share an item.
//
// WHY HAND-WRITTEN, not tools/bindgen. SecItemAdd and friends are plain C
// functions over CFDictionary, and their keys (kSecClass, kSecAttrService,
// …) are CFStringRef GLOBALS reached by dlsym — none of it is the
// Objective-C surface the generator covers. The shape is the one
// nativeui/dispatch.go already uses for libdispatch.
//
// MEMORY. Every CF object created here is released in the same call that
// created it; nothing CF-owned escapes into Go, and nothing Go-owned is
// handed to CF beyond the lifetime of the call. The one exception is the
// CFDataRef a copy returns, which is released as soon as its bytes are
// copied into a Go string.
//
// SIGNING. Keychain ACLs are keyed to the binary's code signature, so an
// unsigned build gets a new identity on every rebuild and macOS re-prompts.
// That is a development-time cost of not having a signed bundle, not a bug
// here (plan 0036).
//
// `go vet` reports "possible misuse of unsafe.Pointer" twice, at `deref` and
// at the CFData read. Both are dlsym-returned C addresses being dereferenced,
// which is what the unsafeptr check exists to find and what FFI legitimately
// does — there is no uintptr-free way to reach a CFStringRef global through
// purego. `go test` (whose vet subset omits unsafeptr) and `go build` are
// clean; ci runs neither `go vet` nor this package's tests.

//go:build darwin

package mackeychain

import (
	"errors"
	"sync"
	"unsafe"

	"github.com/ebitengine/purego"
)

// ErrNotFound is a clean miss: no item for that (service, account). It is
// the ordinary case on a first run, so callers branch on it rather than
// treating it as failure.
var ErrNotFound = errors.New("mackeychain: no such item")

const (
	errSecSuccess       = 0
	errSecItemNotFound  = -25300
	errSecDuplicateItem = -25299
	// kCFStringEncodingUTF8
	cfUTF8 = 0x08000100
)

var (
	initOnce sync.Once
	initErr  error

	secItemAdd          func(query, result uintptr) int32
	secItemCopyMatching func(query, result uintptr) int32
	secItemUpdate       func(query, attrs uintptr) int32
	secItemDelete       func(query uintptr) int32

	cfStringCreate      func(alloc uintptr, cstr []byte, enc uint32) uintptr
	cfDataCreate        func(alloc uintptr, bytes []byte, length int) uintptr
	cfDataGetLength     func(d uintptr) int
	cfDataGetBytePtr    func(d uintptr) uintptr
	cfDictCreateMutable func(alloc uintptr, cap int, keyCb, valCb uintptr) uintptr
	cfDictSetValue      func(d, k, v uintptr)
	cfRelease           func(o uintptr)

	// the CFStringRef globals; each dlsym gives the ADDRESS of the pointer,
	// so the value is one dereference away.
	kSecClass                      uintptr
	kSecClassGenericPassword       uintptr
	kSecAttrService                uintptr
	kSecAttrAccount                uintptr
	kSecValueData                  uintptr
	kSecReturnData                 uintptr
	kSecMatchLimit                 uintptr
	kSecMatchLimitOne              uintptr
	kSecAttrAccessible             uintptr
	kSecAttrAccessibleWhenUnlocked uintptr
	kCFBooleanTrue                 uintptr
	kCFTypeDictKeyCbs              uintptr
	kCFTypeDictValueCbs            uintptr
)

func deref(p uintptr) uintptr { return *(*uintptr)(unsafe.Pointer(p)) }

func sym(lib uintptr, name string) (uintptr, error) {
	p, err := purego.Dlsym(lib, name)
	if err != nil {
		return 0, err
	}
	return deref(p), nil
}

func initialize() error {
	initOnce.Do(func() {
		sec, err := purego.Dlopen(
			"/System/Library/Frameworks/Security.framework/Security",
			purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			initErr = errors.New("mackeychain: dlopen Security: " + err.Error())
			return
		}
		cf, err := purego.Dlopen(
			"/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
			purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			initErr = errors.New("mackeychain: dlopen CoreFoundation: " + err.Error())
			return
		}
		purego.RegisterLibFunc(&secItemAdd, sec, "SecItemAdd")
		purego.RegisterLibFunc(&secItemCopyMatching, sec, "SecItemCopyMatching")
		purego.RegisterLibFunc(&secItemUpdate, sec, "SecItemUpdate")
		purego.RegisterLibFunc(&secItemDelete, sec, "SecItemDelete")
		purego.RegisterLibFunc(&cfStringCreate, cf, "CFStringCreateWithCString")
		purego.RegisterLibFunc(&cfDataCreate, cf, "CFDataCreate")
		purego.RegisterLibFunc(&cfDataGetLength, cf, "CFDataGetLength")
		purego.RegisterLibFunc(&cfDataGetBytePtr, cf, "CFDataGetBytePtr")
		purego.RegisterLibFunc(&cfDictCreateMutable, cf, "CFDictionaryCreateMutable")
		purego.RegisterLibFunc(&cfDictSetValue, cf, "CFDictionarySetValue")
		purego.RegisterLibFunc(&cfRelease, cf, "CFRelease")

		globals := []struct {
			dst  *uintptr
			lib  uintptr
			name string
		}{
			{&kSecClass, sec, "kSecClass"},
			{&kSecClassGenericPassword, sec, "kSecClassGenericPassword"},
			{&kSecAttrService, sec, "kSecAttrService"},
			{&kSecAttrAccount, sec, "kSecAttrAccount"},
			{&kSecValueData, sec, "kSecValueData"},
			{&kSecReturnData, sec, "kSecReturnData"},
			{&kSecMatchLimit, sec, "kSecMatchLimit"},
			{&kSecMatchLimitOne, sec, "kSecMatchLimitOne"},
			{&kSecAttrAccessible, sec, "kSecAttrAccessible"},
			{&kSecAttrAccessibleWhenUnlocked, sec, "kSecAttrAccessibleWhenUnlocked"},
			{&kCFBooleanTrue, cf, "kCFBooleanTrue"},
		}
		for _, g := range globals {
			v, e := sym(g.lib, g.name)
			if e != nil {
				initErr = errors.New("mackeychain: dlsym " + g.name + ": " + e.Error())
				return
			}
			*g.dst = v
		}
		// The dictionary callback structs are ADDRESSES, not pointers to
		// pointers — CFDictionaryCreateMutable takes &kCFTypeDictionaryKeyCallBacks.
		if kCFTypeDictKeyCbs, initErr = purego.Dlsym(cf, "kCFTypeDictionaryKeyCallBacks"); initErr != nil {
			return
		}
		if kCFTypeDictValueCbs, initErr = purego.Dlsym(cf, "kCFTypeDictionaryValueCallBacks"); initErr != nil {
			return
		}
	})
	return initErr
}

// Available reports whether the Keychain seam can be used at all. A caller
// that gets false should fall back to whatever it did before rather than
// fail: a machine without Security.framework is not a machine that should
// lose its app.
func Available() bool { return initialize() == nil }

// cfStr makes a CFStringRef the caller must release.
func cfStr(s string) uintptr {
	b := append([]byte(s), 0)
	return cfStringCreate(0, b, cfUTF8)
}

// query builds the (service, account) dictionary every call starts from.
// The returned dict and the two strings it holds are released by relDict.
func baseQuery(service, account string) (dict, svc, acct uintptr) {
	dict = cfDictCreateMutable(0, 0, kCFTypeDictKeyCbs, kCFTypeDictValueCbs)
	svc = cfStr(service)
	acct = cfStr(account)
	cfDictSetValue(dict, kSecClass, kSecClassGenericPassword)
	cfDictSetValue(dict, kSecAttrService, svc)
	cfDictSetValue(dict, kSecAttrAccount, acct)
	return
}

func relAll(refs ...uintptr) {
	for _, r := range refs {
		if r != 0 {
			cfRelease(r)
		}
	}
}

// Set stores (or replaces) the secret for (service, account).
//
// Add-then-update rather than delete-then-add: a delete that succeeded
// followed by an add that failed would leave the user with no credential at
// all, which is worse than the write not happening.
func Set(service, account, secret string) error {
	if err := initialize(); err != nil {
		return err
	}
	q, svc, acct := baseQuery(service, account)
	data := cfDataCreate(0, []byte(secret), len(secret))
	defer relAll(q, svc, acct, data)

	cfDictSetValue(q, kSecValueData, data)
	cfDictSetValue(q, kSecAttrAccessible, kSecAttrAccessibleWhenUnlocked)
	st := secItemAdd(q, 0)
	if st == errSecSuccess {
		return nil
	}
	if st != errSecDuplicateItem {
		return osStatus("SecItemAdd", st)
	}
	// It exists: update just the data, with a query that names only the item.
	uq, usvc, uacct := baseQuery(service, account)
	attrs := cfDictCreateMutable(0, 0, kCFTypeDictKeyCbs, kCFTypeDictValueCbs)
	udata := cfDataCreate(0, []byte(secret), len(secret))
	defer relAll(uq, usvc, uacct, attrs, udata)
	cfDictSetValue(attrs, kSecValueData, udata)
	if st := secItemUpdate(uq, attrs); st != errSecSuccess {
		return osStatus("SecItemUpdate", st)
	}
	return nil
}

// Get returns the secret for (service, account), or ErrNotFound.
func Get(service, account string) (string, error) {
	if err := initialize(); err != nil {
		return "", err
	}
	q, svc, acct := baseQuery(service, account)
	defer relAll(q, svc, acct)
	cfDictSetValue(q, kSecReturnData, kCFBooleanTrue)
	cfDictSetValue(q, kSecMatchLimit, kSecMatchLimitOne)

	var out uintptr
	st := secItemCopyMatching(q, uintptr(unsafe.Pointer(&out)))
	if st == errSecItemNotFound {
		return "", ErrNotFound
	}
	if st != errSecSuccess {
		return "", osStatus("SecItemCopyMatching", st)
	}
	if out == 0 {
		return "", ErrNotFound
	}
	defer cfRelease(out)
	n := cfDataGetLength(out)
	if n <= 0 {
		return "", nil
	}
	p := cfDataGetBytePtr(out)
	return string(unsafe.Slice((*byte)(unsafe.Pointer(p)), n)), nil
}

// Delete removes the item; a missing item is not an error, since the caller
// wanted it gone either way.
func Delete(service, account string) error {
	if err := initialize(); err != nil {
		return err
	}
	q, svc, acct := baseQuery(service, account)
	defer relAll(q, svc, acct)
	st := secItemDelete(q)
	if st == errSecSuccess || st == errSecItemNotFound {
		return nil
	}
	return osStatus("SecItemDelete", st)
}

func osStatus(call string, st int32) error {
	return errors.New(call + ": OSStatus " + itoa(int(st)))
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}

// ---- the subset-friendly surface -------------------------------------------
// The Sgola facade calls these rather than Set/Get/Delete: an absent item is
// the ORDINARY case on a first run, and modelling it as an exception would
// make every caller wrap a try around the normal path. So a miss is "" and a
// failure is a message, both plain strings.

// Lookup returns the secret, or "" when there is none (or the Keychain
// cannot be reached at all — a caller with no credential behaves the same
// way in both cases: it falls back to what it was given).
func Lookup(service, account string) string {
	s, err := Get(service, account)
	if err != nil {
		return ""
	}
	return s
}

// Store writes the secret and returns "" on success, or the error text. A
// caller that cannot store is still a working client — it just has to be
// told, once, rather than silently failing to remember.
func Store(service, account, secret string) string {
	if err := Set(service, account, secret); err != nil {
		return err.Error()
	}
	return ""
}

// Forget removes the item; "" on success (including "it was not there").
func Forget(service, account string) string {
	if err := Delete(service, account); err != nil {
		return err.Error()
	}
	return ""
}
