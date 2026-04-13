# Metrics Heartbeat Tick

**Origin**: spec handed from [bq268-alpine](../../../bq268-alpine) — the Alpine port wants to gather battery/wifi/cellular metrics on device and correlate energy usage with screen/wifi/cellular time. Rather than run an independent sampler with its own timer (which would fight wata for CPU/radio wakeups), the sampler aligns its wakeups to wata's matrix long-poll loop. wata's only job is to emit a heartbeat datagram once per iteration.

wata MUST NOT read, parse, or care about metrics. It only emits a beat. All metrics content (battery, wifi counters, backlight state, etc.) is the sampler's responsibility and lives in the bq268-alpine repo.

## Rationale

- The MSM8909 cannot suspend (CAF 4.4 SPM bug), so any independent periodic sampler keeps the SoC warm on its own schedule. Coalescing the sampler's wakeups with wata's existing matrix-poll cadence means CPU + radio + sampler all burst together rather than spreading wakeups across the second.
- wata's matrix long-poll is the natural clock: it wakes on either a server event or the poll timeout, and every iteration is a moment where wata is about to do real work anyway.

## Socket

- Path: `/run/wata.tick`
- Type: `AF_UNIX`, `SOCK_DGRAM`
- wata is the **sender**. The sampler daemon (on the bq268-alpine side) owns the socket, binds it, and sets permissions. wata does not create or unlink it.
- Absence of the socket is the normal case (sampler not installed, not running, or started after wata). wata must run unmodified in that case.

## Setup (once, at wata startup)

1. `fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0)`
2. Store a `struct sockaddr_un` pointing at `/run/wata.tick`.
3. If socket creation itself fails, log once and disable the feature. Never abort wata.

No `connect()` is required — use `sendto()` with the address each time. (Optional: `connect()` once to avoid passing the address on every send; either is fine.)

## Per-iteration emit

Place the emit call **immediately after the matrix long-poll returns**, before rendering or other work. One call per iteration, regardless of whether the poll returned events or timed out.

Payload: 16 bytes, little-endian, packed:

| offset | size | field        | meaning |
|-------:|-----:|--------------|---------|
|   0    |   8  | `ts_mono_ns` | `clock_gettime(CLOCK_BOOTTIME)` in nanoseconds |
|   8    |   4  | `seq`        | `uint32`, incremented every emit, wraps freely |
|  12    |   1  | `phase`      | `0` = post-poll (only value used in v1) |
|  13    |   3  | reserved     | zero |

Call:

```c
sendto(fd, buf, 16, MSG_DONTWAIT, (struct sockaddr*)&addr, sizeof(addr));
```

### Errors to silently ignore (no logging in hot path)

- `ENOENT` — socket file does not exist (sampler not running)
- `ECONNREFUSED` — no reader attached
- `EAGAIN` / `EWOULDBLOCK` — sampler's recv queue full; drop the tick
- `ENOTCONN` — only relevant if using connected mode

Any other `errno`: log once and disable further emits for the remainder of this wata run.

## What wata MUST NOT do

- No retries, no buffering, no backpressure handling.
- No blocking calls — `MSG_DONTWAIT` is mandatory.
- No dependency on the sampler being present.
- No metrics content in the payload. If you're tempted to add battery, wifi, or signal-strength fields, stop — that belongs in the sampler.
- No log spam on the hot path. The errors above are expected and silent.

## Testing

1. wata starts and runs normally when `/run/wata.tick` does not exist.
2. wata starts and runs normally when the socket exists but no reader is attached.
3. With a test reader bound to a `SOCK_DGRAM` unix socket at that path, exactly one 16-byte datagram is received per matrix poll iteration, `seq` is monotonic (modulo wrap), and `ts_mono_ns` is monotonic.

A minimal test reader (for local verification):

```sh
# in one shell
rm -f /run/wata.tick
socat -u UNIX-RECV:/run/wata.tick - | xxd
```

## Open questions for the sampler side

These are tracked in bq268-alpine and do not block wata implementation:

- Whether to add a `phase=1` (post-render) tick later for separating network-in from render-out energy. v1 is post-poll only.
- Socket permissions (`0662` vs a dedicated group) depend on what uid wata runs as. wata does not need to care — the sampler sets perms on bind.
