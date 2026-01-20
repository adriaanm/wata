/* eslint-disable */
// Prototype AFSK service - lint checks disabled

/**
 * AFSK Modem Service
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

/**
 * Frame structure for reliable data transmission
 * [PREAMBLE][SYNC][LENGTH_H][LENGTH_L][DATA...][CRC_H][CRC_L][POSTAMBLE]
 */
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
 * Encode JSON data to AFSK audio samples (Float32Array for Web Audio API)
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
 * Modulate bits to AFSK audio samples
 */
function modulateAfsk(bytes: Buffer, config: AfskConfig): Float32Array {
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBit = Math.round(sampleRate / baudRate);
  const totalSamples = bytes.length * 8 * samplesPerBit;

  const samples = new Float32Array(totalSamples);
  let phase = 0;

  for (let byteIndex = 0; byteIndex < bytes.length; byteIndex++) {
    const byte = bytes[byteIndex];

    // LSB first encoding
    for (let bitIndex = 0; bitIndex < 8; bitIndex++) {
      const bit = (byte >> bitIndex) & 1;
      const freq = bit ? markFreq : spaceFreq;

      // Generate samples for this bit
      const startSample = (byteIndex * 8 + bitIndex) * samplesPerBit;

      for (let i = 0; i < samplesPerBit; i++) {
        const t = i / sampleRate;
        // Continuous phase sine wave
        samples[startSample + i] =
          0.5 * Math.sin(2 * Math.PI * freq * (t + phase));
      }

      // Update phase for continuity
      phase += samplesPerBit / sampleRate;
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
 * Demodulate AFSK audio samples to bytes using zero-crossing detection
 * This is a simple but robust method for AFSK
 */
function demodulateAfsk(samples: Float32Array, config: AfskConfig): Buffer {
  const { sampleRate, baudRate, markFreq, spaceFreq } = config;
  const samplesPerBit = Math.round(sampleRate / baudRate);

  // Zero-crossing detector
  const zeroCrossings: number[] = [];
  let previousSign = Math.sign(samples[0]);

  for (let i = 1; i < samples.length; i++) {
    const currentSign = Math.sign(samples[i]);
    if (currentSign !== previousSign && currentSign !== 0) {
      zeroCrossings.push(i);
      previousSign = currentSign;
    }
  }

  // Estimate frequency from zero-crossing rate
  // Frequency ≈ (zeroCrossings / 2) / sampleRate
  const windowSize = samplesPerBit;
  const bits: number[] = [];

  for (
    let bitIndex = 0;
    bitIndex < Math.floor(samples.length / samplesPerBit);
    bitIndex++
  ) {
    const startSample = bitIndex * samplesPerBit;
    const endSample = startSample + samplesPerBit;

    // Count zero-crossings in this bit window
    const crossingsInWindow = zeroCrossings.filter(
      zc => zc >= startSample && zc < endSample,
    ).length;

    // Estimate frequency
    const estimatedFreq = ((crossingsInWindow / 2) * sampleRate) / windowSize;

    // Determine bit based on frequency
    // Midpoint between mark and space frequencies
    const threshold = (markFreq + spaceFreq) / 2;
    const bit = estimatedFreq > threshold ? 0 : 1;
    bits.push(bit);
  }

  // Convert bits to bytes
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
 * Parse AFSK frame and extract data with CRC validation
 */
function parseAfskFrame(bytes: Buffer): { data: Buffer } {
  // Find preamble pattern (0x55 0x55 0x55 0x55)
  let startIndex = -1;

  for (let i = 0; i <= bytes.length - PREAMBLE_COUNT - 4; i++) {
    let match = true;
    for (let j = 0; j < PREAMBLE_COUNT && i + j < bytes.length; j++) {
      if (bytes[i + j] !== PREAMBLE_BYTE) {
        match = false;
        break;
      }
    }
    if (match) {
      startIndex = i + PREAMBLE_COUNT;
      break;
    }
  }

  if (startIndex === -1 || startIndex + 4 > bytes.length) {
    throw new Error('AFSK: Preamble not found');
  }

  // Check sync byte
  if (bytes[startIndex] !== SYNC_BYTE) {
    throw new Error('AFSK: Sync byte mismatch');
  }

  // Read length
  if (startIndex + 2 >= bytes.length) {
    throw new Error('AFSK: Frame too short for length');
  }
  const dataLength = (bytes[startIndex + 1] << 8) | bytes[startIndex + 2];

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

/**
 * Create an AudioBuffer from AFSK samples for playback
 */
export function afskSamplesToAudioBuffer(
  samples: Float32Array,
  sampleRate: number,
): AudioBuffer {
  const audioCtx = new AudioContext({ sampleRate });
  const audioBuffer = audioCtx.createBuffer(1, samples.length, sampleRate);
  const channelData = audioBuffer.getChannelData(0);
  channelData.set(samples);
  return audioBuffer;
}

/**
 * Convert AudioBuffer to Float32Array
 */
export function audioBufferToSamples(audioBuffer: AudioBuffer): Float32Array {
  return audioBuffer.getChannelData(0);
}
