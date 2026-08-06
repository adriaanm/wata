// The view/patch wire format between the Sgola app and this shell.
//
// The Sgola side runs the bodies and the differ (wataui); this side owns the
// retained NSView tree (nativeui). What crosses the facade is one String per
// frame — either the whole first tree or a differ script — in a token stream
// this file decodes into nativeui's mirror types. The ENCODER is the Sgola
// side (wata-mac's wire.scala); the two must agree byte for byte, and the
// mac smoke's tree/patch assertions are what holds them together.
//
// Grammar (every token is followed by exactly one space; strings are
// length-prefixed and byte-verbatim, so text and RGB565 pixel payloads need
// no escaping):
//
//	msg    := "=" view            (set the whole tree)
//	        | "*" n patch*n       (apply a differ script, in order)
//	patch  := "S" path view
//	        | "N" path idx str view    (insert Keyed{str, view} at idx)
//	        | "D" path idx
//	path   := n idx*n
//	view   := "T" col row color str
//	        | "G" x y glyph color
//	        | "R" x y w h color
//	        | "I" x y w h str          (str = RGB565-LE byte pairs)
//	        | "P" n (str view)*n       (group: key, child — in paint order)
//	num    := decimal (optionally negative), then one space
//	str    := num, then exactly that many raw bytes, then one space
//
// Pure Go, no AppKit: decodable and testable on any platform.

package macshell

import (
	"errors"
	"strconv"

	"github.com/adriaanm/wata/go-pkgs/nativeui"
)

// Msg is one decoded frame handoff: a whole tree, or a patch script.
type Msg struct {
	IsTree  bool
	Tree    nativeui.View
	Patches []nativeui.Patch
}

type wireReader struct {
	b   []byte
	pos int
}

var errWire = errors.New("macshell: malformed wire message")

func (r *wireReader) num() (int, error) {
	start := r.pos
	for r.pos < len(r.b) && r.b[r.pos] != ' ' {
		r.pos++
	}
	if r.pos == start || r.pos >= len(r.b) {
		return 0, errWire
	}
	n, err := strconv.Atoi(string(r.b[start:r.pos]))
	if err != nil {
		return 0, errWire
	}
	r.pos++ // the token's trailing space
	return n, nil
}

func (r *wireReader) str() (string, error) {
	n, err := r.num()
	if err != nil || n < 0 || r.pos+n >= len(r.b)+1 {
		return "", errWire
	}
	if r.pos+n > len(r.b) {
		return "", errWire
	}
	s := string(r.b[r.pos : r.pos+n])
	r.pos += n
	if r.pos >= len(r.b) || r.b[r.pos] != ' ' {
		return "", errWire
	}
	r.pos++
	return s, nil
}

func (r *wireReader) tag() (byte, error) {
	if r.pos+1 >= len(r.b) || r.b[r.pos+1] != ' ' {
		return 0, errWire
	}
	t := r.b[r.pos]
	r.pos += 2
	return t, nil
}

func (r *wireReader) view() (nativeui.View, error) {
	t, err := r.tag()
	if err != nil {
		return nil, err
	}
	switch t {
	case 'T':
		col, e1 := r.num()
		row, e2 := r.num()
		color, e3 := r.num()
		text, e4 := r.str()
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
			return nil, errWire
		}
		return nativeui.VText{Col: col, Row: row, Text: text, Color: color}, nil
	case 'G':
		x, e1 := r.num()
		y, e2 := r.num()
		g, e3 := r.num()
		color, e4 := r.num()
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
			return nil, errWire
		}
		return nativeui.VGlyph{X: x, Y: y, Glyph: g, Color: color}, nil
	case 'R':
		x, e1 := r.num()
		y, e2 := r.num()
		w, e3 := r.num()
		h, e4 := r.num()
		color, e5 := r.num()
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil || e5 != nil {
			return nil, errWire
		}
		return nativeui.VRect{X: x, Y: y, W: w, H: h, Color: color}, nil
	case 'I':
		x, e1 := r.num()
		y, e2 := r.num()
		w, e3 := r.num()
		h, e4 := r.num()
		px, e5 := r.str()
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil || e5 != nil {
			return nil, errWire
		}
		return nativeui.VImage{X: x, Y: y, W: w, H: h, Pixels: []byte(px)}, nil
	case 'P':
		n, err := r.num()
		if err != nil || n < 0 {
			return nil, errWire
		}
		kids := make([]nativeui.Keyed, 0, n)
		for i := 0; i < n; i++ {
			key, e1 := r.str()
			v, e2 := r.view()
			if e1 != nil || e2 != nil {
				return nil, errWire
			}
			kids = append(kids, nativeui.Keyed{Key: key, View: v})
		}
		return nativeui.VGroup{Children: kids}, nil
	}
	return nil, errWire
}

func (r *wireReader) path() ([]int, error) {
	n, err := r.num()
	if err != nil || n < 0 {
		return nil, errWire
	}
	p := make([]int, n)
	for i := 0; i < n; i++ {
		p[i], err = r.num()
		if err != nil {
			return nil, errWire
		}
	}
	return p, nil
}

func (r *wireReader) patch() (nativeui.Patch, error) {
	t, err := r.tag()
	if err != nil {
		return nil, err
	}
	switch t {
	case 'S':
		p, e1 := r.path()
		v, e2 := r.view()
		if e1 != nil || e2 != nil {
			return nil, errWire
		}
		return nativeui.PSet{Path: p, View: v}, nil
	case 'N':
		p, e1 := r.path()
		idx, e2 := r.num()
		key, e3 := r.str()
		v, e4 := r.view()
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
			return nil, errWire
		}
		return nativeui.PInsert{Path: p, Idx: idx, Keyed: nativeui.Keyed{Key: key, View: v}}, nil
	case 'D':
		p, e1 := r.path()
		idx, e2 := r.num()
		if e1 != nil || e2 != nil {
			return nil, errWire
		}
		return nativeui.PDelete{Path: p, Idx: idx}, nil
	}
	return nil, errWire
}

// DecodeMsg parses one wire message.
func DecodeMsg(wire string) (Msg, error) {
	r := &wireReader{b: []byte(wire)}
	t, err := r.tag()
	if err != nil {
		return Msg{}, err
	}
	switch t {
	case '=':
		v, err := r.view()
		if err != nil {
			return Msg{}, err
		}
		return Msg{IsTree: true, Tree: v}, nil
	case '*':
		n, err := r.num()
		if err != nil || n < 0 {
			return Msg{}, errWire
		}
		ps := make([]nativeui.Patch, 0, n)
		for i := 0; i < n; i++ {
			p, err := r.patch()
			if err != nil {
				return Msg{}, err
			}
			ps = append(ps, p)
		}
		return Msg{Patches: ps}, nil
	}
	return Msg{}, errWire
}
