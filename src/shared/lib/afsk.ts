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
const PREAMBLE_BYTE = 0x55; // Alternating bits for AGC, clock recovery
const SYNC_BYTE = 0xff; // Sync pattern
const POSTAMBLE_BYTE = 0x00; // End of frame marker

const PREAMBLE_COUNT = 32; // Number of preamble bytes
const POSTAMBLE_COUNT = 4; // Number of postamble bytes

/**
 * CRC-16-CCITT implementation for error detection
 * Polynomial: x^16 + x^12 + x^5 + 1 (0x1021)
 */
function crc16Ccitt(data: Buffer): number {
  let crc = 0xffff;

  for (let i = 0; i < data.length; i++) {
    crc ^= data[i] << 8;

    for (let j = 0; j < 8; j++) {
      if (crc & 0x8000) {
        crc = (crc << 1) ^ 0x1021;
      } else {
        crc = crc << 1;
      }
      crc &= 0xffff; // Keep to 16 bits
    }
  }

  return crc;
}

/**
 * Encode JSON data to AFSK audio samples (Float32Array)
 */
export function encodeAfsk(
  data: unknown,
  config: AfskConfig = DEFAULT_CONFIG,
): Float32Array {
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
  frame[offset++] = (length >> 8) & 0xff;
  frame[offset++] = length & 0xff;

  // Data
  dataBuffer.copy(frame, offset);
  offset += length;

  // CRC (big-endian)
  frame[offset++] = (crc >> 8) & 0xff;
  frame[offset++] = crc & 0xff;

  // Postamble
  for (let i = 0; i < POSTAMBLE_COUNT; i++) {
    frame[offset++] = POSTAMBLE_BYTE;
  }

  // Convert bytes to bits and modulate
  return modulateAfsk(frame, config);
}

/**
 * Modulate bits to AFSK audio samples using proper continuous-phase FSK.
 * Uses fractional bit timing for accurate sample generation.
 * Phase is maintained in radians and accumulated continuously across
 * frequency transitions to avoid discontinuities.
 */
function modulateAfsk(bytes: Buffer, config: AfskConfig): Float32Array {
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBitExact = sampleRate / baudRate; // Fractional (e.g., 13.333)
  const totalBits = bytes.length * 8;
  const totalSamples = Math.round(totalBits * samplesPerBitExact);

  const samples = new Float32Array(totalSamples);
  let phase = 0; // Phase in radians, accumulated continuously
  const dt = 1 / sampleRate; // Time step per sample

  let sampleIndex = 0;
  let bitPosition = 0; // Fractional bit boundary position

  for (let byteIndex = 0; byteIndex < bytes.length; byteIndex++) {
    const byte = bytes[byteIndex];

    // LSB first encoding
    for (let bitIndex = 0; bitIndex < 8; bitIndex++) {
      const bit = (byte >> bitIndex) & 1;
      const freq = bit ? markFreq : spaceFreq;
      const omega = 2 * Math.PI * freq; // Angular frequency

      // Calculate where this bit ends (fractional)
      const nextBitPosition = bitPosition + samplesPerBitExact;
      const endSample = Math.round(nextBitPosition);

      // Generate samples for this bit with continuous phase
      while (sampleIndex < endSample && sampleIndex < totalSamples) {
        samples[sampleIndex++] = 0.8 * Math.sin(phase);
        phase += omega * dt; // Accumulate phase
      }

      bitPosition = nextBitPosition;
    }
  }

  return samples;
}

/**
 * Decode AFSK audio samples to JSON data
 */
export async function decodeAfsk(
  samples: Float32Array,
  config: AfskConfig = DEFAULT_CONFIG,
): Promise<unknown> {
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

  let s0 = 0,
    s1 = 0,
    s2 = 0;

  for (let i = 0; i < length; i++) {
    s0 = samples[start + i] + coeff * s1 - s2;
    s2 = s1;
    s1 = s0;
  }

  // Return magnitude squared (avoid sqrt for efficiency)
  return s1 * s1 + s2 * s2 - coeff * s1 * s2;
}

/**
 * Find AFSK signal boundaries using frequency-specific detection.
 * Looks for the presence of mark (1200Hz) and space (2200Hz) frequencies
 * rather than just energy, making it robust against broadband noise.
 * Returns {start, end} sample indices.
 */
