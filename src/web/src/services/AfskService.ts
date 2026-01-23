/**
 * AFSK Service - DEPRECATED
 *
 * This module has been replaced by OnboardingAudioService.
 * Re-exports for backwards compatibility.
 *
 * @deprecated Use OnboardingAudioService directly
 */

export {
  encodeOnboardingAudio as encodeAfsk,
  decodeOnboardingAudio as decodeAfsk,
  samplesToAudioBuffer as afskSamplesToAudioBuffer,
  audioBufferToSamples,
  DEFAULT_CONFIG,
  type MfskConfig as AfskConfig,
} from './OnboardingAudioService.js';
