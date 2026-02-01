# Remove FFmpeg Dependency

## Goal
Make wata self-contained by replacing FFmpeg with pure JS/WASM audio handling using `@evan/opus` + custom Ogg container implementation.

## Key Constraints
- **Sample rate**: 16kHz (voice-optimized, 3x smaller than 48kHz)
- **Format**: Ogg Opus (`audio/ogg; codecs=opus`) for Matrix compatibility
- **Codec library**: `@evan/opus` - the only JS/WASM Opus lib with native 16kHz support
- **Platforms**: TUI (Node.js), Web (browser), Android/iOS (React Native)

## Current State (After Phase 2)
- **TUI**: ✅ Uses shared @wata/shared audio library (Ogg Opus at 16kHz)
- **Web**: Uses browser MediaRecorder (no FFmpeg) - sample rate not guaranteed
- **Android/iOS**: Uses react-native-audio-recorder-player with AAC (no FFmpeg) - no sample rate control

## Target State
- All platforms use shared `@wata/shared` audio encoding/decoding
- All platforms record/encode at 16kHz
- Ogg Opus format everywhere for Matrix compatibility
- No external binary dependencies

---

## Phase 1: Switch TUI to @evan/opus ✅ COMPLETE

**Status**: Done. Committed as `0a88324`.

**What was implemented** in `src/tui/services/PvRecorderAudioService.ts`:
- `OggDemuxer` class (lines ~49-265) - extracts Opus packets from Ogg container
- `OggOpusMuxer` class (lines ~477-636) - creates valid Ogg Opus files
- `oggCrc32()` function with Ogg's polynomial (0x04C11DB7)
- `createOggPage()`, `createOpusHead()`, `createOpusTags()` helper functions
- Encoding uses `@evan/opus` Encoder + OggOpusMuxer
- Decoding uses OggDemuxer + `@evan/opus` Decoder + encodeWav

**Tested**: TUI audio recording and playback works without FFmpeg.

---

## Phase 2: Extract Shared Audio Library ✅ COMPLETE

**Status**: Done. Committed as `d14e3f8`, `acb9cab`, `59ebd89`.

**What was implemented**:
- `src/shared/lib/ogg.ts` - Ogg container muxer/demuxer with Logger interface
- `src/shared/lib/opus.ts` - Opus encoder/decoder wrapper
- `src/shared/lib/resample.ts` - Audio resampling (any rate → 16kHz)
- `src/shared/lib/audio-codec.ts` - High-level encodeOggOpus/decodeOggOpus API
- Comprehensive unit tests: ogg.test.ts (57 tests), resample.test.ts (25 tests), audio-codec.test.ts (70 tests)
- TUI refactored to use shared library (removed ~627 lines of inline code)
- Removed unused dependencies: @discordjs/opus, @evan/opus, libopus-node from TUI

**Files created/modified**:
- New: `src/shared/lib/ogg.ts`, `src/shared/lib/opus.ts`, `src/shared/lib/resample.ts`, `src/shared/lib/audio-codec.ts`
- New: `src/shared/lib/__tests__/ogg.test.ts`, `resample.test.ts`, `audio-codec.test.ts`
- Modified: `src/tui/services/PvRecorderAudioService.ts` (now uses shared lib)
- Modified: `src/tui/package.json` (removed unused Opus deps)

### Tasks

