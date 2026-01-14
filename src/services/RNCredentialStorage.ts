import * as Keychain from 'react-native-keychain';

import type { CredentialStorage, StoredCredentials } from './CredentialStorage';

const KEYCHAIN_SERVICE = 'wata-matrix-credentials';

/**
 * React Native Keychain implementation of CredentialStorage.
 * Uses secure iOS Keychain / Android Keystore.
 */
export class RNCredentialStorage implements CredentialStorage {
  async store(username: string, password: string): Promise<void> {
    await Keychain.setGenericPassword(username, password, {
      service: KEYCHAIN_SERVICE,
    });
  }

  async retrieve(): Promise<{ username: string; password: string } | null> {
    const credentials = await Keychain.getGenericPassword({
      service: KEYCHAIN_SERVICE,
    });

    if (!credentials) {
      return null;
    }

    return {
      username: credentials.username,
      password: credentials.password,
    };
  }

  async clear(): Promise<void> {
    await Keychain.resetGenericPassword({ service: KEYCHAIN_SERVICE });
  }

  /**
   * Store Matrix session credentials (used by MatrixService)
   */
  async storeSession(credentials: StoredCredentials): Promise<void> {
    await Keychain.setGenericPassword(
      credentials.userId,
      JSON.stringify(credentials),
      { service: KEYCHAIN_SERVICE },
    );
  }

  /**
   * Retrieve Matrix session credentials (used by MatrixService)
   */
  async retrieveSession(): Promise<StoredCredentials | null> {
    const credentials = await Keychain.getGenericPassword({
      service: KEYCHAIN_SERVICE,
    });

    if (!credentials) {
      return null;
    }

    return JSON.parse(credentials.password) as StoredCredentials;
  }
}
