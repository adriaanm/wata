# wataui — design notes

`wataui` is the declarative UI layer: **a screen is a value**. A pure
function of the application state builds a view tree, and a per-backend
interpreter turns that tree into something a person can see — pixels on
the device's 160x128 panel today, retained native widgets later.

The module links `core` and **nothing else**. No `json`, no
`wataclient`, no go facade, no clock, no IO. That is not tidiness: the
whole reason the layer exists is that the same screens have to render
under a second backend, and a backend can only link a module whose
dependencies it can satisfy. `tools/wataui-tests.sh` holds both edges as
tripwires (a `go.` reference, or an `sgo.deps` file appearing, fails the
gate).

## The algebra

Five constructors, `view.scala`:

| constructor | what it is |
|---|---|
| `VText(col, row, text, color)` | ASCII on a character grid |
| `VGlyph(x, y, glyph, color)` | one glyph by code, at a pixel |
| `VRect(x, y, w, h, color)` | a filled rectangle |
| `VImage(x, y, w, h, pixels)` | a raw RGB565-LE blit |
| `VGroup(children: List[Keyed])` | an ordered composite |

plus `Keyed(key, view)`, a child and its identity within its group.

The vocabulary is deliberately the **painter's**, not a layout engine's.
The device UI is a 26x15 character grid over a 160x128 panel, and the
adoption oracle is pixel-identical goldens, so the primitives are exactly
what the painter can already do. Coordinates are *semantic positions*:
`VText` addresses a grid cell, the rest address pixels, and a backend
that is not this panel scales both. Colors are RGB565 ints, the panel's
own format.

Children paint in list order — a later child draws over an earlier one,
the same rule the immediate-mode painter always had.

**Higher-level ideas are functions returning views, not constructors.** A
selectable row list, a footer legend, a right-aligned mark column: each
is a `def` that builds a `VGroup` out of the five. Sugar composes;
backends only ever see five cases. A sixth constructor waits until a real
screen cannot be expressed without one.

`VImage` carries the panel's own byte format rather than a mask plus a
color, so a framebuffer arm is a row copy. The one image wata draws is
the enrolment QR block, whose body scales the module bitmap into that
buffer.

## The purity rule

A body is `(state, snapshot) => View`, and **it reads its two arguments
and nothing else**. No atomic cells, no clock, no network probe, no
channel poke. Anything the body needs is copied into the snapshot by the
frame loop *before* the body runs. Effects stay where they already are:
input handlers return a new state and push actions; a body never does.

The rule is not style. It is what makes the differ sound (same inputs,
same tree), the goldens deterministic, and a retained backend
thread-safe — a body can run on any thread, and only the apply step
touches the toolkit. The debt it repays is real: the render path used to
fire the enrolment announce and run a blocking network test.

## The differ

`diff.scala`. `Diff.diff(old, new): List[Patch]`, where

```
Patch = PSet(path, view) | PInsert(path, idx, keyed) | PDelete(path, idx)
```

A `path` is the child indices from the root, outermost first; `Nil` is
the root. The script is **ordered**: each patch is written against the
tree the patches before it produced, so it must be applied in order.
`Patches.applyAll(script, old)` is that application over a plain view
tree — the "retained mirror" a real backend keeps beside its widgets —
and `applyAll(diff(a, b), a) == b` is the invariant the module is judged
by.

- Same constructor at the same position: compare fields, emit `PSet` on
  change. **Leaf granularity** — a changed line of text patches that one
  text node, never its group.
- Different constructor: replace the subtree.
- `VGroup` children: an in-order scan with a mirror of the child list.
  At each position, a child already carrying the wanted identity is
  recursed into; an identity that turns up later in the mirror is
  reached by deleting forward; anything else is inserted. Leftovers are
  deleted at the end.
- **Keys are identity.** A non-empty key names the thing a child shows —
  a room id, a contact id, a menu item id. An empty key means "match me
  by position", which is what every static screen element (a title, a
  footer, a status line) wants; naming those would be ceremony.

There is **no move patch**, so a reorder costs a delete and an insert of
the rows that had to pass each other. That is the deliberate trade the
plan calls: these lists are at most a dozen rows, and a minimal-move
script (an LIS over the key positions) buys nothing perceivable at the
price of the one part of the module that would be hard to read.

**The framebuffer backend does not use the differ.** It clears and
repaints the whole tree every frame, exactly as before views were data —
which is what makes golden-equivalence a byte-identical claim rather
than an approximate one. The differ is for retained backends and is
tested entirely on its own.

## The framebuffer backend

`wata-fb/src/main/scala/paint.scala`. `FbPaint.draw(px, view)` is five
match arms over the same `Font`/`Draw` entry points the immediate-mode
painter always called, in the same order with the same arguments —
`VText` to `Font.drawText`, `VGlyph` to `Font.drawChar`, `VRect` to
`Draw.fillRect`, `VImage` to a clipped `Draw.setPixel` walk over the
RGB565 pairs, `VGroup` to its children in order. Nothing new sits
between a view and the pixels, which is why a ported screen leaves every
golden byte-identical.

`FbPaint.centerCol` is the one piece of layout that had to move: the
grid arithmetic `Font.drawTextCentered` did, lifted so a BODY does it
and hands the interpreter a plain positioned `VText`. Centering is
layout, and a backend that is not a 26-column grid must be free to place
the same text its own way.

Adoption is applet by applet, each step its own commit so a golden drift
bisects to one screen. The boot screen (`WataLogic.bodyBoot`) is the
first; the connectivity element (`WataLogic.netView`) came with it,
since three screens share it and two implementations of one indicator
would eventually disagree. The conversation screen
(`WataLogic.bodyConversation`) and the contact list
(`WataLogic.bodyContacts`) follow.

**A screen's alternative states belong inside its body, not around it.**
The contact list is three screens — the boot screen until the link has
been live once, a connection line while the sync has produced nothing
yet, then the list — and the body owns that choice, calling `bodyBoot`
for the first rather than being picked between by the caller. What the
caller keeps is the one branch that is not a view at all: the enrolment
screen announces the device to the server on the frame it appears, and
an effect cannot live inside a pure function of the state.

**A selected row is a group whose first child is its highlight.** The
immediate-mode painter filled the highlight rectangle and then drew the
row's text over it; as data that ordering is the child list's, since
children paint in list order. So a row is
`VGroup(hl? :: mark? :: dur :: sender :: star?)` — the `VRect` before
everything it sits behind — and not a highlight layer drawn beside the
rows. Keeping the highlight inside the row it belongs to is also what
lets the row carry ONE key: rows key on the thing they show (a message's
event id, a conversation's room-or-contact id), and the highlight moves
with its row for free.

## The oracle

`oracle.scala`'s `DiffOracle.report()` is a deterministic report, driven
by `wata-fb difftest` and byte-diffed against
`tools/wataui-diff.expected.txt` by `tools/wataui-tests.sh` (`just
wataui-tests`, in `just ci`). wata-fb is only the driver — it links
wataui and has a `main`; its painter never calls the differ.

Each case prints two things, and the difference matters:

- a `roundtrip … ok=` line — the property, `applyAll(diff(a, b), a) == b`.
  A false there is a failure however the script is spelled, and the
  harness greps for it independently of the byte diff.
- the exact edit script — so a change in *how* the differ reaches the
  answer (leaf granularity, keyed matching, positional fallback, subtree
  replace, the reorder's delete+insert) shows up in review instead of
  silently.

Everything in the module builds and returns its own `String`: a
`StringBuilder` is a function-local accumulator in this dialect and
cannot cross a `def` boundary.
