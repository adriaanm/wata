/**
 * AudioCode - "QR Code over Audio"
 *
 * Acoustic data transfer for device onboarding using 16-MFSK modulation
 * with Reed-Solomon error correction. Optimized for speaker-to-microphone
 * transmission in noisy environments.
 *
 * ## Modulation (16-MFSK)
 *
 * - 16 frequencies spaced across 1500-3375 Hz (125 Hz spacing)
 * - 4 bits per symbol (16 tones)
 * - 35ms per symbol (25ms tone + 10ms guard interval)
 * - ~28.5 symbols/second
 * - Raw rate: ~114 bps, ~36 bps after RS overhead
 *
 * ## Error Correction (Reed-Solomon)
 *
 * - GF(256) field (ByteAs8bit preset from reedsolomon.es)
 * - 100% redundancy ratio (1:1 data:parity)
 * - For N data bytes: produces 2N total bytes (N data + N parity)
 * - Can correct up to N/2 byte errors per block
 * - With 100% redundancy: ~50% error correction capability
 *
 * Note: The library's strict decode mode has a bug that fails on clean data,
 * so we use sloppy mode which still provides error correction.
 *
 * ## Frame Structure
 *
 * ```
 * [PREAMBLE] [SYNC] [LENGTH] [RS-ENCODED PAYLOAD] [END]
 *     5 sym     4 sym   2 sym       variable         2 sym
 * ```
 *
 * - PREAMBLE: Alternating tone 0/8 for AGC and timing recovery
 * - SYNC: 0xA5A5 pattern for frame synchronization
 * - LENGTH: 2 symbols (1 byte) = original payload length (max 255 bytes)
 * - RS-ENCODED: Payload + Reed-Solomon parity bytes
 * - END: 0xFF marker
 *
 * ## Performance
 *
 * | Payload | Encoded | Audio Duration |
 * |---------|---------|----------------|
 * | 17 bytes (JSON) | 50 bytes | 3.3s |
 * | 111 bytes (onboarding) | 222 bytes | 13.3s |
 *
 * ## Usage
 *
 * ```ts
 * import { encodeAudioCode, decodeAudioCode } from '@shared/lib/audiocode.js';
 *
 * // Encode
 * const data = { homeserver: "https://...", username: "...", ... };
 * const audio = encodeAudioCode(data);
 *
 * // Decode
 * const decoded = await decodeAudioCode(audio);
 * ```
 */



import { Buffer } from 'buffer';

// import type { ReedSolomonES } from './reedsolomon.es.d';
import { ReedSolomonES } from './rsWrapper.js';

// ============================================================================
// Configuration
// ============================================================================

export interface AudioCodeConfig {
  sampleRate: number;
  symbolDuration: number; // Total symbol time in ms (tone + guard)
  toneDuration: number; // Tone duration in ms
  baseFrequency: number; // Lowest tone frequency
  frequencySpacing: number; // Hz between adjacent tones
  numTones: number; // Number of distinct frequencies (16 = 4 bits/symbol)
}

export const DEFAULT_CONFIG: AudioCodeConfig = {
  sampleRate: 16000,
  symbolDuration: 35, // 35ms per symbol = ~28.5 symbols/sec
  toneDuration: 25, // 25ms tone, 10ms guard
  baseFrequency: 1500, // Start at 1500 Hz
  frequencySpacing: 125, // 125 Hz spacing
  numTones: 16, // 16 tones = 4 bits per symbol
};

// ============================================================================
// Constants
// ============================================================================

// Frame markers (as symbol values 0-15)
const SYNC_PATTERN = [0xa, 0x5, 0xa, 0x5]; // Alternating pattern for sync
const END_PATTERN = [0xf, 0xf]; // End marker

// Preamble: alternating between tone 0 and tone 8 for AGC and timing
const PREAMBLE_SYMBOLS = 5;

// Reed-Solomon parameters
const RS_PRESET = 'ByteAs8bit' as const; // GF(256)
const RS_REDUNDANCY_RATIO = 1.0; // 100% redundancy (1:1 data:parity)

