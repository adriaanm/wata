# 0039 — the handset says hello

Status: done — shipped and heard on the handset; the cold-boot leg of the
verification is outstanding (WATA-TODO.md, client/device)

## The problem

The BQ268 boots into wata with no sound at all. A walkie-talkie that comes
up silent gives its user nothing to distinguish "ready" from "still
booting" from "broken" — and this device has a ~40s runlevel it cannot
shorten (the two radios cannot boot together), so that ambiguity lasts a
long time. A kid holding it has no way to know when it started working.

A short bleep at startup answers that, and the same asset is the natural
PTT and roger beep later, which is the reason to get its shape right now
rather than ship a one-off.

## The source, and what is actually shipped

The owner picked the sound: the first second of
`walkie-talkie-radio-signal-activation-bosnow-1-00-02.mp3`. Profiled per
100ms, that file is **two** distinct bleeps with silence between them —
the first runs 100–560ms, then nothing until a second bleep at
1200–1710ms. So "the first second" contains exactly one bleep plus ~440ms
of trailing silence.

We ship **0.00–0.60s**: the whole first bleep with a short tail, and none
of the silence. Trailing silence costs almost nothing in Opus but it does
cost 400ms of startup before the thing is done making noise, and the
second bleep is deliberately not included — one bleep is the "ready"
signal, two would read as a message arriving.

Format is the device's own: **Ogg/Opus, mono, 48 kHz**, which is what the
Q6 accepts and what every voice message already is. So the chirp plays
through the *same* decode-and-play path as a message
(`Ogg.readFrames` → `audio.Decoder.decodeFrame` → `audio.playMessage`),
with no second audio path to keep working.

### One packet per page, and why that is not a detail

ffmpeg's default Ogg muxer packs many Opus packets into one page. Our
reader does not split a page's lacing into packets — `Ogg.readFrames`
treats a page's whole payload as one frame — so a default-muxed file is
read as a **single 2000-byte "frame"** whose TOC claims 20ms. The
repo's own foreign-container oracle says so out loud:

```
$ wata-fb oggforeign chirp.ogg        # ffmpeg defaults
packet 1: 2000B toc-cfg 31 -> 960 samples@48k
readFrames-count 1
granule-matches-toc false             # 29112 granule vs 960 claimed
```

Encoding with `-page_duration 20000` puts one 20ms packet in each page
and the same oracle reads 31 packets. That is what the asset is built
with.

The reader limitation was **real and was not this plan's to fix**: a
voice message from a foreign encoder that pages the same way would be
mis-read the same way, which is a playback bug nobody had hit because the
pinned fixture happens to be one-packet-per-page. Filed separately as
`OGG-MULTI-PACKET-PAGE`; noted here because the encoder flag is otherwise
an unexplained incantation.

**Fixed 2026-08-08.** `Ogg.readFrames` walks the lacing table, so the
flag no longer protects anything and the asset would be correct without
it. It stays only because the asset is committed as bytes and
`make-chirp.py --check` has to keep reproducing them; drop it at the next
regeneration from the source. What the fix did add here is
`wata-fb/assets/chirp-repaged.ogg` — this same chirp re-muxed with
ffmpeg's default paging (`make-chirp.py --repage`), 31 packets over 3
pages instead of 33. Because a re-mux moves the Opus packets untouched,
the two assets differ in their lacing and nothing else, so wata-fb's
smoke can assert the exact property: the reader's report of the two is
identical apart from the byte and page counts.

### Byte-stability needs one more flag

`-fflags +bitexact`. The Ogg muxer otherwise picks a RANDOM stream serial per
run and stamps its own vendor string into the comment header, so two encodes
of identical audio differ in the serial and in every page CRC — and "did the
asset change" stops being a question the bytes can answer. bitexact pins the
serial to `serial_offset` (0) and drops the vendor string; the audio is
untouched.

## The decision

