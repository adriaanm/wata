# Voice/Audio Architecture

This document describes the audio recording, encoding, and playback stack for the wata project.

## Overview

Both the TUI and Android app need to:
1. Record voice from microphone
2. Encode to a compressed format
3. Upload to Matrix as `m.audio` messages
4. Download and play audio from Matrix

The platforms use different libraries but target the same output format for Matrix interoperability.

## Matrix Audio Format

Matrix `m.audio` events expect audio files with proper container formats. The recommended format is:

| Property | Value |
|----------|-------|
| Container | Ogg |
| Codec | Opus |
| Sample Rate | 16kHz (voice) or 48kHz (music) |
| Channels | Mono |
| MIME Type | `audio/ogg; codecs=opus` |

Alternatively, AAC in MP4/M4A container is widely supported:

| Property | Value |
|----------|-------|
| Container | MP4/M4A |
| Codec | AAC |
| Sample Rate | 44.1kHz |
| Channels | Mono |
| MIME Type | `audio/mp4` |

## TUI Audio Stack (macOS)

### Current Implementation: PvRecorderAudioService

Uses native Node.js libraries for recording and FFmpeg for encoding/playback.

**Recording Pipeline:**
```
PvRecorder (16kHz PCM) → accumulate samples → FFmpeg (libopus) → Ogg Opus
```

**Playback Pipeline:**
```
Matrix URL → download → detect format → FFmpeg (decode to WAV) → afplay
```

**Components:**

