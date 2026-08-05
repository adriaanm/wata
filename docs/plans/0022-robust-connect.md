# 0022 — the device app off the happy path

Status: accepted

## Problem

`[FB-ROBUST-CONNECT]` `[FB-ROBUST-SEND]` A failed first connect on the
device is a dead end: there is no retry, no cancel, and no honest error
on screen. A full sweep of `wata-fb` + `wataclient` (2026-08-05)
confirmed the app is happy-path biased end to end, and found the
inherited design (the Zig reference has every one of these holes too —
they are not port regressions). The wedge-class findings:

| # | finding | where |
|---|---------|-------|
| W1 | Login failure is terminal: one failed `/login` and `syncLoop` returns; nothing respawns it | `wataclient/.../runtime.scala:145-161` |
| W2 | The stuck screen shows the calm "waiting for network" forever; the real "Connection error" text is unreachable (gated behind `everLive`, which never latches if login never succeeded) | `wata-fb/.../applets.scala:383,492-527`, `netstatus.scala:96` |
| W3 | No retry/cancel key in the boot state; the only live key (Back) quits the app — screen dark, device looks dead | `applets.scala:185-192`, `ui.scala:288` |
| W4 | No client-side HTTP timeout anywhere (TCP `DefaultClient` and iroh post-dial both unbounded); a half-open connection hangs boot or the sync loop forever with the UI showing last good state | `caps.scala:51-53`, `mhttp.scala:23-32`, `irohnet_cgo.go:355` |
| D2 | UI freeze: after a failed login the action loop stands down, but `sendAction` is a blocking send from the frame goroutine onto a cap-64 channel; when it fills, the UI thread blocks forever (receipts alone fill it). Also reachable via W4: one hung upload stalls the serial action loop | `runtime.scala:141,359-361` |
| P1 | Shutdown panic: Settings→Network OFF calls `stopClient`, quit-edge teardown calls it again; "close twice panics" is documented on the function | `applets.scala:935`, `ui.scala:176`, `runtime.scala:137` |
| D1 | A send while offline is dropped: 2s "SEND FAILED" flash, the Ogg bytes are gone; no outbox, no retry | `runtime.scala:382-397`, `applets.scala:464-467` |
| M1 | No playback-loading state: `s.playing` is set but never rendered; a slow or hung download gives zero feedback and `playing` never clears | `applets.scala:236-241,347` |
| W5 | iroh client init failure downgrades silently to a client that can never work (every request → status 0), stdout-only notice | `caps.scala:59-64` |

Lower-tier findings (stop-unaware backoff sleeps, silent config-write
failure, zero-evdev silent boot, best-effort discards on receipts/
favorites, backfill error indistinct from its page cap, staleness
invisible mid-session) are recorded in `WATA-TODO.md` rather than
scoped here. What already works and must not be re-invented: the sync
loop's 1s→60s backoff with reset-on-success, the 429 retry clamp, the
`NetStatus` health model, the iroh refusal loudness + 30s dial
deadline, the audio-thread error tiering, the LED fault model.

## Decision

Two milestones; A is the user-facing complaint plus every freeze/panic,
B is the data-loss and feedback tier. The design principle throughout:
**the client core never terminates on failure — it reports and retries;
the UI always names the state and always has a live key.**

**Milestone A — the connect lifecycle** `[FB-ROBUST-CONNECT]`

1. **Login retries forever.** Wrap `loginOrResume` in the same 1s→60s
   backoff loop `syncRounds` already uses; `syncLoop` only exits on
   `stop`. Auth *rejection* (403, bad password) is distinguished from
   transport failure: it still retries (the server may be mid-
   provisioning) but at the 60s ceiling immediately, and publishes a
   distinct `ConnAuthRejected` state so the UI can say "check account"
   rather than "waiting for network".
