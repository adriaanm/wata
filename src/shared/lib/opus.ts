/**
 * Opus encoder/decoder wrapper around @evan/wasm opus module
 *
 * Provides a cleaner API for encoding and decoding Opus audio packets.
 * Opus is a low-latency audio codec ideal for voice communication.
 *
 * @module opus
 */

import type { Logger } from './wata-client/types.js';

// ============================================================================
// Types
// ============================================================================

/**
 * Opus encoder options
 */
export interface OpusEncoderOptions {
  /** Sample rate in Hz (8000, 12000, 16000, 24000, or 48000) */
  sampleRate: 8000 | 12000 | 16000 | 24000 | 48000;
  /** Number of channels (1 = mono, 2 = stereo). Default: 1 */
  channels?: 1 | 2;
  /** Encoding application type. Default: 'voip' */
  application?: 'voip' | 'audio' | 'restricted_lowdelay';
  /** Optional logger for debugging */
  logger?: Logger;
}

/**
 * Opus decoder options
 */
export interface OpusDecoderOptions {
  /** Sample rate in Hz (8000, 12000, 16000, 24000, or 48000) */
  sampleRate: 8000 | 12000 | 16000 | 24000 | 48000;
  /** Number of channels (1 = mono, 2 = stereo). Default: 1 */
  channels?: 1 | 2;
  /** Optional logger for debugging */
  logger?: Logger;
}

// ============================================================================
// Abstract Encoder/Decoder Types
// ============================================================================

/**
 * Abstract interface for audio encoders
 *
 * Defines the contract for platform-specific encoder implementations.
 */
export interface Encoder {
  /**
   * Encode PCM audio data
   *
   * @param pcm - PCM audio data to encode
   * @returns Encoded audio packet
   */
  encode(pcm: Int16Array | Float32Array): Uint8Array;

  /**
   * Reset encoder state
   */
  reset(): void;
}

/**
 * Abstract interface for audio decoders
 *
 * Defines the contract for platform-specific decoder implementations.
 */
export interface Decoder {
  /**
   * Decode audio packet to PCM data
   *
   * @param packet - Encoded audio packet to decode
   * @returns Decoded PCM audio data as Uint8Array
   */
  decode(packet: Uint8Array): Uint8Array;

  /**
   * Reset decoder state
   */
  reset(): void;
}

// ============================================================================
// Factory Functions
// ============================================================================

/**
 * Factory function type for creating Encoder instances
 *
 * Takes individual arguments instead of an options object.
 */
export type EncoderFactory = (
  sampleRate: 8000 | 12000 | 16000 | 24000 | 48000,
  channels: 1 | 2,
  application: 'voip' | 'audio' | 'restricted_lowdelay'
) => Encoder;

/**
 * Factory function type for creating Decoder instances
 *
 * Takes individual arguments instead of an options object.
 */
export type DecoderFactory = (
  sampleRate: 8000 | 12000 | 16000 | 24000 | 48000,
  channels: 1 | 2
) => Decoder;

// ============================================================================
// OpusEncoder
// ============================================================================

/**
 * Wraps the @evan/wasm opus Encoder with a cleaner API
 *
 * Encodes PCM audio data to Opus format.
 *
 * @example
 * ```ts
 * const encoder = new OpusEncoder({ sampleRate: 48000 });
 * const opusPacket = encoder.encode(pcmData);
 * ```
 */
export class OpusEncoder {
  private encoder: Encoder;
  private readonly logger?: Logger;

  /**
   * Create a new Opus encoder
   *
   * @param options - Encoder configuration options
   * @param mkEncoder - Factory function for creating encoder instances
   */
  constructor(options: OpusEncoderOptions, mkEncoder: EncoderFactory) {
    this.logger = options.logger;

    const { sampleRate, channels = 1, application = 'voip' } = options;

    this.logger?.log(`Creating Opus encoder: ${sampleRate}Hz, ${channels}ch, ${application}`);

    this.encoder = mkEncoder(sampleRate, channels, application);

    this.logger?.log('Opus encoder created successfully');
  }

