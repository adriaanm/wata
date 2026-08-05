// The runtime leg of the bindings proof: the generated wrappers, driven
// against the real Objective-C runtime on this Mac.
//
// Foundation, not PushToTalk, because PushToTalk exists only on iOS hardware —
// but the machinery under test is the same for both: objc_msgSend dispatch
// through generated wrappers, string/error/data bridging, blocks, and a
// runtime-synthesized class whose selectors Foundation itself invokes.
//
// Build-tagged, so `go test ./...` and `just ci` do not need a Mac:
//
//	just bindgen-runtime      (go test -tags objcruntime ./foundation)
//go:build darwin && objcruntime

package foundation

import (
	"strings"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

// ── dispatch: values out of, and into, real Foundation objects ────────────────

func TestClassPropertyAndStringReturn(t *testing.T) {
	info := GetNSProcessInfoClass().ProcessInfo()
	if info.ID == 0 {
		t.Fatal("+[NSProcessInfo processInfo] returned nil")
	}
	if name := info.ProcessName(); name == "" {
		t.Error("processName is empty")
	}
	if host := info.HostName(); host == "" {
		t.Error("hostName is empty")
	}
}

func TestStringRoundTripThroughASetter(t *testing.T) {
	info := GetNSProcessInfoClass().ProcessInfo()
	const want = "wata-bindgen-røundtrip"
	info.SetProcessName(want)
	if got := info.ProcessName(); got != want {
		t.Errorf("processName = %q, want %q", got, want)
	}
}

func TestIntegerAndFloatReturns(t *testing.T) {
	info := GetNSProcessInfoClass().ProcessInfo()
	if n := info.ProcessorCount(); n == 0 {
		t.Error("processorCount = 0")
	}
	if mem := info.PhysicalMemory(); mem == 0 {
		t.Error("physicalMemory = 0")
	}
	if up := info.SystemUptime(); up <= 0 {
		t.Errorf("systemUptime = %v", up)
	}
	if pid := info.ProcessIdentifier(); pid <= 0 {
		t.Errorf("processIdentifier = %v", pid)
	}
}

func TestObjectRoundTripThroughAnInitializer(t *testing.T) {
	uuid := GetNSUUIDClass().UUID()
	s := uuid.UUIDString()
	if len(s) != 36 {
		t.Fatalf("UUIDString = %q", s)
	}
	again := GetNSUUIDClass().Alloc().InitWithUUIDString(s)
	if again.ID == 0 {
		t.Fatal("-[NSUUID initWithUUIDString:] returned nil")
	}
	if got := again.UUIDString(); got != s {
		t.Errorf("round trip: %q -> %q", s, got)
	}
}

func TestBytesRoundTripThroughNSData(t *testing.T) {
	want := []byte{0, 1, 2, 250, 251, 255}
	data := NSData{objcrt.NSData(want)}
	if int(data.Length()) != len(want) {
		t.Fatalf("length = %d, want %d", data.Length(), len(want))
	}
	got := objcrt.GoBytes(data.ID)
	if string(got) != string(want) {
		t.Errorf("bytes round trip: %v -> %v", want, got)
	}
	if !data.IsEqualToData(NSData{objcrt.NSData(want)}) {
		t.Error("-[NSData isEqualToData:] says the copies differ")
	}
}

// ── the NSError ** out-parameter ─────────────────────────────────────────────

func TestErrorOutParameter(t *testing.T) {
	missing := t.TempDir() + "/no-such-directory/file"
	_, err := GetNSDataClass().DataWithContentsOfFileOptionsError(missing, 0)
	if err == nil {
		t.Fatal("reading a missing file reported no error")
	}
	var ns *objcrt.NSError
	if !errorsAs(err, &ns) {
		t.Fatalf("error is %T, want *objcrt.NSError", err)
	}
	if ns.Domain != "NSCocoaErrorDomain" {
		t.Errorf("domain = %q", ns.Domain)
	}
	if ns.Description == "" {
		t.Error("localizedDescription is empty")
	}

	ok, err := NSData{objcrt.NSData([]byte("hello"))}.
		WriteToFileOptionsError(t.TempDir()+"/out.bin", NSDataWritingAtomic)
	if !ok || err != nil {
		t.Fatalf("write: ok=%v err=%v", ok, err)
	}
}

func errorsAs(err error, target **objcrt.NSError) bool {
	e, ok := err.(*objcrt.NSError)
	if ok {
		*target = e
	}
	return ok
}

// ── blocks: Foundation calling back into a Go closure ────────────────────────

func TestBlockCallback(t *testing.T) {
	center := GetNSNotificationCenterClass().DefaultCenter()
	const name = "WataBindgenBlockNotification"
	var got string
	observer := center.AddObserverForNameObjectQueueUsingBlock(
		name, 0, NSOperationQueue{}, func(n NSNotification) { got = n.Name() })
	defer center.RemoveObserver(observer)

	center.PostNotificationNameObject(name, 0)
	if got != name {
		t.Errorf("block saw %q, want %q", got, name)
	}
}

// ── trampolines: Foundation calling a selector implemented in Go ─────────────

func TestSelectorTrampoline(t *testing.T) {
	const name = "WataBindgenSelectorNotification"
	calls := 0
	var seen string
	obs := objcrt.NewDelegate("WataTestObserver", nil, []objcrt.Method{{
		Sel: "handleNote:",
		Fn: func(_ objc.ID, _ objc.SEL, note objc.ID) {
			calls++
			seen = NSNotification{note}.Name()
		},
	}})

	center := GetNSNotificationCenterClass().DefaultCenter()
	center.AddObserverSelectorNameObject(obs, objc.RegisterName("handleNote:"), name, 0)
	defer center.RemoveObserver(obs)

	center.PostNotificationNameObject(name, 0)
	if calls != 1 {
		t.Fatalf("the Go implementation ran %d times, want 1", calls)
	}
	if seen != name {
		t.Errorf("notification name = %q, want %q", seen, name)
	}
}

// TestGeneratedProtocolDelegate is the delegate story end to end: a generated
// protocol struct, a synthesized ObjC class conforming to NSXMLParserDelegate,
// and Foundation driving it — several distinct selectors, in order, with
// bridged arguments. NSXMLParser parses synchronously, so no run loop is
// involved.
func TestGeneratedProtocolDelegate(t *testing.T) {
	var events []string
	delegate := NewNSXMLParserDelegate(NSXMLParserDelegate{
		ParserDidStartDocument: func(NSXMLParser) {
			events = append(events, "start-document")
		},
		ParserDidStartElementNamespaceURIQualifiedNameAttributes: func(
			_ NSXMLParser, elementName, _, _ string, _ NSDictionary) {
			events = append(events, "start:"+elementName)
		},
		ParserFoundCharacters: func(_ NSXMLParser, s string) {
			if strings.TrimSpace(s) != "" {
				events = append(events, "text:"+strings.TrimSpace(s))
			}
		},
		ParserDidEndElementNamespaceURIQualifiedName: func(
			_ NSXMLParser, elementName, _, _ string) {
			events = append(events, "end:"+elementName)
		},
		ParserDidEndDocument: func(NSXMLParser) {
			events = append(events, "end-document")
		},
	})

	xml := `<greeting to="wata">hello</greeting>`
	parser := GetNSXMLParserClass().Alloc().InitWithData(NSData{objcrt.NSData([]byte(xml))})
	if parser.ID == 0 {
		t.Fatal("-[NSXMLParser initWithData:] returned nil")
	}
	parser.SetDelegate(delegate)
	if !parser.Parse() {
		t.Fatalf("parse failed: %v", parser.ParserError())
	}

	want := []string{"start-document", "start:greeting", "text:hello", "end:greeting", "end-document"}
	if strings.Join(events, ",") != strings.Join(want, ",") {
		t.Errorf("events = %v, want %v", events, want)
	}
}

// TestOptionalMethodsAreNotInstalled pins the optional-method semantics the
// generated delegate relies on: a nil field means the synthesized class does
// not answer that selector, which is how ObjC tells required from optional.
func TestOptionalMethodsAreNotInstalled(t *testing.T) {
	delegate := NewNSXMLParserDelegate(NSXMLParserDelegate{
		ParserDidStartDocument: func(NSXMLParser) {},
	})
	responds := objc.RegisterName("respondsToSelector:")
	if !objc.Send[bool](delegate, responds, objc.RegisterName("parserDidStartDocument:")) {
		t.Error("the installed selector is not answered")
	}
	if objc.Send[bool](delegate, responds, objc.RegisterName("parserDidEndDocument:")) {
		t.Error("a nil field was installed anyway")
	}
	conforms := objc.RegisterName("conformsToProtocol:")
	if !objc.Send[bool](delegate, conforms, objc.GetProtocol("NSXMLParserDelegate")) {
		t.Error("the synthesized class does not declare the protocol")
	}
}
