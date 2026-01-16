/**
 * Auto-Login Tests
 *
 * Tests for the automatic login flow and session restoration.
 * These tests verify the device auto-login functionality used on
 * PTT handhelds without keyboard input.
 */

import { TestOrchestrator } from './helpers';
import { MATRIX_CONFIG } from '../../src/shared/config/matrix';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

describe('Auto-Login Flow', () => {
  let orchestrator: TestOrchestrator;

  beforeAll(async () => {
    // Check if server is running
    try {
      const response = await fetch(
        `${TEST_HOMESERVER}/_matrix/client/versions`,
      );
      if (!response.ok) {
        throw new Error('Matrix server not responding');
      }
    } catch {
      throw new Error(
        'Matrix server not running. Start it with: npm run dev:server',
      );
    }
  }, 10000);

  beforeEach(async () => {
    orchestrator = new TestOrchestrator(TEST_HOMESERVER);
  }, 5000);

  afterEach(async () => {
    await orchestrator.cleanup();
  }, 10000);

  test('should auto-login with config credentials', async () => {
    // Create client using the same username/password as in MATRIX_CONFIG
    await orchestrator.createClient(
      MATRIX_CONFIG.username,
      MATRIX_CONFIG.password,
    );

    const client = orchestrator.getClient(MATRIX_CONFIG.username);
    expect(client).toBeDefined();

    // Verify we can interact with the server
    const userId = client?.getUserId();
    expect(userId).toBe(`@${MATRIX_CONFIG.username}:localhost`);

    // Verify sync works
    const syncState = await client?.waitForSync(10000);
    expect(syncState).toBeUndefined(); // Promise resolves without error
  }, 20000);

  test('should login with valid credentials', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );

    const client = orchestrator.getClient('alice');
    expect(client).toBeDefined();

    const userId = client?.getUserId();
    expect(userId).toBe('@alice:localhost');
  }, 20000);

  test('should fail login with invalid password', async () => {
    await expect(
      orchestrator.createClient(TEST_USERS.alice.username, 'wrongpassword'),
    ).rejects.toThrow();

    // Client should not be created (getClient throws when not found)
    expect(() => orchestrator.getClient('alice')).toThrow('Client not found');
  }, 20000);

  test('should fail login with non-existent user', async () => {
    await expect(
      orchestrator.createClient('nonexistent', 'password'),
    ).rejects.toThrow();

    // Client should not be created (getClient throws when not found)
    expect(() => orchestrator.getClient('nonexistent')).toThrow(
      'Client not found',
    );
  }, 20000);

  test('should login multiple users concurrently', async () => {
    // Login alice and bob at the same time
    await Promise.all([
      orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      ),
      orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      ),
    ]);

    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    expect(aliceClient).toBeDefined();
    expect(bobClient).toBeDefined();

    expect(aliceClient?.getUserId()).toBe('@alice:localhost');
    expect(bobClient?.getUserId()).toBe('@bob:localhost');
  }, 30000);

  test('should complete sync after login', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );

    const client = orchestrator.getClient('alice');

    // Wait for sync to complete - should not timeout
    await expect(client?.waitForSync(15000)).resolves.toBeUndefined();
  }, 25000);

  test('should retrieve user ID after login', async () => {
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const client = orchestrator.getClient('bob');
    const userId = client?.getUserId();

    expect(userId).toBeDefined();
    expect(userId).toMatch(/^@bob:/);
    expect(userId).toContain('localhost');
  }, 20000);
});