  /**
   * Encode PCM audio data to Opus
   *
   * Accepts either Int16Array (16-bit signed integers) or Float32Array
   * (32-bit floating point in range [-1.0, 1.0]).
   *
   * @param pcm - PCM audio data to encode
   * @returns Opus-encoded packet
   */
  encode(pcm: Int16Array | Float32Array): Uint8Array {
    this.logger?.log(`Encoding ${pcm.length} samples to Opus`);

    try {
      const result = this.encoder.encode(pcm);
      this.logger?.log(`Encoded to ${result.length} bytes`);
      return result;
    } catch (error) {
      this.logger?.error(`Encoding failed: ${error}`);
      throw error;
    }
  }

  /**
   * Destroy the encoder and free resources
   *
   * Note: @evan/wasm opus uses finalization for cleanup, but calling reset()
   * can help free encoder state when no longer needed.
   */
  destroy(): void {
    this.logger?.log('Destroying Opus encoder');
    // @evan/wasm opus Encoder doesn't have an explicit destroy method
    // but reset() can be used to clear encoder state
    try {
      this.encoder.reset();
    } catch (error) {
      // Ignore errors during cleanup
      this.logger?.warn(`Encoder reset failed during destroy: ${error}`);
    }
  }
}

// ============================================================================
// OpusDecoder
// ============================================================================

/**
 * Wraps the @evan/wasm opus Decoder with a cleaner API
 *
 * Decodes Opus packets to PCM audio data.
 *
 * @example
 * ```ts
 * const decoder = new OpusDecoder({ sampleRate: 48000 });
 * const pcmData = decoder.decode(opusPacket);
 * ```
 */
export class OpusDecoder {
  private decoder: Decoder;
  private readonly logger?: Logger;

  /**
   * Create a new Opus decoder
   *
   * @param options - Decoder configuration options
   * @param mkDecoder - Factory function for creating decoder instances
   */
  constructor(options: OpusDecoderOptions, mkDecoder: DecoderFactory) {
    this.logger = options.logger;

    const { sampleRate, channels = 1 } = options;

    this.logger?.log(`Creating Opus decoder: ${sampleRate}Hz, ${channels}ch`);

    this.decoder = mkDecoder(sampleRate, channels);

    this.logger?.log('Opus decoder created successfully');
  }

  /**
   * Decode an Opus packet to PCM audio data
   *
   * Returns 16-bit signed integer PCM data (Int16Array).
   *
   * @param opusPacket - Opus-encoded packet to decode
   * @returns Decoded PCM audio data as Int16Array
   */
  decode(opusPacket: Uint8Array): Int16Array {
    this.logger?.log(`Decoding ${opusPacket.length} bytes from Opus`);

    try {
      // @evan/wasm opus decode() returns Uint8Array containing Int16 samples
      const decodedBytes = this.decoder.decode(opusPacket);

      // Create Int16Array view of the same buffer
      const pcmData = new Int16Array(
        decodedBytes.buffer,
        decodedBytes.byteOffset,
        decodedBytes.byteLength / 2
      );

      this.logger?.log(`Decoded to ${pcmData.length} samples`);
      return pcmData;
    } catch (error) {
      this.logger?.error(`Decoding failed: ${error}`);
      throw error;
    }
  }

  /**
   * Destroy the decoder and free resources
   *
   * Note: @evan/wasm opus uses finalization for cleanup, but calling reset()
   * can help free decoder state when no longer needed.
   */
  destroy(): void {
    this.logger?.log('Destroying Opus decoder');
    // @evan/wasm opus Decoder doesn't have an explicit destroy method
    // but reset() can be used to clear decoder state
    try {
      this.decoder.reset();
    } catch (error) {
      // Ignore errors during cleanup
      this.logger?.warn(`Decoder reset failed during destroy: ${error}`);
    }
  }
}
