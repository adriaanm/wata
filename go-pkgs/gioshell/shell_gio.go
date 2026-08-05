//go:build gioshell

// The real window, behind the `gioshell` build tag. "The phone is a bigger
// BQ268": this draws the device's 160x128 framebuffer as an integer-scaled,
// nearest-neighbour texture with a row of touch buttons under it, and every
// press it collects becomes one of the five keys the handset has. Nothing
// about wata-fb's UI is reimplemented here — the frame arriving through
// Present is the same frame the panel gets and the same one the fb-ui goldens
// pin.
//
// Threading: app.Main owns the process's main goroutine (macOS requires the
// NSApplication run loop there), so the window loop runs on a goroutine of
// its own and the wata frame loop runs on a third. They meet only through
// shell.go's mutex-and-atomics state; Present wakes the window with
// Invalidate, and pointer/key events land in the key queue.

package gioshell

import (
	"image"
	"image/color"
	"os"
	"time"

	"gioui.org/app"
	"gioui.org/font/gofont"
	"gioui.org/io/event"
	"gioui.org/io/key"
	"gioui.org/io/pointer"
	"gioui.org/layout"
	"gioui.org/op"
	"gioui.org/op/clip"
	"gioui.org/op/paint"
	"gioui.org/text"
	"gioui.org/unit"
	"gioui.org/widget/material"
)

// Start brings the window up and returns immediately; Main must then be
// called from the main goroutine. scale is a forced integer magnification, or
// 0 to fit the window; maxFrames > 0 quits after that many presented frames
// (the unattended sanity run).
func Start(w, h, scale, maxFrames int) error {
	s := initState(w, h, scale, maxFrames)
	go windowLoop(s)
	return nil
}

// Main runs Gio's event loop. It must be called from the main goroutine and
// it does not return: the process leaves through Done or a window close.
func Main() { app.Main() }

// ---- the shell chrome's geometry (device-independent pixels) ---------------

const (
	btnRowDp   = 64 // one button row's height
	btnGapDp   = 6
	ledRowDp   = 22 // the LED + hint strip under the buttons
	marginDp   = 8
	labelSizeSp = 15
)

// zone is one touch target: a rectangle in window pixels and the key it sends.
type zone struct {
	r     image.Rectangle
	code  int
	label string
}

var (
	bg      = color.NRGBA{R: 0x10, G: 0x12, B: 0x14, A: 0xff}
	btnBg   = color.NRGBA{R: 0x28, G: 0x2c, B: 0x32, A: 0xff}
	btnDown = color.NRGBA{R: 0x4a, G: 0x74, B: 0xa8, A: 0xff}
	pttBg   = color.NRGBA{R: 0x34, G: 0x3a, B: 0x30, A: 0xff}
	pttDown = color.NRGBA{R: 0x3c, G: 0x9c, B: 0x50, A: 0xff}
	fg      = color.NRGBA{R: 0xe8, G: 0xe8, B: 0xe8, A: 0xff}
	dim     = color.NRGBA{R: 0x60, G: 0x64, B: 0x68, A: 0xff}
	ledOnG  = color.NRGBA{G: 0xdc, A: 0xff}
	ledOffG = color.NRGBA{R: 0x14, G: 0x28, B: 0x14, A: 0xff}
	ledOnR  = color.NRGBA{R: 0xdc, A: 0xff}
	ledOffR = color.NRGBA{R: 0x28, G: 0x14, B: 0x14, A: 0xff}
)

// the keyboard keys a desktop run accepts, mirroring tools/fb-sim.sh's
// terminal mapping: arrows = d-pad, Enter = select, Esc/Backspace = back,
// Space = PTT (a real press/release pair here, so the sim's repeat-gap
// inference is not needed), z/x = prev/next applet, f = F2.
var keyMap = []struct {
	name key.Name
	code int
}{
	{key.NameUpArrow, KeyUp},
	{key.NameDownArrow, KeyDown},
	{key.NameLeftArrow, KeyLeft},
	{key.NameRightArrow, KeyRight},
	{key.NameReturn, KeyEnter},
	{key.NameEnter, KeyEnter},
	{key.NameEscape, KeyBack},
	{key.NameDeleteBackward, KeyBack},
	{key.NameSpace, KeyPtt},
	{"Z", KeyDot1},
	{"X", KeyDot2},
	{"F", KeyF2},
}