// Magic byte to identify binary format
const BINARY_FORMAT_MAGIC = 0xb1;

// ============================================================================
// Types
// ============================================================================

/**
 * Onboarding credentials structure for type-safe binary encoding
 */
export interface OnboardingCredentials {
  homeserver: string;
  username: string;
  password: string;
  room: string;
}

// ============================================================================
// Reed-Solomon Error Correction
// ============================================================================

/**
 * Encode data with Reed-Solomon error correction.
 * Input: N bytes, Output: 2N bytes (N data + N parity)
 */
function rsEncode(data: Uint8Array): Uint8Array {
  const encoded = ReedSolomonES.encode(data, RS_PRESET, RS_REDUNDANCY_RATIO);
  return new Uint8Array(encoded);
}

/**
 * Calculate expected encoded length for given data length.
 * With 100% redundancy: encodedLength = dataLength * 2
 */
function getEncodedLength(dataLength: number): number {
  return dataLength + Math.floor(dataLength * RS_REDUNDANCY_RATIO);
}

/**
 * Decode Reed-Solomon encoded data with error correction.
 * Uses sloppy mode due to library bug in strict mode.
 */
function rsDecode(encoded: Uint8Array, originalDataLength: number): Uint8Array {
  const decoded = ReedSolomonES.decode(
    encoded,
    RS_PRESET,
    RS_REDUNDANCY_RATIO,
    true, // sloppy mode - strict mode has a bug that fails on clean data
  );
  return new Uint8Array(decoded.subarray(0, originalDataLength));
}

// ============================================================================
// Serialization
// ============================================================================

/**
 * Check if data is an OnboardingCredentials object
 */
function isOnboardingCredentials(data: unknown): data is OnboardingCredentials {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as OnboardingCredentials).homeserver === 'string' &&
    typeof (data as OnboardingCredentials).username === 'string' &&
    typeof (data as OnboardingCredentials).password === 'string' &&
    typeof (data as OnboardingCredentials).room === 'string'
  );
}

/**
 * Serialize data to compact binary format.
 *
 * For OnboardingCredentials:
 *   [MAGIC:1][homeserver_len:1][homeserver][user_len:1][user][pass_len:1][pass][room_len:1][room]
 *
 * For other types: JSON string
 */
function serializePayload(data: unknown): Buffer {
  if (isOnboardingCredentials(data)) {
    const homeserver = Buffer.from(data.homeserver, 'utf-8');
    const username = Buffer.from(data.username, 'utf-8');
    const password = Buffer.from(data.password, 'utf-8');
    const room = Buffer.from(data.room, 'utf-8');

    // Validate lengths fit in 1 byte
    if (
      homeserver.length > 255 ||
      username.length > 255 ||
      password.length > 255 ||
      room.length > 255
    ) {
      throw new Error('Field too long for binary encoding');
    }

    const totalLen =
      1 + // magic
      1 +
      homeserver.length +
      1 +
      username.length +
      1 +
      password.length +
      1 +
      room.length;

    const buffer = Buffer.alloc(totalLen);
    let offset = 0;

    buffer[offset++] = BINARY_FORMAT_MAGIC;
    buffer[offset++] = homeserver.length;
    homeserver.copy(buffer, offset);
    offset += homeserver.length;
    buffer[offset++] = username.length;
    username.copy(buffer, offset);
    offset += username.length;
    buffer[offset++] = password.length;
    password.copy(buffer, offset);
    offset += password.length;
    buffer[offset++] = room.length;
    room.copy(buffer, offset);

    return buffer;
  }

  // Fallback to JSON for other data types
  const json = JSON.stringify(data);
  return Buffer.from(json, 'utf-8');
}

/**
 * Deserialize binary format or JSON to data
 */
