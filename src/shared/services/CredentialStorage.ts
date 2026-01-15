/**
 * Platform-agnostic credential storage interface.
 * Implementations should use secure storage (Keychain on iOS/macOS, Keystore on Android).
 */
export interface CredentialStorage {
  /**
   * Store credentials securely
   */
  store(username: string, password: string): Promise<void>;

  /**
   * Retrieve stored credentials
   * @returns credentials if found, null otherwise
   */
  retrieve(): Promise<{ username: string; password: string } | null>;

  /**
   * Clear stored credentials
   */
  clear(): Promise<void>;

  /**
   * Store Matrix session credentials for a specific user
   * @param username - Username to store session for
   * @param credentials - Session credentials
   */
  storeSession(username: string, credentials: StoredCredentials): Promise<void>;

  /**
   * Retrieve Matrix session credentials for a specific user
   * @param username - Username to retrieve session for
   * @returns Session credentials or null if not found
   */
  retrieveSession(username: string): Promise<StoredCredentials | null>;

  /**
   * Clear credentials for a specific user
   * @param username - Username to clear credentials for
   */
  clearUser(username: string): Promise<void>;
}

/**
 * Stored Matrix session data
 */
export interface StoredCredentials {
  accessToken: string;
  userId: string;
  deviceId: string;
  homeserverUrl: string;
}
