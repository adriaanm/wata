/**
 * Test Service Factory
 *
 * Factory function for creating test instances of MatrixService or MatrixServiceAdapter.
 * This allows tests to be run against either implementation by setting the USE_WATA_CLIENT
 * environment variable.
 *
 * Usage:
 *   const service = createTestService(homeserverUrl, credentialStorage);
 *   await service.login(username, password);
 *
 * Environment variables:
 *   USE_WATA_CLIENT=true - Use MatrixServiceAdapter (WataClient implementation)
 *   USE_WATA_CLIENT=false or unset - Use MatrixService (matrix-js-sdk implementation)
 */

import type { CredentialStorage } from '@shared/services/CredentialStorage';
import { MatrixService, setHomeserverUrl as setMatrixServiceHomeserverUrl } from '@shared/services/MatrixService';
import { MatrixServiceAdapter, setHomeserverUrl as setAdapterHomeserverUrl } from '@shared/services/MatrixServiceAdapter';

// Environment variable to toggle between implementations
const USE_WATA_CLIENT = process.env.USE_WATA_CLIENT === 'true';

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
  if (USE_WATA_CLIENT) {
    // Use WataClient implementation via adapter
    const adapter = new MatrixServiceAdapter(credentialStorage);
    setAdapterHomeserverUrl(homeserver);
    return adapter;
  } else {
    // Use original matrix-js-sdk implementation
    const service = new MatrixService(credentialStorage);
    setMatrixServiceHomeserverUrl(homeserver);
    return service;
  }
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
  return USE_WATA_CLIENT;
}

/**
 * Get the name of the current implementation
 *
 * Useful for logging test configuration.
 *
 * @returns 'WataClient' or 'matrix-js-sdk'
 */
export function getImplementationName(): string {
  return USE_WATA_CLIENT ? 'WataClient' : 'matrix-js-sdk';
}
