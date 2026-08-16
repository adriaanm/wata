# 0057 — the data row sets watchdog policy, not raw radios

Status: done (queue item KID-DATA-WATCHDOG-PROTOCOL, unblocked
2026-08-16 when net-watchdog supervision went live on the handset)

## The problem

The kid data row drives raw radios (`rc-service wifi start/stop`,
`pppd call cellular` direct), while the rootfs's net-watchdog — now
alive and supervised — manages cellular through its `cell-data`
wrapper and a force-mode file. They fight: pick "off" and within two
30s checks the watchdog sees unhealthy wifi and brings cellular up;
pick "cell" via raw pppd and the watchdog's state file never knows,
so a healthy-wifi check would tear down a link it thinks it owns.

## The decision

The row's three values become watchdog POLICY:

- **wifi** = `rc-service wifi start` + `cell-data auto` — wifi
  preferred, the watchdog owns failover (cell as backup within
  ~60–90s of losing real internet, back to wifi when it recovers).
  This is the everyday mode and the row's help says so ("wifi, cell
  as backup").
- **cell** = `cell-data force` (pins cellular up, failover disabled)
  + `rc-service wifi stop`.
- **off** = `cell-data off` (NEW verb, small bq268-alpine change:
  touch the force file + tear cellular down — "force" alone pins
  things ON) + `rc-service wifi stop`.

The blocking `cell-data` verbs run backgrounded like `pppd` did (its
attach budget is 45s; the plan-0056 applying spinner is the wait UI).
The force file lives in `/run` (tmpfs), so a reboot resets policy to
auto-with-wifi — the safe walkie-talkie default, documented on the
row's state doc.

Also retired in passing: `Diag.dataStart`'s comment still claimed the
one-data-call-per-boot pin plan 0055 retired everywhere else.

## What changes

- bq268-alpine `tools/cell-data.sh`: the `off` verb + status wording
  (`FORCED` covers pinned-up and pinned-down); pushed to the handset
  like the watchdog init file (rw rootfs, no reflash).
- wata `diag.scala`: `dataAuto`/`dataForce`/`dataOff` replace
  `dataStart`/`dataStop` (whose only caller was the kid apply).
- `applets.scala`: `applyCalls` maps the three targets as above; help
  text for "wifi" gains the backup phrasing.
- Kid goldens regenerate (help text); design doc data paragraph.

## Verification

`just ci`, `just mac-build`, fb-ui green. On hardware (2026-08-16,
row driven remotely by evdev key injection, observed over the USB
serial console while wifi was down): kid "off" → both radios down and
STAYING down through ~8 watchdog cycles, pin held (the fight this
plan ends); kid "cell" → FORCED, ppp0 up with real traffic (2/2 pings
via ppp0); kid "wifi" → pin removed, wlan0 back, and the WATCHDOG
tore cellular down on its next healthy check — the designed backup
cleanup, seen live. Bonus find while watching its log: net-watchdog
had a `local`-outside-a-function crash loop on every healthy check
(masked by supervision); fixed in bq268-alpine `5b902c0`, after which
one process survived past the 120s modem-sleep line for the first
time.

## Out of scope

- Persisting the policy across reboots (deliberate: boot = reachable).
- The M4 iroh flip test (M4-FLIP-TEST — this plan is its prerequisite
  hygiene, not the test).
