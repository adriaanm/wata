/**
 * Wrapper to import shared code from main src/ directory.
 * This file bridges the CommonJS (React Native) and ESM (TUI) module systems.
 */

// Use dynamic import to load CommonJS modules into ESM context
const matrixServiceModule = await import('../../src/services/MatrixService.ts');
const credentialStorageModule = await import('../../src/services/CredentialStorage.ts');

// Re-export the named exports
export const { MatrixService } = matrixServiceModule;
export type { MatrixRoom, VoiceMessage } = matrixServiceModule;
export type { StoredCredentials } = credentialStorageModule;
