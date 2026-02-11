/**
 * Test Service Factory
 *
 * Factory function for creating test instances of WataService.
 *
 * Usage:
 *   const service = createTestService(homeserverUrl, credentialStorage);
 *   await service.login(username, password);
 */

import type { CredentialStorage } from '@shared/services/CredentialStorage';
import { WataService } from '@shared/services/WataService';
import { setHomeserverUrl } from '@shared/services/WataService';

/**
 * Create a test service instance
 *
 * @param homeserver - Matrix homeserver URL (e.g., 'http://localhost:8008')
 * @param credentialStorage - Credential storage implementation
 * @returns WataService instance
 */
export function createTestService(
  homeserver: string,
  credentialStorage: CredentialStorage
): WataService {
  // Set the homeserver URL
  setHomeserverUrl(homeserver);

  // Create WataService instance
  return new WataService(credentialStorage);
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
