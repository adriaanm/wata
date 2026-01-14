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
