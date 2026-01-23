/**
 * MFSK Modem Codec (Multi-Frequency Shift Keying)
 *
 * Robust acoustic data transfer for device onboarding.
 * Optimized for speaker-to-microphone transmission in noisy environments.
 *
 * Modulation: 16-MFSK (4 bits per symbol)
 * - 16 frequencies spaced across 1500-3375 Hz
 * - 125 Hz spacing between tones
 * - 35ms per symbol (25ms tone + 10ms guard)
 * - Effective rate: ~36 bps after RS overhead
 *
 * Error Correction: Reed-Solomon using reedsolomon.es library
 * - ByteAs8bit preset (GF(256))
 * - 50% redundancy (~25% error correction capability)
 * - Can correct up to 12.5% byte errors per block
 *
 * Frame structure:
 * [PREAMBLE] [SYNC 4 sym] [LEN 2 sym] [RS-ENCODED PAYLOAD] [END 2 sym]
 */

/// <reference path="./reedsolomon.es.d.ts" />

import { Buffer } from 'buffer';
import { ReedSolomonES } from './rsWrapper.js';

export interface MfskConfig {
  sampleRate: number;
  symbolDuration: number; // Total symbol time in ms (tone + guard)
  toneDuration: number; // Tone duration in ms
  baseFrequency: number; // Lowest tone frequency
  frequencySpacing: number; // Hz between adjacent tones
  numTones: number; // Number of distinct frequencies (16 = 4 bits/symbol)
}

export const DEFAULT_CONFIG: MfskConfig = {
  sampleRate: 16000,
  symbolDuration: 35, // 35ms per symbol = ~28.5 symbols/sec
  toneDuration: 25, // 25ms tone, 10ms guard
  baseFrequency: 1500, // Start at 1500 Hz
  frequencySpacing: 125, // 125 Hz spacing
  numTones: 16, // 16 tones = 4 bits per symbol
};

// Frame markers (as symbol values 0-15)
const SYNC_PATTERN = [0xa, 0x5, 0xa, 0x5]; // Alternating pattern for sync detection
const END_PATTERN = [0xf, 0xf]; // End marker

// Preamble: alternating between tone 0 and tone 8 for AGC and timing
const PREAMBLE_SYMBOLS = 5; // 250ms of preamble at 50ms/symbol

// RS parameters using high-level API
const RS_PRESET = 'ByteAs8bit' as const; // 8-bit symbols = GF(256)
const RS_REDUNDANCY_RATIO = 0.5; // 50% redundancy (~25% error correction)

/**
 * Encode data bytes with Reed-Solomon error correction
 * Uses the high-level ReedSolomonES.encode() API
 */
function rsEncode(data: Uint8Array): Uint8Array {
  // The high-level API handles all the complexity
  const encoded = ReedSolomonES.encode(data, RS_PRESET, RS_REDUNDANCY_RATIO);
  return new Uint8Array(encoded);
}

/**
 * Get the expected encoded length for a given data length
 * For small data (< 256 bytes with ByteAs8bit), the library uses a single block
 * Formula: dataLength + floor(dataLength * redundancyRatio * 2)
 */
function getEncodedLength(dataLength: number): number {
  // The library encodes as: dataLength + Math.floor(dataLength * redundancyRatio * 2)
  // For 17 bytes with 25% ratio: 17 + Math.floor(17 * 0.25 * 2) = 17 + 8 = 25
  return dataLength + Math.floor(dataLength * RS_REDUNDANCY_RATIO * 2);
}

/**
 * Decode Reed-Solomon encoded data with error correction
 * Uses the high-level ReedSolomonES.decode() API
 */
function rsDecode(encoded: Uint8Array, originalDataLength: number): Uint8Array {
  // The high-level API handles error correction
  const decoded = ReedSolomonES.decode(
    encoded,
    RS_PRESET,
    RS_REDUNDANCY_RATIO,
    true, // sloppy mode - more tolerant of some errors
  );

  // Return only the original data length (decoded may have padding)
  return new Uint8Array(decoded.subarray(0, originalDataLength));
}

/**
 * Onboarding data structure for type-safe binary encoding
 */
interface OnboardingPayload {
  homeserver: string;
  username: string;
  password: string;
  room: string;
}

