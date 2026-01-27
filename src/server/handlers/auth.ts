import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError, Debug } from '../utils.js';

export async function handleGetLoginFlows(
  _request: Request,
  _store: Store,
  _config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  return jsonResponse({ flows: [{ type: 'm.login.password' }] });
}

export async function handlePostLogin(
  request: Request,
  store: Store,
  config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  const body = await request.json();
  Debug.log('AUTH', `Login attempt with body:`, body);

  // Extract username from identifier or deprecated top-level user field
  let localpart: string | undefined;
  if (body.identifier?.type === 'm.id.user') {
    localpart = body.identifier.user;
  } else if (body.user) {
    localpart = body.user;
  }

  if (!localpart) {
    Debug.log('AUTH', `Login failed: missing user identifier`);
    return matrixError('M_FORBIDDEN', 'Missing user identifier', 403);
  }

  const user = store.getUserByLocalpart(localpart);
  if (!user) {
    Debug.log('AUTH', `Login failed: unknown user ${localpart}`);
    return matrixError('M_FORBIDDEN', 'Invalid username or password', 403);
  }

  if (user.password !== body.password) {
    Debug.log('AUTH', `Login failed: wrong password for ${localpart}`);
    return matrixError('M_FORBIDDEN', 'Invalid username or password', 403);
  }

  const userId = store.getUserId(localpart);
  const device = store.createDevice(
    userId,
    body.initial_device_display_name,
  );

  Debug.log('AUTH', `Login success: ${userId}, device ${device.deviceId}`);
  return jsonResponse({
    user_id: userId,
    access_token: device.accessToken,
    device_id: device.deviceId,
    home_server: config.serverName,
  });
}

export async function handlePostLogout(
  request: Request,
  store: Store,
  _config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  store.removeDevice(auth.deviceId);
  return jsonResponse({});
}

export async function handleWhoami(
  request: Request,
  store: Store,
  _config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  return jsonResponse({
    user_id: auth.userId,
    device_id: auth.deviceId,
  });
}
