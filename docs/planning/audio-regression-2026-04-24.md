# BQ268 audio regression — investigation log (2026-04-24)

**Status:** unresolved. Audio was working previously; now neither wata-fb
playback nor `aplay -D hw:0,0` produces audible sound, even on a clean
reboot with `audio-mixer` init.d service started. This doc captures
what was ruled out so we can resume.

## Symptoms

- App: received voice messages do not play; echo test in settings
  applet silently fails (no errors surfaced, but no sound either).
- Recording also reported as failing end-to-end, but mic capture
  *does* produce non-zero samples in isolation (peak ~4700/32767 in
  selftest stage 3 — mic route is alive).
- Kernel toggles the external speaker PA (GPIO 36) correctly around
  every playback stream (`Ext Spk event = 2` on start, `= 4` on end).
- `aplay -D hw:0,0 /tmp/tone.wav` produces the same kernel pattern
  and is also silent, so this is **not** a wata-fb bug at the point
  of this writing.

## Leading hypothesis

The cellular modem is now initialised at boot on this device (it was
not when audio was last confirmed working). MSM8909 shares the Q6 DSP
between ADSP (audio) and MDSP (modem); a modem-initiated voice-call
path, BT/SCO route, or AFE port contention could preempt or silence
MultiMedia1 → PRI_MI2S_RX. ACDB calibration failures
(`afe_find_cal: no matching cal_block found`, `q6asm_send_cal:
cal_block is NULL`) are noisy in dmesg and might be masking a real
configuration issue when the modem is present.

## Ruled out this session

- **Mixer routing.** All six controls from the boot-time audio-mixer
  service verified `on`/correct via `amixer -c 0 cget`:
  `PRI_MI2S_RX Audio Mixer MultiMedia1 = on`, `RX2 MIX1 INP1 = RX1`,
  `RDAC2 MUX = RX2`, `HPHR = Switch`, `Ext Spk Switch = On`,
  `RX2 Digital Volume = 96` (was 84; matched init.d now).
- **Volume.** `RX2 Digital Volume` bumped to max (124). Silent.
- **Kernel tainted state.** Reboot clears prior oops from
  `msm_pcm_trigger` / xrun traces; silence persists on fresh boot.
- **Contention.** No other processes hold `/dev/snd/*`, no second
  wata-fb, PCM substream reports `closed` when idle.
- **Our code.** `aplay` bypasses wata-fb entirely and is also silent.

## Fixes landed this session (independent of the silence)

These are real bugs fixed along the way; keep them even if the root
cause of silence is elsewhere.

1. `audio_thread.zig:audioThreadMain` now calls `alsa.setupMixer()`
   at startup. It was calling `setupPlaybackMixer()`, which commit
   `5a64830` had turned into a no-op — meaning the mixer was never
   configured when wata-fb ran standalone. Relied on the boot-time
   init.d service having set the right state.
2. `alsa.setupMixer()` now explicitly asserts
   `PRI_MI2S_RX Audio Mixer MultiMedia1 = 1` — the route gate from
   PCM device 0 (MultiMedia1) to the output DAI. Every other
   control we set depends on it, but it was absent.
3. `audio_thread.zig:doPlayback` reverted from 12-period chunked
   writes to a single `pcm_writei` of the whole decoded buffer.
   Chunked writes stalled inside `msm_pcm_playback_copy:
   wait_event_timeout failed`; echo_test's single-write pattern (the
   one described in `docs/voice.md` line 248) works. Kernel-handles-
   chunking comment was accurate at commit `f6b057c` but regressed
   since — likely related to the overall silence issue.
4. Error paths in `audio_thread.zig` and `alsa.zig` now surface
   `pcm_get_error` strings and named errors via stderr instead of
   silent `catch {}`. Previously a failed `pcm_writei` produced no
   sign at all from inside the app.

## Diagnostic: `wata-fb --selftest [echo|play|all]`

Spawns the real production audio thread and drives it through its
command mailbox — no reimplementation of PCM plumbing. Exercises:

- `echo_test`: record 2s → encode → decode → play (full production
  roundtrip)
- `play`: synthesise 1.5s of 440 Hz tone via the real Opus encoder
  and Ogg writer, send it through the production `play` command.

Build + deploy + run:

```
just fb-audio-test all        # both stages
just fb-audio-test play       # just the ogg-decode-and-play path
just fb-audio-test echo       # just echo_test
```

## Next steps

- Check `Tainted: G D WC` kernel traces during modem boot — compare
  a wata-only boot against a full boot and see if the modem DSP
  startup touches ADSP state.
- Try disabling modem services (`rc-service modem stop` or
  whatever the right lever is in bq268-alpine) and re-running
  `aplay`. If audio returns, the modem-vs-audio Q6 contention is
  confirmed as the cause.
- If modem isn't the cause: run `alsabat` / `alsaloop` as a
  reference implementation that's known to drive the ADSP correctly,
  compare the ioctl sequence with `strace`.
- Consider asking: was any kernel-side change to the bq268 device
  tree or WCD regmap wrapper made between the last known-working
  audio test and now?

## Artefacts worth keeping

- `bq268-alpine/rootfs/files/etc/init.d/audio-mixer` — source of
  truth for the boot-time mixer configuration.
- `bq268-alpine/docs/roadmap.md` §"Audio — done" — signal-path doc.
- `docs/voice.md` §"MSM Q6 ADSP Constraints" — the hard-won lessons
  about `period_count=2`, auto-start, and single-write playback.
