# 0049 — the message row reads status, age, sender, duration

Status: accepted

## Problem

Owner ask (2026-08-16): a message row should say, left to right, WHEN
it arrived (relative for anything under a day), WHO it is from ("me"
for the user's own), and its DURATION, right-aligned — with the
status marks staying first. Today the row is marks, duration, sender,
with no time at all and a column start that shifts between own and
received rows.

## Decision

One fixed 26-column grid for every row, so the columns align down the
list:

    cols 0-1   marks (own rows: check + peer-played check, as today;
               received rows: play/played mark in col 0 — col 1 now
               reserved on every row, so own and received text align)
    col 2      age, 3 wide: "now" under a minute, then "59m", "23h",
               "99d" (capped). Days stay relative too — a month-day
               date needs hand-rolled calendar math for a column the
               owner asked to say "when it arrived"; revisit if real
               use wants dates. A FUTURE timestamp renders "now": the
               handset boots at 1970 until the clock steps, and the
               scripted harness runs a virtual frame clock against
               real server timestamps — both land in this arm, which
               also makes the goldens deterministic.
    col 6      sender — "me" for own rows, else the display name,
               clipped to the room left of the duration
    right      duration ("m:ss"), right-aligned ending at col 24;
               col 25 stays the favorite star, so marking a message
               still never shifts the text

`body` gains a `nowMs` parameter (the CLIENT clock — real even in
scripted runs, where only the frame clock is virtual), threaded to the
conversation screen; both drivers already hold it at the call site.

## What changes (file-level)

- `wata-fb/src/main/scala/applets.scala`: `ageStr`, the new
  `msgRowView` layout, `body`/`bodyConversation` signatures.
- `wata-fb/src/main/scala/ui.scala`, `wata-mac/src/main/scala/main.scala`:
  pass `nowMs`.
- `wata-fb agecheck`: a pure-function selfcheck of `ageStr`'s arms
  (the goldens only ever see "now"), run by fb-smoke beside `exitfit`.
- Goldens: fb-ui-tests frames with message rows regenerate; mac-ui-tests
  tree-dump expectations follow the new columns.

## Verification

`just ci` (fb-ui-tests + golden + mac-ui-tests carry the layout); the
regenerated frames reviewed by eye before pinning.

## Out of scope

- Calendar dates in the age column.
- Any change to the marks vocabulary or the star.
