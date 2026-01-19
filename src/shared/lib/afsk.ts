/**
 * AFSK Modem Codec (Shared)
 *
 * Pure JS implementation of Bell 202 AFSK (Audio Frequency Shift Keying) modem.
 * Compatible with old-school 1200 baud modems.
 *
 * Standard: Bell 202
 * - Mark (1): 1200 Hz
 * - Space (0): 2200 Hz
 * - Baud rate: 1200 baud
 * - Sample rate: 16000 Hz (matching our audio pipeline)
 *
 * No external dependencies - pure JS/TS math.
 */

import { Buffer } from 'buffer';

export interface AfskConfig {
  sampleRate: number;
  baudRate: number;
  markFreq: number;
  spaceFreq: number;
}

export const DEFAULT_CONFIG: AfskConfig = {
  sampleRate: 16000,
  baudRate: 1200,
  markFreq: 1200,
  spaceFreq: 2200,
};

// Frame markers
const PREAMBLE_BYTE = 0x55;  // Alternating bits for AGC, clock recovery
const SYNC_BYTE = 0xFF;       // Sync pattern
const POSTAMBLE_BYTE = 0x00; // End of frame marker

const PREAMBLE_COUNT = 32;    // Number of preamble bytes
const POSTAMBLE_COUNT = 4;    // Number of postamble bytes

/**
 * CRC-16-CCITT implementation for error detection
 * Polynomial: x^16 + x^12 + x^5 + 1 (0x1021)
 */
function crc16Ccitt(data: Buffer): number {
  let crc = 0xFFFF;

  for (let i = 0; i < data.length; i++) {
    crc ^= (data[i] << 8);

    for (let j = 0; j < 8; j++) {
      if (crc & 0x8000) {
        crc = (crc << 1) ^ 0x1021;
      } else {
        crc = crc << 1;
      }
      crc &= 0xFFFF; // Keep to 16 bits
    }
  }

  return crc;
}

/**
 * Encode JSON data to AFSK audio samples (Float32Array)
 */
export function encodeAfsk(data: unknown, config: AfskConfig = DEFAULT_CONFIG): Float32Array {
  // Serialize to JSON and convert to bytes
  const jsonStr = JSON.stringify(data);
  const dataBuffer = Buffer.from(jsonStr, 'utf-8');

  // Build frame: [PREAMBLE][SYNC][LENGTH][DATA][CRC][POSTAMBLE]
  const length = dataBuffer.length;
  const crc = crc16Ccitt(dataBuffer);

  const frameSize = PREAMBLE_COUNT + 1 + 2 + length + 2 + POSTAMBLE_COUNT;
  const frame = Buffer.alloc(frameSize);

  let offset = 0;

  // Preamble
  for (let i = 0; i < PREAMBLE_COUNT; i++) {
    frame[offset++] = PREAMBLE_BYTE;
  }

  // Sync
  frame[offset++] = SYNC_BYTE;

  // Length (big-endian)
  frame[offset++] = (length >> 8) & 0xFF;
  frame[offset++] = length & 0xFF;

  // Data
  dataBuffer.copy(frame, offset);
  offset += length;

  // CRC (big-endian)
  frame[offset++] = (crc >> 8) & 0xFF;
  frame[offset++] = crc & 0xFF;

  // Postamble
  for (let i = 0; i < POSTAMBLE_COUNT; i++) {
    frame[offset++] = POSTAMBLE_BYTE;
  }

  // Convert bytes to bits and modulate
  return modulateAfsk(frame, config);
}

/**
 * Modulate bits to AFSK audio samples using proper continuous-phase FSK.
 * Phase is maintained in radians and accumulated continuously across
 * frequency transitions to avoid discontinuities.
 */
function modulateAfsk(bytes: Buffer, config: AfskConfig): Float32Array {
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBit = Math.round(sampleRate / baudRate);
  const totalSamples = bytes.length * 8 * samplesPerBit;

  const samples = new Float32Array(totalSamples);
  let phase = 0; // Phase in radians, accumulated continuously
  const dt = 1 / sampleRate; // Time step per sample

  let sampleIndex = 0;
  for (let byteIndex = 0; byteIndex < bytes.length; byteIndex++) {
    const byte = bytes[byteIndex];

    // LSB first encoding
    for (let bitIndex = 0; bitIndex < 8; bitIndex++) {
      const bit = (byte >> bitIndex) & 1;
      const freq = bit ? markFreq : spaceFreq;
      const omega = 2 * Math.PI * freq; // Angular frequency

      // Generate samples for this bit with continuous phase
      for (let i = 0; i < samplesPerBit; i++) {
        samples[sampleIndex++] = 0.8 * Math.sin(phase);
        phase += omega * dt; // Accumulate phase
      }
    }
  }

  // Normalize phase to prevent floating point drift over long messages
  // (Not strictly necessary but good practice)
  // phase = phase % (2 * Math.PI);

  return samples;
}