1. **Design API**
   ```typescript
   // src/shared/lib/audio-codec.ts
   import type { Logger } from '@shared/lib/wata-client/types.js';

   interface EncodeOptions {
     sampleRate: number;  // Input sample rate (will resample to 16kHz if needed)
     channels?: 1;        // Only mono supported
     logger?: Logger;     // Optional logger (reuse shared Logger interface)
   }

   interface DecodeOptions {
     logger?: Logger;     // Optional logger
   }

   interface DecodeResult {
     pcm: Int16Array;
     sampleRate: 16000;   // Always 16kHz output
     duration: number;    // Seconds
   }

   // Encoding: accepts any sample rate, resamples to 16kHz internally
   function encodeOggOpus(pcm: Int16Array | Float32Array, options: EncodeOptions): Buffer;

   // Decoding: always outputs 16kHz
   function decodeOggOpus(ogg: Buffer, options?: DecodeOptions): DecodeResult;
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

## Phase 3: Android Ogg Opus Support

**Objective**: Android records and sends Ogg Opus (like TUI), while Web keeps native MediaRecorder.

### Platform Status

| Platform | Recording | Encoding | Status |
|----------|-----------|----------|--------|
| TUI | PvRecorder 16kHz | Shared lib (@evan/opus) | ✅ Complete |
| Web | MediaRecorder | Native browser Opus | ✅ Keep as-is |
| Android | react-native-audio-recorder-player | AAC | ❌ Needs work |

### Design Decisions

1. **TUI**: Already uses shared lib at 16kHz. No changes needed.

2. **Web**: Keep native MediaRecorder Opus encoding.
   - Browsers output webm/ogg opus at their native rate (typically 48kHz)
   - No need for WASM - browser's Opus encoder is battle-tested
   - Smaller bundle size
   - Playback works everywhere (HTML5 Audio handles Opus natively)

3. **Android**: Replace `react-native-audio-recorder-player` with `react-native-audio-pcm-stream`
   - Current library only supports AAC, not Opus
   - `react-native-audio-pcm-stream` provides raw PCM at configurable sample rate
   - Configure for 16kHz mono to match TUI
   - Encode with shared lib (`encodeOggOpus`)
   - Playback: Android MediaPlayer supports Ogg Opus natively (API 21+)

### Tasks

1. **Add react-native-audio-pcm-stream dependency**
   ```bash
   pnpm add react-native-audio-pcm-stream --filter @wata/rn
   cd ios && pod install  # if iOS support needed later
   ```

2. **Refactor `src/rn/services/AudioService.ts`**
   - Replace `react-native-audio-recorder-player` imports
   - Configure PCM stream: `{ sampleRate: 16000, channels: 1, bitsPerSample: 16 }`
   - Accumulate PCM chunks during recording
   - On stop: encode accumulated PCM with `encodeOggOpus({ sampleRate: 16000 })`
   - Return Ogg Opus buffer with `mimeType: 'audio/ogg; codecs=opus'`

3. **Update playback**
   - Android MediaPlayer handles Ogg Opus natively
   - May need to write temp file for playback (check if URL playback works)

4. **Test cross-platform**
   - TUI → Android: TUI sends Ogg Opus, Android plays
   - Android → TUI: Android sends Ogg Opus, TUI plays
   - Web → Android: Web sends webm/ogg opus, Android plays
   - Android → Web: Android sends Ogg Opus, Web plays

### Files to Modify
- `src/rn/package.json` - add react-native-audio-pcm-stream
- `src/rn/services/AudioService.ts` - rewrite for PCM + shared lib

### Dependencies
- `react-native-audio-pcm-stream` (https://github.com/mybigday/react-native-audio-pcm-stream)
  - Configurable sample rate (16kHz supported)
  - Streams raw PCM via events
  - Works with bare React Native

### Success Criteria
- Android produces Ogg Opus at 16kHz
- Android plays Ogg Opus from TUI and Web
- TUI and Web play Ogg Opus from Android
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
import { Encoder, Decoder } from '@evan/opus';

// Encoding (uses options object, not positional args)
const encoder = new Encoder({
  sample_rate: 16000,
  channels: 1,
  application: 'voip',
});
const opusPacket: Uint8Array = encoder.encode(pcmBuffer);

// Decoding
const decoder = new Decoder({
  sample_rate: 16000,
  channels: 1,
});
const pcm: Uint8Array = decoder.decode(opusPacket);
// Note: decode returns Uint8Array, convert to Int16Array:
// new Int16Array(pcm.buffer, pcm.byteOffset, pcm.length / 2)
```

### Audio Parameters (Fixed)
- Sample rate: 16000 Hz
- Channels: 1 (mono)
- Opus frame size: 960 samples (60ms)
- Opus bitrate: 24kbps
- Opus application: voip

