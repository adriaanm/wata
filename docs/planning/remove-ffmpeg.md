# Remove FFmpeg Dependency

## Goal
Make wata self-contained by replacing FFmpeg with pure JS/WASM audio handling using `@evan/opus` + custom Ogg container implementation.

## Key Constraints
- **Sample rate**: 16kHz (voice-optimized, 3x smaller than 48kHz)
- **Format**: Ogg Opus (`audio/ogg; codecs=opus`) for Matrix compatibility
- **Codec library**: `@evan/opus` - the only JS/WASM Opus lib with native 16kHz support
- **Platforms**: TUI (Node.js), Web (browser), Android/iOS (React Native)

## Current State
- **TUI**: Uses FFmpeg for PCM→Ogg Opus encoding and Ogg→WAV decoding
- **Web**: Uses browser MediaRecorder (no FFmpeg) - sample rate not guaranteed
- **Android/iOS**: Uses react-native-audio-recorder-player with AAC (no FFmpeg) - no sample rate control

## Target State
- All platforms use shared `@wata/shared` audio encoding/decoding
- All platforms record/encode at 16kHz
- Ogg Opus format everywhere for Matrix compatibility
- No external binary dependencies

---

## Phase 1: Switch TUI to @evan/opus

**Objective**: Replace FFmpeg calls in `PvRecorderAudioService.ts` with `@evan/opus` + inline Ogg handling.

### Tasks

1. **Replace encoding** (`encodeToOggOpus` method at line 292)
   - Use `@evan/opus` Encoder (already in package.json)
   - Implement minimal Ogg muxer inline (can be extracted later)
   - Required Ogg structures: OggS pages, OpusHead, OpusTags

2. **Replace decoding** (`startPlayback` method at line 426)
   - Implement Ogg demuxer to extract Opus packets
   - Use `@evan/opus` Decoder
   - Output PCM, write as WAV for `afplay`

3. **Test**
   - Record voice message, verify Ogg output plays in Element
   - Receive Ogg message from Element, verify playback works
   - Verify round-trip: TUI → Matrix → TUI

### Files to Modify
- `src/tui/services/PvRecorderAudioService.ts`

### Success Criteria
- `pnpm tui` works without FFmpeg installed
- Audio messages interoperate with Element (Matrix client)

---

## Phase 2: Extract Shared Audio Library

**Objective**: Move Opus/Ogg handling into `@wata/shared` for cross-platform use.

### Tasks

1. **Design API**
   ```typescript
   // src/shared/lib/audio-codec.ts

   interface EncodeOptions {
     sampleRate: number;  // Input sample rate (will resample to 16kHz if needed)
     channels?: 1;        // Only mono supported
   }

   interface DecodeResult {
     pcm: Int16Array;
     sampleRate: 16000;   // Always 16kHz output
     duration: number;    // Seconds
   }

   // Encoding: accepts any sample rate, resamples to 16kHz internally
   function encodeOggOpus(pcm: Int16Array | Float32Array, options: EncodeOptions): Buffer;

   // Decoding: always outputs 16kHz
   function decodeOggOpus(ogg: Buffer): DecodeResult;
   ```

2. **Extract Ogg implementation**
   - Move Ogg muxer from Phase 1 into `src/shared/lib/ogg.ts`
   - Move Ogg demuxer into same file
   - Add unit tests

3. **Implement resampler**
   - Create `src/shared/lib/resample.ts`
   - Support common rates → 16kHz (44100→16000, 48000→16000)
   - Use linear interpolation or higher quality algorithm
   - API: `resample(samples: Float32Array, fromRate: number, toRate: number): Float32Array`
   - Consider: `libsamplerate` WASM for high quality, or simple JS for smaller bundle

4. **Extract Opus wrapper**
   - Create `src/shared/lib/opus.ts` wrapping `@evan/opus`
   - Handle encoder/decoder lifecycle
   - Auto-resample input to 16kHz if needed
   - Add unit tests

5. **Verify platform compatibility**
   - Test `@evan/opus` WASM in Node.js (TUI) ✓ (already works)
   - Test in Vite/browser (Web)
   - Test in React Native (may need polyfills)

### New Files
- `src/shared/lib/ogg.ts` - Ogg container mux/demux
- `src/shared/lib/resample.ts` - Audio resampling (any rate → 16kHz)
- `src/shared/lib/opus.ts` - Opus encoder/decoder wrapper
- `src/shared/lib/audio-codec.ts` - High-level API
- `src/shared/lib/__tests__/ogg.test.ts`
- `src/shared/lib/__tests__/resample.test.ts`
- `src/shared/lib/__tests__/audio-codec.test.ts`

### Investigation: Platform Recording Capabilities

**Web**: Check actual sample rate from getUserMedia
```typescript
const stream = await navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000 } });
const track = stream.getAudioTracks()[0];
const settings = track.getSettings();
console.log('Actual sample rate:', settings.sampleRate); // May not be 16000
```

**Android**: Test `react-native-audio-recorder-player` with explicit sample rate
```typescript
const audioSet = {
  AudioSamplingRateAndroid: 16000,
  AVSampleRateKeyIOS: 16000,
  // ...
};
```

**Fallback strategy**: If 16kHz recording isn't reliably available:
1. Record at native rate
2. Resample to 16kHz in shared lib before Opus encoding
3. Would need a resampler (e.g., `libsamplerate` WASM or simple linear interpolation)

### Success Criteria
- Shared lib passes unit tests
- Imports work from all three platforms
- Document actual sample rates achieved on each platform

---

## Phase 3: Refactor Platforms to Use Shared Lib

**Objective**: All platforms use `@wata/shared` audio codec with consistent 16kHz Ogg Opus.

### Current Platform Issues

