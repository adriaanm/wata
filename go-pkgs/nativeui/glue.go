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
	selSetAlign   = objc.RegisterName("setAlignment:")
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
// then the callback applies everything pending on the AppKit thread (a queue
// turn may drain several published frames).
func OnMain(fn uintptr) {
	poolInit()
	MainQueue().Async(func() {
		pool := poolPush()
		purego.SyscallN(fn)
		poolPop(pool)
	})
}

// ---- cross-class casts -------------------------------------------------------

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

// AsBox / AsImageView adopt the NSView the interpreter holds as its concrete
// class — the cast is Go's; the interpreter drives the facet through the
// facade's own bindings (boxType, imageScaling and their consts bind
// directly as defined scalars).
func AsBox(v appkit.NSView) appkit.NSBox { return appkit.NSBox{ID: v.ID} }

func AsImageView(v appkit.NSView) appkit.NSImageView { return appkit.NSImageView{ID: v.ID} }

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

// SetLabelAlignment writes NSControl's `alignment` — where the text sits in
// the label's own frame, which is what wataui's VLabel box + TextAlign mean.
// It is here rather than in a facade because bindgen refuses the property
// (`no Go mapping for 'NSTextAlignment'`, appkit/REFUSALS.md). The value is
// AppKit's enum, which the caller maps (MacType.alignment): left 0, RIGHT 1,
// CENTER 2 — deliberately NOT UIKit's ordering.
func SetLabelAlignment(v appkit.NSView, a int) {
	v.ID.Send(selSetAlign, a)
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

// SameView answers whether two handles are the SAME native object — the
// facade's zero-field bound-subset `==` cannot say (no bound fields), and
// the in-place-mutation test needs exactly this.
func SameView(a, b appkit.NSView) bool { return a.ID == b.ID }

// RepPixelsWide / RepPixelsHigh read a bitmap rep's TRUE pixel size, so a
// probe on a non-1 backing scale cannot skew its address (render tests).
func RepPixelsWide(rep appkit.NSBitmapImageRep) int {
	return appkit.NSImageRep{ID: rep.ID}.PixelsWide()
}

func RepPixelsHigh(rep appkit.NSBitmapImageRep) int {
	return appkit.NSImageRep{ID: rep.ID}.PixelsHigh()
}
