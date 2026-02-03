/**
 * High-level audio codec interface for encoding/decoding Ogg Opus audio
 *
 * This module provides a simple API for converting PCM audio to/from Ogg Opus format,
 * handling sample rate conversion, encoding, and container muxing/demuxing internally.
 *
 * ## Opus Encoding Details
 *
 * - Target sample rate: 16kHz (OPUS_SAMPLE_RATE)
 * - Channels: Mono (OPUS_CHANNELS = 1)
 * - Frame size: 960 samples (60ms at 16kHz)
 * - Pre-skip: 312 samples (standard Opus encoder delay)
 *
 * @module audio-codec
 */

import { OggOpusMuxer, OggDemuxer } from './ogg.js';
import { EncoderFactory, DecoderFactory, OpusEncoder, OpusDecoder } from './opus.js';
import { resample } from './resample.js';
import type { Logger } from './wata-client/types.js';

// ============================================================================
// Constants
// ============================================================================

/** Opus encoder's internal sample rate in Hz */
export const OPUS_SAMPLE_RATE = 16000;

/** Number of audio channels (mono) */
export const OPUS_CHANNELS = 1;

/** Opus frame size in samples (60ms at 16kHz) */
export const OPUS_FRAME_SIZE = 960;

/** Pre-skip samples (Opus encoder delay) */
export const OPUS_PRE_SKIP = 312;

// ============================================================================
// Types
// ============================================================================

/**
 * Options for encoding PCM audio to Ogg Opus
 */
export interface EncodeOptions {
  /** Input sample rate in Hz (will resample to 16kHz if needed) */
  sampleRate: number;
  /** Number of channels (only mono supported) */
  channels?: 1;
  /** Optional logger for debugging */
  logger?: Logger;
}

/**
 * Options for decoding Ogg Opus to PCM
 */
export interface DecodeOptions {
  /** Optional logger for debugging */
  logger?: Logger;
}

/**
 * Result from decoding Ogg Opus audio
 */
export interface DecodeResult {
  /** Decoded PCM audio data (16-bit signed integers) */
  pcm: Int16Array;
  /** Sample rate (always 16000 Hz) */
  sampleRate: 16000;
  /** Duration in seconds */
  duration: number;
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Convert Int16Array PCM to Float32Array (normalized to [-1.0, 1.0])
 *
 * @param int16 PCM data as 16-bit signed integers
 * @returns PCM data as 32-bit floats
 */
function int16ToFloat32(int16: Int16Array): Float32Array {
  const float32 = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) {
    float32[i] = int16[i] / 32768.0;
  }
  return float32;
}

/**
 * Convert Float32Array PCM to Int16Array (from [-1.0, 1.0] range)
 *
 * @param float32 PCM data as 32-bit floats
 * @returns PCM data as 16-bit signed integers
 */
function float32ToInt16(float32: Float32Array): Int16Array {
  const int16 = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    // Clamp to [-1.0, 1.0] before conversion
    const clamped = Math.max(-1.0, Math.min(1.0, float32[i]));
    int16[i] = Math.round(clamped * 32767.0);
  }
  return int16;
}

// ============================================================================
// Public API
// ============================================================================

/**
 * Encode PCM audio to Ogg Opus format
 *
 * Accepts PCM data at any sample rate and automatically resamples to 16kHz
 * for Opus encoding. Input can be either Int16Array (16-bit signed) or
 * Float32Array (32-bit float in [-1.0, 1.0] range).
 *
 * The encoding process:
 * 1. Convert Int16Array to Float32Array if needed
 * 2. Resample to 16kHz if input sample rate differs
 * 3. Split into 960-sample frames (60ms at 16kHz)
 * 4. Encode each frame with Opus encoder
 * 5. Mux packets into Ogg container with proper headers
 *
 * @param pcm - Input PCM audio data (Int16Array or Float32Array)
 * @param options - Encoding options including sample rate
 * @returns Ogg Opus encoded audio as a Buffer
 *
 * @example
 * ```ts
 * // Encode from 44.1kHz recording
 * const oggOpus = encodeOggOpus(pcmData, { sampleRate: 44100 });
 * ```
 */
