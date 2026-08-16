# 0056 — the data row shows the switch in progress, not "off"

Status: accepted (owner follow-up 2026-08-16: switching wifi→cell, the
row read "off" while the radios changed over)

## The problem

After OK applies a data target, the row keeps deriving its label from
the live diagnostics — and mid-transition the truthful reading is
"off" (old radio down, new one not up yet), which looks like the
switch failed or landed on the wrong value. pppd negotiation alone is
tens of seconds.

## The decision

An APPLYING state between OK and the radios agreeing: the row keeps
showing the chosen target in yellow with a small ascii spinner beside
it (`| / - \`, advanced every few frames off the applet's own frame
counter, so scripted checkpoints stay deterministic), and the help row
says "switching to cell…". It resolves:

- **success** — the derived state matches the target: pending clears,
  the row shows the real state green, as before;
- **immediate failure** — the apply reported an error: the red report
  + keep-pending OK-retry shape from plan 0055, unchanged;
- **timeout** — no report but the radios never agree within 75s
  (pppd's worst negotiation plus margin): drop to the red report row
  ("no link — OK retries") with the target kept pending.

**Contrast** (same owner note): the pending value is yellow on the
selected row's GREEN highlight bar — poor contrast exactly when the
kid is looking at it. The pending value (and its spinner) draws on a
small BLACK patch behind the value columns: yellow keeps its
"not-yet-real" identity, the patch supplies the contrast, and on an
unselected row the patch blends into the background.

The spinner phase and the timeout both count FRAMES in the kid state
(the applet already ticks every frame), not wall time — deterministic
for the goldens, and the sim can walk all three exits (the off-device
apply reports "not on device", exercising the failure arm; the timeout
arm gets a scripted pin by entering applying with a no-op apply).

## What changes

`applets.scala` (kid data state machine + row/help rendering), the
kid-settings script (a spinner checkpoint; a timeout leg if cheap),
goldens, the design doc's data paragraph.

## Verification

`just ci`, `just mac-build`, fb-ui green, frames eyeballed; on
hardware: wifi→cell shows the spinner until ppp0 is up, then "cell".

## Out of scope

Changing the diag refresh cadence (5s is fine under a 75s window).
