/**
 * Audio resampling using linear interpolation
 *
 * This module provides audio resampling capabilities to convert between
 * different sample rates (e.g., 44100 Hz → 16000 Hz for Opus encoding).
 *
 * ## Algorithm: Linear Interpolation
 *
 * Linear interpolation is a simple and efficient resampling method that
 * estimates new sample values by drawing straight lines between existing
 * samples. While not as high-quality as sinc interpolation, it's sufficient
 * for speech audio and much faster.
 *
 * ### How it works:
 *
 * 1. Calculate the step size: `step = fromRate / toRate`
 *    - This tells us how many input samples correspond to one output sample
 *    - For downsampling (e.g., 44100 → 16000), step > 1
 *    - For upsampling (e.g., 16000 → 48000), step < 1
 *
 * 2. For each output sample index `i`:
 *    - Calculate the position in the input array: `pos = i * step`
 *    - Get the floor (integer part) and ceiling of the position
 *    - Calculate the fractional part between them
 *    - Interpolate: `output[i] = input[floor] * (1 - frac) + input[ceil] * frac`
 *
 * ### Example (Downsampling 44100 → 16000):
 *
 * ```
 * step = 44100 / 16000 ≈ 2.75625
 *
 * For i = 0: pos = 0 * 2.75625 = 0.0
 *   → Use input[0] directly (at boundary)
 *
 * For i = 1: pos = 1 * 2.75625 = 2.75625
 *   → floor = 2, ceil = 3, frac = 0.75625
 *   → output[1] = input[2] * 0.24375 + input[3] * 0.75625
 * ```
 *
 * ### Supported Sample Rates
 *
 * Common sample rates used in audio applications:
 * - 8000 Hz   - Telephony quality
 * - 16000 Hz  - Wideband speech (Opus default)
 * - 22050 Hz  - Half CD quality
 * - 44100 Hz  - CD quality (common recording rate)
 * - 48000 Hz  - Professional audio
 *
 * The algorithm works for any pair of sample rates, not just common ones.
 *
 * @module resample
 */

import type { Logger } from './wata-client/types.js';

/**
 * No-op logger implementation used when no logger is provided
 */
const noopLogger: Logger = {
  log: () => {},
  warn: () => {},
  error: () => {},
};

/**
 * Resamples audio from one sample rate to another using linear interpolation.
 *
 * @param samples - Input audio samples as Float32Array (typically -1.0 to 1.0 range)
 * @param fromRate - Source sample rate in Hz (e.g., 44100, 48000)
 * @param toRate - Target sample rate in Hz (e.g., 16000, 8000)
 * @param logger - Optional logger for debugging (defaults to no-op)
 * @returns Resampled audio as Float32Array
 *
 * @example
 * ```ts
 * // Resample from CD quality to Opus-friendly rate
 * const cdQuality = new Float32Array([/* 44100 Hz samples */]);
 * const opusReady = resample(cdQuality, 44100, 16000);
 * ```
 */
export function resample(
  samples: Float32Array,
  fromRate: number,
  toRate: number,
  logger: Logger = noopLogger
): Float32Array {
  // Validate inputs
  if (samples.length === 0) {
    logger.warn('resample: empty input array, returning empty output');
    return new Float32Array(0);
  }

  if (fromRate <= 0 || toRate <= 0) {
    throw new Error(`Invalid sample rates: fromRate=${fromRate}, toRate=${toRate}`);
  }

  if (fromRate === toRate) {
    logger.log(`resample: rates are equal (${fromRate} Hz), returning copy`);
    return new Float32Array(samples);
  }

  const isDownsampling = toRate < fromRate;
  const ratio = fromRate / toRate;

  logger.log(
    `resample: ${fromRate} Hz → ${toRate} Hz (${isDownsampling ? 'downsampling' : 'upsampling'}), ` +
      `${samples.length} samples, ratio=${ratio.toFixed(4)}`
  );

  // Calculate output length
  // For exact conversion: outputLength = inputLength * (toRate / fromRate)
  const outputLength = Math.ceil((samples.length * toRate) / fromRate);
  const output = new Float32Array(outputLength);

  // Linear interpolation resampling
  for (let i = 0; i < outputLength; i++) {
    // Calculate position in input array
    const pos = i * ratio;

    // Get the two surrounding samples
    const index = Math.floor(pos);
    const nextIndex = Math.min(index + 1, samples.length - 1);

    // Calculate fractional part for interpolation
    const frac = pos - index;

    // Linear interpolation: y = y0 * (1 - t) + y1 * t
    // Where t is the fractional position between samples
    const sample = samples[index] * (1 - frac) + samples[nextIndex] * frac;

    output[i] = sample;
  }

  logger.log(`resample: output ${outputLength} samples`);

  return output;
}