export function encodeOggOpus(
  pcm: Int16Array | Float32Array,
  options: EncodeOptions,
  mkEncoder: EncoderFactory
): Buffer {
  const { sampleRate, channels = 1, logger } = options;

  // Validate channels (only mono supported)
  if (channels !== 1) {
    throw new Error(`Only mono audio is supported, got channels=${channels}`);
  }

  logger?.log(
    `encodeOggOpus: starting, ${pcm.length} samples at ${sampleRate}Hz`
  );

  // Step 1: Convert Int16Array to Float32Array if needed
  let floatPcm: Float32Array;
  if (pcm instanceof Int16Array) {
    logger?.log('encodeOggOpus: converting Int16Array to Float32Array');
    floatPcm = int16ToFloat32(pcm);
  } else {
    floatPcm = pcm;
  }

  // Step 2: Resample to 16kHz if needed
  let resampledPcm: Float32Array;
  if (sampleRate !== OPUS_SAMPLE_RATE) {
    logger?.log(
      `encodeOggOpus: resampling ${sampleRate}Hz → ${OPUS_SAMPLE_RATE}Hz`
    );
    resampledPcm = resample(floatPcm, sampleRate, OPUS_SAMPLE_RATE, logger);
  } else {
    resampledPcm = floatPcm;
  }

  logger?.log(`encodeOggOpus: ${resampledPcm.length} samples at ${OPUS_SAMPLE_RATE}Hz`);

  // Step 3: Create Opus encoder
  const encoder = new OpusEncoder({
    sampleRate: OPUS_SAMPLE_RATE,
    channels,
    application: 'voip',
    logger
  }, mkEncoder );

  // Step 4: Create Ogg muxer
  // Note: Don't call writeHeaders() here - muxPackets() handles that
  const muxer = new OggOpusMuxer(OPUS_SAMPLE_RATE, channels, OPUS_PRE_SKIP);

  // Step 5: Encode frames and mux
  // Note: @evan/wasm opus treats Float32Array differently, so we must convert to Int16Array
  const packets: Array<{ data: Uint8Array; samples: number }> = [];
  let offset = 0;

  while (offset < resampledPcm.length) {
    const remaining = resampledPcm.length - offset;

    // @evan/wasm opus requires exactly OPUS_FRAME_SIZE samples per frame
    // For partial frames, pad with zeros
    const frameFloat = remaining >= OPUS_FRAME_SIZE
      ? resampledPcm.subarray(offset, offset + OPUS_FRAME_SIZE)
      : (() => {
          const padded = new Float32Array(OPUS_FRAME_SIZE);
          padded.set(resampledPcm.subarray(offset), 0);
          logger?.log(`encodeOggOpus: padded partial frame (${remaining} → ${OPUS_FRAME_SIZE} samples)`);
          return padded;
        })();

    // Convert Float32Array to Int16Array for @evan/wasm opus
    // Float32 range: [-1.0, 1.0] -> Int16 range: [-32768, 32767]
    const frame = float32ToInt16(frameFloat);

    // Encode frame
    const packet = encoder.encode(frame);
    packets.push({ data: packet, samples: Math.min(remaining, OPUS_FRAME_SIZE) });

    offset += OPUS_FRAME_SIZE;
  }

  // If we had no audio data, create one silent frame
  if (packets.length === 0) {
    logger?.log('encodeOggOpus: no audio data, creating silent frame');
    const silentFrame = new Int16Array(OPUS_FRAME_SIZE); // zeros
    const packet = encoder.encode(silentFrame);
    packets.push({ data: packet, samples: 0 });
  }

  logger?.log(`encodeOggOpus: encoded ${packets.length} Opus packets`);

  // Step 6: Finalize muxing
  const result = muxer.muxPackets(packets);

  // Cleanup
  encoder.destroy();

  logger?.log(`encodeOggOpus: complete, ${result.length} bytes`);

  // Return as Buffer (from Uint8Array)
  return Buffer.from(result);
}

/**
 * Decode Ogg Opus audio to PCM
 *
 * Decodes Ogg Opus audio and returns PCM data at 16kHz mono.
 * All output is normalized to 16kHz regardless of the original
 * input sample rate used during encoding.
 *
 * The decoding process:
 * 1. Parse Ogg container and extract Opus packets
 * 2. Skip OpusHead and OpusTags header packets
 * 3. Decode each Opus packet with Opus decoder
 * 4. Concatenate decoded frames into continuous PCM
 * 5. Calculate duration from sample count
 *
 * @param ogg - Ogg Opus encoded audio as a Buffer
 * @param options - Decoding options
 * @returns Decoded PCM audio with metadata
 *
 * @example
 * ```ts
 * const result = decodeOggOpus(oggBuffer);
 * console.log(`Duration: ${result.duration}s`);
 * // Play result.pcm at result.sampleRate
 * ```
 */
export function decodeOggOpus(
  ogg: Buffer,
  mkDecoder: DecoderFactory,
  options?: DecodeOptions
): DecodeResult {
  const { logger } = options ?? {};

  logger?.log(`decodeOggOpus: starting, ${ogg.length} bytes`);

  // Step 1: Demux Ogg container to get Opus packets
  const demuxer = new OggDemuxer(logger);
  const opusPackets = demuxer.demux(ogg);

  if (opusPackets.length === 0) {
    throw new Error('No Opus packets found in Ogg data');
  }

  logger?.log(`decodeOggOpus: extracted ${opusPackets.length} Opus packets`);

  // Step 2: Create Opus decoder
  const decoder = new OpusDecoder({
    sampleRate: OPUS_SAMPLE_RATE,
    channels: OPUS_CHANNELS,
    logger,
  }, mkDecoder);

  // Step 3: Decode all packets
  const frames: Int16Array[] = [];
  let totalSamples = 0;

  for (let i = 0; i < opusPackets.length; i++) {
    const packet = opusPackets[i];
    const frame = decoder.decode(packet);
    frames.push(frame);
    totalSamples += frame.length;
  }

  logger?.log(`decodeOggOpus: decoded ${totalSamples} samples`);

  // Step 4: Concatenate all frames
  const pcm = new Int16Array(totalSamples);
  let offset = 0;
  for (const frame of frames) {
    pcm.set(frame, offset);
    offset += frame.length;
  }

  // Step 5: Calculate duration
  const duration = totalSamples / OPUS_SAMPLE_RATE;

  // Cleanup
  decoder.destroy();

  logger?.log(`decodeOggOpus: complete, ${duration.toFixed(2)}s`);

  return {
    pcm,
    sampleRate: OPUS_SAMPLE_RATE,
    duration,
  };
}
