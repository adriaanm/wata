# 0011 — UI flow coverage: the main use cases, pixel-pinned

Status: done

`[UI-FLOWS]`

## Problem

The `fb-ui-tests` harness (plan 0004) proves the frame loop end to end,
but its four scenarios grew out of feature work, not out of the product:
the DM round-trip — the flow plan 0007 rebuilt the server around — has
no UI coverage at all, every scenario is two-user, and the failure
flashes have never been rendered under test. The harness can now carry
full-flow scenarios cheaply; the gap is the scenarios.

## What changes

Four new scenarios (scripts + reviewed goldens), one existing server
knob's worth of new surface, no UI code changes expected — defects the
scenarios expose are fixed under their own keys:

1. **`dm-roundtrip`** — alice selects bob's conversation from the
   roster, first PTT send resolves the canonical room via the dialect
   endpoint, bob's phase sees the DM with an unplayed badge, plays it
   (played mark, receipt), replies; alice's second phase pins the reply
   and the cleared badge. This is the canonical-DM design rendered.
2. **`family-three`** — `$WATA_USERS` gains charlie for this scenario;
   all three send into the family room; checkpoints pin sender
   attribution rows and interleaved ordering, and charlie's roster
   shows two DM-able contacts.
3. **`badges-across-restart`** — bob accumulates unplayed messages in
   two conversations, restarts (session resume), badges survive; playing
   one conversation clears only its badge.
4. **`send-play-failed`** — the failure flashes. Needs fail-on-demand:
   `wata-server` gains a test hook, enabled only by
   `WATA_TEST_HOOKS=1`, `POST /_wata/v1/test/fail` `{"count": N}` —
   the next N media uploads/downloads return 500. The route is not
   registered without the env var, so the production surface is
   unchanged. Script: arm the hook, PTT → `SEND FAILED` flash
   checkpoint, disarm, resend ok; same for play.

`docs/design/wata-fb.md` gets the use-case/coverage table (the one this
plan was written from) so the next gap is visible instead of
rediscovered; `wata-server.md` documents the test hook.

## Out of scope

- Offline/degraded-boot flows: the harness kills the server between
  phases already, but a mid-phase network drop needs a proxy or a
  server pause facility — design it when connection-status UX is
  worked on, not as a side effect here.
- Interactive-sim (`fb-sim`) changes; snake/clock/charmap.

## Verification

`just ci` green with the four scenarios in the suite; goldens reviewed
by eye at creation (`--update` + inspect); `just conformance` unaffected
(hook route absent without the env var), verified by grepping the
route table in both modes.

## Outcome

Landed as planned — four scenarios (20 new goldens, all inspected at
creation), the `WATA_TEST_HOOKS=1` fail hook, and the coverage table in
wata-fb.md. Deviations and findings:

- **The hook gate is asserted behaviorally, not by grepping.** The
  route table lives in code, so "grep it in both modes" became
  something stronger: `fb-ui-tests.py` POSTs the hook route on every
  server it boots and requires 404 (200 only for the scenario that
  opts in) — the production surface is re-proven on every run of the
  suite, not once at review.
- **Harness/driver surface grew slightly beyond "scripts + goldens":**
  a per-scenario `users` key (writes `$WATA_USERS`; family-three needs
  a third account), a `hooks` key, a multi-peer `family` directive, a
  `failnext` directive, and `sendfail`/`playfail` probes over the
  session tallies (Ui gained the playfail tally; the timeout diag line
  reports it).
- **dm-roundtrip exposed a real server defect, fixed with it:** invite
  membership content carried no displayname (join content does), so an
  invited-but-unjoined DM peer rendered as a raw MXID in the
  conversation header. `memberInviteContent` now stamps the invitee's
  profile displayname, as real homeservers do.
- **badges-across-restart got its assertion for free:** the pre- and
  post-restart badge goldens are byte-identical files, which is the
  survival claim in its strongest form.
- Bob (not alice) bootstraps the family in dm-roundtrip and
  badges-across-restart: the roster's roomless DM rows derive from
  JOINED family membership, so the DM peer must be in the room before
  the sending phase runs.
