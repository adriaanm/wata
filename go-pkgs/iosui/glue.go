// The Go glue under the coming Sgola iOS stage (plan 0044 stage 3) — the
// UIKit twin of nativeui/glue.go. The interpreter's logic is Sgola over
// facades on the generated uikit bindings; what belongs here is exactly what
// a facade cannot say today, one category per block:
//
//   - the main-queue seam (OnMain, dispatch.go) and the autorelease-pool
//     brackets — the dispatch/callback machinery docs/design/sgola-ffi.md
//     keeps Go;
//   - cross-class casts (`uikit.UIView{ID: l.ID}`): a facade handle is a
//     bound-subset case class with a private constructor, so adopting one
//     class's id as another's is Go's;
//   - the raw-RGBA-to-UIImage crossing: bindgen refuses every CGImageRef
//     shape (`UIImage initWithCGImage:` in uikit/REFUSALS.md), so the pixels
//     cross through the CoreGraphics C API (CGBitmapContext) here;
//   - the offscreen render probe: iOS UIColor has NO component reads
//     (getRed:… takes CGFloat* out-params, refused), so a test asserting
//     "this view really shows colour X" must render the view into a bitmap
//     and read the pixel — the layer/renderInContext: path is also refused
//     (CALayer is not on the allowlist);
//   - the ObjC-runtime reads tests assert with (ViewClassName, subview
//     walks, SameView).

//go:build darwin

package iosui

import (
	"sync"
	"unsafe"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

var (
	selLayer           = objc.RegisterName("layer")
	selRenderInContext = objc.RegisterName("renderInContext:")
	selInitWithCGImage = objc.RegisterName("initWithCGImage:")
	selDesc            = objc.RegisterName("description")
)

// ---- autorelease pools + the main-queue seam --------------------------------

var poolPush func() uintptr
var poolPop func(p uintptr)
var poolOnce sync.Once

func poolInit() {
	poolOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("iosui: dlopen libobjc: " + err.Error())
		}
		purego.RegisterLibFunc(&poolPush, lib, "objc_autoreleasePoolPush")
		purego.RegisterLibFunc(&poolPop, lib, "objc_autoreleasePoolPop")
	})
}

// PoolPush opens an autorelease pool; PoolPop drains it. Every UIKit
// excursion runs inside one — the same threading rule as wata-mac.md's.
func PoolPush() uintptr {
	poolInit()
	return poolPush()
}

func PoolPop(p uintptr) { poolPop(p) }

// OnMain enqueues a registered callback trampoline (a `go.callback` address
// the Sgola side mints once at startup) on the MAIN QUEUE, wrapped in an
// autorelease pool — the windowed frame hop, exactly nativeui.OnMain: the
// pump publishes its frame, then the callback applies it on the UIKit
// thread, one frame per queue turn.
func OnMain(fn uintptr) {
	poolInit()
	MainQueue().Async(func() {
		pool := poolPush()
		purego.SyscallN(fn)
		poolPop(pool)
	})
}

// ---- cross-class casts -------------------------------------------------------

// AllocLabelAsView allocates a UILabel, adopted as the UIView the interpreter
// holds (init comes through the UIView facet — initWithFrame: may return a
// different object than alloc, so the caller adopts ITS return too).
func AllocLabelAsView() uikit.UIView {
	return uikit.UIView{ID: uikit.GetUILabelClass().Alloc().ID}
}

// AllocImageViewAsView allocates a UIImageView as a UIView.
func AllocImageViewAsView() uikit.UIView {
	return uikit.UIView{ID: uikit.GetUIImageViewClass().Alloc().ID}
}

// AsLabel / AsImageView adopt the UIView the interpreter holds as its
// concrete class — the cast is Go's; text, font, colour and image then bind
// directly through the facade's own UILabel/UIImageView methods.
func AsLabel(v uikit.UIView) uikit.UILabel { return uikit.UILabel{ID: v.ID} }

func AsImageView(v uikit.UIView) uikit.UIImageView { return uikit.UIImageView{ID: v.ID} }

// WindowAsView adopts a UIWindow as the UIView it also is (UIWindow is a
// UIView subclass) — the shell's container splice needs the view facet.
func WindowAsView(w uikit.UIWindow) uikit.UIView { return uikit.UIView{ID: w.ID} }

// ---- the raw-RGBA-to-UIImage crossing ----------------------------------------

var cgOnce sync.Once
var cgColorSpaceCreateDeviceRGB func() uintptr
var cgColorSpaceRelease func(space uintptr)
var cgBitmapContextCreate func(data uintptr, w, h, bitsPerComponent, bytesPerRow int, space uintptr, bitmapInfo uint32) uintptr
var cgBitmapContextGetData func(ctx uintptr) *byte
var cgBitmapContextCreateImage func(ctx uintptr) uintptr
var cgContextRelease func(ctx uintptr)
var cgImageRelease func(img uintptr)
var cgContextTranslateCTM func(ctx uintptr, tx, ty float64)
var cgContextScaleCTM func(ctx uintptr, sx, sy float64)

// kCGImageAlphaPremultipliedLast with default byte order: 8-bit components
// in memory order R,G,B,A — the layout wata's frames already use (opaque
// 0xff alpha, so premultiplication changes nothing).
const cgRGBA8888 = 1

