/**
 * TUI-specific Opus encoder/decoder factories
 *
 * Wraps the Node.js-specific @evan/wasm opus module for use with
 * the platform-agnostic audio-codec.ts in shared.
 *
 * @module opus-factories
 */

import { Encoder, Decoder } from '@evan/wasm/target/opus/node.mjs';
import type { EncoderFactory, DecoderFactory } from '@shared/lib/opus.js';

/**
 * Create an Opus encoder for the TUI (Node.js) environment
 */
export const mkEncoder: EncoderFactory = (sampleRate, channels, application) =>
  new Encoder({
      sample_rate: sampleRate,
      channels,
      application,
   });

/**
 * Create an Opus decoder for the TUI (Node.js) environment
 */
export const mkDecoder: DecoderFactory = (sampleRate, channels) =>
  new Decoder({
      sample_rate: sampleRate,
      channels,
    });