function findAfskSignalBoundaries(
  samples: Float32Array,
  sampleRate: number,
  markFreq: number,
  spaceFreq: number,
  windowMs: number = 15,
): { start: number; end: number } {
  const windowSize = Math.round((sampleRate * windowMs) / 1000);
  const windowStep = Math.round(windowSize / 4); // 75% overlap for finer resolution

  // Calculate AFSK power for each window
  const afskPowers: number[] = [];
  const windowStarts: number[] = [];

  for (let i = 0; i < samples.length - windowSize; i += windowStep) {
    const markPower = goertzelMagnitude(
      samples,
      i,
      windowSize,
      markFreq,
      sampleRate,
    );
    const spacePower = goertzelMagnitude(
      samples,
      i,
      windowSize,
      spaceFreq,
      sampleRate,
    );
    const afskPower = markPower + spacePower;

    afskPowers.push(afskPower);
    windowStarts.push(i);
  }

  if (afskPowers.length === 0) {
    return { start: 0, end: samples.length };
  }

  // Find max power and use percentage threshold
  const maxPower = Math.max(...afskPowers);

  // Sort to find percentiles for robust thresholding
  const sortedPowers = [...afskPowers].sort((a, b) => a - b);
  const p10 = sortedPowers[Math.floor(sortedPowers.length * 0.1)]; // 10th percentile (noise floor)
  const p50 = sortedPowers[Math.floor(sortedPowers.length * 0.5)]; // 50th percentile (median)
  const _p90 = sortedPowers[Math.floor(sortedPowers.length * 0.9)]; // 90th percentile (signal level)

  // Calculate signal-to-noise ratio indicator
  const snrRatio = maxPower / (p50 + 0.001);

  // For reliable detection, max should be significantly above median (SNR > 3)
  if (snrRatio < 3) {
    debugLog(
      `WARNING: Poor SNR (${snrRatio.toFixed(1)}x) - signal may be too weak or too much noise`,
    );
  }

  // Check if this looks like a pure signal (loopback) vs signal embedded in noise
  // For pure signal: p10 and p50 are similar (both are signal, just different windows)
  // For signal+noise: p10 is much lower than p50 (p10 is noise, p50 may include signal)
  const isPureSignal = p10 > p50 * 0.3;

  // Threshold: balance between catching signal and rejecting noise
  let threshold: number;
  if (isPureSignal) {
    // Pure signal (loopback test) - use very low threshold
    threshold = p10 * 0.5;
    debugLog(
      `Pure signal detected (p10/p50=${(p10 / p50).toFixed(2)}) - using low threshold`,
    );
  } else if (snrRatio > 10) {
    // Good SNR - use low threshold to catch full signal
    threshold = p50 * 1.5;
  } else if (snrRatio > 5) {
    // Medium SNR - moderate threshold
    threshold = p50 + (maxPower - p50) * 0.15;
  } else {
    // Poor SNR - higher threshold to reject noise
    threshold = p50 + (maxPower - p50) * 0.3;
  }

  // Ensure minimum threshold above absolute minimum (for noisy recordings)
  if (!isPureSignal) {
    threshold = Math.max(threshold, p10 * 2);
  }

  // Find first and last windows exceeding threshold
  let startIdx = -1;
  let endIdx = -1;

  for (let i = 0; i < afskPowers.length; i++) {
    if (afskPowers[i] > threshold) {
      if (startIdx === -1) startIdx = i;
      endIdx = i;
    }
  }

  if (startIdx === -1) {
    // No clear AFSK signal found - return full range
    debugLog(
      `WARNING: No clear AFSK signal detected (max: ${maxPower.toFixed(1)}, threshold: ${threshold.toFixed(1)}, SNR: ${snrRatio.toFixed(1)}x)`,
    );
    return { start: 0, end: samples.length };
  }

  // Also check that the detected region isn't too long (max ~3s for typical message)
  const maxSignalWindows = Math.round((3 * sampleRate) / windowStep);
  if (endIdx - startIdx > maxSignalWindows) {
    debugLog(
      `WARNING: Detected region too long (${endIdx - startIdx} windows) - likely noise, narrowing search`,
    );
    // Try to find a tighter region with higher threshold
    const higherThreshold = threshold * 2;
    let newStartIdx = -1;
    let newEndIdx = -1;
    for (let i = 0; i < afskPowers.length; i++) {
      if (afskPowers[i] > higherThreshold) {
        if (newStartIdx === -1) newStartIdx = i;
        newEndIdx = i;
      }
    }
    if (newStartIdx !== -1 && newEndIdx - newStartIdx < maxSignalWindows) {
      startIdx = newStartIdx;
      endIdx = newEndIdx;
      debugLog(
        `Narrowed to ${endIdx - startIdx} windows with threshold ${higherThreshold.toFixed(1)}`,
      );
    }
  }

  // Convert window indices to sample positions with margin
  const marginWindows = 3;
  const start = windowStarts[Math.max(0, startIdx - marginWindows)];
  const end = Math.min(
    samples.length,
    windowStarts[Math.min(afskPowers.length - 1, endIdx + marginWindows)] +
      windowSize,
  );

  debugLog(
    `AFSK detection: noise(p50)=${p50.toFixed(1)}, signal(max)=${maxPower.toFixed(1)}, threshold=${threshold.toFixed(1)}, SNR=${snrRatio.toFixed(1)}x`,
  );

  return { start, end };
}