func cgInit() {
	cgOnce.Do(func() {
		lib, err := purego.Dlopen(
			"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
			purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("iosui: dlopen CoreGraphics: " + err.Error())
		}
		purego.RegisterLibFunc(&cgColorSpaceCreateDeviceRGB, lib, "CGColorSpaceCreateDeviceRGB")
		purego.RegisterLibFunc(&cgColorSpaceRelease, lib, "CGColorSpaceRelease")
		purego.RegisterLibFunc(&cgBitmapContextCreate, lib, "CGBitmapContextCreate")
		purego.RegisterLibFunc(&cgBitmapContextGetData, lib, "CGBitmapContextGetData")
		purego.RegisterLibFunc(&cgBitmapContextCreateImage, lib, "CGBitmapContextCreateImage")
		purego.RegisterLibFunc(&cgContextRelease, lib, "CGContextRelease")
		purego.RegisterLibFunc(&cgImageRelease, lib, "CGImageRelease")
		purego.RegisterLibFunc(&cgContextTranslateCTM, lib, "CGContextTranslateCTM")
		purego.RegisterLibFunc(&cgContextScaleCTM, lib, "CGContextScaleCTM")
	})
}

// newBitmapContext makes a w*h RGBA8888 CG bitmap context that owns its own
// buffer (data NULL). The caller releases it with cgContextRelease.
func newBitmapContext(w, h int) uintptr {
	cgInit()
	space := cgColorSpaceCreateDeviceRGB()
	defer cgColorSpaceRelease(space)
	ctx := cgBitmapContextCreate(0, w, h, 8, 4*w, space, cgRGBA8888)
	if ctx == 0 {
		panic("iosui: CGBitmapContextCreate answered nil")
	}
	return ctx
}

// ImageFromRGBA builds a UIImage over a CGImage holding a COPY of rgba
// (w*h*4 bytes, meshed RGBA, opaque rows, row 0 = top). The context
// allocates its own buffer and the pixels are copied in through
// CGBitmapContextGetData — every CGImageRef shape is refused by bindgen
// (uikit/REFUSALS.md), which is why this lives here and not in a facade.
func ImageFromRGBA(rgba []byte, w, h int) uikit.UIImage {
	ctx := newBitmapContext(w, h)
	copy(unsafe.Slice(cgBitmapContextGetData(ctx), 4*w*h), rgba)
	img := cgBitmapContextCreateImage(ctx)
	if img == 0 {
		panic("iosui: CGBitmapContextCreateImage answered nil")
	}
	ui := uikit.UIImage{ID: uikit.GetUIImageClass().Alloc().ID.Send(selInitWithCGImage, img)}
	cgImageRelease(img)
	cgContextRelease(ctx)
	return ui
}

// ---- the offscreen render probe ----------------------------------------------

// RenderViewRGBA renders a view (its layer tree: backgrounds, sublayers,
// label glyphs, image contents) into a fresh bitmap sized to its bounds in
// points, one pixel per point, and returns the RGBA bytes with row 0 = the
// view's TOP row. The context is flipped (translate 0,h; scale 1,-1) before
// `-[CALayer renderInContext:]` so UIKit's top-left origin lands as the
// buffer's row order; the hello's two-band assert pins that orientation.
// This needs no window and no screen — exactly what the interptest's colour
// assertions need, since UIColor cannot answer for its own components on iOS.
func RenderViewRGBA(v uikit.UIView) ([]byte, int, int) {
	b := v.Bounds()
	w, h := int(b.Size.Width), int(b.Size.Height)
	ctx := newBitmapContext(w, h)
	cgContextTranslateCTM(ctx, 0, float64(h))
	cgContextScaleCTM(ctx, 1, -1)
	v.ID.Send(selLayer).Send(selRenderInContext, ctx)
	out := make([]byte, 4*w*h)
	copy(out, unsafe.Slice(cgBitmapContextGetData(ctx), len(out)))
	cgContextRelease(ctx)
	return out, w, h
}

// RenderPixel is the one-pixel read of RenderViewRGBA, packed 0xRRGGBB —
// the primitive-typed form a Sgola test asserts on.
func RenderPixel(v uikit.UIView, x, y int) int {
	rgba, w, h := RenderViewRGBA(v)
	if x < 0 || y < 0 || x >= w || y >= h {
		return -1
	}
	i := 4 * (y*w + x)
	return int(rgba[i])<<16 | int(rgba[i+1])<<8 | int(rgba[i+2])
}

// ---- the ObjC-runtime reads the tests assert with ----------------------------

// ViewClassName asks the runtime what an object is (the class object's
// description is its name).
func ViewClassName(v uikit.UIView) string {
	return objcrt.GoString(objc.ID(v.ID.Class()).Send(selDesc))
}

// SubviewCount / SubviewAt walk a view's subviews in paint order.
func SubviewCount(v uikit.UIView) int { return int(v.Subviews().Count()) }

func SubviewAt(v uikit.UIView, i int) uikit.UIView {
	return uikit.UIView{ID: v.Subviews().ObjectAtIndex(uint(i))}
}

// SameView answers whether two handles are the SAME native object — the
// facade's zero-field bound-subset `==` cannot say (no bound fields).
func SameView(a, b uikit.UIView) bool { return a.ID == b.ID }
