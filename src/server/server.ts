// Type imports
import type { ServerConfig } from './config.js';
import type { Store } from './store.js';

// Handler imports
import {
  handleGetAccountData,
  handleSetAccountData,
  handleGetRoomAccountData,
  handleSetRoomAccountData,
} from './handlers/account-data.js';
import {
  handleGetLoginFlows,
  handlePostLogin,
  handlePostLogout,
  handleWhoami,
} from './handlers/auth.js';
import { handleSendEvent, handleRedactEvent } from './handlers/events.js';
import { handleMessages } from './handlers/messages.js';
import { handleUpload, handleDownload, handleClientDownload } from './handlers/media.js';
import {
  handleGetProfile,
  handleSetDisplayName,
  handleSetAvatarUrl,
  initProfiles,
} from './handlers/profile.js';
import { handleReceipt } from './handlers/receipts.js';
import {
  handleCreateRoom,
  handleJoinRoom,
  handleJoinRoomById,
  handleInvite,
  handlePublicRooms,
  handleResolveAlias,
} from './handlers/rooms.js';
import { handleSync } from './handlers/sync.js';

// Utility imports
import { jsonResponse, matrixError, Debug } from './utils.js';

type Handler = (
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
) => Promise<Response>;

interface Route {
  method: string;
  pattern: string;
  handler: Handler;
}

function matchRoute(
  pattern: string,
  path: string,
): Record<string, string> | null {
  const patternParts = pattern.split('/');
  const pathParts = path.split('/');

  if (patternParts.length !== pathParts.length) return null;

  const params: Record<string, string> = {};
  for (let i = 0; i < patternParts.length; i++) {
    const pp = patternParts[i];
    const actual = pathParts[i];
    if (pp.startsWith(':')) {
      // URL-decode path parameters (room IDs contain ':', event IDs contain '$')
      params[pp.slice(1)] = decodeURIComponent(actual);
    } else if (pp !== actual) {
      return null;
    }
  }
  return params;
}

const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'Access-Control-Allow-Headers':
    'Origin, X-Requested-With, Content-Type, Accept, Authorization',
};

function addCorsHeaders(response: Response): Response {
  const newHeaders = new Headers(response.headers);
  for (const [key, value] of Object.entries(CORS_HEADERS)) {
    newHeaders.set(key, value);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: newHeaders,
  });
}

