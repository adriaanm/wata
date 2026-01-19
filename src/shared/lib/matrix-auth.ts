/**
 * Matrix Authentication Helper
 *
 * Shared login logic used by both the app and integration tests.
 * This ensures consistent authentication flow across all environments.
 */

import * as matrix from 'matrix-js-sdk';

import { createFixedFetch } from './fixed-fetch-api';

// Optional logger interface that platforms can provide
export interface Logger {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

// Default no-op logger (platforms can override via setLogger)
let logger: Logger | undefined;

export function setLogger(l: Logger | undefined): void {
  logger = l;
}

// Helper to log to platform logger (no-op if not set)
const log = (message: string): void => {
  logger?.log(message);
};

const logError = (message: string): void => {
  logger?.error(message);
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
 * OAuth2-style access tokens (for SDK compatibility)
 * Conduit doesn't use OAuth2, but we store a dummy refresh token
 * to make the SDK call our refresh callback.
 */
export interface AccessTokens {
  accessToken: string;
  refreshToken?: string;
  expiry?: Date;
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
  /**
   * Callback to refresh the access token when it expires.
   * For Conduit (no OAuth2), the refresh token parameter is ignored;
   * the callback should do a password-based re-login.
   */
  tokenRefreshFunction?: (refreshToken: string) => Promise<AccessTokens>;
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
  /** Refresh token callback to pass through (for use by refresh callbacks themselves) */
  passThroughRefreshFunction?: (refreshToken: string) => Promise<AccessTokens>,
): Promise<matrix.MatrixClient> {
  // Handle backwards compatibility - old signature passed deviceName as string
  const opts: LoginOptions =
    typeof options === 'string' ? { deviceName: options } : options || {};

  // If a refresh callback was explicitly passed (for recursive refresh scenarios),
  // it takes precedence over any option in the LoginOptions
  if (passThroughRefreshFunction) {
    opts.tokenRefreshFunction = passThroughRefreshFunction;
  }
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
    // Note: SDK expects 'tokenRefreshFunction' (not 'refreshTokens' or 'refreshToken')
    // The SDK only calls this function when a 'refreshToken' string is also present.
    // Since Conduit doesn't use OAuth2 refresh tokens, we use a dummy string.
    if (opts.tokenRefreshFunction) {
      clientOpts.tokenRefreshFunction = opts.tokenRefreshFunction;
      // Store a dummy refresh token so the SDK actually calls our refresh function
      // Conduit doesn't use OAuth2, so this is just a trigger value
      clientOpts.refreshToken = 'conduit-dummy-refresh-token';
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
