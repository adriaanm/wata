# 0045 — the non-happy paths, as an adult meets them

Status: done (all five slices landed 2026-08-09/10; `just mac-ui-tests`
is the suite. The startup-verdict/stdout inventory findings (items 8,
19, 20) did not ride slice 3 beyond what the title now says — they
remain follow-ups under ADULT-UX-NONHAPPY's iOS half.)

## Problem

The mac client's failure behavior is correct at the client-core level
(plan 0022's never-terminate/backoff and classified-send rulings hold,
and the integ suite proves them) but its *surfaces* were inherited from
a 160×128 kid's handset, and an adult at a Mac meets them cold. The
full evidence inventory (22 states, what each shows, what is tested)
was taken 2026-08-09; its ranked findings drive this plan. The five
that matter most:

1. A wrong password loops back to an **identical login sheet** with no
   failure line — and "password refused" is indistinguishable from
   "server unreachable" (`go-pkgs/macshell/login.go` has constant
   text; `main.scala`'s session loop knows why but cannot say it).
2. The mac `HttpDo` has **no timeout at all** (`caps.scala:34` uses
   Go's `DefaultClient`), so a hung server freezes the state machine
   in `Connecting`/`Syncing` forever — wata-fb has a 30s default +
   `WATA_HTTP_TIMEOUT_MS`; the mac simply never got it.
3. After the first successful sync, mid-session network loss is
   announced **only by two blinking dots** in the header — no words
   anywhere for the rest of the session (`bodyLive`'s `everLive`
   gate).
4. A **denied microphone reads as "SEND FAILED"** — `AeRecordingError`
   is routed into the send-error flash (`applets.scala:440`), blaming
   the network for a TCC denial.
5. Unsent/undeliverable messages are **unlabelled glyphs** (0x8E/0x8F)
   whose only notice can be cleared by opening the row.

## Decision

**The shared grid stays the kid's; the adult surfaces are the mac
chrome.** `applets.scala`/`netstatus.scala`/`paint.scala` are symlinks
into wata-fb — every word added there lands on the handset and costs a
golden re-bless — so this plan adds adult-facing language only where
the handset does not go: the login sheet, the window title, the menu
bar. The one shared-grid change is the mislabelled-mic fix, which is
wrong on both platforms.

**One connectivity element stays the law.** The window title's state
text derives from the same `NetState` the header dots derive from —
the title is a louder rendering, never a second opinion.

Existing rulings stand unmodified: never terminate (0022), classified
sends (0022), calm-outranks-failure boot ordering (0035), stored
password (0036), three session endings and Settings-shows-not-edits
(0037), no preferences tree (0037).

## What changes, in slices

**Slice 1 — the mac HTTP timeout** (`wata-mac/src/main/scala/caps.scala`).
Copy wata-fb's shape: 30s default, `WATA_HTTP_TIMEOUT_MS` override.
Purely additive to the state machine — a hung server now becomes
`ConnError` + backoff like any other failure, and every downstream
surface (dots, title, boot screen) starts telling the truth about it.
Also unlocks the hung-server test scenario on mac.

**Slice 2 — the login sheet says why** (`go-pkgs/macshell/login.go` +
`wata-mac/src/main/scala/main.scala`, `facades.scala`). `Login` gains a
reason parameter (empty = first ask). The session loop passes what it
already knows: rejected → "The server refused this password." /
unreachable-at-login → "Could not reach <host>." Retyping into an
unexplained sheet ends. `login_test.go` extends to pin the reason row's
presence/absence. Field validation: empty homeserver/user do not
commit.

**Slice 3 — the window title carries the state**
(`wata-mac/src/main/scala/netstatus.scala` derivation consumed in
`main.scala` via `macshell.SetTitle`). Post-`everLive`: "Wata" when
healthy; "Wata — reconnecting…" on `ConnError`/backoff; "Wata —
offline" once the backoff ceiling is reached. Words an adult sees in
the Dock and the title bar, zero pixels on the shared grid, one
NetState source.

**Slice 4 — the mic tells the truth** (`applets.scala:440` + flash
strings). `AeRecordingError` gets its own flash `"MIC FAILED"`, and the
mac session-start path surfaces the likely cause once per run in
chrome (a notify-path banner naming Microphone permission, System
Settings > Privacy). Shared-grid change: re-bless the affected fb
goldens deliberately.

**Slice 5 — the mac failure-scenario suite** (`tools/mac-ui-tests.py`,
modelled on `tools/fb-ui-tests.py`'s table). Scenarios: wrong-password
(sheet shows reason), unreachable-at-login, hung-server (needs slice
1), mid-session-loss (title asserts "reconnecting"), send-fail →
outbox glyph + flash, recording-error (mic flash, not send). Judged
like mac-smoke judges (tree dumps + the title/query seams); `just
mac-ui-tests`, not in ci (macOS-gated), same posture as mac-smoke.

## Verification

Slice 5 is the verification of slices 1–4; each earlier slice also
keeps `just ci`, `mac-smoke`, `mac-creds-smoke`, `nativeui-tests`
green, and slice 4 re-blesses fb goldens explicitly (the diff is the
review). The startup-verdict/stdout findings (inventory items 8, 19,
20) ride slice 3's chrome seam where trivial, else they are recorded
follow-ups, not this plan's scope.

## Out of scope

- Redesigning the shared grid's status vocabulary or the outbox glyph
  language for the handset (the glyph legend question is real but it
  is a *handset* design question — separate plan if pursued).
- iOS: these bodies reach iOS through plan 0044; the sheet/title
  equivalents there are UIKit questions for after the port boots.
- Silent action drops (inventory item 10): a client-core queue-policy
  change, follow-up ticket, not a surface fix.
- Any settings/preferences growth (0037's ruling stands).
