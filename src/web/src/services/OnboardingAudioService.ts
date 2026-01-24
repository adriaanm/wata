/**
 * Onboarding Audio Service
 *
 * Handles audio-based credential transfer for device onboarding using AudioCode.
 * AudioCode = "QR Code over Audio" - 16-MFSK modulation with Reed-Solomon error correction.
 *
 * Use case: Transfer homeserver URL, username, password, and room ID
 * from a phone/computer to a Zello PTT handheld that lacks a camera.
 */

import {
  encodeAudioCode,
  decodeAudioCode,
  DEFAULT_CONFIG,
  type AudioCodeConfig,
} from '@shared/lib/audiocode.js';

export { DEFAULT_CONFIG, type AudioCodeConfig };

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
  config: AudioCodeConfig = DEFAULT_CONFIG,
): Float32Array {
  return encodeAudioCode(data, config);
}

/**
 * Decode audio samples to onboarding data
 */
export async function decodeOnboardingAudio(
  samples: Float32Array,
  config: AudioCodeConfig = DEFAULT_CONFIG,
): Promise<OnboardingData> {
  const data = await decodeAudioCode(samples, config);
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
  config: AudioCodeConfig = DEFAULT_CONFIG,
): number {
  const samples = encodeAudioCode(data, config);
  return samples.length / config.sampleRate;
}

/**
 * Get human-readable modulation specs
 */
export function getModulationSpecs(config: AudioCodeConfig = DEFAULT_CONFIG): {
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
  // Account for 100% RS redundancy: 1/2 efficiency
  const bitRate = symbolRate * bitsPerSymbol * 0.5;

  return {
    modulation: `16-MFSK (AudioCode)`,
    tones: config.numTones,
    baseFreq: config.baseFrequency,
    maxFreq,
    symbolRate,
    bitRate: Math.round(bitRate),
    errorCorrection: 'RS(100% redundancy)',
  };
}
