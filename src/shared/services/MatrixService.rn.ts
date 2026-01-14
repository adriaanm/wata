/**
 * React Native singleton instance of MatrixService.
 * Uses RNCredentialStorage for secure credential storage on iOS/Android.
 */
import { MatrixService } from '@shared/services/MatrixService';
import { RNCredentialStorage } from '@rn/services/RNCredentialStorage';

const rnCredentialStorage = new RNCredentialStorage();
export const matrixService = new MatrixService(rnCredentialStorage);
