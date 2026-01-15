/**
 * Profile configuration for multi-user support in the TUI.
 * Each profile represents a different Matrix user account.
 */
export interface Profile {
  username: string;
  password: string;
  displayName: string;
  color: string; // Terminal color name for visual distinction
}

/**
 * Hardcoded profiles for local testing.
 * Both alice and bob use the same test password.
 */
export const PROFILES: Record<string, Profile> = {
  alice: {
    username: 'alice',
    password: 'testpass123',
    displayName: 'Alice',
    color: 'cyan',
  },
  bob: {
    username: 'bob',
    password: 'testpass123',
    displayName: 'Bob',
    color: 'magenta',
  },
};

export type ProfileKey = keyof typeof PROFILES;