/**
 * Check if data is an onboarding payload
 */
function isOnboardingPayload(data: unknown): data is OnboardingPayload {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as OnboardingPayload).homeserver === 'string' &&
    typeof (data as OnboardingPayload).username === 'string' &&
    typeof (data as OnboardingPayload).password === 'string' &&
    typeof (data as OnboardingPayload).room === 'string'
  );
}

// Magic byte to identify binary format
const BINARY_FORMAT_MAGIC = 0xb1;

/**
 * Serialize data to compact binary format
 * Format: [MAGIC:1][homeserver_len:1][homeserver][user_len:1][user][pass_len:1][pass][room_len:1][room]
 */
function serializePayload(data: unknown): Buffer {
  // Use compact binary format for onboarding data
  if (isOnboardingPayload(data)) {
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
 * Deserialize compact binary format to data
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

/**
 * Encode onboarding data to MFSK audio samples
 */
export function encodeMfsk(
  data: unknown,
  config: MfskConfig = DEFAULT_CONFIG,
): Float32Array {
  // Serialize to compact format
  const payload = serializePayload(data);
  const payloadLength = payload.length;

  // Apply Reed-Solomon encoding
  const encoded = rsEncode(new Uint8Array(payload));

  // Convert bytes to 4-bit symbols
  const dataSymbols = bytesToSymbols(encoded);

  // Build frame
  const frame: number[] = [];

  // Preamble: alternating tones for AGC and timing
  for (let i = 0; i < PREAMBLE_SYMBOLS; i++) {
    frame.push(i % 2 === 0 ? 0 : 8);
  }

  // Sync pattern
  frame.push(...SYNC_PATTERN);

  // Original payload length (before RS) as 2 symbols (1 byte, max 255)
  frame.push((payloadLength >> 4) & 0x0f);
  frame.push(payloadLength & 0x0f);

  // RS-encoded payload as symbols
  frame.push(...dataSymbols);

  // End marker
  frame.push(...END_PATTERN);

  // Modulate to audio
  return modulateMfsk(frame, config);
}

/**
 * Generate frequency for a given symbol (0-15)
 */
function symbolToFrequency(symbol: number, config: MfskConfig): number {
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
    // Ramp up
    return 0.5 * (1 - Math.cos((Math.PI * sample) / rampSamples));
  } else if (sample > totalSamples - rampSamples) {
    // Ramp down
    const remaining = totalSamples - sample;
    return 0.5 * (1 - Math.cos((Math.PI * remaining) / rampSamples));
  }
  return 1.0;
}

/**
 * Modulate symbols to audio samples
 */
function modulateMfsk(symbols: number[], config: MfskConfig): Float32Array {
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

    // Generate tone with smooth envelope
    for (let j = 0; j < samplesPerTone; j++) {
      // Raised cosine envelope for smooth on/off
      const envelope = raisedCosineEnvelope(j, samplesPerTone, 0.1);
      samples[symbolStart + j] = 0.8 * envelope * Math.sin(phase);
      phase += omega * dt;
    }

    // Guard interval is silence (samples already 0)
  }

  return samples;
}

/**
 * Decode MFSK audio samples to data
 */