function deserializePayload(buffer: Buffer): unknown {
  // Check for binary format magic byte
  if (buffer.length > 0 && buffer[0] === BINARY_FORMAT_MAGIC) {
    let offset = 1;

    const homeserverLen = buffer[offset++];
    const homeserver = buffer
      .subarray(offset, offset + homeserverLen)
      .toString('utf-8');
    offset += homeserverLen;

    const usernameLen = buffer[offset++];
    const username = buffer
      .subarray(offset, offset + usernameLen)
      .toString('utf-8');
    offset += usernameLen;

    const passwordLen = buffer[offset++];
    const password = buffer
      .subarray(offset, offset + passwordLen)
      .toString('utf-8');
    offset += passwordLen;

    const roomLen = buffer[offset++];
    const room = buffer.subarray(offset, offset + roomLen).toString('utf-8');

    return { homeserver, username, password, room };
  }

  // Fallback to JSON
  const json = buffer.toString('utf-8');
  return JSON.parse(json);
}

// ============================================================================
// Symbol Conversion
// ============================================================================

/**
 * Convert bytes to 4-bit symbols (nibbles)
 */
function bytesToSymbols(bytes: Uint8Array): number[] {
  const symbols: number[] = [];
  for (const byte of bytes) {
    symbols.push((byte >> 4) & 0x0f); // High nibble
    symbols.push(byte & 0x0f); // Low nibble
  }
  return symbols;
}

/**
 * Convert 4-bit symbols back to bytes
 */
function symbolsToBytes(symbols: number[]): Uint8Array {
  const bytes = new Uint8Array(Math.floor(symbols.length / 2));
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = ((symbols[i * 2] & 0x0f) << 4) | (symbols[i * 2 + 1] & 0x0f);
  }
  return bytes;
}

// ============================================================================
// Modulation
// ============================================================================

/**
 * Generate frequency for a given symbol (0-15)
 */
function symbolToFrequency(symbol: number, config: AudioCodeConfig): number {
  return config.baseFrequency + symbol * config.frequencySpacing;
}

/**
 * Raised cosine envelope for smooth tone transitions
 */
function raisedCosineEnvelope(
  sample: number,
  totalSamples: number,
  rolloff: number,
): number {
  const rampSamples = Math.round(totalSamples * rolloff);
  if (sample < rampSamples) {
    return 0.5 * (1 - Math.cos((Math.PI * sample) / rampSamples));
  }
  if (sample > totalSamples - rampSamples) {
    const remaining = totalSamples - sample;
    return 0.5 * (1 - Math.cos((Math.PI * remaining) / rampSamples));
  }
  return 1.0;
}

/**
 * Modulate symbols to audio samples
 */
function modulate(symbols: number[], config: AudioCodeConfig): Float32Array {
  const { sampleRate, symbolDuration, toneDuration } = config;

  const samplesPerSymbol = Math.round((sampleRate * symbolDuration) / 1000);
  const samplesPerTone = Math.round((sampleRate * toneDuration) / 1000);
  const totalSamples = symbols.length * samplesPerSymbol;

  const samples = new Float32Array(totalSamples);
  let phase = 0;
  const dt = 1 / sampleRate;

  for (let i = 0; i < symbols.length; i++) {
    const symbol = symbols[i];
    const freq = symbolToFrequency(symbol, config);
    const omega = 2 * Math.PI * freq;
    const symbolStart = i * samplesPerSymbol;

    for (let j = 0; j < samplesPerTone; j++) {
      const envelope = raisedCosineEnvelope(j, samplesPerTone, 0.1);
      samples[symbolStart + j] = 0.8 * envelope * Math.sin(phase);
      phase += omega * dt;
    }
  }

  return samples;
}

// ============================================================================
// Demodulation
// ============================================================================

/**
 * Goertzel algorithm for single-frequency magnitude detection
 */
function goertzelMagnitude(
  samples: Float32Array,
  start: number,
  length: number,
  targetFreq: number,
  sampleRate: number,
): number {
  const k = Math.round((length * targetFreq) / sampleRate);
  const omega = (2 * Math.PI * k) / length;
  const coeff = 2 * Math.cos(omega);

  let s0 = 0;
  let s1 = 0;
  let s2 = 0;

  for (let i = 0; i < length && start + i < samples.length; i++) {
    s0 = samples[start + i] + coeff * s1 - s2;
    s2 = s1;
    s1 = s0;
  }

  return s1 * s1 + s2 * s2 - coeff * s1 * s2;
}