| Library | Purpose | Link |
|---------|---------|------|
| `@picovoice/pvrecorder-node` | Cross-platform audio capture | [GitHub](https://github.com/Picovoice/pvrecorder) |
| FFmpeg | Ogg Opus encoding/playback | - |

**Frame Accumulator:**

PvRecorder outputs 512 samples per frame at 16kHz. The `FrameAccumulator` class buffers samples for Opus encoding (960 samples = 60ms frames):

```
PvRecorder: 512 samples → → → → 512 samples → ...
                   ↓ accumulate
PCM buffer:        continuous samples for FFmpeg
```

**Why 16kHz?**
- Optimized for voice/speech (not music)
- 3x smaller than 48kHz audio
- Sufficient quality for walkie-talkie use case
- Matches Opus "voip" application mode

**Future: Real-time Streaming**

For future real-time Opus encoding (e.g., live PTT streaming), consider using `@evan/opus` which supports 16kHz natively. Many other Opus encoders (e.g., Discord's) only support 48kHz, which would require resampling overhead.

**PTT (Push-to-Talk) Behavior:**

The TUI supports hold-to-record PTT:
- Hold Space → recording starts, releases when Space events stop
- Tap Space → toggle mode (press again to stop)
- Key release detection via gap in terminal key repeat events (~200ms timeout)

**Files:**
- `src/tui/services/PvRecorderAudioService.ts` - Production service
- `src/tui/hooks/useAudioRecorder.ts` - Recording hook with PTT loop
- `src/tui/hooks/useAudioPlayer.ts` - Playback hook
- `scripts/test-audio-poc.mjs` - POC with real microphone

### TuiAudioService (AudioCode Testing)

Utility service for testing the ABBREE hardware's audio codec. Used only in `bootstrap.ts` and `AdminView.tsx` for AudioCode testing.

**Functions:**
- `playWav(path)` - Play WAV files via afplay (for tone verification)
- `recordRawPcm(durationMs)` - Record raw PCM at 16kHz for AudioCode decoding

**Recording:** `rec (sox) → temp file → Float32Array`
**Playback:** `WAV file → afplay`

Main voice recording/playback uses `PvRecorderAudioService` instead.

## Android Audio Stack

### Current Implementation: Native Kotlin with Opus

The Android app uses native Kotlin with the `android-opus-codec` library for encoding and `AudioRecord`/`AudioTrack` for recording and playback.

**Recording Pipeline:**
```
AudioRecord (16kHz PCM) → OpusCodec.encode() → OggMuxer → Ogg Opus file
```

**Playback Pipeline:**
```
Matrix URL → download → OggDemuxer → OpusCodec.decode() → AudioTrack
```

**Components:**

| Class | Purpose | Location |
|-------|---------|----------|
| `AudioService` | Recording/playback orchestration | `src/android/app/src/main/java/com/wata/audio/AudioService.kt` |
| `OpusCodec` | Opus encoding/decoding wrapper | `src/android/app/src/main/java/com/wata/audio/OpusCodec.kt` |
| `OggMuxer` | Ogg container creation | `src/android/app/src/main/java/com/wata/audio/OggMuxer.kt` |
| `OggDemuxer` | Ogg container parsing | `src/android/app/src/main/java/com/wata/audio/OggDemuxer.kt` |

**Recording Configuration:**
- Sample rate: 16kHz (voice-optimized)
- Channels: mono
- Opus frame size: 960 samples (60ms at 16kHz)
- Bitrate: ~24kbps (VOIP mode)

**Playback:**
- Uses `AudioTrack` for low-latency playback
- Downloads from MXC URLs to temporary file
- Parses Ogg container, decodes Opus packets
- Streams decoded PCM to AudioTrack

**Files:**
- `src/android/app/src/main/java/com/wata/audio/` - All audio-related classes
- See [docs/android-development.md](android-development.md) for architecture details

## Playback Considerations

### Download vs Streaming

Current approach downloads entire file before playback. For long messages, consider:

1. **Progressive download** - Start playback after initial buffer
2. **HLS/DASH** - Chunked streaming (overkill for short messages)
3. **Range requests** - Resume interrupted downloads

For walkie-talkie use (short messages), download-first is acceptable.

### Platform-Specific Playback

| Platform | Player | Supported Formats |
|----------|--------|-------------------|
| macOS TUI | `afplay` | M4A, MP3, WAV, AIFF |
| macOS TUI | `ffplay` | Any (via FFmpeg) |
| Android | MediaPlayer | M4A, MP3, Ogg, WebM |

For Opus playback on macOS:
```bash
# Via FFmpeg (converts to WAV and pipes to ffplay)
ffmpeg -f opus -ar 16000 -ac 1 -i input.opus -f wav - | ffplay -

# Or convert to playable format first
ffmpeg -f opus -ar 16000 -ac 1 -i input.opus output.m4a
afplay output.m4a
```

## Ogg Container Format

To wrap raw Opus packets in Ogg for Matrix compatibility:

### Required Components

1. **Ogg Page Header** (27+ bytes per page)
   - Capture pattern: "OggS"
   - Version, header type, granule position
   - Serial number, page sequence, CRC

2. **OpusHead Packet** (first packet)
   - Magic: "OpusHead"
   - Version, channels, pre-skip
   - Sample rate, output gain

3. **OpusTags Packet** (second packet)
   - Magic: "OpusTags"
   - Vendor string, comment list

4. **Audio Data Packets**
   - Segmented into Ogg pages
   - Granule positions track sample count

### Implementation Options

1. **Manual implementation** - Build Ogg pages with Buffer
2. **ogg-packet** npm package - Low-level Ogg handling
3. **FFmpeg** - Shell out to wrap in container

## Compression Comparison

Typical compression ratios for 5 seconds of voice:

| Format | Raw PCM | Compressed | Ratio |
|--------|---------|------------|-------|
| PCM 16kHz | 160 KB | - | 100% |
| Opus 16kHz | - | ~8 KB | 5% |
| AAC 44.1kHz | - | ~40 KB | 25% |

Opus achieves 4-5x better compression than AAC for voice.

## Testing

### POC Scripts

```bash
# Real microphone recording (5 seconds)
node scripts/test-audio-poc.mjs

# Synthetic data test
node scripts/test-audio-poc-simple.mjs
```

### Playing Raw Opus Output

```bash
# The POC outputs raw Opus packets to /tmp/poc-opus-*.bin
# Play via FFmpeg:
ffmpeg -f opus -ar 16000 -ac 1 -i /tmp/poc-opus-*.bin -f wav - | ffplay -
```
