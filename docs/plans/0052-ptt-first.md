# 0052 — PTT is the talk key everywhere

Status: done (field-test feedback 2026-08-16 — the first real
garden-range session: kids pressed OK to open a conversation before
every send, and PTT from another applet recorded invisibly)

## The problem

PTT already records from the wata applet's contact list, targeting the
selected row (`convIdxForSend` reads `s.selected` on `VContacts`) — but
nothing SAYS so: the contacts footer lists only "UP/DN sel OK open",
so the kids learned press-OK-then-PTT as the ritual, one screen more
than a walkie-talkie should need.

Worse, PTT pressed while ANOTHER applet is active (settings, snake)
routes to the wata applet and records — the shell's `routeWata` seam —
with the recording overlay invisible behind the active applet's frame.
A kid mid-snake holding the talk button broadcasts without any screen
saying so.

## The decision

PTT is the talk key on every screen, with one rule per side of the
applet boundary:

- **Inside the wata applet**: press-to-talk, as today, from either
  view — the contact list sends to the selected row. The contacts
  footer says so: `UP/DN sel OK open PTT talk` (26 columns, exactly
  the grid).
- **From any other applet**: the press CHIMES and brings the wata
  screen up, and does NOT record. The kid then sees where they are,
  picks who to talk to, and presses PTT again. (The owner's ruling:
  this beats introducing a "last used channel" concept just so a blind
  press could send somewhere.) The matching release lands on the wata
  applet as a no-op (`pttHeld` is false).

The mac client needs nothing: its pump has no other applets — space is
already talk-or-nothing.

## What changes

- `wata-fb/src/main/scala/shell.scala` — the PTT arm of `handleInput`
  becomes `pttGlobal`: active == WATA routes to the applet as today;
  otherwise a press sends `AcChime` and activates WATA, swallowing the
  key.
- `wata-fb/src/main/scala/applets.scala` — `contactsFooter` gains
  `PTT talk`.
- `tools/fb-ui-scripts/alice-ptt-first.txt` + a `ptt-first` scenario in
  `tools/fb-ui-tests.py`: PTT press/hold/release on the contact list
  sends to the selected conversation (`wait msgs 1` with no OK ever
  pressed); then dot-switch to settings, PTT press → checkpoint shows
  the contact list again with NO recording overlay (the switch-and-
  chime), release, press again → checkpoint with the REC overlay over
  the contact list, release → `wait msgs 2`.
- Goldens: the footer change touches every contact-list frame;
  regenerate.

## Verification

`just ci` (fb-ui suite carries the new scenario); on hardware: PTT
from the contact list reaches the selected kid, PTT from snake chimes
and lands on the contact list.

## Out of scope

- Auto-sending from other applets ("last used channel") — rejected
  above.
- The settings redesign (plan 0053).
- Screen-off behavior: the wake-on-key path is untouched.