2. **The action loop never stands down and the UI never blocks.**
   `actionLoop` runs regardless of login state (actions fail fast into
   their existing failure events while unauthenticated); `sendAction`
   becomes non-blocking (`trySend`; on a full queue drop receipts/
   favorites silently, surface send/play as their failure flash). The
   frame goroutine must be unable to block on the client, ever.
3. **Timeouts.** The TCP path gets a per-request deadline (~30s) via
   the http.Client `Timeout` field; the iroh path gets matching
   post-dial read/write deadlines in `go-pkgs/irohnet` (dial already
   has 30s). A hung request becomes a failed round, which the backoff
   machinery already handles.
4. **The boot/error screen tells the truth and has keys.** Boot copy
   gains the failure states: "can't reach server — retrying Ns"
   (transport), "account rejected — check server" (auth), with OK =
   retry-now (resets backoff) and Back retaining quit but only via the
   existing two-step (first press shows "again to exit" for 2s) so a
   stuck user can't black-screen the device by accident. `everLive`
   stops gating the error copy: connection-error text renders whenever
   health says error, latched or not.
5. **Idempotent teardown.** `stopClient` guards the double-close (a
   `closed` flag in `Runtime`), fixing the Settings→Network OFF + Back
   panic. The disconnect path and quit path may then both call it.
6. **iroh init failure is loud**: `caps` publishes it as a permanent
   `ConnAuthRejected`-tier state ("transport unavailable") instead of
   silently downgrading to the placeholder-host client.

**Milestone B — sends survive, playback shows itself** `[FB-ROBUST-SEND]`

1. **A bounded on-disk outbox.** A failed voice send persists the Ogg
   plus target under the config dir (cap ~16, oldest dropped loudly);
   the runtime retries the outbox head on each successful sync round
   (connectivity is proven at that moment), in order. UI: an unsent
   marker on the conversation row rather than a transient flash;
   "SEND FAILED" remains for the moment of failure. Outbox survives
   restart.
2. **Playback feedback.** Render `s.playing` (the selected row's glyph
   becomes a play indicator immediately); the fetch rides the new W4
   timeout so a hung download resolves to the failure flash and clears
   `playing`. Distinguish "fetch failed" from "audio unavailable" in
   the flash text.

## What changes (file-level)

- A: `wataclient/src/main/scala/runtime.scala` (login loop, action
  loop, non-blocking sendAction, stopClient guard),
  `wata-fb/src/main/scala/caps.scala` (client timeout, loud iroh
  failure), `netstatus.scala` (auth-rejected state, error-copy
  un-gating), `applets.scala` (boot-state keys + copy, two-step quit),
  `ui.scala` (quit edge), `go-pkgs/irohnet/` (post-dial deadlines).
- B: `runtime.scala` + new `outbox.scala` in wataclient,
  `applets.scala` (unsent marker, playing glyph), `config.scala`
  (outbox dir).
- Docs: `wata-fb.md` + `wataclient.md` (connection lifecycle, outbox);
  `WATA-TODO.md` (lower-tier findings recorded).

## Verification

- A: integ/uitest legs — server down at boot → screen shows retrying
  copy, server comes up → session proceeds with no restart; wrong
  password → "account rejected" copy; OK-retry observable (tallies);
  Back is two-step; kill -STOP'd server (hung, not refused) → round
  fails within the deadline and backoff resumes; conversation-open
  spam with server down does not freeze the frame loop (frame counter
  still advances); Settings→Network OFF then Back exits clean, no
  panic. Existing goldens for boot frames updated deliberately.
- B: send with server down → unsent marker; server up → next sync
  round delivers it; restart in between → still delivered. Playback
  golden shows the playing glyph.
- Full gate green throughout; conformance 84/84 untouched.

## Out of scope

- Staleness indication mid-session (glyphs already exist; a "last
  synced" surface is a design question for the conversation screen).
- Backfill error requeue, receipt/favorite retry (best-effort stands).
- Zero-evdev and fb-open recovery (hardware-absent boot is an alpine
  integration concern).
- Any change to the Zig reference (read-only).
