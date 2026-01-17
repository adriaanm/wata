import keytar from 'keytar';

import type { StoredCredentials } from '../../shared/services/CredentialStorage.js';

const SERVICE_NAME_BASE = 'wata-matrix-credentials';

/**
 * Keytar (macOS Keychain) implementation of CredentialStorage for TUI.
 * Uses macOS Keychain for secure credential storage.
 * Supports multiple profiles with separate credential stores per user.
 */
export class KeytarCredentialStorage {
  /**
   * Get service name for a specific username
   */
  private getServiceName(username: string): string {
    return `${SERVICE_NAME_BASE}-${username}`;
  }

  async store(username: string, password: string): Promise<void> {
    await keytar.setPassword(this.getServiceName(username), username, password);
  }

  async retrieve(): Promise<{ username: string; password: string } | null> {
    // Legacy method - not used with profiles
    const accounts = await keytar.findCredentials(SERVICE_NAME_BASE);
    if (accounts.length === 0) {
      return null;
    }

    return {
      username: accounts[0].account,
      password: accounts[0].password,
    };
  }

  async clear(): Promise<void> {
    // Clear all wata credentials (legacy format)
    const legacyAccounts = await keytar.findCredentials(SERVICE_NAME_BASE);
    for (const account of legacyAccounts) {
      await keytar.deletePassword(SERVICE_NAME_BASE, account.account);
    }

    // Also clear per-user credentials for known profiles
    for (const username of ['alice', 'bob']) {
      const serviceName = this.getServiceName(username);
      const accounts = await keytar.findCredentials(serviceName);
      for (const account of accounts) {
        await keytar.deletePassword(serviceName, account.account);
      }
    }
  }

  /**
   * Clear credentials for a specific user
   */
  async clearUser(username: string): Promise<void> {
    const serviceName = this.getServiceName(username);
    const accounts = await keytar.findCredentials(serviceName);
    for (const account of accounts) {
      await keytar.deletePassword(serviceName, account.account);
    }
  }

  /**
   * Store Matrix session credentials for a specific user
   */
  async storeSession(
    username: string,
    credentials: StoredCredentials,
  ): Promise<void> {
    await keytar.setPassword(
      this.getServiceName(username),
      credentials.userId,
      JSON.stringify(credentials),
    );
  }

  /**
   * Retrieve Matrix session credentials for a specific user
   */
  async retrieveSession(username: string): Promise<StoredCredentials | null> {
    const accounts = await keytar.findCredentials(
      this.getServiceName(username),
    );
    if (accounts.length === 0) {
      return null;
    }

    return JSON.parse(accounts[0].password) as StoredCredentials;
  }

  /**
   * List all stored profile usernames
   */
  async listProfiles(): Promise<string[]> {
    const allAccounts = await keytar.findCredentials(SERVICE_NAME_BASE);
    // Extract unique usernames from service names
    const usernames = new Set<string>();
    for (const account of allAccounts) {
      // Service name format: wata-matrix-credentials-alice
      const match = account.account.match(/-([^-]+)$/);
      if (match) {
        usernames.add(match[1]);
      }
    }
    return Array.from(usernames);
  }
}
