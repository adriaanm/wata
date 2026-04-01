# Framebuffer Client — Design Exploration

## Target Hardware (BQ268)

From [bq268-alpine](https://github.com/adriaanm/bq268-alpine):

| Spec | Value |
|------|-------|
| SoC | MSM8909 (Snapdragon 210), 4× Cortex-A7 @ 1.267 GHz |
| Arch | **32-bit ARM** (`armeabi-v7a`) — cross-compile target: `arm-linux-musleabihf` |
| RAM | ~512 MB |
| Display | ST7735S 128×160 SPI, **27 Hz**, RGB565, 90° rotation |
| Display driver | `panel-mipi-dbi-spi` (DRM tiny → fbcon → `/dev/fb0`) |
| Keypad | 2×3 GPIO matrix: UP/DOWN/LEFT/RIGHT/BACK/SELECT |
| Side keys | F1 (PTT), F2, F3, F6 — GPIO keys via evdev |
| LEDs | GPIO68 (red), GPIO69 (green), GPIO1 (button backlight) |
| Audio | **Blocked** — no mainline LPASS driver for MSM8909 yet |
| WiFi | WCNSS + WCN3620 (CAF prima driver) |
| Modem | Hexagon Q6 DSP, 4G data-only |
| Storage | eMMC, ~2.1 GiB userdata |

## Requirements

1. **Single static binary** — Zig cross-compiled for `arm-linux-musleabihf`
2. **Framebuffer rendering** — mmap `/dev/fb0`, RGB565, 128×160
3. **Status indicator** — 1px colored line at top (see below)
4. **App switching** — F3/F6 (dot buttons) cycle between applets
5. **Applets:** wata (voice messaging), snake, settings, home automation, ...
6. **Aesthetic:** minimalist 80s console — 6×8 pixel font, sharp borders, high contrast
7. **D-pad + side key navigation** — all UI via evdev, no touch
8. **LED control** — red/green LEDs for status (connected, recording, error, ...)

## Status Indicator Design

At 128×160, every pixel row matters. Three-tier approach:

### Tier 1: 1px color line (default)
Top row of pixels. Color encodes state:
- **Green** — connected, idle
- **Cyan** — syncing
- **Red** — disconnected / error
- **Yellow** — recording
- **Pulsing** — via alternating frames (27 Hz gives ~13 fps perception)

### Tier 2: LEDs
The red/green GPIO LEDs supplement the pixel line:
- **Green LED** — connected to server
- **Red LED** — error / low battery / missed message
- **Both** — recording (or blink pattern)

LEDs are visible even when screen is blanked.

### Tier 3: Expandable status bar (future)
When user presses UP at the top of the current view, the status bar expands to show details: time, battery %, wifi signal, modem status. Press UP again or BACK to collapse. This is not in initial scope — just a reserved gesture.

**Effective app area: 128×159** (1px status line) — practically the full screen.

## Language: Zig (0.16-dev)

This is a learning/hobby project. Goals: learn Zig, explore its async I/O model, enjoy the C interop story.

### Zig 0.16-dev highlights relevant to this project
- **New `std.Io` async model** — no function coloring, works with thread pool or event loop backend. Good fit for concurrent input reading + Matrix sync + rendering.
- **`std.http.Client`** — built-in HTTP with pure-Zig TLS 1.3. Sufficient for talking to Conduit on LAN. Falls back to libcurl if needed.
- **`std.json`** — mature parse + stringify. Covers the Matrix API.
- **`@cImport`** — seamless C library integration, the Zig superpower.
- **Cross-compilation** — `zig build -Dtarget=arm-linux-musleabihf` just works, including for C dependencies.

### C Libraries via `@cImport`

| Need | C Library | Notes |
|------|-----------|-------|
| Audio capture/playback | `libasound` (ALSA) | Standard Linux audio. Blocked until LPASS driver lands. |
| Opus codec | `libopus` | Encode/decode 16kHz mono. Same format as Android/TUI clients. |
| HTTP (fallback) | `libcurl` | Only if `std.http.Client` TLS issues arise. Probably unnecessary for LAN. |
| LED control | sysfs | No library — write to `/sys/class/leds/*/brightness` |

## Graphics Stack

### Raw framebuffer + tiny C helper

For a 128×160 RGB565 display, raw mmap is the right call. The framebuffer is **40 KB** — a `memcpy` blit is instant.

```
open("/dev/fb0") → ioctl(FBIOGET_*) → mmap → draw to back buffer → memcpy to fb
```

**Helper library options** (all trivially `@cImport`-able):

| Library | Size | Stars | Features |
|---------|------|-------|----------|
| [FBG](https://github.com/grz0zrg/fbg) | 2 C files | 525 | Double buffering, page flip, parallel rendering |
| [tfblib](https://github.com/vvaltchev/tfblib) | tiny | 124 | Built-in 8×16 PSF font, minimal API |
| [FBGL](https://github.com/lvntky/fbgl) | single header | 71 | 2D primitives (line, rect, circle) |

Or: **write it in pure Zig.** The fb mmap + blit is ~50 lines. Drawing primitives (filled rect, horizontal/vertical line, blit glyph) add maybe 100 more. For a 128×160 screen this is almost more natural than pulling in a C dep. The "no widgets from scratch" concern is really about layout/focus/scroll logic, not pixel drawing.

### Font: 6×8 bitmap

At 6×8: **21 columns × 19 rows** (with 1px status line: 159/8 ≈ 19). Enough for short names and status text.

| Library | Format | Notes |
|---------|--------|-------|
| [font8x8](https://github.com/dhepper/font8x8) | C header arrays | Zero deps, CP437 + Unicode blocks |
| [raster-fonts](https://github.com/idispatch/raster-fonts) | C arrays | 5×8, 6×8, 7×9, 8×8 variants |
| [Spleen](https://github.com/fcambus/spleen) | BDF/PSF/OTB | Beautiful bitmap fonts, 5×8 through 32×64 |
| [SSFN](https://codeberg.org/bzt/scalable-font2) | single C header | Loads PSF/BDF, renders to pixel buffer, no FPU |

Recommendation: embed `raster-fonts` 6×8 directly (it's a `const` array). Add SSFN later if multiple sizes are needed.

### Input: raw evdev

```zig
const fd = std.posix.open("/dev/input/event0", .{ .ACCMODE = .RDONLY }, 0);
var ev: std.os.linux.input_event = undefined;
_ = std.posix.read(fd, std.mem.asBytes(&ev));
// ev.type == EV_KEY, ev.code == KEY_UP/KEY_DOWN/..., ev.value == 0/1/2
```

The BQ268 keypad produces standard `KEY_*` codes via the `gpio-matrix-keypad` and `gpio-keys` drivers. ~20 lines, no library needed.

## Architecture

```
┌──────────────────────────────────────────┐
│ 1px status line (colored)                │
├──────────────────────────────────────────┤
│                                          │
│                                          │
│          Active Applet                   │
│       128 × 159 pixels                   │
│       21 × 19 characters                 │
│                                          │
│                                          │
└──────────────────────────────────────────┘

F3: prev applet    F6: next applet    F1: PTT (always)
```

### Applet Interface

```zig
const Applet = struct {
    name: []const u8,
    init: *const fn (*AppContext) void,
    deinit: *const fn (*AppContext) void,
    handleInput: *const fn (*AppContext, Key, KeyState) Action,
    update: *const fn (*AppContext, f32) void,  // dt in seconds — for snake, animations
    render: *const fn (*AppContext, *Framebuffer) void,
};

const Action = enum { none, quit };

const AppContext = struct {
    // shared state the shell provides to applets
    allocator: std.mem.Allocator,
    // ... applet-specific state via @fieldParentPtr or userdata pointer
};
```

### Shell (main loop)

```
1. Init framebuffer (mmap /dev/fb0, alloc back buffer)
2. Open evdev input device(s)
3. Init all applets
4. Loop:
   a. Poll evdev for input (non-blocking or with timeout)
   b. If F3/F6: switch active applet (blur old, focus new)
   c. If F1 (PTT): always route to wata applet regardless of active
   d. Route other keys to active applet's handleInput()
   e. Call active applet's update(dt)
   f. Clear back buffer
   g. Draw 1px status line
   h. Call active applet's render() on remaining region
   i. Blit back buffer to framebuffer
   j. Sleep to target frame time (33ms for ~30fps, or event-driven)
```

### Applets (planned)

| Applet | Description | Complexity |
|--------|-------------|------------|
| **wata** | Contact list → chat → PTT record/send | Medium — Matrix client, audio |
| **snake** | Classic snake game on 21×19 grid | Simple — pure game logic |
| **settings** | WiFi, volume, brightness, LED mode | Simple — sysfs reads/writes |
| **door** | Home automation — unlock front door | Simple — single HTTP call |
| **clock** | Full-screen clock (when idle) | Trivial |

### Matrix Client

No Zig Matrix library exists. Implement directly with `std.http.Client` + `std.json`:

```
POST /_matrix/client/v3/login
GET  /_matrix/client/v3/sync?since=...&timeout=30000
PUT  /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}
POST /_matrix/media/v3/upload
GET  /_matrix/client/v1/media/download/{serverName}/{mediaId}
```

Sync runs in a background thread (or async task on 0.16), pushes events to the main loop via a channel/queue.

### Audio Pipeline

Same as all other wata clients, but via C interop:

```
ALSA capture (16kHz PCM) → libopus encode → Ogg mux → HTTP upload
HTTP download → Ogg demux → libopus decode → ALSA playback
```

**Note:** Audio is blocked until LPASS lands in the mainline kernel. The app can be developed and tested with audio stubbed out — the Matrix messaging part works independently.

## Project Structure

```
src/fbclient/
├── build.zig
├── build.zig.zon
├── src/
│   ├── main.zig           # entry point, main loop, signal handling
│   ├── shell.zig          # applet manager, status line, F3/F6 switching
│   ├── framebuffer.zig    # /dev/fb0 mmap, back buffer, blit, drawing primitives
│   ├── input.zig          # evdev reader, key mapping
│   ├── font.zig           # 6×8 bitmap font rendering
│   ├── ft_font.zig        # FreeType TTF rendering (wata applet)
│   ├── freetype.zig       # FreeType C API wrapper
│   ├── led.zig            # LED + backlight + battery via sysfs
│   ├── matrix/
│   │   ├── client.zig     # Matrix client (login, sync, send, upload)
│   │   ├── types.zig      # Matrix event types, room state
│   │   └── audio.zig      # Opus encode/decode, Ogg mux/demux (C interop)
│   └── applets/
│       ├── wata.zig        # voice messaging applet
│       ├── snake.zig       # snake game
│       ├── settings.zig    # device settings
│       └── clock.zig       # idle clock display
└── fonts/
    └── 6x8.zig            # embedded font data
```

## Cross-Compilation

```bash
# From macOS or any host:
zig build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSafe

# Deploy to device:
scp zig-out/bin/wata-fb bq268:/usr/bin/
ssh bq268 wata-fb
```

Zig cross-compiles C dependencies (libopus, ALSA headers) using its bundled clang. For system libraries on the target, either:
- Vendor the C source and compile from source (preferred for libopus)
- Use the target's sysroot for headers + link dynamically

## Open Questions

1. **Display rotation** — implemented 90° CW rotation (app 128×160 → fb 160×128) based on HARDWARE.md. Set `FbdevBackend.rotate = false` if the kernel DTS already applies rotation. May also need to try 90° CCW if the image appears mirrored — swap `height - 1 - y` to `y` in the rotation loop.
2. **Framebuffer pixel format** — assumed RGB565 based on HARDWARE.md. The mmap uses hardcoded 160×128×2 = 40960 bytes.
3. **Input device paths** — resolved from HARDWARE.md: event0 (PMIC PON), event1 (matrix keypad), event2 (GPIO side keys). All three opened, failures silently skipped.
4. **Audio timeline** — LPASS bringup is the critical blocker. Until then, develop with audio stubbed.
5. **0.16-dev stability** — async I/O is landing incrementally. May need to pin a specific dev build if things break. `zig version` snapshots are available by date.
6. **FreeType cross-compilation** — `linkSystemLibrary("freetype2")` won't find ARM headers during cross-compile. Options: vendor FreeType source, use a device sysroot, or build natively on the device.

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| Framebuffer (SDL2) | Done | Dev backend, 4× scaled window |
| Framebuffer (fbdev) | Done | mmap /dev/fb0, 90° rotation |
| Input (SDL2) | Done | Keyboard mapping |
| Input (evdev) | Done | 3 input devices, non-blocking reads |
| LED/backlight | Done | sysfs writes, battery read |
| Shell + applet switching | Done | Status line, F3/F6 switching |
| Bitmap font (6×8) | Done | Embedded raster font |
| FreeType font | Done | TTF rendering for wata applet |
| Matrix client | Done | Login, sync, state snapshots |
| Snake applet | Done | |
| Clock applet | Done | |
| Charmap applet | Done | |
| Wata applet | Done | Contact list + conversation views |
| Audio | Blocked | Waiting for LPASS driver |

## Next Steps

1. **Test on device** — deploy binary, verify display + input + rotation
2. Cross-compilation: resolve FreeType linking for `arm-linux-musleabihf`
3. Audio when LPASS lands