export async function decodeMfsk(
  samples: Float32Array,
  config: MfskConfig = DEFAULT_CONFIG,
): Promise<unknown> {
  const symbols = demodulateMfsk(samples, config);
  const frame = parseMfskFrame(symbols);

  // Convert symbols back to bytes
  const encodedBytes = symbolsToBytes(frame.encodedPayload);

  // Decode RS
  let decoded: Uint8Array;
  try {
    decoded = rsDecode(encodedBytes, frame.dataLength);
  } catch {
    throw new Error('MFSK: Reed-Solomon decoding failed - too many errors');
  }

  // Deserialize
  return deserializePayload(Buffer.from(decoded));
}

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
  config: MfskConfig,
): { start: number; end: number } {
  const { sampleRate, baseFrequency, frequencySpacing, numTones } = config;
  const windowMs = 50;
  const windowSize = Math.round((sampleRate * windowMs) / 1000);
  const windowStep = Math.round(windowSize / 2);

  // Calculate total MFSK power for each window
  const powers: { power: number; start: number }[] = [];

  for (let i = 0; i < samples.length - windowSize; i += windowStep) {
    let totalPower = 0;
    // Sum power across all MFSK frequencies
    for (let tone = 0; tone < numTones; tone++) {
      const freq = baseFrequency + tone * frequencySpacing;
      totalPower += goertzelMagnitude(samples, i, windowSize, freq, sampleRate);
    }
    powers.push({ power: totalPower, start: i });
  }

  if (powers.length === 0) {
    return { start: 0, end: samples.length };
  }

  // Find threshold using percentiles
  const sorted = [...powers].sort((a, b) => a.power - b.power);
  const p10 = sorted[Math.floor(sorted.length * 0.1)].power;
  const p90 = sorted[Math.floor(sorted.length * 0.9)].power;
  const threshold = p10 + (p90 - p10) * 0.3;

  // Find first and last windows above threshold
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
  config: MfskConfig,
): number {
  const { sampleRate, symbolDuration, toneDuration } = config;
  const samplesPerSymbol = Math.round((sampleRate * symbolDuration) / 1000);
  const samplesPerTone = Math.round((sampleRate * toneDuration) / 1000);

  let bestOffset = start;
  let bestScore = -1;

  // Search through possible offsets
  const searchStep = Math.round(samplesPerSymbol / 4);
  const maxSearch = Math.min(
    end - start,
    samplesPerSymbol * (PREAMBLE_SYMBOLS + 10),
  );

  for (let offset = 0; offset < maxSearch; offset += searchStep) {
    const testStart = start + offset;

    // Score this offset by checking for preamble pattern (alternating 0 and 8)
    let score = 0;
    for (let i = 0; i < PREAMBLE_SYMBOLS; i++) {
      const expectedTone = i % 2 === 0 ? 0 : 8;
      const expectedFreq = symbolToFrequency(expectedTone, config);
      const power = goertzelMagnitude(
        samples,
        testStart + i * samplesPerSymbol,
        samplesPerTone,
        expectedFreq,
        sampleRate,
      );
      score += power;
    }

    if (score > bestScore) {
      bestScore = score;
      bestOffset = testStart;
    }
  }

  return bestOffset;
}

/**
 * Demodulate audio samples to symbols using Goertzel algorithm
 */
function demodulateMfsk(samples: Float32Array, config: MfskConfig): number[] {
  const { sampleRate, symbolDuration, toneDuration, numTones } = config;

  const samplesPerSymbol = Math.round((sampleRate * symbolDuration) / 1000);
  const samplesPerTone = Math.round((sampleRate * toneDuration) / 1000);

  // Find signal boundaries
  const { start, end } = findSignalBoundaries(samples, config);

  // Synchronize to preamble
  const syncOffset = findSyncOffset(samples, start, end, config);

  const symbols: number[] = [];
  let position = syncOffset;

  while (position + samplesPerTone <= end) {
    // Detect strongest frequency in this symbol window
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
 * Parse MFSK frame from symbols
 */
function parseMfskFrame(symbols: number[]): {
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
    throw new Error('MFSK: Sync pattern not found');
  }

  // Read length (2 symbols after sync = 1 byte = original payload length)
  const lenIndex = syncIndex + SYNC_PATTERN.length;
  if (lenIndex + 2 > symbols.length) {
    throw new Error('MFSK: Frame too short for length field');
  }
  const dataLength = (symbols[lenIndex] << 4) | symbols[lenIndex + 1];

  // Calculate expected RS-encoded length
  const encodedLength = getEncodedLength(dataLength);
  const encodedSymbols = encodedLength * 2; // 2 symbols per byte

  // Extract encoded payload
  const payloadStart = lenIndex + 2;
  if (payloadStart + encodedSymbols > symbols.length) {
    throw new Error('MFSK: Frame too short for payload');
  }

  const encodedPayload = symbols.slice(
    payloadStart,
    payloadStart + encodedSymbols,
  );

  return { dataLength, encodedPayload };
}

// Debug logging
let mfskDebugLog: string[] = [];

export function clearMfskDebugLog(): void {
  mfskDebugLog = [];
}

export function getMfskDebugLog(): string[] {
  return [...mfskDebugLog];
}

// Re-export for compatibility with existing code
export { encodeMfsk as encodeAfsk, decodeMfsk as decodeAfsk };