// windowLoop is the Gio side: it owns the window, the pixel scratch buffers
// and the pointer state, and it never touches wata's state except through
// shell.go.
func windowLoop(s *shellState) {
	w := new(app.Window)
	w.Option(
		app.Title("wata"),
		app.Size(unit.Dp(s.w*3), unit.Dp(s.h*3+btnRowDp*2+ledRowDp+marginDp*4)),
	)
	inval := w.Invalidate
	s.invalidate.Store(&inval)

	th := material.NewTheme()
	th.Shaper = text.NewShaper(text.WithCollection(gofont.Collection()))
	th.Palette.Fg = fg
	th.Palette.Bg = bg

	v := &view{s: s, th: th, held: -1}
	var ops op.Ops
	for {
		switch e := w.Event().(type) {
		case app.DestroyEvent:
			// the window is gone: tell the frame loop to stop, give its
			// teardown a moment, then leave.
			s.quit.Store(true)
			s.waitDone(3 * time.Second)
			os.Exit(0)
		case app.FrameEvent:
			gtx := app.NewContext(&ops, e)
			v.layout(gtx)
			e.Frame(gtx.Ops)
			s.painted.Add(1)
		}
	}
}

// view is the window loop's own state: scratch pixel buffers, the current
// zones, and which zone a pointer is holding down.
type view struct {
	s    *shellState
	th   *material.Theme
	exp  *image.RGBA
	sc   *image.RGBA
	raw  []byte
	gen  uint64
	seen bool

	zones []zone
	held  int // index into zones, or -1

	// where the last layout put the magnified frame, and at what factor —
	// the window's own record of the blit, which the draw-path test asserts
	// against.
	dst      image.Rectangle
	dstScale int
}

func (v *view) layout(gtx layout.Context) layout.Dimensions {
	size := gtx.Constraints.Max
	paint.FillShape(gtx.Ops, bg, clip.Rect(image.Rectangle{Max: size}).Op())

	margin := gtx.Dp(marginDp)
	gap := gtx.Dp(btnGapDp)
	rowH := gtx.Dp(btnRowDp)
	ledH := gtx.Dp(ledRowDp)
	chrome := rowH*2 + gap + ledH + margin*3
	panelH := size.Y - chrome
	if panelH < 1 {
		panelH = 1
	}

	v.blit(gtx, image.Rect(0, 0, size.X, panelH))
	v.buttons(gtx, image.Rect(margin, panelH+margin, size.X-margin, size.Y-ledH-margin))
	v.ledRow(gtx, image.Rect(margin, size.Y-ledH-margin/2, size.X-margin, size.Y))
	v.input(gtx, size)
	return layout.Dimensions{Size: size}
}

// blit draws the newest wata frame into area at an integer magnification,
// centred, letterboxed. The magnification is the largest that fits (or the
// forced one), never fractional: the panel's pixels are the design.
func (v *view) blit(gtx layout.Context, area image.Rectangle) {
	if raw, gen := v.s.frame(v.raw); !v.seen || gen != v.gen {
		v.raw, v.gen, v.seen = raw, gen, true
		v.exp = Expand(v.raw, v.s.w, v.s.h, v.exp)
		v.sc = nil // force a rescale below
	}
	if v.exp == nil {
		return
	}
	scale := v.s.scale
	if scale <= 0 {
		scale = min(area.Dx()/v.s.w, area.Dy()/v.s.h)
	}
	if scale < 1 {
		scale = 1
	}
	if v.sc == nil || v.sc.Bounds().Dx() != v.s.w*scale {
		v.sc = ScaleNearest(v.exp, scale, nil)
	}
	sz := v.sc.Bounds().Size()
	at := image.Pt(area.Min.X+(area.Dx()-sz.X)/2, area.Min.Y+(area.Dy()-sz.Y)/2)
	v.dst = image.Rectangle{Min: at, Max: at.Add(sz)}
	v.dstScale = scale

	defer op.Offset(at).Push(gtx.Ops).Pop()
	defer clip.Rect(image.Rectangle{Max: sz}).Push(gtx.Ops).Pop()
	im := paint.NewImageOp(v.sc)
	im.Filter = paint.FilterNearest
	im.Add(gtx.Ops)
	paint.PaintOp{}.Add(gtx.Ops)
}

