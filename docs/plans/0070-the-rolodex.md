# 0070 — the rolodex: one design language for the handset and the watch

status: accepted

## The problem

The main screen is model-forward. It renders the client's data structure — a
scrolling list of 18-character conversation rows, a header, and a footer
spelling out four key bindings — on a display a kid holds at arm's length and a
parent glances at on a wrist. Nothing about it says *who*, which is the only
question either end of this product asks.

Two queued items are the same complaint from two devices. `FB-BIG-CONTACT-ROWS`
(owner, after the first field test) says the handset's rows are unreadable at
arm's length. `WATCH-LAYOUT` says wata's 160×128 grid is wider than tall while
the watch's panel is 208×248 taller than wide, so the watch runs at scale 1 with
half its height unused and its text too small. Neither is a scale factor away
from right.

They matter more than their tracker lines suggest: **the handset and the watch
are the product**. A parent talks to their kid from a wrist; the kid answers
from the walkie-talkie in their pocket. The mac, iOS and TUI clients are
development and admin surfaces.

The watch is also where this goes commercial (owner, 2026-08-22): the platform
has an opening for it, and a watch app is the shippable half of a
family-walkie-talkie product. That raises the bar on this screen — it is the one
a stranger judges in ten seconds — and it adds constraints the handset never
had, which the design below has to be able to grow into rather than be rebuilt
for. They are listed under *what commercial means for the layout*.

## The decision

One design language across both, per-client layouts, and no pretence that a
160×128 landscape panel and a 208×248 portrait one want the same arithmetic.

**The rolodex.** At rest the screen is one contact, full bleed, in that person's
own colour: their name as large as the panel allows, one line of state, nothing
else. Navigating (crown on the watch, arrow keys on the handset) shrinks the
card to reveal it was always one of a vertical stack, and the stack scrolls
under a fixed centre band. Stopping for ~450 ms closes the stack back over
whoever is centred. Holding the talk button talks to the centre card at any
zoom, so the gesture never waits for the animation.

Why this shape:

- **Identity is the screen, not a field on it.** Colour is recognised before
  text is read, which is what a glance and a pre-literate kid both need.
- **The list still exists, but only when asked for.** The zoom-out *is* the
  roster — every visible card carries its colour, name and unheard count — so
  nothing is lost by not showing it at rest.
- **It survives both devices.** The handset gets it in landscape with bigger
  absolute type than the watch; the watch gets it in portrait with two
  neighbours visible. Same language, different sentence.
- **It is one mental model for a family.** "Roll to my colour and hold the
  button" is literally true on either device.

**A thread is drawn, not listed.** Cards answer *which person*; inside a person
the question is *which of these have I not heard*, and that wants density —
but not today's density, which is a column of `hh:mm 0:07` strings you have to
read like a table. Each message becomes a bar whose length is its duration, in
the speaker's colour, growing from the left if it is theirs and from the right
in white if it is mine. Unheard is full ink with a yellow cap; played is the
same bar at a third of it. Duration, direction, speaker and state are all
shape, and the family thread needs no names at all. Eight rows fit the watch,
six the handset, with no chrome.

Two details carry more than their size:

- **Stamps back off.** A time column earns its width only where it says
  something new, so precision degrades with age — the minute under five
  minutes, five minutes under an hour, the quarter hour to six, the hour for
  today, the day this week, the date beyond — and consecutive identical labels
  collapse. A burst inside a minute carries one stamp; last week is a single
  weekday. Rows without one leave the column empty rather than reflowing. The
  exception is the row that is **playing**: it states its exact time in yellow
  while it plays, because that is the one message whose "45m" is not the
  question being asked.
- **Delivery is squares, not checkmarks.** Two check marks are a borrowed
  metaphor that must be taught and sit oddly beside a language made of bars. My
  rows carry two small squares in the right gutter that fill as the message
  travels: both hollow while queued, one filled once the server has it, both
  once somebody played it, one red square when it will never arrive. Same
  rectangle vocabulary as the unheard cap on the other side, and it survives
  being 5 px wide on the handset — a pair of ticks does not.

