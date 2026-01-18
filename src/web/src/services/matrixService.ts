/**
 * Web-specific MatrixService singleton.
 * Uses WebCredentialStorage for browser credential management.
 */

import { MatrixService } from '@shared/services/MatrixService';
import { webCredentialStorage } from './WebCredentialStorage';

// Create the singleton instance with web-specific credential storage
export const matrixService = new MatrixService(webCredentialStorage);
