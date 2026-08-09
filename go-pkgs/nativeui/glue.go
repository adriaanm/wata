// The Go glue under the Sgola interpreter (wata-mac/src/main/scala/
// interp.scala). The interpreter's logic is Sgola over facades on the
// generated appkit bindings; what remains here is exactly what a facade
// cannot say today, one category per block:
//
//   - the main-queue seam (OnMain) and the autorelease-pool brackets —
//     the dispatch/callback machinery docs/design/sgola-ffi.md keeps Go;
//   - cross-class casts (`appkit.NSView{ID: box.ID}`): a facade handle is a
//     bound-subset case class with a private constructor, so adopting one
//     class's id as another's is Go's;
//   - FACADE-GO-NAMED-SCALAR: methods whose Go signature carries a defined
//     scalar type (`NSBoxType`, `NSWindowOrderingMode`, `NSImageScaling`) —
//     a facade Int does not convert to a Go named type, so those calls are
//     wrapped here (ticket filed in the sgola inbox; grep the key to find
//     every site this unblocks);
//   - raw-pointer crossings the bindings refuse (`initWithBitmapDataPlanes:`
//     — see appkit/REFUSALS.md), the same category objcrt.NSData exists for;
//   - the ObjC runtime reads the tests and TreeDump assert with
//     (ViewClassName, subview walks).

//go:build darwin

package nativeui

import (
	"sync"
	"unsafe"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// The stage the view algebra addresses: the device panel's geometry
// (display.scala's Display/Font constants). The Sgola interpreter carries
// the same constants; these are for the window/shell side.
const (
	StageW = 160
	StageH = 128
)

var (
	selRetain     = objc.RegisterName("retain")
	selInitBitmap = objc.RegisterName("initWithBitmapDataPlanes:pixelsWide:pixelsHigh:bitsPerSample:samplesPerPixel:hasAlpha:isPlanar:colorSpaceName:bytesPerRow:bitsPerPixel:")
	selBitmapData = objc.RegisterName("bitmapData")
	selDesc       = objc.RegisterName("description")
)

// ---- autorelease pools + the main-queue seam --------------------------------

var poolPush func() uintptr
var poolPop func(p uintptr)
var poolOnce sync.Once

func poolInit() {
	poolOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("nativeui: dlopen libobjc: " + err.Error())
		}
		purego.RegisterLibFunc(&poolPush, lib, "objc_autoreleasePoolPush")
		purego.RegisterLibFunc(&poolPop, lib, "objc_autoreleasePoolPop")
	})
}

// PoolPush opens an autorelease pool; PoolPop drains it. Every AppKit
// excursion runs inside one (wata-mac.md's threading rule); the Sgola side
// brackets its direct (headless) stage calls with this pair.
func PoolPush() uintptr {
	poolInit()
	return poolPush()
}

func PoolPop(p uintptr) { poolPop(p) }

// OnMain enqueues a registered callback trampoline (a `go.callback` address
// the Sgola side minted once at startup) on the MAIN QUEUE, wrapped in an
// autorelease pool — the windowed frame hop: the pump publishes its frame,
// then the callback applies it on the AppKit thread, one frame per queue
// turn.
func OnMain(fn uintptr) {
	poolInit()
	MainQueue().Async(func() {
		pool := poolPush()
		purego.SyscallN(fn)
		poolPop(pool)
	})
}

// ---- cross-class casts + named-scalar wrappers -------------------------------

// AllocBoxAsView allocates an NSBox, adopted as the NSView the interpreter
// holds (init comes from the Sgola side; -init may return a different object
// than -alloc, so the caller adopts ITS return too).
func AllocBoxAsView() appkit.NSView {
	return appkit.NSView{ID: appkit.GetNSBoxClass().Alloc().ID}
}

// AllocImageViewAsView allocates an NSImageView as an NSView.
func AllocImageViewAsView() appkit.NSView {
	return appkit.NSView{ID: appkit.GetNSImageViewClass().Alloc().ID}
}

// SetupBox makes an initialised NSBox the interpreter's VRect element:
// custom/borderless, filled. FACADE-GO-NAMED-SCALAR: boxType and
// titlePosition are Go named scalars.
func SetupBox(v appkit.NSView, fill appkit.NSColor) {
	box := appkit.NSBox{ID: v.ID}
	box.SetBoxType(appkit.NSBoxCustom)
	box.SetTitlePosition(appkit.NSNoTitle)
	box.SetBorderWidth(0)
	box.SetFillColor(fill)
}

// SetBoxFill recolors a VRect's box (the PSet fast path).
func SetBoxFill(v appkit.NSView, fill appkit.NSColor) {
	appkit.NSBox{ID: v.ID}.SetFillColor(fill)
}