/**
 * Decode AFSK audio samples to JSON data
 */
export async function decodeAfsk(samples: Float32Array, config: AfskConfig = DEFAULT_CONFIG): Promise<unknown> {
  const bytes = demodulateAfsk(samples, config);
  const frame = parseAfskFrame(bytes);

  // Parse data as JSON
  const jsonStr = Buffer.from(frame.data).toString('utf-8');
  return JSON.parse(jsonStr);
}

/**
 * Debug logging for AFSK demodulation
 */
let afskDebugLog: string[] = [];

export function clearAfskDebugLog(): void {
  afskDebugLog = [];
}

export function getAfskDebugLog(): string[] {
  return [...afskDebugLog];
}

function debugLog(msg: string): void {
  afskDebugLog.push(msg);
}

/**
 * Goertzel algorithm for detecting the magnitude of a specific frequency.
 * More efficient than FFT when we only need one or two frequencies.
 */
function goertzelMagnitude(samples: Float32Array, start: number, length: number, targetFreq: number, sampleRate: number): number {
  const k = Math.round((length * targetFreq) / sampleRate);
  const omega = (2 * Math.PI * k) / length;
  const coeff = 2 * Math.cos(omega);

  let s0 = 0, s1 = 0, s2 = 0;

  for (let i = 0; i < length; i++) {
    s0 = samples[start + i] + coeff * s1 - s2;
    s2 = s1;
    s1 = s0;
  }

  // Return magnitude squared (avoid sqrt for efficiency)
  return s1 * s1 + s2 * s2 - coeff * s1 * s2;
}

/**
 * Find the start of the AFSK signal using energy detection.
 * Returns the sample index where the signal starts.
 */
function findSignalStart(samples: Float32Array, sampleRate: number, windowMs: number = 10): number {
  const windowSize = Math.round(sampleRate * windowMs / 1000);

  // Calculate RMS energy for each window
  const energies: number[] = [];
  for (let i = 0; i < samples.length - windowSize; i += windowSize) {
    let sum = 0;
    for (let j = 0; j < windowSize; j++) {
      sum += samples[i + j] * samples[i + j];
    }
    energies.push(Math.sqrt(sum / windowSize));
  }

  // Find max energy and set threshold
  const maxEnergy = Math.max(...energies);
  const threshold = maxEnergy * 0.2; // 20% of max as threshold

  // Find first window exceeding threshold
  for (let i = 0; i < energies.length; i++) {
    if (energies[i] > threshold) {
      // Return sample index, backing up a bit for safety
      return Math.max(0, (i - 2) * windowSize);
    }
  }

  return 0; // No signal found, start from beginning
}

/**
 * Find the end of the AFSK signal using energy detection.
 * Returns the sample index where the signal ends.
 */
function findSignalEnd(samples: Float32Array, sampleRate: number, windowMs: number = 10): number {
  const windowSize = Math.round(sampleRate * windowMs / 1000);

  // Calculate RMS energy for each window
  const energies: number[] = [];
  for (let i = 0; i < samples.length - windowSize; i += windowSize) {
    let sum = 0;
    for (let j = 0; j < windowSize; j++) {
      sum += samples[i + j] * samples[i + j];
    }
    energies.push(Math.sqrt(sum / windowSize));
  }

  // Find max energy and set threshold
  const maxEnergy = Math.max(...energies);
  const threshold = maxEnergy * 0.2;

  // Find last window exceeding threshold
  for (let i = energies.length - 1; i >= 0; i--) {
    if (energies[i] > threshold) {
      // Return sample index, adding a bit for safety
      return Math.min(samples.length, (i + 3) * windowSize);
    }
  }

  return samples.length;
}

/**
 * Demodulate AFSK audio samples to bytes.
 * Uses Goertzel algorithm for robust frequency detection per bit window.
 */
