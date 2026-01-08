/**
 * Matrix Authentication Helper
 *
 * Shared login logic used by both the app and integration tests.
 * This ensures consistent authentication flow across all environments.
 */

import * as matrix from 'matrix-js-sdk';

import { createFixedFetch } from './fixed-fetch-api';

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

/**
 * Login to a Matrix homeserver using username and password
 *
 * @param baseUrl - The homeserver URL (e.g., 'http://localhost:8008')
 * @param username - The username (not the full Matrix ID)
 * @param password - The user's password
 * @param deviceName - Optional display name for this device/session
 * @returns MatrixClient configured with the authenticated session
 */
export async function loginToMatrix(
  baseUrl: string,
  username: string,
  password: string,
  deviceName?: string,
): Promise<matrix.MatrixClient> {
  console.log('[matrix-auth] loginToMatrix called:', {
    baseUrl,
    username,
    deviceName,
  });

  // Construct login request in Conduit-compatible format
  const loginRequest: MatrixLoginRequest = {
    identifier: {
      type: 'm.id.user',
      user: username,
    },
    password,
  };

  if (deviceName) {
    loginRequest.initial_device_display_name = deviceName;
  }

  console.log('[matrix-auth] Calling SDK login with request:', {
    type: 'm.login.password',
    identifier: loginRequest.identifier,
    hasPassword: !!password,
    deviceName: loginRequest.initial_device_display_name,
  });

  try {
    // Use direct fetch to avoid SDK's buggy URL construction in React Native
    const loginUrl = `${baseUrl}/_matrix/client/v3/login`;
    console.log('[matrix-auth] POST to:', loginUrl);

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

    console.log('[matrix-auth] Login successful:', {
      userId: loginResponse.user_id,
      deviceId: loginResponse.device_id,
    });

    // Create authenticated client with custom fetch that fixes URLs
    // and configuration suitable for Conduit server
    return matrix.createClient({
      baseUrl,
      accessToken: loginResponse.access_token,
      userId: loginResponse.user_id,
      deviceId: loginResponse.device_id,
      fetchFn: createFixedFetch(),
      // Reduce load on Conduit by disabling optional features
      useAuthorizationHeader: true,
      timelineSupport: true,
    });
  } catch (error) {
    console.error('[matrix-auth] Login failed:', error);
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
