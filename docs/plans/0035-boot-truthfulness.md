# 0035 — the boot screen tells the truth, and says so out loud

Status: accepted

## The problem

Powering the handset on, the boot screen ran through this sequence
(observed 2026-08-07, device uptime under four minutes):

    can't reach server / retrying...      <- immediately, before wifi
    starting up...                        <- a flash
    can't reach server / retrying...      <- with wifi UP
    waiting for network

and then stayed unreachable indefinitely, with `/tmp/wata.log` holding
exactly two lines from the whole run — a DNS failure and a dial timeout,
both from the first minute. The device never connected until the app was
restarted by hand.

Three separate defects, one screen.

### 1. The device boots with no clock, and that alone kills the transport

`/etc/init.d/hwclock` has no battery-backed RTC to restore from, so the
system clock starts at **Jan 1 1970**. chronyd starts before wifi, its
`pool pool.ntp.org` names do not resolve yet, and its retry cadence leaves
the clock at 1970 for minutes (`chronyc sources` was empty at uptime 6m,
"8 sources with unknown address").

At 1970 every TLS handshake fails certificate validation — proven on the
device with plain `wget https://euw1-1.relay.iroh.network/`
(`certificate verify failed`). iroh's relay connections and its pkarr/DNS
address discovery are both HTTPS, so with a wrong clock the transport
CANNOT work, no matter how healthy wifi is. That is the whole outage:
setting the clock by hand and restarting wata-fb brought the contact list
up within seconds.

The clock itself is the rootfs's job, not wata's — handed off to
bq268-alpine (`docs/planning/clock-at-boot.md` there). What is wata's job
is not calling it a server failure.

### 2. The screen blamed the server for the device still booting

`bootMsg` puts `isConnError` above every calm state, so the first failed
dial — made before there is any network at all — prints "can't reach
server". Plan 0022 put it there for a real reason: a client that never
connected, under a live wifi glyph, must not sit on "waiting for network"
forever. But that reasoning only applies once the device HAS a network.
With no interface holding an address, or with no valid clock, a failed
dial says nothing about the server.

### 3. A stuck client is silent

`Dialer.logDialError` prints one line per distinct reason, forever. A
handset that has been failing the same way for an hour looks, in the log,
exactly like one that failed twice and recovered. There was no way to
tell whether the client was still trying.

## The decision

**Calm states outrank failure states while the device cannot possibly
succeed** — precisely: while the pipe is explicitly `PipeNone` (the device
has interfaces and none has an address) or the system clock is
implausible. `PipeUnknown` — the host answer — keeps plan 0022's
behavior exactly, so a Mac client pointed at a dead server still says
"can't reach server".

The clock test is a latch (`NetStatus.clockOk`): the system clock only
ever becomes valid, and a device whose clock has been set once should not
be able to fall back into the calm state. Floor: 2025-01-01 — far above
any unset clock, far below any real one.

**The network arriving retries immediately.** When the pipe goes from no
interface to an interface, the frame loop pokes `Runtime.retryNow`, which
resets the sync loop's backoff to 1s. Without it a device whose backoff
had already climbed to the 60s ceiling while it had no network sits on a
stale "can't reach server" for up to a minute after wifi is up — which is
the second "can't reach server" in the observed sequence.

**A stuck client says so on a cadence.** Two log surfaces:

- `NetStatus.logTransition` prints one line per CHANGE of the (pipe,
  health, connection, clock) tuple, with the seconds since process start:
  the boot sequence above becomes four legible lines instead of a guess.
- `Dialer.logDialError` keeps its once-per-distinct-reason rule but adds a
  repeat every 60s carrying the attempt count, so a client stuck on one
  reason is visible as such.

## What changes

| file | change |
|------|--------|
| `wata-fb/src/main/scala/netstatus.scala` | `clockOk` latch (`go.time`, floor 2025-01-01); `logTransition`; pipe-arrival edge reported by `poll` |
| `wata-fb/src/main/scala/applets.scala` | `bootMsg`/`bootSubMsg`/`bootColor` take `clockOk`; the calm-outranks-failure ordering; `bodyContacts` hoists the new read |
| `wata-fb/src/main/scala/ui.scala` | frame step: log transitions, poke `Runtime.retryNow` on the pipe-arrival edge |
| `wata-mac/src/main/scala/main.scala` | same two lines in its frame step (the applets/netstatus files are symlinks to wata-fb's) |
| `go-pkgs/irohnet/irohnet_cgo.go` | dial-failure repeat log every 60s with the attempt count |
| `tools/fb-shot.py`, `justfile` | `just fb-shot` — the panel as a PNG over ssh, which is how this was diagnosed |

## Verification

- `just fb-ui-tests` / `just ci` — the boot-screen goldens and the integ
  arcs (`integ.scala`'s provisioning arc asserts on exactly these
  strings) must stay green; a golden that changes must change because
  the new ordering is right for it, and be re-blessed deliberately.
- A new uitest script pins the device boot sequence: no pipe + a
  connection error renders "starting up...", not "can't reach server".
- On hardware, after `just fb-deploy install` and a cold boot: the
  screen shows "starting up..." until wifi is up, and `/tmp/wata.log`
  carries the transition lines.

### What the hardware runs showed (2026-08-07)

Two cold boots on the device, with the app installed at
`/opt/wata/wata-fb`. The panel said "starting up..." for the whole boot
— through the failed dials at 2s, 11s, 21s, 30s, through the modem
coming up at 40s and wifi at 48s — where the old build said "can't
reach server" from the second frame on. The log is the whole story:

```
net: +0s  pipe=none conn=connecting clock=UNSET
net: +2s  pipe=none conn=error      clock=UNSET
...
net: +40s pipe=cell conn=connecting clock=UNSET
net: +48s pipe=wifi conn=connecting clock=UNSET
irohnet: dial a90ec927… still failing after 2 more attempts: dial timeout
net: +0s  pipe=wifi conn=connecting clock=set        <- the clock, set by hand
net: +22s pipe=wifi conn=connected  clock=set
net: +22s pipe=wifi conn=syncing    clock=set
```

The last three lines are the confirmation that the clock IS the outage:
the running process — no restart, no endpoint rebuild — connected 22s
after the clock was stepped, having failed for eight minutes before it.
Left alone, chronyd never sets the clock at all (rootfs bug, root-caused
in the handoff: `/etc/resolv.conf` is `0600 root:root` and chronyd runs
as user `chrony`, so its pool never resolves), so the handset stays
calmly unusable until that lands.

## Out of scope

- Fixing the clock. That is bq268-alpine's (`docs/planning/clock-at-boot.md`).
- A "clock not set" screen of its own. Until the rootfs fix lands the
  clock is unset only for the first seconds of a boot, which is exactly
  what "starting up..." means; a second sentence for it would be a
  diagnostic on a kid's screen. The log line carries the detail.
