//go:build darwin

// The login sheet, minus the modal. `runModal` blocks until somebody clicks,
// which no test can do — so the sheet is split (login.go) into a builder and
// a reader, and everything between them is asserted here: the controls exist,
// they are the right CLASSES (a password field that is not an
// NSSecureTextField would echo the password to the screen), the prefills
// land, the cursor starts where there is something to type, and the answer
// encodes what the controls hold.

package macshell

import (
	"strings"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego/objc"
)

func className(id objc.ID) string {
	return objcrt.GoString(objc.ID(id.Class()).Send(objc.RegisterName("description")))
}

func setValue(id objc.ID, s string) {
	appkit.NSControl{ID: id}.SetStringValue(s)
}

func TestLoginSheetControls(t *testing.T) {
	f := buildLoginFields("http://home:8008", "alice")

	if got := className(f.pass); got != "NSSecureTextField" {
		t.Fatalf("the password field is a %s — it would echo the password", got)
	}
	if got := className(f.hs); got != "NSTextField" {
		t.Fatalf("homeserver field is a %s", got)
	}
	if got := className(f.remember); got != "NSButton" {
		t.Fatalf("the remember control is a %s", got)
	}

	// prefilled, so a re-auth does not make anyone retype what we know
	if got := stringValue(f.hs); got != "http://home:8008" {
		t.Fatalf("homeserver prefill = %q", got)
	}
	if got := stringValue(f.user); got != "alice" {
		t.Fatalf("user prefill = %q", got)
	}
	// "stay signed in" defaults ON: the stores are the point of the app
	// remembering anything at all.
	if int(f.remember.Send(objc.RegisterName("state"))) != controlStateOn {
		t.Fatal("the remember checkbox does not default to on")
	}
	// every control is actually IN the accessory view — a field built and
	// never added is invisible, and the sheet would look empty.
	subs := appkit.NSView{ID: f.box}.Subviews()
	if n := int(subs.Count()); n != 7 {
		t.Fatalf("accessory view holds %d subviews, want 7 (3 labels + 3 fields + checkbox)", n)
	}
}

func TestLoginSheetAnswer(t *testing.T) {
	f := buildLoginFields("", "")
	setValue(f.hs, "  http://pi.local:8008 ") // trimmed: people paste with spaces
	setValue(f.user, " alice ")
	setValue(f.pass, " s3cret with spaces ")  // NOT trimmed: a space is a character

	got := f.answer()
	want := "http://pi.local:8008\talice\t s3cret with spaces \t1"
	if got != want {
		t.Fatalf("answer =\n %q\nwant\n %q", got, want)
	}
	// tab-separated, so a password holding spaces survives the trip
	if n := len(strings.Split(got, "\t")); n != 4 {
		t.Fatalf("answer has %d fields, want 4", n)
	}

	f.remember.Send(objc.RegisterName("setState:"), 0)
	if !strings.HasSuffix(f.answer(), "\t0") {
		t.Fatalf("clearing the checkbox is not reported: %q", f.answer())
	}
}

// Where the cursor starts, which is the difference between a sheet that is
// ready to type into and one that makes you click first.
func TestLoginSheetFirstField(t *testing.T) {
	f := buildLoginFields("", "")
	if f.firstField("", "") != f.hs {
		t.Fatal("a fresh install should start at the server field")
	}
	if f.firstField("http://home", "") != f.user {
		t.Fatal("with a server known, it should start at the name")
	}
	if f.firstField("http://home", "alice") != f.pass {
		t.Fatal("a re-auth should start at the password — the only thing to retype")
	}
}
