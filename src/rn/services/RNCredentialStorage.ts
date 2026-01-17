import type {
  CredentialStorage,
  StoredCredentials,
} from '@shared/services/CredentialStorage';
import * as Keychain from 'react-native-keychain';


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
  async storeSession(
    username: string,
    credentials: StoredCredentials,
  ): Promise<void> {
    await Keychain.setGenericPassword(username, JSON.stringify(credentials), {
      service: KEYCHAIN_SERVICE,
    });
  }

  /**
   * Retrieve Matrix session credentials (used by MatrixService)
   */
  async retrieveSession(username: string): Promise<StoredCredentials | null> {
    const credentials = await Keychain.getGenericPassword({
      service: KEYCHAIN_SERVICE,
    });

    if (!credentials) {
      return null;
    }

    return JSON.parse(credentials.password) as StoredCredentials;
  }

  /**
   * Clear credentials for a specific user
   */
  async clearUser(username: string): Promise<void> {
    // RN Keychain doesn't support per-user deletion in the same service
    // We clear all credentials since RN uses single-user model
    await Keychain.resetGenericPassword({ service: KEYCHAIN_SERVICE });
  }
}
