/**
 * React Native singleton instance of MatrixService.
 * Uses RNCredentialStorage for secure credential storage on iOS/Android.
 */
import { RNCredentialStorage } from '@rn/services/RNCredentialStorage';
import { MatrixService } from '@shared/services/MatrixService';

const rnCredentialStorage = new RNCredentialStorage();
export const matrixService = new MatrixService(rnCredentialStorage);