/**
 * Decode bits from samples starting at a given offset with fractional timing.
 * Returns array of decoded bits.
 */
function decodeBitsFromOffset(
  samples: Float32Array,
  startOffset: number,
  numBits: number,
  samplesPerBitExact: number,
  windowSize: number,
  markFreq: number,
  spaceFreq: number,
  sampleRate: number,
): number[] {
  const bits: number[] = [];
  let bitPosition = 0;

  for (let i = 0; i < numBits; i++) {
    const start = startOffset + Math.round(bitPosition);
    if (start + windowSize > samples.length) break;

    const markPower = goertzelMagnitude(
      samples,
      start,
      windowSize,
      markFreq,
      sampleRate,
    );
    const spacePower = goertzelMagnitude(
      samples,
      start,
      windowSize,
      spaceFreq,
      sampleRate,
    );

    bits.push(markPower > spacePower ? 1 : 0);
    bitPosition += samplesPerBitExact;
  }

  return bits;
}

/**
 * Convert bits to bytes (LSB first encoding)
 */
function bitsToBytes(bits: number[]): Buffer {
  const numBytes = Math.floor(bits.length / 8);
  const bytes = Buffer.alloc(numBytes);

  for (let byteIndex = 0; byteIndex < numBytes; byteIndex++) {
    let byte = 0;
    for (let bitIndex = 0; bitIndex < 8; bitIndex++) {
      if (bits[byteIndex * 8 + bitIndex] === 1) {
        byte |= 1 << bitIndex;
      }
    }
    bytes[byteIndex] = byte;
  }

  return bytes;
}

/**
 * Count how many preamble bytes (0x55) appear in a sequence
 */
function countPreambleBytes(bytes: Buffer, maxCheck: number = 40): number {
  let count = 0;
  for (let i = 0; i < Math.min(bytes.length, maxCheck); i++) {
    if (bytes[i] === PREAMBLE_BYTE) count++;
  }
  return count;
}

/**
 * Demodulate AFSK audio samples to bytes.
 * Uses Goertzel algorithm for frequency detection and preamble-based synchronization.
 */
