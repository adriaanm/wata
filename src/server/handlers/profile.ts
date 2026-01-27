import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

interface ProfileData {
  displayname: string;
  avatar_url?: string;
}

const profiles = new Map<string, ProfileData>();

export function initProfiles(config: ServerConfig): void {
  profiles.clear();
  for (const user of config.users) {
    const userId = `@${user.localpart}:${config.serverName}`;
    profiles.set(userId, { displayname: user.displayName });
  }
}

export async function handleGetProfile(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const userId = params.userId;
  const profile = profiles.get(userId);
  if (!profile) {
    return matrixError('M_NOT_FOUND', 'User not found', 404);
  }

  const result: Record<string, string> = { displayname: profile.displayname };
  if (profile.avatar_url) {
    result.avatar_url = profile.avatar_url;
  }
  return jsonResponse(result);
}

export async function handleSetDisplayName(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot set displayname for other users', 403);
  }

  const body = (await request.json()) as { displayname: string };
  const profile = profiles.get(userId) ?? { displayname: '' };
  profile.displayname = body.displayname;
  profiles.set(userId, profile);

  return jsonResponse({});
}

export async function handleSetAvatarUrl(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const userId = params.userId;
  if (auth.userId !== userId) {
    return matrixError('M_FORBIDDEN', 'Cannot set avatar_url for other users', 403);
  }

  const body = (await request.json()) as { avatar_url: string };
  const profile = profiles.get(userId) ?? { displayname: '' };
  profile.avatar_url = body.avatar_url;
  profiles.set(userId, profile);

  return jsonResponse({});
}
