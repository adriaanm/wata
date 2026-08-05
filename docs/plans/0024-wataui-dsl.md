# 0024 — wataui: the declarative UI layer

Status: accepted — the module and the fb interpreter have landed;
adoption continues applet by applet (`[UI-DSL-WATAUI]`)

## Problem

`[UI-DSL-WATAUI]` Plan 0023 M4: wata's UI is an immediate-mode painter
(`applets.scala`: `render` walks `WataState` and calls
`Font.drawText`/`Draw.fillRect`/`Font.drawChar` at absolute cells).
That works for one backend. The phone path needs the same screens
rendered by a *retained* native toolkit (UIKit via M3's generated
bindings), and hand-writing the UI twice is exactly what plan 0023
exists to avoid. The move: views become data, rendering becomes an
interpreter, and each backend is one interpreter.

## The view algebra

A sealed family in a new Sgola library module `wataui/`. The algebra is
deliberately the *painter's* vocabulary, not a layout engine — the fb
UI is 160×128 with a 6×9 cell grid, and golden-equivalence (the
adoption oracle) demands pixel-identical output, so the primitives are
exactly what the painter can already do:

```scala
sealed trait View
case class VText(col: Int, row: Int, text: String, color: Int) extends View
case class VGlyph(x: Int, y: Int, glyph: Int, color: Int) extends View   // custom icons > 0x7F
case class VRect(x: Int, y: Int, w: Int, h: Int, color: Int) extends View
case class VImage(x: Int, y: Int, w: Int, h: Int, pixels: Bytes) extends View  // QR block
case class VGroup(children: List[Keyed]) extends View
case class Keyed(key: String, view: View)                                 // "" = positional
```

Everything the current applets draw is expressible in those five
constructors. Higher-level ideas — a selectable row list with
scrollbar-free clamping, a footer legend, a right-aligned mark column —
are **library functions returning views**, not new constructors:
`Rows.contactList(...)` builds `VGroup(VRect(highlight) :: VText(name)
:: VGlyph(mark) :: ...)`. Sugar composes; backends only ever see the
five primitives.

The UIKit backend maps the same tree differently (VText → UILabel with
a cell-grid → point transform, VRect → UIView background, VGroup →
subview list). It does NOT have to look like a 160×128 blit — the
cell/pixel coordinates are semantic positions the backend scales. M2's
blit shell is the bridge until that mapping is good.

## What body may close over

`body` is a pure function `(WataState, FrameSnapshot) => View`. The
rule, learned the hard way (the enrol announce and `Diag.netTest`
sitting synchronously in today's render path — recorded reviewer
debt): **body reads its two arguments and nothing else.** No `Atomic`
reads, no clock, no IO, no channel pokes. Everything body needs is
copied into `FrameSnapshot` by the frame loop before body runs
(`ctx.snap`, net status, outbox keys, quit-arm remaining — all already
snapshot-shaped in `FrameCtx`). Effects stay where they are: input
handlers return a new `WataState` and push actions; body never does.

Purity is what makes the differ sound (same inputs → same tree), the
goldens deterministic, and the UIKit backend thread-safe (body can run
on any thread; only the apply step touches the toolkit).

## The differ

`diff(old: View, new: View) => List[Patch]`, with
`Patch = PSet(path, view) | PInsert(path, idx, keyed) | PDelete(path, idx)`.

- Same constructor, same position → compare fields; emit `PSet` on
  change (leaf granularity — a text change patches one label).
- `VGroup` children: match by `key` when non-empty (conversation rows
  key on roomId-or-contact-id — the same identity `convKeyed` already
  uses), positionally otherwise. Keyed matching is
  insertion-order-scan with a seen-set, not LIS; lists here are ≤ 12
  rows and simplicity beats minimal-move output.
- Different constructor → replace subtree.

**The fb backend does not use the differ.** It clears and repaints the
whole tree every frame, exactly like today — that is what makes
golden-equivalence a byte-identical claim rather than an
approximately-equal one. The differ exists for retained backends
(UIKit) and is tested on its own: property-style unit tests in the
wataui module (apply(diff(a,b)) to a retained mirror == b), no Apple
anything required.

## Adoption path (the fb backend, golden-gated)

1. `wataui/` module: the ADTs, the differ, `FbPaint.draw(px, view)` —
   an interpreter whose five arms call the existing `Font`/`Draw`
   entry points. **Done.** The module is `core`-only, gated by
   `just wataui-tests` (two tripwires + the differ oracle, in `just
   ci`); the interpreter is `wata-fb/src/main/scala/paint.scala`.
   `VImage` carries RGB565-LE pairs rather than a mask, so the fb arm
   is a copy — its first real exercise is the enrolment QR.
2. Applet by applet, `renderX(s, px, ctx)` becomes
   `bodyX(s, snap): View` + one `FbPaint.draw` call at the frame loop.
   Order: boot screen (smallest), conversation, contacts (the mark
   alignment subtleties), settings, enrol (VImage), diag last.
   **The boot screen is done** — `WataLogic.bodyBoot`, with
   `FbCaps.transportUnavailable()` hoisted to the call site so the body
   reads only its arguments. The connectivity element rode along as
   `WataLogic.netView`: three screens draw it, and one view definition
   beats two painters of the same indicator. All 18 uitest scenarios
   and the fb golden stayed byte-identical; nothing was regenerated.
   **The conversation screen is done** — `WataLogic.bodyConversation(s,
   snap)`: rows are a `VGroup` keyed on the message's event id, and a
   row's selection highlight is a `VRect` first in that row's child
   list, the list-order painting rule's first real exercise.
3. After each applet: `just fb-ui-tests` — every golden byte-identical.
   A golden that moves means the port is wrong; goldens are not
   regenerated during adoption.
4. When all applets are bodies: `render` is one match dispatching to
   bodies, and M2's blit shell drives the identical code on the phone.

## What changes (file-level)

- `wataui/src/main/scala/{view.scala,diff.scala}` + module scaffolding
  (`sgo.deps` sibling resolution, same shape as wataclient).
- `wata-fb/src/main/scala/paint.scala` (the fb interpreter),
  `applets.scala` bodies replacing render functions incrementally.
- `wataui` unit tests for the differ; no new goldens.
- `docs/design/wataui.md` once it exists in the tree.

## Verification

- Differ unit suite (apply∘diff round-trip, keyed reorder, subtree
  replace) green.
- `just fb-ui-tests`: all existing goldens byte-identical after every
  adoption step — the whole point; each step is its own commit so a
  drift bisects to one applet.
- Full `just ci` green.

## Out of scope

The UIKit interpreter (lands with plan 0023 M3's bindings), any visual
change whatsoever (this plan must be invisible on screen), layout/
flexbox ambitions (five primitives until a real screen needs a sixth),
and animation (the flash/overlay timers stay in WataState ticks).

## Open questions for the owner

- none blocking; sequencing after M1 spike results is designer's call.