// SetupImageView configures a fresh NSImageView: never interpolate (the
// pixels arrive pre-scaled), then show img. FACADE-GO-NAMED-SCALAR:
// imageScaling is a Go named scalar.
func SetupImageView(v appkit.NSView, img appkit.NSImage) {
	iv := appkit.NSImageView{ID: v.ID}
	iv.SetImageScaling(appkit.NSImageScaleAxesIndependently)
	iv.SetImage(img)
}

// SetImageViewImage swaps the image (the PSet fast path).
func SetImageViewImage(v appkit.NSView, img appkit.NSImage) {
	appkit.NSImageView{ID: v.ID}.SetImage(img)
}

// AddSubviewBelow splices child into parent's subviews BELOW other —
// earlier in subview order = painted first. FACADE-GO-NAMED-SCALAR:
// the ordering mode is a Go named scalar.
func AddSubviewBelow(parent, child, other appkit.NSView) {
	parent.AddSubviewPositionedRelativeTo(child, appkit.NSWindowBelow, other)
}

// NewLabel is `+[NSTextField labelWithString:]`, adopted as an NSView.
func NewLabel(text string) appkit.NSView {
	return appkit.NSView{ID: appkit.GetNSTextFieldClass().LabelWithString(text).ID}
}

// SetLabelFont / SetLabelText / SetLabelColor / LabelText address a label
// through its NSControl/NSTextField facets — cross-class casts.
func SetLabelFont(v appkit.NSView, f appkit.NSFont) {
	appkit.NSControl{ID: v.ID}.SetFont(f)
}

func SetLabelText(v appkit.NSView, s string) {
	appkit.NSControl{ID: v.ID}.SetStringValue(s)
}

func SetLabelColor(v appkit.NSView, c appkit.NSColor) {
	appkit.NSTextField{ID: v.ID}.SetTextColor(c)
}

func LabelText(v appkit.NSView) string {
	return appkit.NSControl{ID: v.ID}.StringValue()
}

// RetainFont retains a factory-made (autoreleased) font the stage keeps
// across pools — wata-mac.md's cross-pool lifetime rule.
func RetainFont(f appkit.NSFont) { f.ID.Send(selRetain) }

// ---- the raw-pointer crossing the bindings refuse ----------------------------

// ImageFromRGBA builds an NSImage over one NSBitmapImageRep holding a COPY
// of rgba (w*h*4 bytes, meshed RGBA, opaque rows). The rep allocates its own
// buffer (planes NULL) and the pixels are copied in through bitmapData —
// `initWithBitmapDataPlanes:` and `bitmapData` are refused by bindgen (raw
// unsigned char* shapes), which is why this lives here and not in a facade.
func ImageFromRGBA(rgba []byte, w, h int) appkit.NSImage {
	rep := appkit.GetNSBitmapImageRepClass().Alloc()
	id := rep.ID.Send(selInitBitmap,
		uintptr(0), // planes NULL: the rep owns its buffer
		w, h,
		8,    // bitsPerSample
		4,    // samplesPerPixel (RGBA)
		true, // hasAlpha (opaque 0xff alpha in the payload)
		false,
		objcrt.NSString("NSCalibratedRGBColorSpace"),
		4*w, // bytesPerRow
		32)  // bitsPerPixel
	if id == 0 {
		panic("nativeui: initWithBitmapDataPlanes answered nil")
	}
	data := objc.Send[uintptr](id, selBitmapData)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(data)), len(rgba)), rgba)
	img := appkit.GetNSImageClass().Alloc().InitWithSize(appkit.CGSize{
		Width: float64(w), Height: float64(h)})
	img.AddRepresentation(appkit.NSImageRep{ID: id})
	return img
}

// ---- the ObjC-runtime reads the tests and TreeDump assert with ---------------

// ViewClassName asks the runtime what an object is (the class object's
// description is its name).
func ViewClassName(v appkit.NSView) string {
	return objcrt.GoString(objc.ID(v.ID.Class()).Send(selDesc))
}

// SubviewCount / SubviewAt walk a view's subviews in paint order.
func SubviewCount(v appkit.NSView) int { return int(v.Subviews().Count()) }

func SubviewAt(v appkit.NSView, i int) appkit.NSView {
	return appkit.NSView{ID: v.Subviews().ObjectAtIndex(uint(i))}
}

// RepPixelsWide / RepPixelsHigh read a bitmap rep's TRUE pixel size, so a
// probe on a non-1 backing scale cannot skew its address (render tests).
func RepPixelsWide(rep appkit.NSBitmapImageRep) int {
	return appkit.NSImageRep{ID: rep.ID}.PixelsWide()
}

func RepPixelsHigh(rep appkit.NSBitmapImageRep) int {
	return appkit.NSImageRep{ID: rep.ID}.PixelsHigh()
}
