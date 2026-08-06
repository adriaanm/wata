# 0020 — the wifi panel: provisioning a handset's network from the tui

Status: done (2026-08-06; owner rulings 2026-08-05: mailbox seam approved
as designed; commanding is ADMIN-FLAG-gated — superseding the pre-0021
"any authenticated account" stance below; the device's own poll/report
routes authenticate as the device's account, unchanged)

Outcome: landed as specified — `devicecmd.scala` (server), the tui
`wifi`/`join` flow, `cmdpoller.scala`/`WifiCmd` (wata-fb), and the
`wifi-join` helper spec handed off to bq268-alpine
(`docs/planning/wifi-join-helper.md` + its TASKS.md line). One addition
beyond the letter of the plan: the device side's poll/report accept the
trusted `X-Wata-Node-Id` header as an alternative credential — resolved
through the account BINDING (`Bindings.userFor`), since the mailbox is
addressed per account and transport proof alone names no queue; over TCP
that path is unreachable by construction (both edges strip the header),
which the cmd-smoke pins. Reports carry a server-stamped monotonic seq
so the tui's queue-then-wait is skew-free. Gates: `just cmd-smoke` (in
ci), the tui-smoke wifi session, and integ `wifi-cmd` (the real poller
against the fake wifi seam, PSK-via-stdin proven). Still open: the
on-device `wifi_join` hardware pass, blocked on the alpine helper
(queue key `WIFI-JOIN-DEVICE-PASS`).

## Problem

`[TUI-WIFI-PANEL]` Joining a handset to a new wifi network today means
editing `/etc/wpa_supplicant/wpa_supplicant.conf` over ssh — a dev
workflow, not a family one. The handset has no keyboard; typing an
SSID/PSK on a 12-key pad is not an answer. The admin's keyboard is on
the mac, in the tui (plan 0016). What's missing is the channel between
them.

The catch-22 resolves itself on this hardware: a device that needs new
wifi credentials still reaches the server over cellular (iroh, plan
0013) or over the wifi it currently has — the client-server link is
exactly the channel that survives.

## Decision

**A device-command mailbox on the server** — a small dialect surface,
deliberately general because enrolment (plan 0014) and any future
remote-admin need land on the same seam:

```
POST /_wata/v1/cmd/{userId}      {"op": …, …}        queue a command for that user's device
GET  /_wata/v1/cmd/poll?wait=<s>                     the device's own account: long-poll its queue
POST /_wata/v1/cmd/report        {"op": …, "result": …}   the device reports back
GET  /_wata/v1/cmd/{userId}/report?op=…              the admin reads the latest report
```

- **In-memory only, never journaled.** Commands are transient by nature,
  and `wifi_join` carries a PSK — the journal is append-only with no
  compaction, and a secret that outlives its use in a file is a defect.
  A server restart drops pending commands; the tui retries. (The PSK
  still crosses the wire: iroh is encrypted, plain LAN HTTP is inside
  the trust boundary — recorded, not solved, here.)
- **Auth** (ruled 2026-08-05): queueing a command and reading a report
  require the ADMIN flag (plan 0021's gate — commanding a device,
  especially pushing wifi credentials, is an admin act; the tui logs in
  as an admin account). The device's own `/cmd/poll` and `/cmd/report`
  authenticate as the device's account. The plan's original
  any-member stance predated the admin flag and is superseded.
- The long-poll reuses the waiter discipline `/sync` already has;
  device-side cost is one extra idle long-poll goroutine.

**Two ops ship with this plan:**

| op | device does | reports |
|---|---|---|
| `wifi_scan` | `wpa_cli scan` + `scan_results` via the exec facade | `[{ssid, signal, secured}]` |
| `wifi_join` | invokes the alpine-provided `wifi-join <ssid>` helper with the PSK on stdin (never argv — argv is world-readable in /proc) | `{ok, detail}` — then the connectivity element (plan 0013 M4) shows the outcome anyway |

The **join mechanics belong to alpine** (writing the wpa_supplicant
block, `wpa_cli reconfigure`, surviving reboot): handed off as
`bq268-alpine/docs/planning/wifi-join-helper.md` — wata-fb only shells
out to the helper, mirroring the settings toggles' pattern. Until the
helper exists on-device, `wifi_join` reports `{ok: false, detail:
"wifi-join helper missing"}` honestly.

**The tui flow** (`wifi <conv#|user>`): queue `wifi_scan` → poll the
report → numbered network list → `join <n>` prompts for the PSK
(stdin, not echoed if the terminal allows) → queue `wifi_join` → poll
the report and say what happened. All plain lines, scriptable like
every other tui command.

**wata-fb** grows the command poller: one goroutine long-polling
`/cmd/poll`, dispatching to the same `Diag`/exec surface the settings
toggles use, reporting results. No device UI — the handset's screen
shows nothing during provisioning beyond the connectivity element
reacting to the network change.

## What changes (file-level)

- `wata-server/src/main/scala/devicecmd.scala` (new) — the mailbox:
  per-user queues + reports behind their own small mutex (not the
  store's; nothing journals), the four routes, the long-poll wait.
- `wataclient`: nothing — the poller is device-plumbing, not portable
  client core; it lives in `wata-fb` and calls the endpoint through the
  existing `Hs`.
- `wata-fb`: `cmdpoller.scala` (new) — the poll/dispatch/report loop;
  wifi ops via the exec facade.
- `wata-tui`: the `wifi` command flow in `repl.scala`.
- `bq268-alpine`: the `wifi-join` helper spec (handoff, committed
  there).
- Docs: wata-server.md (the mailbox), wata-fb.md (the poller),
  wata-tui.md (the flow).

## Verification

- Server integ: queue/poll/report round-trip incl. long-poll wake and
  the in-memory-only property (restart drops the queue).
- `tui-smoke` extension or a dedicated smoke: a fake device session
  (the tui itself can play the device: it polls `/cmd/poll` too)
  answers a scan with canned results; the admin session's `wifi` flow
  is asserted line-by-line. On-device wifi_join is a hardware pass,
  recorded when it happens.
- **Field 2026-08-06, both halves on hardware** (the plan's hardware
  pass, complete). Scan: owner ran `wifi alma` from the tui against the
  live handset (real server, mailbox, fb poller, real radio):
  `net 1 youbetcha signal=-52 secured=true` — exactly the report shape
  the smoke pins, from a real scan. Join: `join 1` with the PSK on
  stdin came back
  `wifi join ok wifi "youbetcha" saved (psk, priority 1); wpa_supplicant reconfigured`
  — the tui → mailbox → poller → `/usr/local/bin/wifi-join` chain end
  to end on the device, PSK never in an argv, and the handset stayed
  associated through the reconfigure.

## Out of scope

- Encrypting the PSK beyond the transport; per-op authorization tiers.
- Deleting/prioritizing stored networks; enterprise (802.1X) wifi.
- Any use of the mailbox beyond the two wifi ops (enrolment will add
  its own ops under plan 0014, not here).
