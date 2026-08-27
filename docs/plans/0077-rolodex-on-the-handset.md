# 0077 — the rolodex on the handset

status: accepted

## The problem

Plan 0070's design language is live on the watch and nowhere else. The
handset — the other half of the product, and the half a kid holds — still
shows the model-forward contact list that plan 0070 exists to replace, and
`FB-BIG-CONTACT-ROWS` (the owner's field-test complaint that the rows are
unreadable at arm's length) has been blocked on one thing since 2026-08-22:
wata-fb's `VLabel` arm honours the box, the alignment and the alpha but
draws every role at 6×8, because the handset has no type but the 5×8 bitmap
font. A full-bleed display name cannot exist until it does.

The owner's direction (2026-08-27) is to park the watch threads and land the
design language on wata-fb next — with the note that this same UI then
becomes the iOS app's, so anything shareable should land in the shared
layers, not in wata-fb private corners.

This plan is plan 0070's handset half plus plan 0071's step 5, in one
sequence: baked strikes, the role-aware painter, motion in the fb frame
loop, and the rolodex bodies. It subsumes `FB-BIG-CONTACT-ROWS`.

## What the survey established (so the plan builds on facts, not memory)

- `wataui/motion.scala` is fully portable — links `core` only, fixed
  sub-step integration, and `Motion.impulse/step/centre/offset/openness/
  live` is the whole API a pump needs.
- `wata-watch/rolodex.scala`'s `body`/`cardView` are pure functions of
  `(cards, count, motion, w, h)`; the watch-specific residue is only the
  geometry fractions (`VISIBLE=5`, `h/5` rows) chosen for a portrait panel.
  Its data half (`Rolodex.cards`) calls only functions wata-fb already has
  verbatim (`convName`, `convKey`, `outboxMark`, `ageStr`, `Palette.*`).
- The fb painter already has everything the vocabulary needs EXCEPT type:
  `Draw.fillRoundRect` blends rounded rects with alpha, `Alpha.over` is the
  RGB565 source-over, `FbPaint.drawLabel` honours box/align/alpha and
  ignores role/weight.
- Kernel key repeat already arrives (`Evdev.mapState` value 2 →
  `Repeat()`) and is dropped by every `isPressed` gate — so the impulse
  ramp needs no new input plumbing, only a consumer.
- The scripted harness ticks a fixed 33 ms clock per frame, so motion is
  deterministic under `advance N` and the goldens can pin mid-roll frames.
- There is no font tooling anywhere in the tree. The committed-`IArray`
  form of `Font.glyphs` is the precedent for how strike data lands.

## The decision

Four stages, each independently useful, ordered so the wall (type) falls
first and the risk (frame rate) is measured before anything is tuned.

### 1. Strikes and the role-aware painter — with the producer researched, not assumed

A **strike** is one face at one size as renderable data: per-glyph
alpha-coverage bitmaps (4-bit packed) plus advance/bearing tables,
printable ASCII, hand-picked to exactly what the design uses. Coverage,
not 1-bit — at these sizes on this panel an antialiased edge is the
difference between type and squares, and the ink is always blended over a
card colour anyway. **The strike format is the interface**: the painter
consumes strikes and does not know where they came from, which is what
lets the painter land while the producer question is still open.

The producer is a research question (owner, 2026-08-27), answered by a
probe before it is answered by a commitment. The candidates:

- **A — baked offline** (plan 0070's default): `tools/gen-strikes.py`
  (python + freetype, real TrueType hinting available) emits
  `strikes.scala` as committed `IArray[Int]` literals — the `Font.glyphs`
  shape — with a `--check` byte-compare wired into fb-smoke, the
  `make-chirp.py` discipline. Zero runtime cost, zero dependencies; fixed
  sizes, regeneration to change anything.
- **B — rasterised at boot** by a pure-Go rasteriser
  (`golang.org/x/image/font/opentype`, or `github.com/golang/freetype`
  whose `truetype` carries a bytecode hinter, though the project is
  archived) behind a `go-pkgs/` module — the ordinary external-dep pattern
  plan 0014 opened. One vendored `.ttf`, any size at will (what a later
  settings "text size" would want); ~a hundred glyphs × three sizes is
  milliseconds even on this CPU if done once at startup, never per frame.
  The risk is quality, not speed: unhinted AA at small ppem on a panel
  where every pixel is visible.
- **C — an existing bitmap family** (Spleen, Terminus) — kept only as the
  control in the probe; the owner's read is that the design has outgrown
  bitmap fonts, and they are monospace where a name wants proportional.

The probe renders the same mock rolodex frames (names on coloured cards,
160×128, upscaled for viewing) across candidate faces × sizes × rendering
paths and puts them side by side; the owner picks by eye, then the losing
path is deleted. Face candidates are hand-picked for legibility at low
resolution — Inter, Atkinson Hyperlegible, IBM Plex Sans, DejaVu Sans
Bold — all OFL/free, vendored under `tools/fonts/`.

Whatever produces them:

- **The sizes come from the roles**, resolved by a new `FbTypeRoles` in the
  renderer (the fb analogue of the watch's `TypeRoles.labelPoints`):
  `DISPLAY` ~30 px bold, `NAME` ~16 px in bold and medium, `CAPTION`
  ~11 px medium. `STATUS`, and any role a strike cannot serve yet, falls
  back to the 5×8 grid font exactly as today — stated in the painter, so
  the gap is visible, not silent. Exact pixel sizes are tuned against the
  probe's renders; the table lives in one place.
- **`FbPaint.drawLabel` becomes role-aware**: pick the strike from
  `FbTypeRoles`, lay glyphs by advance, honour `TextAlign` by measured
  width, clip to the box (a label may never overflow itself — the watch
  learned this), blend coverage × alpha × colour per pixel.

`paint.scala` and the new files ride the existing wata-mac symlink share,
so the mac client compiles them unchanged; its retained backend keeps
drawing `VLabel` natively and never reads the strikes.

### 2. Motion in the fb frame loop

The integrator stays in `wataui`; wata-fb grows the pump side, mirroring
the watch's `Pump.stepMotion` shape:

- `WataState` carries the `Motion` value (it is applet state, not a UI
  cell); `WataLogic.contactsInput` feeds `Motion.impulse` on up/down —
  **`Pressed` and `Repeat` both**, one detent each, which is the key-repeat
  ramp plan 0070 asks the handset to contribute.
- `Ui.frameStep` steps the motion with the frame's real `dt` (the scripted
  device's fixed clock keeps it deterministic), clamps past-end with
  `placeAt`, and writes `Motion.centre` into `selected` every frame — the
  invariant that the talk button and the drawn emphasis can never disagree.
- **Frame pace**: `frameSleep` picks 16 ms while `Motion.live`, else the
  existing 33/idle behaviour. Whether the panel can actually show 60 fps is
  stage 4's measurement; if it cannot, the pace stays 33 ms and only the
  physics dt cares.

> **Landed 2026-08-27, with one deliberate narrowing:** the integrator does
> NOT yet write `Motion.centre` into `selected` — that flip is stage 3's,
> when the rolodex body exists to show what the centre means. This stage is
> invisible plumbing: `selected` stays the discrete authority, a settled
> integrator is re-seated on it (`placeAt`) so the two cannot drift, and the
> observable surface is the `motioncentre`/`motionlive` probes plus the
> probe-only `motion-pump` fb-ui-tests scenario (no goldens touched — the
> acceptance bar). Detail in `docs/design/wata-fb.md`, "The rolodex motion
> pump".

### 3. The rolodex bodies

`rolodex.scala` lands in wata-fb (shared to wata-mac by symlink, like
`applets.scala`) as the watch file with **landscape geometry**: on 160×128
the open stack is `VISIBLE=3` (centre row ~42 px tall — bigger absolute
type than the watch, which is the point), `REACH` and the insets re-derived,
the same single interpolation, quiet-neighbour treatment, nubs, and the
one-element unheard band. `WataLogic.bodyContacts` yields it in place of
the grid list; enrolment, boot, connection line, empty roster, recording
bar and flashes stay their own screens, unchanged. The mac client gets the
same body through the shared source — it is a dev surface and that is
acceptable drift from plan 0070's "mac keeps running"; mac-smoke's contact
assertions are updated with it (and its long-stale legend assertion,
`MAC-SMOKE-STALE-LEGEND`, dies in the same change).

Duplication note: the watch keeps its own `rolodex.scala` for now — the
two differ only in geometry constants, and unifying them in `wataui` is
blocked on `Palette` living in `wataclient` (which `wataui` may not link).
When the iOS client adopts the rolodex, that is the moment to lift the
shared body up rather than make a third copy; recorded here so it is a
decision, not an accident.

### 4. Gates, goldens, and the hardware floor

- Fresh `fb-ui-tests` phases: stack open, mid-roll (a fixed `advance` count
  after an impulse), settled-back-to-full-bleed, and the centre-card
  emphasis with same-colour cards (the watch's `rolodexCentreCardIsMarked`
  discipline: seen to fail with the emphasis disabled before believed).
- The contact-screen goldens are **regenerated wholesale** — plan 0070
  calls this a redesign, not a regression. `just golden`'s single frame is
  re-pinned last, after the design settles.
- A strike-rendering selfcheck in fb-smoke (draw a known string, assert
  measured width and a couple of coverage pixels) so a regenerated
  `strikes.scala` cannot silently ship garbage.
- **On hardware, before any constant is tuned**: measure the frame rate the
  panel actually sustains during a coasting flick (a timed counter in the
  frame loop, read over ssh). Plan 0070's floor: physics at 8 fps is worse
  than none. Then the arm's-length photo — the oracle proves it draws what
  we said; only the photo proves it is readable.

## What changes

- `tools/gen-strikes.py`, `tools/fonts/` (vendored face), and
  `wata-fb/src/main/scala/strikes.scala` (generated, committed) — new.
- `wata-fb` `paint.scala` (role-aware `drawLabel`, `FbTypeRoles`),
  `applets.scala` (`WataState.motion`, impulses, `bodyContacts` → rolodex),
  `ui.scala` (stepMotion, frame pace), `rolodex.scala` — new, symlinked
  into wata-mac.
- `tools/fb-ui-scripts/` + `tools/fb-ui-golden/` — new scenarios, goldens
  regenerated; `tools/fb-golden.png` re-pinned.
- `tools/mac-smoke.py` — contact-screen assertions updated (retiring the
  stale-legend red).
- `docs/design/wata-fb.md` — the display-stack and applet sections
  rewritten to describe the rolodex; `docs/design/wataui.md` if
  `FbTypeRoles` ends up shaping the vocabulary's wording.

## How it is verified

`just ci` green with the new goldens; the fb-smoke strike selfcheck; the
four new fb-ui-tests phases, each seen to fail with its claim inverted
before its green run is believed (the watch's rule); `just mac-smoke`
green for the first time in weeks; the hardware frame-rate measurement and
the arm's-length photo as the owner's leg.

## Out of scope

- The drawn thread (plan 0070's message bars) on either client — next plan,
  after the rolodex has been held on hardware. The conversation screen
  keeps today's grid list.
- The colour field, its server side, and the picker (`ROLODEX-COLOUR-FIELD`
  stays queued; `Palette.forRoster` remains the interim).
- Plan 0071 steps 1–2 for the handset in general (intents/metrics beyond
  what the motion pump needs). The impulse consumer added here is
  deliberately small and does not pretend to be the intent layer.
- iOS/mac adopting the rolodex as a product surface (mac inherits it only
  through source sharing); the watch threads, parked per the owner.

## Open questions

- **Strike sizes and the palette on the physical panel** — both tuned
  against hardware, not a mock; the producer and `FbTypeRoles` keep each
  a one-line change. The panel is low-resolution and **desaturates**
  (owner, 2026-08-27): re-interpret the design language in bold, saturated
  colour — a hue that reads muted on the watch may vanish here, and the
  near-neutral `sand` is the first suspect. The palette is shared
  (`wataclient`), so the answer is choosing hues that survive BOTH panels,
  or a documented per-device saturation bias in the fb renderer — decide
  on hardware, record the ruling in plan 0070's palette section.
- **Does the ST7735S path sustain >30 fps at all?** If not, `MOTION_FRAME_MS`
  is 33 and the springs still work; the measurement decides, not the code.
- **Key-repeat feel**: one detent per repeat event rides the kernel's
  repeat rate. If the ramp feels wrong it may want hold-time integration
  (the `pttHoldTime` shape) instead — a body-level change, noted so the
  first tuning session knows where the knob is.
