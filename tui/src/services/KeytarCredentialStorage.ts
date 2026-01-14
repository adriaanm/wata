import * as keytar from 'keytar';
import type { StoredCredentials } from '@shared/services/CredentialStorage';

const SERVICE_NAME = 'wata-matrix-credentials';

/**
 * Keytar (macOS Keychain) implementation of CredentialStorage for TUI.
 * Uses macOS Keychain for secure credential storage.
 */
export class KeytarCredentialStorage {
  async store(username: string, password: string): Promise<void> {
    await keytar.setPassword(SERVICE_NAME, username, password);
  }

  async retrieve(): Promise<{ username: string; password: string } | null> {
    const accounts = await keytar.findCredentials(SERVICE_NAME);
    if (accounts.length === 0) {
      return null;
    }

    return {
      username: accounts[0].account,
      password: accounts[0].password,
    };
  }

  async clear(): Promise<void> {
    const accounts = await keytar.findCredentials(SERVICE_NAME);
    for (const account of accounts) {
      await keytar.deletePassword(SERVICE_NAME, account.account);
    }
  }

  /**
   * Store Matrix session credentials (used by MatrixService)
   */
  async storeSession(credentials: StoredCredentials): Promise<void> {
    await keytar.setPassword(
      SERVICE_NAME,
      credentials.userId,
      JSON.stringify(credentials)
    );
  }

  /**
   * Retrieve Matrix session credentials (used by MatrixService)
   */
  async retrieveSession(): Promise<StoredCredentials | null> {
    const accounts = await keytar.findCredentials(SERVICE_NAME);
    if (accounts.length === 0) {
      return null;
    }

    return JSON.parse(accounts[0].password) as StoredCredentials;
  }
}
