import type { Store } from './store.js';

// ── Debug Logging ─────────────────────────────────────────────────────

const DEBUG = process.env.WATA_SERVER_DEBUG === '1';

export namespace Debug {
  export function log(category: string, message: string, ...args: unknown[]): void {
    if (DEBUG) {
      const timestamp = new Date().toISOString().split('T')[1].slice(0, -1);
      console.error(`[${timestamp}] [${category}] ${message}`, ...args);
    }
  }

  export function request(method: string, path: string, userId?: string): void {
    if (DEBUG) {
      log('REQ', `${method} ${path}${userId ? ` (user: ${userId})` : ''}`);
    }
  }

  export function response(status: number, path: string): void {
    if (DEBUG) {
      log('RESP', `${status} ${path}`);
    }
  }

  export function error(category: string, message: string, error?: unknown): void {
    console.error(`[${category}] ${message}`, error ?? '');
  }
}

export function generateRandomId(): string {
  return crypto.randomUUID().replace(/-/g, '').slice(0, 12);
}

export function generateRoomId(serverName: string): string {
  return `!${generateRandomId()}:${serverName}`;
}

export function generateEventId(serverName: string): string {
  return `$${generateRandomId()}:${serverName}`;
}

export function generateDeviceId(): string {
  return crypto.randomUUID().replace(/-/g, '').slice(0, 8).toUpperCase();
}

export function generateAccessToken(localpart: string): string {
  return `syt_${localpart}_${generateRandomId()}`;
}

export function generateMediaId(): string {
  return crypto.randomUUID().replace(/-/g, '').slice(0, 24);
}

export function matrixError(
  errcode: string,
  error: string,
  status: number,
): Response {
  return new Response(JSON.stringify({ errcode, error }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export interface AuthResult {
  userId: string;
  deviceId: string;
}

export function authenticate(
  request: Request,
  store: Store,
): AuthResult | Response {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return matrixError('M_MISSING_TOKEN', 'Missing access token', 401);
  }

  const token = authHeader.slice('Bearer '.length);
  const device = store.getDeviceByToken(token);
  if (!device) {
    return matrixError('M_UNKNOWN_TOKEN', 'Unknown access token', 401);
  }

  return { userId: device.userId, deviceId: device.deviceId };
}

export function parsePathParams(
  pattern: string,
  path: string,
): Record<string, string> | null {
  const patternParts = pattern.split('/');
  const pathParts = path.split('/');

  if (patternParts.length !== pathParts.length) {
    return null;
  }

  const params: Record<string, string> = {};
  for (let i = 0; i < patternParts.length; i++) {
    const pp = patternParts[i];
    const actual = decodeURIComponent(pathParts[i]);
    if (pp.startsWith(':')) {
      params[pp.slice(1)] = actual;
    } else if (pp !== actual) {
      return null;
    }
  }

  return params;
}
