import type { CredentialStorage, StoredCredentials } from '@shared/services/CredentialStorage';

/**
 * Web credential storage using localStorage.
 *
 * For production, credentials should be encrypted before storing.
 *
 * TODO: Add encryption for production deployment
 */
export class WebCredentialStorage implements CredentialStorage {
  private readonly CREDENTIALS_KEY = 'wata_credentials';
  private readonly SESSION_PREFIX = 'wata_session_';

  async store(username: string, password: string): Promise<void> {
    const data = { username, password };
    localStorage.setItem(this.CREDENTIALS_KEY, JSON.stringify(data));
  }

  async retrieve(): Promise<{ username: string; password: string } | null> {
    const data = localStorage.getItem(this.CREDENTIALS_KEY);
    if (!data) return null;

    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  }

  async clear(): Promise<void> {
    const keys = Object.keys(localStorage);
    for (const key of keys) {
      if (key.startsWith('wata_')) {
        localStorage.removeItem(key);
      }
    }
  }

  async storeSession(username: string, credentials: StoredCredentials): Promise<void> {
    const key = `${this.SESSION_PREFIX}${username}`;
    localStorage.setItem(key, JSON.stringify(credentials));
  }

  async retrieveSession(username: string): Promise<StoredCredentials | null> {
    const key = `${this.SESSION_PREFIX}${username}`;
    const data = localStorage.getItem(key);
    if (!data) return null;

    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  }

  async clearUser(username: string): Promise<void> {
    const key = `${this.SESSION_PREFIX}${username}`;
    localStorage.removeItem(key);
  }
}

// Singleton instance
export const webCredentialStorage = new WebCredentialStorage();