**A file beside the binary, not bytes in the source.** `chirp.ogg` is
~3 KB, committed under `wata-fb/assets/`, deployed to
`/opt/wata/chirp.ogg`. The alternative — a generated 3000-element array
literal, the way the font table is spelled — makes an unreviewable diff
out of something that is plainly data. The cost is a deploy that can skew:
the app logs one line and stays quiet if the asset is missing, rather than
failing to start.

`tools/make-chirp.py` rebuilds the asset from the owner's source, so the
trim, the bitrate and the page-duration flag are recorded as code rather
than as a command someone once ran.

## What changes

| file | change |
|---|---|
| `wata-fb/assets/chirp.ogg` | the asset (new, committed) |
| `tools/make-chirp.py` | rebuilds it from the source mp3; documents trim/format |
| `wata-fb/src/main/scala/chirp.scala` | load, decode and play once; a `play()` the PTT beep can reuse |
| `wata-fb/src/main/scala/ui.scala` (or the startup path) | play after the mixer is set up |
| `tools/fb-deploy.sh` | ship the asset next to the binary, both run and install |
| `docs/design/wata-fb.md` | the asset, its format, and the page-duration reason |
| `justfile` | `make-chirp` and `chirp-check` recipes |
| `tools/wata-fb-smoke.sh` | the `oggforeign` oracle over the committed asset |
| `tools/chirp-check.py` | the device oracle: is the bleep audible? |

## The silent position

The volume knob is **hardware** — there is no software volume anywhere in
the client (`PlayVol` is a fixed 8192, and settings has no volume row), so
the knob's off position silences the chirp by construction, exactly as it
silences a message. Nothing to implement; recorded because the ticket
raises it and the answer is not obvious from the code.

## The cold-boot race, stated rather than solved

The codec resets `RX2 MIX1 INP1` to zero as the Q6 comes up, so a route
written too early is wiped. bq268-alpine's `audio-mixer` applies, verifies
and then watches both directions for two minutes after boot, which covers
the boot window — but wata's own `SetupMixer` runs once at startup, and
the chirp plays right after it. On an unlucky cold boot the chirp can
therefore be inaudible while everything reports success.

That is the same defect `AUDIO-ROUTE-REAPPLY` already tracks (it cost a
real send four seconds of digital silence), and the chirp does not make it
worse — it makes it **audible**, which is the point: a missing hello is
the cheapest possible signal that the route is wrong, available before
anyone tries to send anything. The durable fix stays that ticket's.

## Verification

- `tools/make-chirp.py` is rerunnable and its output is byte-stable, so a
  regenerated asset that differs is a real change.
- `wata-fb oggforeign wata-fb/assets/chirp.ogg` reads 31 packets with
  `reader-sees-all-packets true` — the existing oracle, pointed at the new
  asset, is what proves the container is one our own reader understands.
- `just ci` green; `just fb-smoke` and the goldens unmoved (this adds no
  screen).
- On the device, after `just fb-deploy install`: the bleep is audible.
  bq268-alpine's `just speaker-check` cannot judge it — it plays its OWN
  sine through `speaker-test` on `hw:0,0`, which wata holds, and its band
  test assumes a single tone. `just chirp-check` is the counterpart that
  can: same in-recording ratios, but it makes the APP play and scans for
  the loudest 0.5s window, since the chirp is one short event. Measured
  on the handset: 8x the neighbouring band, 5-8x the baseline, against a
  negative control at 0.3x.
- The cold-boot leg — the one where the codec can wipe the route as the Q6
  comes up — is still owed (`just chirp-check --cold-boot`, WATA-TODO.md):
  the attempt landed the device in aboot's fastboot rather than Linux, so
  it recorded a device that was not booting.

## Out of scope

- The PTT and roger beeps. The asset and `Chirp.play()` are shaped so they
  are a call site, not a redesign, but choosing when they fire is a
  product decision that belongs with `MSG-NOTIFICATION-DESIGN`.
- Fixing `Ogg.readFrames` for multi-packet pages (`OGG-MULTI-PACKET-PAGE`)
  — done separately on 2026-08-08, see above.
- Any software volume control.
