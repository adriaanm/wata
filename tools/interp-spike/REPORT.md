# interp-spike — can a facade express AppKit's geometry?

Plan [0038](../../docs/plans/0038-ffi-in-sgola.md) says `nativeui/interp.go`
moves to Sgola "over facades on the generated `appkit` bindings", and calls
that the part that needs nothing from the compiler. This spike is the smallest
program that checks, because the interpreter's whole AppKit surface is
geometry and AppKit's geometry is **C structs by value**:

```go
func (o NSView) InitWithFrame(frameRect CGRect) NSView
func (o NSView) Frame() CGRect
type CGRect struct { Origin CGPoint; Size CGSize }
```

The oracle is arithmetic, like the objc spike's: a view initialised with
`{{0,0},{160,128}}` reports a frame 160 wide and 128 high, and a struct
marshalled wrong gives zeros or garbage rather than a near miss.

Run it with `just interp-spike`. It does **not** build, and that is the
finding — the spike is left spelled the way the interpreter wants it.

## Result: one gap, in two sizes

Everything else works. Method binding, chained calls, nested field reads and
the `@go.name` mapping are all exactly right; the emitted line

```go
fmt.Println("interp-spike: frame = " + sgolaDoubleStr(f.Size.Width) + …)
```

is what a hand-written binding would say. There is a single wall, and no
second problem behind it.

**A facade class type is always a Go POINTER.** `go-pkgs/appleptt/appkit`'s
types are values — `CGRect` is a struct of floats, and even the ObjC handles
are `type NSView struct{ objc.ID }`, passed by value throughout the bindings.
The emitter has no spelling for that, so every crossing mismatches:

```
./main.go:11:6: cannot use appkit.GetNSViewClass().Alloc().Frame()
    (value of struct type appkit.CGRect) as *appkit.CGRect value in assignment
```

This is the whole failure of the read-only leg — the spike's committed form,
which never constructs a struct, exists precisely to show the mapping fails on
its own.

**And a facade class cannot be constructed.** Reading a struct the Go side
handed over is half of it; the interpreter also *builds* rects from wataui
coordinates. No facade anywhere — not in this repo, not in sgola's generated
`go.*` — is constructible: every facade class is `private[go] ()` and obtained
from a function, because until now every one of them stood for an opaque
handle. Two spellings were tried:

- `@go.name("CGRect") final case class CGRect(@go.name("Origin") origin: …)`
  compiles, and emits the **Sgola-side** name at the construction site:
  `stage = appkit_CGRect{appkit_CGPoint{0.0, 0.0}, …}` → `undefined:
  appkit_CGRect`. The `@go.name`s on the class and its fields are ignored on
  this path (field reads came out `f.size.width`, not `f.Size.Width`).
- `final class CGRect(@go.name("Origin") val origin: …)` with `new` **crashes
  the plugin**: `sgola: unsupported expression (Apply)` followed by
  `unhandled exception ... in the compiler plugin named "sgolaBackend"`, with
  the `New(TypeTree[...])` tree dumped. A refusal would be fine; a crash is
  not.

Both sizes are one feature: a facade class whose Go type is used **by value**,
and which Sgola can build. Filed upstream as `FACADE-VALUE-STRUCT`.

## What it means for the port

`WIRE-DIES-INTERP-TO-SGOLA` is blocked on that ticket. Nothing about it is
bindgen's: the bindings are already the right shape, and
`BINDGEN-TYPED-STRUCTS` (collapsing the decomposed trampolines) is a separate
choice that does not change what a facade can say. Rewriting the bindings to
hand out pointers instead would be a wata-owned Go shim in front of generated
Go — which is what `macshell` already is, and what the port exists to delete.

The `image/png` half of the gate is untouched here: `interp.go` encodes
`VImage` bytes to PNG to hand them to `NSImage`. That is a Go stdlib
dependency, not an AppKit one, and it is answered by picking a different
`NSImage` constructor (`NSBitmapImageRep` over raw RGBA is already in the
bindings), not by the compiler.
