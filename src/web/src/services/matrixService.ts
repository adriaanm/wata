/**
 * Web-specific WataService singleton.
 * Uses WebCredentialStorage for browser credential management.
 */

import { WataService } from '@shared/services';
import { setLogger } from '@shared/services/WataService';

import { LogService } from './LogService';
import { webCredentialStorage } from './WebCredentialStorage';

// Wire up web's LogService to WataService
setLogger({
  log: (message: string) => LogService.getInstance().addEntry('log', message),
  warn: (message: string) => LogService.getInstance().addEntry('warn', message),
  error: (message: string) =>
    LogService.getInstance().addEntry('error', message),
});

// Create the singleton instance with web-specific credential storage
export const matrixService = new WataService(webCredentialStorage);
