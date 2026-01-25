/**
 * React Native singleton instance of MatrixService.
 * Uses RNCredentialStorage for secure credential storage on iOS/Android.
 */
import { RNCredentialStorage } from '@rn/services/RNCredentialStorage';
import { createMatrixService } from '@shared/services';

const rnCredentialStorage = new RNCredentialStorage();
export const matrixService = createMatrixService({
  credentialStorage: rnCredentialStorage,
});
