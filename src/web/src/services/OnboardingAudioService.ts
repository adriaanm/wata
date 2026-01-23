/**
 * Onboarding Audio Service
 *
 * Handles audio-based credential transfer for device onboarding.
 * Uses MFSK (Multi-Frequency Shift Keying) modulation with Reed-Solomon
 * error correction for robust transmission in noisy acoustic environments.
 *
 * Use case: Transfer homeserver URL, username, password, and room ID
 * from a phone/computer to a Zello PTT handheld that lacks a camera.
 */

import {
  encodeMfsk,
  decodeMfsk,
  DEFAULT_CONFIG,
  type MfskConfig,
} from '@shared/lib/mfsk.js';

export { DEFAULT_CONFIG, type MfskConfig };

/**
 * Onboarding payload structure
 */
export interface OnboardingData {
  homeserver: string;
  username: string;
  password: string;
  room: string;
}

/**
 * Encode onboarding data to audio samples
 */
export function encodeOnboardingAudio(
  data: OnboardingData,
  config: MfskConfig = DEFAULT_CONFIG,
): Float32Array {
  return encodeMfsk(data, config);
}

/**
 * Decode audio samples to onboarding data
 */
export async function decodeOnboardingAudio(
  samples: Float32Array,
  config: MfskConfig = DEFAULT_CONFIG,
): Promise<OnboardingData> {
  const data = await decodeMfsk(samples, config);
  // Validate structure
  if (
    typeof data !== 'object' ||
    data === null ||
    typeof (data as OnboardingData).homeserver !== 'string' ||
    typeof (data as OnboardingData).username !== 'string' ||
    typeof (data as OnboardingData).password !== 'string' ||
    typeof (data as OnboardingData).room !== 'string'
  ) {
    throw new Error('Invalid onboarding data structure');
  }
  return data as OnboardingData;
}

/**
 * Create an AudioBuffer from samples for Web Audio API playback
 */
export function samplesToAudioBuffer(
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
 * Convert AudioBuffer to Float32Array samples
 */
export function audioBufferToSamples(audioBuffer: AudioBuffer): Float32Array {
  return audioBuffer.getChannelData(0);
}

/**
 * Calculate expected audio duration in seconds
 */
export function calculateDuration(
  data: OnboardingData,
  config: MfskConfig = DEFAULT_CONFIG,
): number {
  const samples = encodeMfsk(data, config);
  return samples.length / config.sampleRate;
}

/**
 * Get human-readable modulation specs
 */
export function getModulationSpecs(config: MfskConfig = DEFAULT_CONFIG): {
  modulation: string;
  tones: number;
  baseFreq: number;
  maxFreq: number;
  symbolRate: number;
  bitRate: number;
  errorCorrection: string;
} {
  const maxFreq =
    config.baseFrequency + (config.numTones - 1) * config.frequencySpacing;
  const symbolRate = 1000 / config.symbolDuration;
  const bitsPerSymbol = Math.log2(config.numTones);
  // Account for RS(15,9) overhead: 9/15 = 60% efficiency
  const bitRate = symbolRate * bitsPerSymbol * (9 / 15);

  return {
    modulation: `${config.numTones}-MFSK`,
    tones: config.numTones,
    baseFreq: config.baseFrequency,
    maxFreq,
    symbolRate,
    bitRate: Math.round(bitRate),
    errorCorrection: 'RS(15,9)',
  };
}