function demodulateAfsk(samples: Float32Array, config: AfskConfig): Buffer {
  clearAfskDebugLog();
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBitExact = sampleRate / baudRate; // Use exact fractional value
  const windowSize = Math.round(samplesPerBitExact);

  debugLog(
    `Sample rate: ${sampleRate}Hz, Baud: ${baudRate}, Samples/bit: ${samplesPerBitExact.toFixed(2)}`,
  );
  debugLog(`Mark: ${markFreq}Hz, Space: ${spaceFreq}Hz`);
  debugLog(`Total samples: ${samples.length}`);

  // Find max amplitude for signal quality check
  let maxAmp = 0;
  for (let i = 0; i < samples.length; i++) {
    const amp = Math.abs(samples[i]);
    if (amp > maxAmp) maxAmp = amp;
  }
  debugLog(
    `Max amplitude: ${maxAmp.toFixed(3)} (signal quality: ${maxAmp > 0.1 ? 'GOOD' : 'WEAK'})`,
  );

  // Normalize audio if signal is weak (amplify to use full range)
  // This helps with quiet recordings from distant speakers
  let normalizedSamples = samples;
  if (maxAmp > 0.01 && maxAmp < 0.5) {
    const gain = 0.8 / maxAmp;
    normalizedSamples = new Float32Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
      normalizedSamples[i] = samples[i] * gain;
    }
    debugLog(
      `Normalized audio: gain=${gain.toFixed(1)}x (${maxAmp.toFixed(3)} → 0.8)`,
    );
  }

  // Find signal boundaries using frequency-specific detection
  const { start: signalStart, end: signalEnd } = findAfskSignalBoundaries(
    normalizedSamples,
    sampleRate,
    markFreq,
    spaceFreq,
  );
  const signalLength = signalEnd - signalStart;

  debugLog(
    `Signal detected: samples ${signalStart} to ${signalEnd} (${(signalLength / sampleRate).toFixed(2)}s)`,
  );

  if (signalLength < windowSize * 100) {
    debugLog('WARNING: Signal too short, may not contain valid data');
  }

  // Preamble synchronization: search through the signal to find where the preamble starts
  // Speaker/mic artifacts may cause garbage at the start, so search deeper into the signal
  debugLog('Synchronizing to preamble...');

  let bestOffset = signalStart;
  let bestPreambleCount = 0;

  // Search range: up to ~100 bytes into the signal (to skip startup artifacts)
  // Also try different bit phases (sub-bit alignment)
  const maxSearchBytes = 100;
  const maxSearchSamples = Math.round(maxSearchBytes * 8 * samplesPerBitExact);
  const searchEnd = Math.min(signalStart + maxSearchSamples, signalEnd - 1000);

  // Step through byte boundaries (8 bits = ~107 samples)
  const byteStep = Math.round(8 * samplesPerBitExact);
  // Also try different phases within each byte
  const phaseStep = Math.round(samplesPerBitExact / 2);

  for (
    let byteOffset = 0;
    byteOffset < maxSearchSamples && signalStart + byteOffset < searchEnd;
    byteOffset += byteStep
  ) {
    // Try different bit phases at this byte boundary
    for (let phase = -windowSize; phase <= windowSize; phase += phaseStep) {
      const testStart = signalStart + byteOffset + phase;
      if (testStart < 0 || testStart >= searchEnd) continue;

      // Decode first ~50 bytes worth of bits
      const testBits = decodeBitsFromOffset(
        normalizedSamples,
        testStart,
        400,
        samplesPerBitExact,
        windowSize,
        markFreq,
        spaceFreq,
        sampleRate,
      );
      const testBytes = bitsToBytes(testBits);
      const preambleCount = countPreambleBytes(testBytes);

      if (preambleCount > bestPreambleCount) {
        bestPreambleCount = preambleCount;
        bestOffset = testStart;
      }
    }
  }

  debugLog(
    `Best sync offset: ${bestOffset - signalStart} samples (${bestPreambleCount} preamble bytes found)`,
  );

  // Now decode all bits from the synchronized position
  const maxBits = Math.floor((signalEnd - bestOffset) / samplesPerBitExact);
  const bits = decodeBitsFromOffset(
    normalizedSamples,
    bestOffset,
    maxBits,
    samplesPerBitExact,
    windowSize,
    markFreq,
    spaceFreq,
    sampleRate,
  );

  debugLog(`Bits decoded: ${bits.length}`);

  // Show first 64 bits for debugging
  if (bits.length >= 64) {
    const first64 = bits.slice(0, 64).join('');
    debugLog(`First 64 bits: ${first64}`);
    const firstBytes: string[] = [];
    for (let i = 0; i < 64; i += 8) {
      let byte = 0;
      for (let j = 0; j < 8; j++) {
        if (bits[i + j] === 1) byte |= 1 << j;
      }
      firstBytes.push(byte.toString(16).padStart(2, '0'));
    }
    debugLog(`First 8 bytes: ${firstBytes.join(' ')}`);
  }

  // Convert bits to bytes
  const bytes = bitsToBytes(bits);

  debugLog(`Bytes decoded: ${bytes.length}`);

  if (bytes.length > 0) {
    const preview = bytes
      .subarray(0, Math.min(16, bytes.length))
      .toString('hex');
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
  if (syncByte !== SYNC_BYTE && syncByte !== 0xfe && syncByte !== 0x7f) {
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
    throw new Error(
      `AFSK: CRC mismatch (received ${receivedCrc.toString(16)}, calculated ${calculatedCrc.toString(16)})`,
    );
  }

  return { data };
}
