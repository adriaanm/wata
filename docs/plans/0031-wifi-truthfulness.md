# 0031 — wifi truthfulness: settled scans, association verdicts, and a fallback test switch

Status: accepted (owner rulings 2026-08-06, recorded per item below; the
mechanism decisions here are this plan's)

Plan 0020 built the wifi panel and proved it end to end on hardware.
Field use the next day found the two places it reports something other
than the truth, and asked for one new op. Three items, one theme: **what
the tui prints must be what the radio did.**

## 1. Scan reports the previous sweep (WIFI-SCAN-SETTLE)

Field 2026-08-06: `wpa_cli scan` is asynchronous; `WifiCmd.scanWith`
read `scan_results` immediately, reporting the *previous cached sweep* —
a visible, currently-associated network was missing from the listing.

**Decision:** read `scan_results` once *before* triggering the scan,
then poll it (500 ms steps) until the output differs from that baseline
or a ceiling passes, and report the last read. The ceiling is
`$WATA_WIFI_SETTLE_MS` (default 4000): a sweep whose results happen to
be identical to the cache costs the full ceiling — indistinguishable
without wpa_supplicant event listening, and 4 s is an honest price. The
harness fakes stay instant by setting the ceiling to 0 (the integ
harness exports it; the tui-smoke's fake device is a Python thread and
never runs this code).

## 2. Join says ok before the network does (WIFI-JOIN-ASSOC-VERDICT)

Owner-ruled 2026-08-06 (field: a mistyped PSK answered "join ok" then
the handset silently roamed to a fallback): **the join verdict IS the
association outcome** — "ok" must be impossible without association.
The alpine helper's exit-0 = config-applied contract stays; the verdict
layer is the poller's probe.

**Decision:** after the helper exits 0, the poller probes
`wpa_cli status` (1 s steps) until the *target* ssid shows
`wpa_state=COMPLETED` or `$WATA_WIFI_ASSOC_MS` (default 20000) passes.
Only then it reports: ok = `joined <ssid>` / error =
`auth failed for <ssid>, still on <actual>` (`<actual>` = the ssid
`status` shows now, or `nothing`).

**Keep the prior psk until the new one associates once** — solved
purely in the poller (no helper change, per the ruling's preference):
before invoking the helper it copies
`/etc/wpa_supplicant/wpa_supplicant.conf` aside (same directory,
`.wata-prev` suffix); on a failed association it restores the copy and
runs `wpa_cli reconfigure`, so a bad join can never destroy the working
credential; on success the copy is deleted. The config file is handled
as opaque bytes — its format stays the helper's business. Off-device
(no conf file) the backup/restore legs are no-ops.

## 3. `wifi off` — the cellular-fallback test switch (WIFI-OFF-CMD)

Owner request 2026-08-06: a way to take a handset's wifi down from the
tui to test cellular fallback, with a stranded handset **impossible**.

**Decision:** new mailbox op `wifi_off {minutes}` (default 10, clamped
1..120), tui command `wifi off <conv#|user> [minutes]`. The device runs
`wpa_cli disable_network all` — runtime-only: nothing calls
`save_config`, so persistent config is untouched and a reboot restores
wifi — and arms an in-process auto-restore timer (`enable_network all`
+ `reassociate` after the window; `$WATA_WIFI_RESTORE_MS` overrides the
delay for the harness; a second `wifi_off` re-arms via an epoch counter,
never stacking restores). The op result is reported *after* wlan0 goes
down, over whatever transport survives — that report arriving IS the
test. The server needs no change: the mailbox is deliberately
op-agnostic (plan 0020), the body rides through untouched.

## What changes (file-level)

- `wata-fb/src/main/scala/cmdpoller.scala` — all three: the settle
  poll, the association probe + conf backup/restore, the `wifi_off`
  dispatch + restore timer.
- `wata-tui/src/main/scala/repl.scala` — the `wifi off` flow.
- `tools/integ-wifi-cli.py` — grows `status`, `disable_network`,
  `enable_network`, `reassociate` verbs, an invocation log
  (`$WATA_WIFI_CLI_LOG`), and a fake association state so both join
  verdicts are drivable.
- `wata-fb/src/main/scala/integ.scala` (`wifi-cmd`) +
  `tools/wataclient-integ.sh` — the new envs; asserts the settled scan,
  both join verdicts, the wifi_off report, and the auto-restore firing.
- `tools/tui-smoke.py` — the fake-device thread answers `wifi_off`; the
  admin script runs `wifi off`.
- Docs: wata-fb.md (poller ops), wata-tui.md (the command),
  wata-server.md untouched code-wise (the op-agnostic property is
  already recorded).

## Verification

`just ci` (integ `wifi-cmd` is the poller oracle) + `just tui-smoke`.
Device passes — the join verdict against a real mistyped PSK, and
`wifi off` proving the report rides cellular — are hardware work,
recorded in WATA-TODO.md for the main session's next deploy.

## Out of scope

- Helper/alpine changes (none needed — probe-then-rollback lives in the
  poller).
- rfkill (disable_network suffices and keeps wpa_supplicant alive to
  restore).
- Listening on wpa_supplicant's event socket (would make settle exact;
  not worth a new dependency for a 4 s ceiling).
