// Pure tests: the algebra mirror, applyAll, pixels, glyphs, keys — no
// AppKit, runnable anywhere Go runs.

package nativeui

import "testing"

func row(key string, hl bool, name string, y int) Keyed {
	kids := []Keyed{}
	if hl {
		kids = append(kids, Keyed{"hl", VRect{0, y, 160, 8, 0x07e0}})
	}
	kids = append(kids, Keyed{"name", VText{0, (y - 1) / 8, name, 0xffff}})
	return Keyed{key, VGroup{kids}}
}

// fixture mirrors the shape of WataLogic's contact-list body (applets.scala
// bodyLive): title, the net element, a keyed row list, a footer.
func fixture() View {
	return VGroup{[]Keyed{
		{"title", VText{0, 0, "WATA", 0x07ff}},
		{"net", VGroup{[]Keyed{{"mark", VGlyph{150, 1, 0x84, 0x07e0}}}}},
		{"rows", VGroup{[]Keyed{
			row("@alice:h", true, "alice", 17),
			row("@bob:h", false, "bob", 33),
		}}},
		{"footer", VText{0, 14, "OK open", 0x632c}},
	}}
}

// script is a plausible differ output for: selection moves from alice to
// bob, the title recolors, and a new row appears at the end. Written in
// script order against the tree the previous patches produced.
func script() []Patch {
	return []Patch{
		PDelete{[]int{2, 0}, 0},                                      // alice loses her highlight
		PInsert{[]int{2, 1}, 0, Keyed{"hl", VRect{0, 33, 160, 8, 0x07e0}}}, // bob gains one
		PSet{[]int{0}, VText{0, 0, "WATA", 0xffe0}},                  // title recolors
		PInsert{[]int{2}, 2, row("@carol:h", false, "carol", 49)},    // a new contact
	}
}

func TestApplyAllMatchesHandExpectation(t *testing.T) {
	got := ApplyAll(script(), fixture())
	want := VGroup{[]Keyed{
		{"title", VText{0, 0, "WATA", 0xffe0}},
		{"net", VGroup{[]Keyed{{"mark", VGlyph{150, 1, 0x84, 0x07e0}}}}},
		{"rows", VGroup{[]Keyed{
			{"@alice:h", VGroup{[]Keyed{{"name", VText{0, 2, "alice", 0xffff}}}}},
			row("@bob:h", true, "bob", 33),
			row("@carol:h", false, "carol", 49),
		}}},
		{"footer", VText{0, 14, "OK open", 0x632c}},
	}}
	if !EqView(got, want) {
		t.Fatalf("applyAll diverged:\n got %#v\nwant %#v", got, want)
	}
}

func TestPSetKeepsTheChildKey(t *testing.T) {
	// A PSet changes what a child shows, never who it is (Patches.replaceAt).
	v := ApplyAll([]Patch{PSet{[]int{0}, VText{1, 1, "x", 0}}},
		VGroup{[]Keyed{{"keyed", VText{0, 0, "a", 0}}}})
	g := v.(VGroup)
	if g.Children[0].Key != "keyed" {
		t.Fatalf("PSet lost the key: %q", g.Children[0].Key)
	}
}

func TestApplyAllToleratesBadPaths(t *testing.T) {
	// Mirror semantics: a path into nothing is a no-op, not a panic.
	orig := fixture()
	got := ApplyAll([]Patch{
		PSet{[]int{9, 9}, VText{0, 0, "x", 0}},
		PDelete{[]int{0}, 0}, // path lands on a VText, not a group
		PInsert{[]int{2}, -1, Keyed{"", VText{0, 0, "x", 0}}},
	}, orig)
	if !EqView(got, orig) {
		t.Fatal("bad paths should leave the tree untouched")
	}
}

func TestInsertPastTheEndAppends(t *testing.T) {
	got := ApplyAll([]Patch{PInsert{nil, 99, Keyed{"k", VText{0, 0, "x", 0}}}},
		VGroup{nil})
	if n := len(got.(VGroup).Children); n != 1 {
		t.Fatalf("append expected, got %d children", n)
	}
}

func TestRGB565Components(t *testing.T) {
	cases := []struct {
		c       int
		r, g, b float64
	}{
		{0x0000, 0, 0, 0},
		{0xffff, 1, 1, 1},
		{0xf800, 1, 0, 0},
		{0x07e0, 0, 1, 0},
		{0x001f, 0, 0, 1},
	}
	for _, c := range cases {
		r, g, b := RGB565Components(c.c)
		if r != c.r || g != c.g || b != c.b {
			t.Errorf("%#04x -> (%v %v %v), want (%v %v %v)", c.c, r, g, b, c.r, c.g, c.b)
		}
	}
}

func TestExpandAndScale(t *testing.T) {
	// One red pixel, one green (RGB565 little-endian pairs).
	src := []byte{0x00, 0xf8, 0xe0, 0x07}
	rgba := ExpandRGB565(src, 2, 1)
	want := []byte{255, 0, 0, 255, 0, 255, 0, 255}
	for i := range want {
		if rgba[i] != want[i] {
			t.Fatalf("expand[%d] = %d, want %d", i, rgba[i], want[i])
		}
	}
	big := ScaleRGBANearest(rgba, 2, 1, 3)
	if len(big) != 6*3*4 {
		t.Fatalf("scaled length %d", len(big))
	}
	// pixel (5,2) is the bottom-right of the green block
	o := (2*6 + 5) * 4
	if big[o] != 0 || big[o+1] != 255 || big[o+2] != 0 {
		t.Fatalf("scaled corner = %v", big[o:o+4])
	}
}

func TestGlyphMapping(t *testing.T) {
	if GlyphString(0x80) != "✓" || GlyphString(0x90) != "▶" || GlyphString(0x8d) != "★" {
		t.Fatal("icon mapping broken")
	}
	if GlyphString('A') != "A" {
		t.Fatal("ASCII should render as itself")
	}
	if GlyphString(0xB0) != GlyphPlaceholder || GlyphString(0x05) != GlyphPlaceholder {
		t.Fatal("unmapped codes should render the placeholder")
	}
}

func TestKeyTranslation(t *testing.T) {
	cases := map[uint16]Key{
		126: KeyUp, 125: KeyDown, 123: KeyLeft, 124: KeyRight,
		36: KeyEnter, 76: KeyEnter, 53: KeyBack, 49: KeyPtt, 12: KeyNone,
	}
	for code, want := range cases {
		if got := TranslateKeyCode(code); got != want {
			t.Errorf("keyCode %d -> %v, want %v", code, got, want)
		}
	}
}