// buttons lays the five touch targets out — UP DOWN OK BACK on one row, a
// wide PTT under them — and records their rectangles for hit testing.
func (v *view) buttons(gtx layout.Context, area image.Rectangle) {
	gap := gtx.Dp(btnGapDp)
	rowH := (area.Dy() - gap) / 2
	top := []struct {
		code  int
		label string
	}{
		{KeyUp, "UP"}, {KeyDown, "DOWN"}, {KeyEnter, "OK"}, {KeyBack, "BACK"},
	}
	v.zones = v.zones[:0]
	cw := (area.Dx() - gap*(len(top)-1)) / len(top)
	for i, b := range top {
		x := area.Min.X + i*(cw+gap)
		v.zones = append(v.zones, zone{
			r:     image.Rect(x, area.Min.Y, x+cw, area.Min.Y+rowH),
			code:  b.code,
			label: b.label,
		})
	}
	v.zones = append(v.zones, zone{
		r:     image.Rect(area.Min.X, area.Min.Y+rowH+gap, area.Max.X, area.Min.Y+rowH*2+gap),
		code:  KeyPtt,
		label: "PTT  (hold to talk)",
	})
	for i, z := range v.zones {
		down := v.held == i
		fill := btnBg
		if z.code == KeyPtt {
			fill = pttBg
			if down {
				fill = pttDown
			}
		} else if down {
			fill = btnDown
		}
		paint.FillShape(gtx.Ops, fill, clip.UniformRRect(z.r, gtx.Dp(6)).Op(gtx.Ops))
		v.label(gtx, z.r, z.label, fg, labelSizeSp)
	}
}

// ledRow mirrors the device's two connection LEDs and says what the desktop
// keyboard does. Audio is a no-op in this shell (there is no device codec
// here), which the row states so a voice message that never sends is not a
// mystery.
func (v *view) ledRow(gtx layout.Context, area image.Rectangle) {
	d := area.Dy()
	if d > gtx.Dp(14) {
		d = gtx.Dp(14)
	}
	y := area.Min.Y + (area.Dy()-d)/2
	green, red := ledOffG, ledOffR
	if v.s.green.Load() {
		green = ledOnG
	}
	if v.s.red.Load() {
		red = ledOnR
	}
	g := image.Rect(area.Min.X, y, area.Min.X+d, y+d)
	r := image.Rect(g.Max.X+gtx.Dp(6), y, g.Max.X+gtx.Dp(6)+d, y+d)
	paint.FillShape(gtx.Ops, green, clip.Ellipse(g).Op(gtx.Ops))
	paint.FillShape(gtx.Ops, red, clip.Ellipse(r).Op(gtx.Ops))
	v.label(gtx, image.Rect(r.Max.X+gtx.Dp(10), area.Min.Y, area.Max.X, area.Max.Y),
		"arrows=dpad  enter=ok  esc=back  space=PTT  z/x=applet  f=del   (no audio in this shell)",
		dim, 12)
}

// label centres one line of text in r.
func (v *view) label(gtx layout.Context, r image.Rectangle, txt string, col color.NRGBA, sp unit.Sp) {
	defer op.Offset(r.Min).Push(gtx.Ops).Pop()
	defer clip.Rect(image.Rectangle{Max: r.Size()}).Push(gtx.Ops).Pop()
	cgtx := gtx
	cgtx.Constraints = layout.Exact(r.Size())
	layout.Center.Layout(cgtx, func(gtx layout.Context) layout.Dimensions {
		l := material.Label(v.th, sp, txt)
		l.Color = col
		l.MaxLines = 1
		return l.Layout(gtx)
	})
}

// input registers the window-wide pointer area and the keyboard filters, then
// drains this frame's events into the key queue.
//
// Press-and-hold is the point: PTT records for as long as the finger is down,
// so a press emits a Pressed event and the matching Release/Cancel emits the
// Released one — exactly the evdev KeyState pair the frame loop consumes.
func (v *view) input(gtx layout.Context, size image.Point) {
	defer clip.Rect(image.Rectangle{Max: size}).Push(gtx.Ops).Pop()
	event.Op(gtx.Ops, v)

	filters := []event.Filter{
		pointer.Filter{
			Target: v,
			Kinds:  pointer.Press | pointer.Release | pointer.Cancel | pointer.Leave,
		},
		key.FocusFilter{Target: v},
	}
	for _, k := range keyMap {
		filters = append(filters, key.Filter{Focus: v, Name: k.name})
	}
	if !gtx.Focused(v) {
		gtx.Execute(key.FocusCmd{Tag: v})
	}
	for {
		e, ok := gtx.Event(filters...)
		if !ok {
			return
		}
		switch e := e.(type) {
		case pointer.Event:
			v.pointer(e)
		case key.Event:
			v.key(e)
		}
	}
}

func (v *view) pointer(e pointer.Event) {
	switch e.Kind {
	case pointer.Press:
		at := image.Pt(int(e.Position.X), int(e.Position.Y))
		for i, z := range v.zones {
			if at.In(z.r) {
				v.held = i
				v.s.push(z.code, true)
				return
			}
		}
	case pointer.Release, pointer.Cancel, pointer.Leave:
		if v.held >= 0 && v.held < len(v.zones) {
			v.s.push(v.zones[v.held].code, false)
		}
		v.held = -1
	}
}

func (v *view) key(e key.Event) {
	for _, k := range keyMap {
		if k.name == e.Name {
			v.s.push(k.code, e.State == key.Press)
			return
		}
	}
}
