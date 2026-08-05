# 0020 — the wifi panel: provisioning a handset's network from the tui

Status: proposed

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
- **Auth**: any authenticated account may command any device in v1 (the
  network is the family; the tui logs in as a real account). Revisit
  alongside plan 0018's power-level story if "any member administers
  any handset" ever becomes wrong.
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

## Out of scope

- Encrypting the PSK beyond the transport; per-op authorization tiers.
- Deleting/prioritizing stored networks; enterprise (802.1X) wifi.
- Any use of the mailbox beyond the two wifi ops (enrolment will add
  its own ops under plan 0014, not here).