---

## Dependencies

**After Phase 2** (current state):
- `@evan/opus` is in `src/shared/package.json` (WASM, 16kHz)
- Unused Opus libs removed from TUI (`@discordjs/opus`, `libopus-node`)

**Phase 3 additions** (`src/rn/package.json`):
- `react-native-audio-pcm-stream` - raw PCM recording at 16kHz

---

## Subagent Task Summary

| Phase | Primary Task | Key Files | Blocker |
|-------|--------------|-----------|---------|
| **1** | Replace FFmpeg with @evan/opus in TUI | `src/tui/services/PvRecorderAudioService.ts` | None |
| **2** | Extract Ogg/Opus into shared lib | `src/shared/lib/ogg.ts`, `opus.ts`, `audio-codec.ts` | Phase 1 |
| **3** | Android Ogg Opus support | `src/rn/services/AudioService.ts` | Phase 2 |

### Phase 1 Subagent Instructions
1. Read `src/tui/services/PvRecorderAudioService.ts`
2. Replace `encodeToOggOpus()` (line ~292) to use `@evan/opus` + inline Ogg muxer
3. Replace FFmpeg decode in `startPlayback()` (line ~480) with Ogg demuxer + `@evan/opus`
4. Test: record in TUI, verify plays in Element; receive from Element, verify plays in TUI

### Phase 2 Subagent Instructions

**Source code to extract** from `src/tui/services/PvRecorderAudioService.ts`:
- Lines ~14-28: OggPage interface and format documentation
- Lines ~30-265: `OggDemuxer` class
- Lines ~271-304: `OGG_CRC32_TABLE` and `oggCrc32()` function
- Lines ~306-387: `createOggPage()` function
- Lines ~389-464: `createOpusHead()` and `createOpusTags()` functions
- Lines ~466-636: `OggOpusMuxer` class

**Note**: The code currently uses `LogService` for warnings. When extracting:
- Accept an optional `Logger` parameter (reuse the existing interface from `@shared/lib/wata-client/types.ts`)
- Use a no-op logger by default (same pattern as `WataClient`, `SyncEngine`, `DMRoomService`)
- This keeps the shared lib platform-agnostic while allowing each platform to provide its own logging implementation

**Steps**:
1. Extract Ogg mux/demux from Phase 1 into `src/shared/lib/ogg.ts`
2. Create `src/shared/lib/resample.ts` for any-rate → 16kHz conversion
3. Create `src/shared/lib/opus.ts` wrapper around `@evan/opus`
4. Create `src/shared/lib/audio-codec.ts` with `encodeOggOpus()` and `decodeOggOpus()`
   - `encodeOggOpus()` accepts any sample rate, resamples to 16kHz internally
   - `decodeOggOpus()` always outputs 16kHz
5. Add unit tests for ogg, resample, and audio-codec
6. Move `@evan/opus` dependency to `src/shared/package.json`
7. Update `PvRecorderAudioService.ts` to import from `@shared/lib/ogg.js` etc.
8. Verify imports work from TUI, Web, and RN workspaces

### Phase 3 Subagent Instructions

**Scope**: Android only. TUI is complete, Web keeps native MediaRecorder.

**Steps**:
1. Add `react-native-audio-pcm-stream` to `src/rn/package.json`
2. Rewrite `src/rn/services/AudioService.ts`:
   - Import from `react-native-audio-pcm-stream` and `@shared/lib/audio-codec.js`
   - Configure: `{ sampleRate: 16000, channels: 1, bitsPerSample: 16, audioSource: 6 }`
   - `startRecording()`: Start PCM stream, accumulate Int16Array chunks
   - `stopRecording()`: Stop stream, concatenate chunks, call `encodeOggOpus(pcm, { sampleRate: 16000 })`
   - Return `{ buffer, mimeType: 'audio/ogg; codecs=opus', duration, size }`
   - Playback: Android MediaPlayer handles Ogg Opus natively (API 21+)
3. Test: record on Android, verify plays in TUI and Web; receive from TUI/Web, verify plays on Android
