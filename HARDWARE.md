# BQ268 Hardware Reference — Walkie-Talkie App

Direct hardware interfaces for an Alpine Linux userspace application.
No display server, no PulseAudio — raw device access.

## Display

| Property | Value |
|----------|-------|
| Device | `/dev/fb0` |
| Driver | `fb_st7735r` (SPI TFT) |
| Resolution | 160 × 128 |
| Pixel format | RGB565 (16-bit: R5 G6 B5) |
| Framebuffer size | 40960 bytes (160 × 128 × 2) |
| Stride | 320 bytes (160 × 2) |

Write directly: `open("/dev/fb0", O_RDWR)` → `mmap()` → write RGB565 pixels.
No vsync or page flipping — writes are immediately visible.

### Backlight

| Path | Range |
|------|-------|
| `/sys/class/leds/lcd-bl/brightness` | 0–40 |

Write `"0"` to turn off, `"40"` for full brightness.

## Audio

All audio routes through the Qualcomm Q6 ADSP. The codec is MSM8X16-WCD ("cajon").

| Property | Value |
|----------|-------|
| Card | `hw:0` (`msm8909-snd-card`) |
| PCM device | `hw:0,0` (MultiMedia1) |
| Sample rate | 48000 Hz (Q6 native; other rates rejected) |
| Format | S16_LE |
| Channels | 1 (mono) |

### Speaker Playback

```sh
# Mixer setup (one-time after boot)
amixer -q cset name='RX2 MIX1 INP1' RX1
amixer -q cset name='RDAC2 MUX' RX2
amixer -q cset name='HPHR' Switch
amixer -q cset name='Ext Spk Switch' 1
amixer -q cset name='RX2 Digital Volume' 84        # 0–124

# Playback
aplay -D hw:0,0 -f S16_LE -r 48000 -c 1 file.wav
```

Route: `MultiMedia1 → PRI_MI2S_RX → RX2 MIX1 → RDAC2 → HPHR PA → GPIO36 ext amp → speaker`

Volume: `RX2 Digital Volume` (0–124, ~0.01 dB/step). Hardware potentiometer also works (analog, pre-PA).

### Microphone Capture

```sh
# Mixer setup (one-time after boot)
amixer -q cset name='MultiMedia1 Mixer TERT_MI2S_TX' 1
amixer -q cset name='DEC1 MUX' ADC1
amixer -q cset name='ADC1 Volume' 6                 # 0–8 analog gain
amixer -q cset name='DEC1 Volume' 104               # 0–124 digital gain

# Capture
arecord -D hw:0,0 -f S16_LE -r 48000 -c 1 -d 5 recording.wav
```

Route: `Handset Mic → AMIC1 → MIC BIAS Internal1 → ADC1 → DEC1 → I2S TX1 → TERT_MI2S_TX → MultiMedia1`

**Important:** TX capture uses Tertiary MI2S (not Primary). Primary MI2S is RX-only.

### Programmatic Audio

Use ALSA `snd_pcm_*` API directly. Key constraints:
- Rate must be 48000
- Always open `hw:0,0` for both playback and capture
- Set mixer controls via `snd_mixer_*` or by calling `amixer` at startup
- Playback and capture can run simultaneously (full duplex)

## Input Devices

### Physical Key Layout

The device has 4 side buttons, a 6-key D-pad/nav cluster, and a power button.

### GPIO Keys (`/dev/input/event2`)

| Key | Label | GPIO | Code | Constant |
|-----|-------|------|------|----------|
| Main PTT (side) | `main_ptt` | 91 | 59 | `KEY_F1` |
| Headset PTT (side) | `headset_ptt` | 92 | 60 | `KEY_F2` |
| Side button 3 | `key_f3` | 90 | 61 | `KEY_F3` |
| Side button 4 | `key_f6` | 112 | 68 | `KEY_F10` |

All wake-capable. Main PTT and Headset PTT are the two large side buttons — primary interaction for walkie-talkie.

### Matrix Keypad (`/dev/input/event1`)

2×3 GPIO matrix (rows: GPIO 98/110, cols: GPIO 95/96/97).

| Key | Code | Constant |
|-----|------|----------|
| Enter (center) | 28 | `KEY_ENTER` |
| Up | 103 | `KEY_UP` |
| Down | 108 | `KEY_DOWN` |
| Left | 105 | `KEY_LEFT` |
| Right | 106 | `KEY_RIGHT` |
| Back/Esc | 1 | `KEY_ESC` |

### PMIC PON (`/dev/input/event0`)

| Key | Code | Constant |
|-----|------|----------|
| Power button | 116 | `KEY_POWER` |
| RESIN (side) | 62 | `KEY_F4` |

### Reading Keys

Use `evdev` — `open("/dev/input/eventN")`, `read()` `struct input_event`.
All three devices emit `EV_KEY` events. For PTT: watch `KEY_F1` press/release.

## LEDs

| LED | Path | Use |
|-----|------|-----|
| Red | `/sys/class/leds/red/brightness` | Notification / low battery |
| Green | `/sys/class/leds/green/brightness` | Charging / status |
| Button backlight | `/sys/class/leds/button-backlight/brightness` | Nav key illumination |
| LCD backlight | `/sys/class/leds/lcd-bl/brightness` | Display (0–40) |

Write `"0"` or `"255"` (or `max_brightness`). Binary on/off for red/green/button.

## Battery

| Property | Path | Unit |
|----------|------|------|
| Capacity (SOC) | `/sys/class/power_supply/battery/capacity` | % |
| Voltage | `/sys/class/power_supply/battery/voltage_now` | µV |
| Status | `/sys/class/power_supply/battery/status` | `Charging` / `Discharging` / `Full` |
| USB current | `/sys/class/power_supply/usb/current_max` | µA |
| USB online | `/sys/class/power_supply/usb/online` | 0/1 |

BC1.2 charger detection auto-sets USB current: SDP=500mA, DCP/CDP=1500mA.

## Network

| Interface | Purpose |
|-----------|---------|
| `wlan0` | WiFi (Prima WLAN, WPA2) |
| `usb0` | USB ECM (Ethernet over USB gadget) |

WiFi managed by `wpa_supplicant`. IP via DHCP or static.

## USB Serial Console

| Device (host side) | Device (device side) |
|---------------------|----------------------|
| `/dev/ttyACM0` | `/dev/ttyGS0` |

USB ACM gadget via configfs. `getty` runs on `ttyGS0`.

## Summary: App Init Sequence

```
1. Open /dev/fb0, mmap framebuffer (40960 bytes, RGB565)
2. Set backlight: echo 40 > /sys/class/leds/lcd-bl/brightness
3. Set audio mixer controls (amixer calls or snd_mixer API)
4. Open /dev/input/event0,1,2 for key input (epoll)
5. Open hw:0,0 for audio capture and playback
6. Read battery from /sys/class/power_supply/battery/
7. Control LEDs via /sys/class/leds/*/brightness
```