**Motion is physical, and it is the product's manner.** A walkie-talkie for kids
should be fun to hold, and the rolodex is already a physical metaphor, so the
scrolling is simulated rather than indexed: input adds **velocity**, friction
decays it exponentially, a critically damped spring pulls into the nearest
detent below a threshold speed, and a stiffer spring at each end gives and
bounces back — which is how a kid learns the list has an end without being told.
Two quick presses are twice the shove, so acceleration falls out for free; a drag
takes a few pixels of stiction to break loose and rubber-bands past the last
card while held - on a device whose gestures report a speed, which on the watch
means the crown or a pan recognizer, never a swipe (plan 0071). Starting constants, tuned in the mockups and to be re-tuned on
hardware: 7 cards/s per detent, a 140 ms friction time constant, detent
stiffness 180, wall stiffness 340, and the 450 ms settle before the stack closes.

The model runs **once, above the platform** — plan 0071's boundary — because a
crown, an arrow key and a thumb should differ in what they contribute, not in
how the thing behaves afterwards. That is why navigation intents carry a
magnitude. The horizontal axis is **reserved and unused**: no gesture is spent
on it now, but the integrator runs per axis and layout positions items from an
(x, y) offset, so a later per-card action strip or a scrub along a message is a
body change rather than a re-architecture.

**Colour is a property of the person, stored server-side.** We own both ends, so
each client must not invent its own. The user picks it; it fans out the way
display names already do; a profile that has never set one falls back to a
deterministic derivation from the user id, so every screen is colourful from the
first sync and the picker is the moment it becomes *yours* rather than a setup
gate. The palette is small (≈8) and constrained: every hue must carry black text
and no two may be confusable as a blur going past. The family thread keeps cyan.

