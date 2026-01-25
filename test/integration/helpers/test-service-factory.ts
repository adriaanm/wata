/**
 * Test Service Factory
 *
 * Factory function for creating test instances of MatrixService or MatrixServiceAdapter.
 * This uses the same MATRIX_CONFIG.implementation setting as production code.
 *
 * Usage:
 *   const service = createTestService(homeserverUrl, credentialStorage);
 *   await service.login(username, password);
 *
 * Environment variables:
 *   WATA_MATRIX_IMPL=wata-client - Use MatrixServiceAdapter (WataClient, default)
 *   WATA_MATRIX_IMPL=matrix-js-sdk - Use MatrixService (matrix-js-sdk fallback)
 */

import { MATRIX_CONFIG } from '@shared/config/matrix';
import type { CredentialStorage } from '@shared/services/CredentialStorage';
import { createMatrixService } from '@shared/services';
import { setHomeserverUrl as setAdapterHomeserverUrl } from '@shared/services/MatrixServiceAdapter';
import { setHomeserverUrl as setMatrixServiceHomeserverUrl } from '@shared/services/MatrixService';

/**
 * Create a test service instance
 *
 * @param homeserver - Matrix homeserver URL (e.g., 'http://localhost:8008')
 * @param credentialStorage - Credential storage implementation
 * @returns MatrixService or MatrixServiceAdapter instance
 */
export function createTestService(
  homeserver: string,
  credentialStorage: CredentialStorage
): MatrixService | MatrixServiceAdapter {
  // Set the homeserver URL for both implementations
  setAdapterHomeserverUrl(homeserver);
  setMatrixServiceHomeserverUrl(homeserver);

  // Use the production factory
  return createMatrixService({ credentialStorage });
}

/**
 * Create a simple in-memory credential storage for testing
 *
 * This implementation stores credentials in memory only and is suitable for tests.
 * Data is lost when the process exits.
 *
 * @returns In-memory CredentialStorage implementation
 */
export function createTestCredentialStorage(): CredentialStorage {
  // Simple in-memory storage for testing
  const sessions = new Map<string, { username: string; password: string }>();
  const matrixSessions = new Map<string, import('@shared/lib/matrix-auth').StoredCredentials>();

  return {
    async store(username: string, password: string): Promise<void> {
      sessions.set(username, { username, password });
    },

    async retrieve(): Promise<{ username: string; password: string } | null> {
      // Return the first stored credential (for single-user tests)
      const first = sessions.values().next().value;
      return first || null;
    },

    async clear(): Promise<void> {
      sessions.clear();
      matrixSessions.clear();
    },

    async storeSession(
      username: string,
      credentials: import('@shared/lib/matrix-auth').StoredCredentials
    ): Promise<void> {
      matrixSessions.set(username, credentials);
    },

    async retrieveSession(
      username: string
    ): Promise<import('@shared/lib/matrix-auth').StoredCredentials | null> {
      return matrixSessions.get(username) || null;
    },

    async clearUser(username: string): Promise<void> {
      sessions.delete(username);
      matrixSessions.delete(username);
    },
  };
}

/**
 * Check if the factory is configured to use WataClient
 *
 * Useful for test setup/teardown that depends on the implementation.
 *
 * @returns true if using WataClient via MatrixServiceAdapter
 */
export function isUsingWataClient(): boolean {
  return MATRIX_CONFIG.implementation === 'wata-client';
}

/**
 * Get the name of the current implementation
 *
 * Useful for logging test configuration.
 *
 * @returns 'WataClient' or 'matrix-js-sdk'
 */
export function getImplementationName(): string {
  return MATRIX_CONFIG.implementation === 'wata-client' ? 'WataClient' : 'matrix-js-sdk';
}
