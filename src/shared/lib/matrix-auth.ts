/**
 * Matrix Authentication Helper
 *
 * Shared login logic used by both the app and integration tests.
 * This ensures consistent authentication flow across all environments.
 */

import { LogService } from '@tui/services/LogService';
import * as matrix from 'matrix-js-sdk';

import { createFixedFetch } from './fixed-fetch-api';

// Helper to log to LogService (works in both TUI and RN environments)
const log = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('log', message);
  } catch {
    // LogService not available (e.g., in React Native), silently ignore
  }
};

const logError = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('error', message);
  } catch {
    // LogService not available (e.g., in React Native), silently ignore
  }
};

/**
 * Login request format required by Conduit
 * See: https://spec.matrix.org/v1.11/client-server-api/#post_matrixclientv3login
 */
export interface MatrixLoginRequest {
  identifier: {
    type: 'm.id.user';
    user: string;
  };
  password: string;
  initial_device_display_name?: string;
}

/**
 * Login response from Matrix server
 */
export interface MatrixLoginResponse {
  user_id: string;
  access_token: string;
  device_id: string;
  home_server?: string;
  well_known?: {
    'm.homeserver': { base_url: string };
  };
}

// Logger interface matching matrix-js-sdk's Logger type
interface MatrixLogger {
  trace(...msg: unknown[]): void;
  debug(...msg: unknown[]): void;
  info(...msg: unknown[]): void;
  warn(...msg: unknown[]): void;
  error(...msg: unknown[]): void;
  getChild(namespace: string): MatrixLogger;
}

export interface LoginOptions {
  deviceName?: string;
  logger?: MatrixLogger;
  refreshToken?: () => Promise<{ access_token: string }>;
}

/**
 * Login to a Matrix homeserver using username and password
 *
 * @param baseUrl - The homeserver URL (e.g., 'http://localhost:8008')
 * @param username - The username (not the full Matrix ID)
 * @param password - The user's password
 * @param options - Optional login options (deviceName, logger)
 * @returns MatrixClient configured with the authenticated session
 */
export async function loginToMatrix(
  baseUrl: string,
  username: string,
  password: string,
  options?: LoginOptions | string, // string for backwards compatibility (deviceName)
): Promise<matrix.MatrixClient> {
  // Handle backwards compatibility - old signature passed deviceName as string
  const opts: LoginOptions =
    typeof options === 'string' ? { deviceName: options } : options || {};
  log('[matrix-auth] loginToMatrix called:');
  log(`  baseUrl: ${baseUrl}`);
  log(`  username: ${username}`);
  log(`  deviceName: ${opts.deviceName || '(none)'}`);

  // Construct login request in Conduit-compatible format
  const loginRequest: MatrixLoginRequest = {
    identifier: {
      type: 'm.id.user',
      user: username,
    },
    password,
  };

  if (opts.deviceName) {
    loginRequest.initial_device_display_name = opts.deviceName;
  }

  log('[matrix-auth] Calling SDK login with request:');
  log(`  type: m.login.password`);
  log(`  identifier: ${JSON.stringify(loginRequest.identifier)}`);
  log(`  hasPassword: ${!!password}`);
  log(`  deviceName: ${loginRequest.initial_device_display_name || '(none)'}`);

  try {
    // Use direct fetch to avoid SDK's buggy URL construction in React Native
    const loginUrl = `${baseUrl}/_matrix/client/v3/login`;
    log(`[matrix-auth] POST to: ${loginUrl}`);

    const response = await fetch(loginUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        type: 'm.login.password',
        ...loginRequest,
      }),
    });

    if (!response.ok) {
      const errorData = await response.json();
      throw new Error(
        `Login failed: ${errorData.errcode || response.status} - ${errorData.error || response.statusText}`,
      );
    }

    const loginResponse = (await response.json()) as MatrixLoginResponse;

    log('[matrix-auth] Login successful:');
    log(`  userId: ${loginResponse.user_id}`);
    log(`  deviceId: ${loginResponse.device_id}`);

    // Create authenticated client with custom fetch that fixes URLs
    // and configuration suitable for Conduit server
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const clientOpts: any = {
      baseUrl,
      accessToken: loginResponse.access_token,
      userId: loginResponse.user_id,
      deviceId: loginResponse.device_id,
      fetchFn: createFixedFetch(),
      // Reduce load on Conduit by disabling optional features
      useAuthorizationHeader: true,
      timelineSupport: true,
    };

    // Add custom logger if provided (for TUI to suppress console output)
    if (opts.logger) {
      clientOpts.logger = opts.logger;
    }

    // Add refresh callback if provided (for token renewal)
    if (opts.refreshToken) {
      clientOpts.refreshToken = opts.refreshToken;
    }

    return matrix.createClient(clientOpts);
  } catch (error) {
    logError(`[matrix-auth] Login failed: ${error}`);
    throw error;
  }
}

/**
 * Create credentials object for secure storage
 */
export interface StoredCredentials {
  accessToken: string;
  userId: string;
  deviceId: string;
  homeserverUrl: string;
}

export function createStoredCredentials(
  response: MatrixLoginResponse,
  homeserverUrl: string,
): StoredCredentials {
  return {
    accessToken: response.access_token,
    userId: response.user_id,
    deviceId: response.device_id,
    homeserverUrl,
  };
}
