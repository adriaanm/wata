/**
 * Web-specific MatrixService singleton.
 * Uses WebCredentialStorage for browser credential management.
 */

import { MatrixService, setLogger } from '@shared/services/MatrixService';

import { LogService } from './LogService';
import { webCredentialStorage } from './WebCredentialStorage';

// Wire up web's LogService to the shared MatrixService
setLogger({
  log: (message: string) => LogService.getInstance().addEntry('log', message),
  warn: (message: string) => LogService.getInstance().addEntry('warn', message),
  error: (message: string) =>
    LogService.getInstance().addEntry('error', message),
});

// Create the singleton instance with web-specific credential storage
export const matrixService = new MatrixService(webCredentialStorage);
