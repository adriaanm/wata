package macshell

import (
	"testing"

	"github.com/adriaanm/wata/go-pkgs/nativeui"
)

// The grammar, pinned against hand-written wire bytes (the Sgola encoder
// must produce exactly these shapes; the mac smoke holds the two together
// end to end).
func TestDecodeTree(t *testing.T) {
	wire := "= P 2 5 title T 0 0 2047 4 WATA 3 row P 1 2 hl R 0 9 160 8 2016 "
	msg, err := DecodeMsg(wire)
	if err != nil {
		t.Fatal(err)
	}
	if !msg.IsTree {
		t.Fatal("want a tree message")
	}
	want := nativeui.VGroup{Children: []nativeui.Keyed{
		{Key: "title", View: nativeui.VText{Col: 0, Row: 0, Text: "WATA", Color: 2047}},
		{Key: "row", View: nativeui.VGroup{Children: []nativeui.Keyed{
			{Key: "hl", View: nativeui.VRect{X: 0, Y: 9, W: 160, H: 8, Color: 2016}},
		}}},
	}}
	if !nativeui.EqView(msg.Tree, want) {
		t.Fatalf("decoded %#v, want %#v", msg.Tree, want)
	}
}

func TestDecodeScript(t *testing.T) {
	wire := "* 3 S 2 1 0 T 3 4 63488 3 a b N 1 2 1 3 key G 5 6 128 65535 D 0 2 "
	msg, err := DecodeMsg(wire)
	if err != nil {
		t.Fatal(err)
	}
	if msg.IsTree || len(msg.Patches) != 3 {
		t.Fatalf("want a 3-patch script, got %#v", msg)
	}
	ps := msg.Patches
	s, ok := ps[0].(nativeui.PSet)
	if !ok || len(s.Path) != 2 || s.Path[0] != 1 || s.Path[1] != 0 ||
		!nativeui.EqView(s.View, nativeui.VText{Col: 3, Row: 4, Text: "a b", Color: 63488}) {
		t.Fatalf("PSet decoded wrong: %#v", ps[0])
	}
	n, ok := ps[1].(nativeui.PInsert)
	if !ok || len(n.Path) != 1 || n.Path[0] != 2 || n.Idx != 1 || n.Keyed.Key != "key" ||
		!nativeui.EqView(n.Keyed.View, nativeui.VGlyph{X: 5, Y: 6, Glyph: 128, Color: 65535}) {
		t.Fatalf("PInsert decoded wrong: %#v", ps[1])
	}
	d, ok := ps[2].(nativeui.PDelete)
	if !ok || len(d.Path) != 0 || d.Idx != 2 {
		t.Fatalf("PDelete decoded wrong: %#v", ps[2])
	}
}

// A string token carries its bytes verbatim — spaces included.
func TestDecodeVerbatimString(t *testing.T) {
	wire := "= T 1 2 7 11 hello world "
	msg, err := DecodeMsg(wire)
	if err != nil {
		t.Fatal(err)
	}
	if v, ok := msg.Tree.(nativeui.VText); !ok || v.Text != "hello world" {
		t.Fatalf("decoded %#v", msg.Tree)
	}
}