export function createRouter(
  store: Store,
  config: ServerConfig,
): (request: Request) => Promise<Response> {
  // Initialize profile store
  initProfiles(config);

  const routes: Route[] = [
    // ── Versions ───────────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/versions',
      handler: async () =>
        jsonResponse({
          versions: ['v1.1', 'v1.2', 'v1.3', 'v1.4', 'v1.5', 'v1.6'],
          unstable_features: {},
        }),
    },

    // ── Auth ───────────────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/login',
      handler: handleGetLoginFlows,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/login',
      handler: handlePostLogin,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/logout',
      handler: handlePostLogout,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/account/whoami',
      handler: handleWhoami,
    },

    // ── Sync ───────────────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/sync',
      handler: handleSync,
    },

    // ── Rooms ──────────────────────────────────────────────────
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/createRoom',
      handler: handleCreateRoom,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/rooms/:roomIdOrAlias/join',
      handler: handleJoinRoom,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/join/:roomIdOrAlias',
      handler: handleJoinRoom,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/rooms/:roomId/join',
      handler: handleJoinRoomById,
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/rooms/:roomId/invite',
      handler: handleInvite,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v1/directory/room/:roomAlias',
      handler: handleResolveAlias,
    },

    // ── Events ─────────────────────────────────────────────────
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/rooms/:roomId/send/:eventType/:txnId',
      handler: handleSendEvent,
    },
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/rooms/:roomId/redact/:eventId/:txnId',
      handler: handleRedactEvent,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/rooms/:roomId/messages',
      handler: handleMessages,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v1/rooms/:roomId/messages',
      handler: handleMessages,
    },

    // ── Media ──────────────────────────────────────────────────
    {
      method: 'POST',
      pattern: '/_matrix/media/v3/upload',
      handler: handleUpload,
    },
    {
      method: 'GET',
      pattern: '/_matrix/media/v3/download/:serverName/:mediaId',
      handler: handleDownload,
    },
    {
      method: 'GET',
      pattern: '/_matrix/media/v1/download/:serverName/:mediaId',
      handler: handleDownload,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v1/media/download/:serverName/:mediaId',
      handler: handleClientDownload,
    },

    // ── Profile ────────────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/profile/:userId',
      handler: handleGetProfile,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v2/profile/:userId',
      handler: handleGetProfile,
    },
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/profile/:userId/displayname',
      handler: handleSetDisplayName,
    },
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/profile/:userId/avatar_url',
      handler: handleSetAvatarUrl,
    },

    // ── Account Data ───────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/user/:userId/account_data/:type',
      handler: handleGetAccountData,
    },
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/user/:userId/account_data/:type',
      handler: handleSetAccountData,
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/user/:userId/rooms/:roomId/account_data/:type',
      handler: handleGetRoomAccountData,
    },
    {
      method: 'PUT',
      pattern: '/_matrix/client/v3/user/:userId/rooms/:roomId/account_data/:type',
      handler: handleSetRoomAccountData,
    },

    // ── Receipts ───────────────────────────────────────────────
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/rooms/:roomId/receipt/:receiptType/:eventId',
      handler: handleReceipt,
    },

    // ── Public rooms ───────────────────────────────────────────
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/publicRooms',
      handler: handlePublicRooms,
    },

    // ── Filters (stub) ─────────────────────────────────────────
    // Clients like FluffyChat create a sync filter on startup. We
    // don't honor filter contents — /sync returns everything — but
    // we do need to hand back an id so the client can reference it.
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/user/:userId/filter',
      handler: async () => jsonResponse({ filter_id: '0' }),
    },
    {
      method: 'GET',
      pattern: '/_matrix/client/v3/user/:userId/filter/:filterId',
      handler: async () => jsonResponse({}),
    },

    // ── E2EE keys (no-op stubs) ────────────────────────────────
    // Wata-server does not implement end-to-end encryption. These
    // stubs exist so clients like FluffyChat/Element can complete
    // their post-login device-key handshake and proceed to /sync.
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/keys/query',
      handler: async () =>
        jsonResponse({
          device_keys: {},
          master_keys: {},
          self_signing_keys: {},
          user_signing_keys: {},
          failures: {},
        }),
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/keys/device_signing/upload',
      handler: async () => jsonResponse({}),
    },
    {
      method: 'POST',
      pattern: '/_matrix/client/v3/keys/upload',
      handler: async (request) => {
        // Echo back per-algorithm counts of the one-time keys the client
        // just uploaded. matrix-dart-sdk (FluffyChat) rejects the response
        // if the reported counts don't match what it sent.
        const body = (await request.json().catch(() => ({}))) as {
          one_time_keys?: Record<string, unknown>;
          fallback_keys?: Record<string, unknown>;
        };
        const counts: Record<string, number> = {};
        for (const keyId of Object.keys(body.one_time_keys ?? {})) {
          const algo = keyId.split(':')[0];
          counts[algo] = (counts[algo] ?? 0) + 1;
        }
        if (!('signed_curve25519' in counts)) counts.signed_curve25519 = 0;
        Debug.log('KEYS', `upload: one_time_key_counts=`, counts);
        return jsonResponse({ one_time_key_counts: counts });
      },
    },
  ];

  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    Debug.request(method, path);

    // Handle CORS preflight
    if (method === 'OPTIONS') {
      Debug.response(204, path);
      return addCorsHeaders(new Response(null, { status: 204 }));
    }

    // Try to match a route
    let pathMatched = false;
    for (const route of routes) {
      const params = matchRoute(route.pattern, path);
      if (params !== null) {
        pathMatched = true;
        if (route.method === method) {
          const response = await route.handler(request, store, config, params);
          Debug.response(response.status, path);
          return addCorsHeaders(response);
        }
      }
    }

    if (pathMatched) {
      const response = matrixError('M_UNRECOGNIZED', 'Method not allowed', 405);
      Debug.response(405, path);
      return addCorsHeaders(response);
    }

    const response = matrixError('M_UNRECOGNIZED', `Unrecognized request: ${method} ${path}`, 404);
    Debug.response(404, path);
    return addCorsHeaders(response);
  };
}