/**
 * Find signal boundaries using energy detection
 */
function findSignalBoundaries(
  samples: Float32Array,
  config: AudioCodeConfig,
): { start: number; end: number } {
  const { sampleRate, baseFrequency, frequencySpacing, numTones } = config;
  const windowMs = 50;
  const windowSize = Math.round((sampleRate * windowMs) / 1000);
  const windowStep = Math.round(windowSize / 2);

  const powers: { power: number; start: number }[] = [];

  for (let i = 0; i < samples.length - windowSize; i += windowStep) {
    let totalPower = 0;
    for (let tone = 0; tone < numTones; tone++) {
      const freq = baseFrequency + tone * frequencySpacing;
      totalPower += goertzelMagnitude(samples, i, windowSize, freq, sampleRate);
    }
    powers.push({ power: totalPower, start: i });
  }

  if (powers.length === 0) {
    return { start: 0, end: samples.length };
  }

  const sorted = [...powers].sort((a, b) => a.power - b.power);
  const p10 = sorted[Math.floor(sorted.length * 0.1)].power;
  const p90 = sorted[Math.floor(sorted.length * 0.9)].power;
  const threshold = p10 + (p90 - p10) * 0.3;

  let startIdx = 0;
  let endIdx = powers.length - 1;

  for (let i = 0; i < powers.length; i++) {
    if (powers[i].power > threshold) {
      startIdx = i;
      break;
    }
  }

  for (let i = powers.length - 1; i >= 0; i--) {
    if (powers[i].power > threshold) {
      endIdx = i;
      break;
    }
  }

  return {
    start: Math.max(0, powers[startIdx].start - windowSize),
    end: Math.min(samples.length, powers[endIdx].start + windowSize * 2),
  };
}

/**
 * Find sync offset by searching for preamble pattern
 */
function findSyncOffset(
  samples: Float32Array,
  start: number,
  end: number,
  config: AudioCodeConfig,
): number {
  const { sampleRate, symbolDuration, toneDuration } = config;
  const samplesPerSymbol = Math.round((sampleRate * symbolDuration) / 1000);
  const samplesPerTone = Math.round((sampleRate * toneDuration) / 1000);

  let bestOffset = start;
  let bestScore = -1;
  const searchStep = Math.round(samplesPerSymbol / 4);
  const maxSearch = Math.min(
    end - start,
    samplesPerSymbol * (PREAMBLE_SYMBOLS + 10),
  );

  for (let offset = 0; offset < maxSearch; offset += searchStep) {
    const testStart = start + offset;
    let score = 0;

    for (let i = 0; i < PREAMBLE_SYMBOLS; i++) {
      const expectedTone = i % 2 === 0 ? 0 : 8;
      const expectedFreq = symbolToFrequency(expectedTone, config);
      score += goertzelMagnitude(
        samples,
        testStart + i * samplesPerSymbol,
        samplesPerTone,
        expectedFreq,
        sampleRate,
      );
    }

    if (score > bestScore) {
      bestScore = score;
      bestOffset = testStart;
    }
  }

  return bestOffset;
}

/**
 * Demodulate audio samples to symbols
 */
function demodulate(samples: Float32Array, config: AudioCodeConfig): number[] {
  const { sampleRate, symbolDuration, toneDuration, numTones } = config;

  const samplesPerSymbol = Math.round((sampleRate * symbolDuration) / 1000);
  const samplesPerTone = Math.round((sampleRate * toneDuration) / 1000);

  const { start, end } = findSignalBoundaries(samples, config);
  const syncOffset = findSyncOffset(samples, start, end, config);

  const symbols: number[] = [];
  let position = syncOffset;

  while (position + samplesPerTone <= end) {
    let maxPower = -1;
    let bestSymbol = 0;

    for (let tone = 0; tone < numTones; tone++) {
      const freq = symbolToFrequency(tone, config);
      const power = goertzelMagnitude(
        samples,
        position,
        samplesPerTone,
        freq,
        sampleRate,
      );
      if (power > maxPower) {
        maxPower = power;
        bestSymbol = tone;
      }
    }

    symbols.push(bestSymbol);
    position += samplesPerSymbol;
  }

  return symbols;
}

