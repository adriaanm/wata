/**
 * Test helpers index
 *
 * Centralized exports for all test infrastructure components.
 */

export { TestClient } from './test-client';
export type { MessageFilter } from './test-client';
export { TestOrchestrator } from './test-orchestrator';
export {
  createFakeAudioBuffer,
  createAudioBuffers,
  createIdentifiableAudioBuffer,
  createVariedDurationBuffers,
  AudioDurations,
} from './audio-helpers';
export {
  createTestService,
  createTestCredentialStorage,
  isUsingWataClient,
  getImplementationName,
} from './test-service-factory';