function demodulateAfsk(samples: Float32Array, config: AfskConfig): Buffer {
  clearAfskDebugLog();
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBit = Math.round(sampleRate / baudRate);

  debugLog(`Sample rate: ${sampleRate}Hz, Baud: ${baudRate}, Samples/bit: ${samplesPerBit}`);
  debugLog(`Mark: ${markFreq}Hz, Space: ${spaceFreq}Hz`);
  debugLog(`Total samples: ${samples.length}`);

  // Find max amplitude for signal quality check
  let maxAmp = 0;
  for (let i = 0; i < samples.length; i++) {
    const amp = Math.abs(samples[i]);
    if (amp > maxAmp) maxAmp = amp;
  }
  debugLog(`Max amplitude: ${maxAmp.toFixed(3)} (signal quality: ${maxAmp > 0.1 ? 'GOOD' : 'WEAK'})`);

  // Find signal boundaries using energy detection
  const signalStart = findSignalStart(samples, sampleRate);
  const signalEnd = findSignalEnd(samples, sampleRate);
  const signalLength = signalEnd - signalStart;

  debugLog(`Signal detected: samples ${signalStart} to ${signalEnd} (${(signalLength / sampleRate).toFixed(2)}s)`);

  if (signalLength < samplesPerBit * 100) {
    debugLog('WARNING: Signal too short, may not contain valid data');
  }

  // Decode bits using Goertzel algorithm for frequency detection
  const bits: number[] = [];
  const numBits = Math.floor(signalLength / samplesPerBit);

  debugLog(`Expected bits from signal: ${numBits}`);

  for (let bitIndex = 0; bitIndex < numBits; bitIndex++) {
    const start = signalStart + bitIndex * samplesPerBit;

    // Use Goertzel to measure power at mark and space frequencies
    const markPower = goertzelMagnitude(samples, start, samplesPerBit, markFreq, sampleRate);
    const spacePower = goertzelMagnitude(samples, start, samplesPerBit, spaceFreq, sampleRate);

    // Compare powers to determine bit value
    // Mark (1200Hz) = 1, Space (2200Hz) = 0
    const bit = markPower > spacePower ? 1 : 0;
    bits.push(bit);
  }

  debugLog(`Bits decoded: ${bits.length}`);

  // Show first 64 bits for debugging
  if (bits.length >= 64) {
    const first64 = bits.slice(0, 64).join('');
    debugLog(`First 64 bits: ${first64}`);
    // Also show as bytes
    const firstBytes: string[] = [];
    for (let i = 0; i < 64; i += 8) {
      let byte = 0;
      for (let j = 0; j < 8; j++) {
        if (bits[i + j] === 1) byte |= (1 << j);
      }
      firstBytes.push(byte.toString(16).padStart(2, '0'));
    }
    debugLog(`First 8 bytes: ${firstBytes.join(' ')}`);
  }

  // Convert bits to bytes (LSB first)
  const numBytes = Math.floor(bits.length / 8);
  const bytes = Buffer.alloc(numBytes);

  for (let byteIndex = 0; byteIndex < numBytes; byteIndex++) {
    let byte = 0;
    for (let bitIndex = 0; bitIndex < 8; bitIndex++) {
      if (bits[byteIndex * 8 + bitIndex] === 1) {
        byte |= (1 << bitIndex);
      }
    }
    bytes[byteIndex] = byte;
  }

  debugLog(`Bytes decoded: ${numBytes}`);

  // Show first few bytes for debugging
  if (numBytes > 0) {
    const preview = bytes.subarray(0, Math.min(16, numBytes)).toString('hex');
    debugLog(`First bytes (hex): ${preview}`);
  }

  return bytes;
}

/**
 * Parse AFSK frame and extract data with CRC validation
 */
function parseAfskFrame(bytes: Buffer): { data: Buffer } {
  // Find preamble pattern (0x55 repeated) with some tolerance for bit errors
  // 0x55 = 01010101 in binary, so we need alternating bits
  let startIndex = -1;
  const MIN_PREAMBLE_MATCH = 16; // Require at least 16 matching bytes

  for (let i = 0; i <= bytes.length - MIN_PREAMBLE_MATCH - 4; i++) {
    let matchCount = 0;
    for (let j = 0; j < PREAMBLE_COUNT && i + j < bytes.length; j++) {
      if (bytes[i + j] === PREAMBLE_BYTE) {
        matchCount++;
      }
    }
    if (matchCount >= MIN_PREAMBLE_MATCH) {
      startIndex = i + PREAMBLE_COUNT;
      break;
    }
  }

  if (startIndex === -1 || startIndex + 4 > bytes.length) {
    throw new Error('AFSK: Preamble not found');
  }

  // Check sync byte (allow 0xFF or close alternatives)
  const syncByte = bytes[startIndex];
  if (syncByte !== SYNC_BYTE && syncByte !== 0xFE && syncByte !== 0x7F) {
    throw new Error(`AFSK: Sync byte mismatch (got ${syncByte.toString(16)})`);
  }

  // Read length
  if (startIndex + 2 >= bytes.length) {
    throw new Error('AFSK: Frame too short for length');
  }
  const dataLength = (bytes[startIndex + 1] << 8) | bytes[startIndex + 2];

  // Sanity check length
  if (dataLength > 1000) {
    throw new Error(`AFSK: Invalid data length ${dataLength}`);
  }

  // Read data
  const dataStart = startIndex + 3;
  if (dataStart + dataLength + 2 > bytes.length) {
    throw new Error('AFSK: Frame too short for data');
  }
  const data = bytes.subarray(dataStart, dataStart + dataLength);

  // Read CRC
  const crcStart = dataStart + dataLength;
  const receivedCrc = (bytes[crcStart] << 8) | bytes[crcStart + 1];

  // Validate CRC
  const calculatedCrc = crc16Ccitt(data);
  if (receivedCrc !== calculatedCrc) {
    throw new Error(`AFSK: CRC mismatch (received ${receivedCrc.toString(16)}, calculated ${calculatedCrc.toString(16)})`);
  }

  return { data };
}