/**
 * Parse AudioCode frame from symbols
 */
function parseFrame(symbols: number[]): {
  dataLength: number;
  encodedPayload: number[];
} {
  // Find sync pattern
  let syncIndex = -1;
  for (let i = 0; i <= symbols.length - SYNC_PATTERN.length; i++) {
    let match = true;
    for (let j = 0; j < SYNC_PATTERN.length; j++) {
      if (symbols[i + j] !== SYNC_PATTERN[j]) {
        match = false;
        break;
      }
    }
    if (match) {
      syncIndex = i;
      break;
    }
  }

  if (syncIndex === -1) {
    throw new Error('AudioCode: Sync pattern not found');
  }

  // Read length
  const lenIndex = syncIndex + SYNC_PATTERN.length;
  if (lenIndex + 2 > symbols.length) {
    throw new Error('AudioCode: Frame too short for length field');
  }
  const dataLength = (symbols[lenIndex] << 4) | symbols[lenIndex + 1];

  // Calculate expected RS-encoded length
  const encodedLength = getEncodedLength(dataLength);
  const encodedSymbols = encodedLength * 2;

  // Extract encoded payload
  const payloadStart = lenIndex + 2;
  if (payloadStart + encodedSymbols > symbols.length) {
    throw new Error('AudioCode: Frame too short for payload');
  }

  const encodedPayload = symbols.slice(
    payloadStart,
    payloadStart + encodedSymbols,
  );

  return { dataLength, encodedPayload };
}

// ============================================================================
// Public API
// ============================================================================

/**
 * Encode data to audio samples.
 *
 * @param data - Any serializable data (OnboardingCredentials uses compact binary format)
 * @param config - Optional configuration
 * @returns Float32Array of audio samples at config.sampleRate
 *
 * @example
 * ```ts
 * const credentials = {
 *   homeserver: "https://matrix.org",
 *   username: "alice",
 *   password: "secret",
 *   room: "!family:matrix.org"
 * };
 * const audio = encodeAudioCode(credentials);
 * ```
 */
export function encodeAudioCode(
  data: unknown,
  config: AudioCodeConfig = DEFAULT_CONFIG,
): Float32Array {
  const payload = serializePayload(data);
  const encoded = rsEncode(new Uint8Array(payload));
  const dataSymbols = bytesToSymbols(encoded);

  const frame: number[] = [];

  // Preamble
  for (let i = 0; i < PREAMBLE_SYMBOLS; i++) {
    frame.push(i % 2 === 0 ? 0 : 8);
  }

  // Sync pattern
  frame.push(...SYNC_PATTERN);

  // Length
  frame.push((payload.length >> 4) & 0x0f);
  frame.push(payload.length & 0x0f);

  // Payload
  frame.push(...dataSymbols);

  // End marker
  frame.push(...END_PATTERN);

  return modulate(frame, config);
}

/**
 * Decode audio samples to data.
 *
 * @param samples - Float32Array of audio samples
 * @param config - Optional configuration
 * @returns Promise that resolves to the decoded data
 *
 * @example
 * ```ts
 * const decoded = await decodeAudioCode(audioSamples);
 * console.log(decoded); // { homeserver: "...", username: "...", ... }
 * ```
 */
export async function decodeAudioCode(
  samples: Float32Array,
  config: AudioCodeConfig = DEFAULT_CONFIG,
): Promise<unknown> {
  const symbols = demodulate(samples, config);
  const frame = parseFrame(symbols);
  const encodedBytes = symbolsToBytes(frame.encodedPayload);

  let decoded: Uint8Array;
  try {
    decoded = rsDecode(encodedBytes, frame.dataLength);
  } catch {
    throw new Error(
      'AudioCode: Reed-Solomon decoding failed - too many errors',
    );
  }

  return deserializePayload(Buffer.from(decoded));
}
