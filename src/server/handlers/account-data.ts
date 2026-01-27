import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

export async function handleGetAccountData(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot access account data for other users', 403);
  }

  const item = store.getAccountData(userId, params.type);
  if (!item) {
    return matrixError('M_NOT_FOUND', 'Account data not found', 404);
  }

  return jsonResponse(item.content);
}

export async function handleSetAccountData(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot set account data for other users', 403);
  }

  const body = (await request.json()) as Record<string, unknown>;
  store.setAccountData(userId, params.type, body);
  store.notifyUser(userId);

  return jsonResponse({});
}

export async function handleGetRoomAccountData(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot access account data for other users', 403);
  }

  const item = store.getAccountData(userId, params.type, params.roomId);
  if (!item) {
    return matrixError('M_NOT_FOUND', 'Account data not found', 404);
  }

  return jsonResponse(item.content);
}

export async function handleSetRoomAccountData(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot set account data for other users', 403);
  }

  const body = (await request.json()) as Record<string, unknown>;
  store.setAccountData(userId, params.type, body, params.roomId);
  store.notifyUser(userId);

  return jsonResponse({});
}
