/**
 * Web-specific Opus encoder/decoder factories
 *
 * Wraps the Deno/Web-specific @evan/wasm opus module for use with
 * the platform-agnostic audio-codec.ts in shared.
 *
 * @module opus-factories
*/

import { Encoder, Decoder } from '@evan/wasm/target/opus/deno.js';
import type { EncoderFactory, DecoderFactory } from '@shared/lib/opus.js';

/**
 * Create an Opus encoder for the Web (Deno/browser) environment
 */
export const mkEncoder: EncoderFactory = (sampleRate, channels, application) =>
  new Encoder({
      sample_rate: sampleRate,
      channels,
      application,
   });


/**
 * Create an Opus decoder for the Web (Deno/browser) environment
 */
export const mkDecoder: DecoderFactory = (sampleRate, channels) =>
  new Decoder({
    sample_rate: sampleRate,
    channels,
  });

