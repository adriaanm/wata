# 0070 — the rolodex: one design language for the handset and the watch

status: proposed

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

**Colour is a property of the person, stored server-side.** We own both ends, so
each client must not invent its own. The user picks it; it fans out the way
display names already do; a profile that has never set one falls back to a
deterministic derivation from the user id, so every screen is colourful from the
first sync and the picker is the moment it becomes *yours* rather than a setup
gate. The palette is small (≈8) and constrained: every hue must carry black text
and no two may be confusable as a blur going past. The family thread keeps cyan.

**Real type everywhere.** UIKit clients already build a `UILabel` per text node
with the system font; what they lack is an element carrying its own point size
and box instead of a character-grid cell. The handset has no text engine at all,
and its 5×8 bitmap cannot be scaled into a 40 px name — eight times up is a wall
of squares. It gets **baked strikes**: a chosen face rasterised at the handful of
sizes the design uses, shipped in the binary. No runtime rasteriser, no
dependency, no CPU on a 1.09 GHz ARMv7, and at 160×128 — where every pixel is
visible — a hinted bitmap beats an unhinted curve. A pure-Go TrueType rasteriser
stays the fallback if the layout starts wanting sizes we did not anticipate.

**The shared layer owes a vocabulary, not a layout.** The applets are already
per-client files; what must be shared is the element set every backend can
honour well. Three additions: text placed in pixels with its own size and weight;
rectangles with a corner radius; colours with alpha. Anything a device cannot do
well — shadows, gradients — stays out of the vocabulary rather than degrading
quietly on the device that matters most.

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
- **The stage rests on a bet.** Every UIKit class it uses is marked
  `API_UNAVAILABLE(watchos)` in the SDK headers; it works on the runtime and is
  proven by the full stage suite, but it is undocumented surface for a shipping
  product, and a review or an OS release could take it away. Worth a deliberate
  read of the risk before the commercial work starts — not because the design
  changes, but because the backend under it might have to.

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
  rounded-rect fill, strike blitting), then the new body: card, stack, settle,
  talk. Its goldens are regenerated wholesale — this is a redesign, not a
  regression.
- **`wata-watch`** — stage metrics taken from the screen's bounds rather than a
  scaled 160×128, and the same body in portrait, laid out from those bounds so
  it holds on every watch size.
- **`wata-ios` / `wata-mac`** — map the new elements onto their stages and keep
  running; they do not get the rolodex in this plan.
- **the picker** — one screen, both main clients: swatches, move, keep.

## How it is verified

- `wataui-tests` for the new elements and their diff behaviour.
- `fb-ui-tests` scripted runs of the real frame loop, with fresh goldens per
  checkpoint — including the stack open, mid-roll and settled.
- `just watch-e2e` end to end on the simulator, and `just golden` for the
  handset's byte-exact frame.
- **On hardware, by eye**: a photo of the handset at arm's length and the watch
  on a wrist. The oracles prove it draws what we said; only a wrist proves it is
  readable, and only a kid proves it is usable.

## Out of scope

- Emoji marks. The same profile field carries one and UIKit renders it from a
  label, but the handset needs a mark set in its strikes. Follow-on.
- The conversation view. The rolodex covers choosing a person and talking to
  them; browsing a thread's messages, favourites and deletion keep today's
  screen until the language has been used on hardware.
- iOS, mac and TUI layouts, beyond keeping them building and running.
- Notifications and APNs.

## Open questions

- **Does a kid ever browse messages?** If tapping a card just plays the oldest
  unheard message and then the next, the conversation view may not survive at
  all on the handset. Decide after the first hardware run, not before.
- **Where the picker lives** on a handset whose settings applet is already
  crowded.
- **Palette size**: eight is a guess bounded by "no two confusable in motion".
  It wants checking against real hues on the real panel, which is dimmer and
  cooler than any mock.