| Platform | Recording | Problem |
|----------|-----------|---------|
| TUI | PvRecorder 16kHz | ✅ None - already 16kHz |
| Web | MediaRecorder | ⚠️ `sampleRate: 16000` is a hint, browsers may ignore |
| Android | react-native-audio-recorder-player | ❌ Uses AAC at device default (~44.1kHz) |

### Tasks

1. **Refactor TUI**
   - Replace inline Ogg/Opus code with shared lib imports
   - Verify functionality preserved

2. **Refactor Web**
   - Use AudioWorklet to capture raw PCM at known rate
   - Resample to 16kHz if needed (or accept browser rate)
   - Use shared lib to encode to Ogg Opus
   - Alternative: Accept browser's native Opus encoding if 16kHz not critical for web

3. **Refactor Android**
   - Option A: Configure `react-native-audio-recorder-player` for 16kHz
     ```typescript
     const audioSet = {
       AVSampleRateKeyIOS: 16000,
       AudioSamplingRateAndroid: 16000,
       // ...
     };
     ```
   - Option B: Capture raw PCM via native module, encode with shared lib
   - Option C: Record at native rate, resample + re-encode with shared lib
   - Decision: Try Option A first (lowest effort)

4. **Playback compatibility**
   - All platforms must play both 16kHz Ogg Opus (new) and legacy formats
   - Web: HTML5 Audio supports Ogg Opus natively
   - Android: MediaPlayer supports Ogg Opus on API 21+
   - TUI: Use shared lib to decode → WAV → afplay

### Files to Modify
- `src/tui/services/PvRecorderAudioService.ts`
- `src/web/src/services/WebAudioService.ts`
- `src/rn/services/AudioService.ts`

### Success Criteria
- All platforms record at 16kHz (or consistent rate)
- All platforms produce Ogg Opus output
- All platforms can play Ogg Opus from any other platform
- No FFmpeg dependency anywhere

---

## Technical Reference

### Ogg Container Format (Audio-only)

```
[OggS Page 0: OpusHead]
  - "OggS" magic (4 bytes)
  - Version, flags, granule pos, serial, page seq, CRC
  - OpusHead packet: "OpusHead" + version + channels + pre-skip + sample rate

[OggS Page 1: OpusTags]
  - OpusTags packet: "OpusTags" + vendor string + comments

[OggS Page 2+: Audio Data]
  - Opus packets segmented into pages
  - Granule position tracks samples
```

### @evan/opus API

```typescript
import { Encoder, Decoder } from '@evan/opus/lib.mjs';

// Encoding
const encoder = new Encoder(16000, 1); // 16kHz, mono
const opusPacket: Uint8Array = encoder.encode(pcmBuffer);

// Decoding
const decoder = new Decoder(16000, 1);
const pcm: Int16Array = decoder.decode(opusPacket);
```

### Audio Parameters (Fixed)
- Sample rate: 16000 Hz
- Channels: 1 (mono)
- Opus frame size: 960 samples (60ms)
- Opus bitrate: 24kbps
- Opus application: voip

---

## Dependencies

Current (`src/tui/package.json`):
```json
"@evan/opus": "^1.0.3"  // ✓ Keep - 16kHz WASM encoder/decoder
"@discordjs/opus": "^0.10.0"  // ✗ Remove - 48kHz only, native
"libopus-node": "^0.0.4"  // ✗ Remove - native, not WASM
```

After refactor:
- `@evan/opus` moves to `src/shared/package.json`
- Remove unused Opus libraries from TUI

---

## Subagent Task Summary

| Phase | Primary Task | Key Files | Blocker |
|-------|--------------|-----------|---------|
| **1** | Replace FFmpeg with @evan/opus in TUI | `src/tui/services/PvRecorderAudioService.ts` | None |
| **2** | Extract Ogg/Opus into shared lib | `src/shared/lib/ogg.ts`, `opus.ts`, `audio-codec.ts` | Phase 1 |
| **3** | Refactor all platforms to use shared lib | All `*AudioService.ts` files | Phase 2 |

### Phase 1 Subagent Instructions
1. Read `src/tui/services/PvRecorderAudioService.ts`
2. Replace `encodeToOggOpus()` (line ~292) to use `@evan/opus` + inline Ogg muxer
3. Replace FFmpeg decode in `startPlayback()` (line ~480) with Ogg demuxer + `@evan/opus`
4. Test: record in TUI, verify plays in Element; receive from Element, verify plays in TUI

### Phase 2 Subagent Instructions
1. Extract Ogg mux/demux from Phase 1 into `src/shared/lib/ogg.ts`
2. Create `src/shared/lib/resample.ts` for any-rate → 16kHz conversion
3. Create `src/shared/lib/opus.ts` wrapper around `@evan/opus`
4. Create `src/shared/lib/audio-codec.ts` with `encodeOggOpus()` and `decodeOggOpus()`
   - `encodeOggOpus()` accepts any sample rate, resamples to 16kHz internally
   - `decodeOggOpus()` always outputs 16kHz
5. Add unit tests for ogg, resample, and audio-codec
6. Move `@evan/opus` dependency to `src/shared/package.json`
7. Verify imports work from TUI, Web, and RN workspaces

### Phase 3 Subagent Instructions
1. Investigate actual sample rates on Web and Android (see Investigation section)
2. Update TUI to use shared lib (replace inline code from Phase 1)
3. Update Web:
   - Get raw PCM from AudioWorklet or ScriptProcessorNode
   - Pass to shared lib with actual sample rate (resampling handled internally)
4. Update Android:
   - Either configure `react-native-audio-recorder-player` for lower sample rate
   - Or get raw PCM and pass to shared lib (resampling handled internally)
5. Test cross-platform: message from each platform plays on all others
