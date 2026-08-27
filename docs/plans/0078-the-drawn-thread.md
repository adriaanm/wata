# 0078 — the drawn thread: the message view in the rolodex's language

status: done

## The problem

The rolodex answered *which person* (plan 0077); inside a person the
question is *which of these have I not heard*, and the conversation view
still answers it in the old language — a grid of `hh:mm 0:07` strings
read like a table, in 5×8 type the rest of the app has outgrown. Plan
0070 already designed the replacement ("a thread is drawn, not listed");
the owner's direction (2026-08-27, after judging the rolodex on the
panel) is to build it on the handset next. The watch's copy of the same
screen is `WATCH-DRAWN-THREAD` and stays queued; this plan keeps the
body shareable but ships the handset.

## The decision

Each message is a **bar** whose length is its duration, in the speaker's
colour, growing from the left if it is theirs and from the right in
white if it is mine. Duration, direction, speaker and state are all
shape; the family thread needs no names at all. Six rows fit the panel
with no chrome. The concrete rulings, several of which plan 0070 left
open:

- **Length is linear in duration with a floor and a cap**: a bar is
  `max(MIN_W, duration * usable / CAP_S)` wide, capped at the usable
  width, with `CAP_S = 30` seconds to start — a walkie-talkie clip is
  seconds, not minutes, and exactness is not the point; the constants
  are tuned on the panel like the type sizes were.
- **Unheard is full ink with a yellow cap** — a small yellow block at
  the bar's growing end, the same rectangle vocabulary as the card's
  unheard band. **Played is the same bar at a third of the ink** (alpha
  ≈ 85 over the black ground). Nothing else distinguishes them.
- **My rows carry the delivery squares** in the right gutter (they grow
  from the right, so the gutter is their root): two small squares —
  both hollow while queued, one filled once the server has it, both
  filled once somebody played it, a single red square when it will
  never arrive. This *replaces* the check-glyph convention inside this
  screen; the two-checks grid rows die with the grid.
- **Stamps back off.** The time column carries a label only where it
  says something new: the minute under five minutes, five minutes under
  an hour, the quarter hour to six, the hour today, the day this week,
  the date beyond — and consecutive identical labels collapse to the
  first. Rows without one leave the column EMPTY rather than reflowing
  (fixed column). The one exception: the row that is **playing** shows
  its exact time in yellow while it plays, because that is the one row
  whose "45m" is not the question being asked.
- **The favourite star renders past the bar's outer end** (the side away
  from its growth). Render only — the gestures that set/clear one, and
  deletion, keep today's handling (plan 0070's out-of-scope stands).
- **The thread scrolls on the same motion integrator** as the rolodex —
  one detent per row, impulses from up/down press+repeat, the white
  nubs marking the centre band. One mental model, one feel, and the
  plumbing is proven at 20 fps. The centre row is the actionable one:
  OK plays it, and the emphasis is modest (full-strength stamp beside
  it; bars carry their own meaning and must not be dimmed — played
  thirds vs unheard ink IS the information, so the neighbour treatment
  the rolodex uses would destroy data here. The nubs and the stamp are
  the centre's whole marking.)
- **List changes reconcile like the rolodex's**: the event-id anchor
  semantics the grid list had (redactions, filters moving rows) become
  `Motion.placeAt` on the row the anchor resolves to — the integrator
  never argues with the model about where the list went.
- **Type**: stamps and the playing time at CAPTION (13 px); no other
  text on the screen in the family thread; a DM's rows are the same
  minus even that (both speakers are unambiguous by direction). The
  recording bar, flashes and the header chrome stay exactly as they
  are — this plan touches the message area only.

**The body stays pure and shareable**: a `ThreadRow` record list
(duration, direction, colour, played/unheard, delivery state, stamp
label, favourite, playing) computed by one snapshot-reading function,
the drawing body a pure function of `(rows, motion, w, h)` — the
`Rolodex.body`/`Rolodex.cards` split repeated, so `WATCH-DRAWN-THREAD`
later is a geometry file, not a port.

**The two pure rules live in `wataui` and are tested as such** (plan
0070 names them): the stamp back-off (a list of ages in, labels and
blanks out) and the delivery squares (outbox/receipt state in, square
pair out). They are shared vocabulary, not fb code.

## What changes

- `wataui` — `stamps.scala` (back-off) + `delivery.scala` (squares),
  pure, with `wataui-tests` cases; no new view elements (bars are
  `VFill`, squares are `VRect`/`VFill`, stamps are `VLabel`).
- `wata-fb` — `thread.scala` (the body + the snapshot reader, symlinked
  to wata-mac like `rolodex.scala`); the conversation applet state
  swaps its grid renderer for the body and its discrete scroll for the
  integrator (second `Motion` value in `WataState`, stepped only while
  the conversation shows); `msgRowView` and the grid-row machinery die
  with their goldens.
- Scenarios/goldens: conversation-view frames regenerated wholesale;
  new phases for unheard/played mix, my-row delivery states, the
  playing row's yellow stamp, stamp collapse, and the motion settle —
  the seen-to-fail discipline throughout.
- `docs/design/wata-fb.md` — the conversation section rewritten;
  `wataui.md` gains the two pure rules.

## How it is verified

`wataui-tests` for the two pure rules (exhaustive age/state tables);
`just ci` + `just mac-smoke` green with the regenerated goldens; deploy
and on-panel shots (a thread with unheard, played and own rows); the
owner's eye on bar legibility and the length mapping, tuned like the
type was.

## Out of scope

- Favourite/deletion gestures, and the list-edge affordance rows
  (`FB-MSG-LIST-EDGES` stays queued — the integrator's end springs are
  where those rows will live, noted so the springs' constants are not
  tuned into a corner).
- The watch/iOS adoption (`WATCH-DRAWN-THREAD` stays queued; the pure
  body is the preparation).
- Playback progress beyond the playing row's stamp.
- Auto-play-on-open ("does a kid ever browse" — plan 0070's open
  question stands; watch usage before adding anything).

## Open questions

- `CAP_S` and `MIN_W` on the real panel — tuned by eye like the type.
- Whether six rows or the rolodex's row height wins once real threads
  are on the panel (six ≈ 21 px rows; the stack's 42 px rows would show
  three — density is the point of this screen, so start at six).

## Landed (2026-08-27)

Everything above is in the tree: the two pure rules + their oracle in
wataui (wataui-tests check 4/4), `thread.scala` shared fb/mac, the
second motion integrator with the event-id anchor reconciled through
`Motion.placeAt`, the grid and the discrete scroll deleted, the
`drawn-thread` scenario with its `thr*` pixel probes (played-third,
square-mapping and stamp-collapse claims each seen to fail first), the
conversation goldens regenerated wholesale, and mac-smoke's
conversation assertions re-pinned to the drawn tree. Settled choices:
the stamp column sits LEFT (the right edge belongs to the delivery
squares; own right-rooted bars are the minority); "today" is
approximated as under 24h; the flick physics were pinned as measured
(twelve rapid taps coast to detent 11). Open debt: queued synthetic
rows draw at MIN_W because outbox entry durations are not plumbed to
the UI (WATA-TODO.md).
