# 0051 — symmetric row marks: ✓ delivered, ✓✓ heard, on every row

Status: accepted (owner direction 2026-08-16, after the plan-0050
verification: "the symmetry is more pleasing, even though a single
check does indeed capture all you strictly need")

## The problem

The mark column answers a different question per direction: an own row
shows ✓ (server has it) growing to ✓✓ (peer played it), while a
received row shows nothing until you play it and then a single ✓. The
plan-0050 session showed this asymmetry misreads in practice: a single
check on a received row was taken for a missing receipt.

## The decision

One uniform meaning: **✓ = the message is delivered** (it is in the
timeline — trivially true for a received row, and for an own row the
existing "server has it" statement), **✓✓ = it has been heard by its
audience** — the peer for an own row, yourself for a received one. The
play triangle still replaces the first check while a row is being
fetched/played.

Strictly the second check on a received row is redundant with the
played/unplayed row color, but the column now reads the same on every
row, which is what a glance needs.

## What changes

- `wata-fb/src/main/scala/applets.scala` (`msgRowView`, shared by
  wata-mac via symlink): the first check is unconditional (unless the
  play triangle holds the slot); the second check comes from
  `heardMark` — `playedByPeer` for own rows, `isPlayed` for received.
- `tools/fb-ui-golden/` — regenerate; every received row gains a
  leading check, played received rows gain the second.
- `docs/design/wata-fb.md` — the row-grid bullet's mark description.

## Verification

`just ci` (the fb-ui golden suite is the oracle); eyeball the
regenerated frames; a hardware glance at the papa conversation.

## Out of scope

Any change to receipts, unplayed counts, LED, or row colors.