**Real type everywhere.** UIKit clients already build a `UILabel` per text node
with the system font; what they lack is an element carrying its own type ROLE
and box instead of a character-grid cell. (An element carrying a point size was
this plan's first wording and is wrong — see the vocabulary below.) The handset has no text engine at all,
and its 5×8 bitmap cannot be scaled into a 40 px name — eight times up is a wall
of squares. It gets **baked strikes**: a chosen face rasterised at the handful of
sizes the design uses, shipped in the binary. No runtime rasteriser, no
dependency, no CPU on a 1.09 GHz ARMv7, and at 160×128 — where every pixel is
visible — a hinted bitmap beats an unhinted curve. A pure-Go TrueType rasteriser
stays the fallback if the layout starts wanting sizes we did not anticipate.

**The shared layer owes a vocabulary, not a layout.** The applets are already
per-client files; what must be shared is the element set every backend can
honour well. Three additions: text placed in pixels carrying a type ROLE and a
weight, aligned within a box; rectangles with a corner radius; colours with
alpha. Anything a device cannot do well — shadows, gradients — stays out of the
vocabulary rather than degrading quietly on the device that matters most.

**Landed** (2026-08-22), as `VLabel`, `VFill` and `Alpha` in `wataui`, drawn on
all four backends and proved on the watch by
`watch-interptest`'s `rolodexVocabularyDraws`. One ruling was settled in the
doing and it overrides this plan's looser earlier wording: the element carries a
**role** (`display` / `name` / `caption` / `status`) and never a point size. A
size in an element forecloses Dynamic Type — which "what commercial means"
below already asks for — and forces every body to know the panel; a role keeps
that in the renderer's metrics, which is the one place that knows. The
handset's arm honours the box, the alignment and the alpha, and cannot honour
the role until the baked strikes ship; that is stated in the arm.

## What commercial means for the layout

Not scope for this plan, but the shape below must not foreclose any of it:

- **The panel is not a constant.** 41 mm, 46 mm and Ultra are three sizes, and a
  fourth arrives every year. Cards are sized from the screen's own bounds at
  launch; 208×248 is a device this design runs on, never a number in it.
- **Type is the system's.** A commercial watch app respects Dynamic Type and
  VoiceOver. That is another argument for the label element carrying a text
  *role* (name / caption / status) rather than a hard point size, so the stage
  can resolve the role against the system's preferred size.
- **The watch needs its own front door.** Today enrolment reads config a
  companion app left, and the watch's URL surfaces are inert. A product bought
  on the watch has to be set up on the watch — a screen this design does not yet
  have.
- **The stage rests on a bet.** Every UIKit view class it uses is marked
  `API_UNAVAILABLE(watchos)`; it works on the runtime, but it is undocumented
  surface under a shipping product. Plan 0071 reads that risk and answers it: a
  renderer of our own feeding one image view per platform, which is public API
  everywhere and takes the design's type requirements with it.

## What changes

- **`wata-server`** — a colour on the profile: stored, served, and fanned out in
  member events so a change reaches every roster on the next sync. Validated
  against the palette. Settable by the owner and by an admin.
- **`wataclient`** — `User` carries the colour; the sync engine reads it
  alongside the display name; an action to set your own. The derived fallback
  lives here so every client agrees on it.
- **`wataui`** — the three new elements, the differ cases for them, and the
  oracle. Grid text stays for now; it is what the TUI and the admin surfaces
  use.
- **`wata-fb`** — the baked strikes and the painter work they need (alpha blend,
  rounded-rect fill, strike blitting), then the new bodies: card, stack, settle,
  talk, and the drawn thread. Its goldens are regenerated wholesale — this is a
  redesign, not a regression.
- **`wata-watch`** — stage metrics taken from the screen's bounds rather than a
  scaled 160×128, and the same body in portrait, laid out from those bounds so
  it holds on every watch size.
- **`wata-ios` / `wata-mac`** — map the new elements onto their stages and keep
  running; they do not get the rolodex in this plan.
- **the picker** — one screen, both main clients: swatches, move, keep.
- **motion** — the integrator (velocity, friction, detent spring, end spring)
  lives with the domain UI, once, and each shell only contributes impulses and a
  frame clock. It needs plan 0071's magnitude-carrying intents, and it is the
  reason a frame can now differ from the last one because of time alone.

## How it is verified

- `wataui-tests` for the new elements and their diff behaviour, and for the two
  rules that are pure functions and deserve to be tested as such: the stamp
  back-off (a list of ages in, a list of labels and blanks out) and the delivery
  squares.
- `fb-ui-tests` scripted runs of the real frame loop, with fresh goldens per
  checkpoint — including the stack open, mid-roll and settled.
- `just watch-e2e` end to end on the simulator, and `just golden` for the
  handset's byte-exact frame.
- **A frame-rate floor on the handset**, measured before the motion constants are
  tuned: a flick has to hold the panel's rate for as long as it coasts, and
  physics at 8 fps is worse than none.
- **On hardware, by eye**: a photo of the handset at arm's length and the watch
  on a wrist. The oracles prove it draws what we said; only a wrist proves it is
  readable, and only a kid proves it is usable.

## Out of scope

- Emoji marks. The same profile field carries one and UIKit renders it from a
  label, but the handset needs a mark set in its strikes. Follow-on.
- Favourites and deletion inside a thread. The drawn thread renders a favourite
  (a star past the bar's outer end) but the gestures that set or clear one, and
  deletion, keep today's handling until the language has been used on hardware.
- iOS, mac and TUI layouts, beyond keeping them building and running.
- Notifications and APNs.

## Open questions

- **Does a kid ever browse messages?** The drawn thread makes browsing cheap
  enough to keep, which is a change from where this plan started — but if a tap
  on a card simply plays the oldest unheard message and then the next, the thread
  may be a screen adults use and kids never open. Watch which one gets used
  before adding anything to it.
- **Do the motion constants survive a wrist and a keypad?** They were tuned in a
  browser mockup with a trackpad. A crown has detents of its own and the handset
  has key repeat, so both want re-tuning against the real input — and the two
  may not want the same numbers even though they share the model.
- **Where the picker lives** on a handset whose settings applet is already
  crowded.
- **Palette size**: eight is a guess bounded by "no two confusable in motion".
  It wants checking against real hues on the real panel, which is dimmer and
  cooler than any mock.
