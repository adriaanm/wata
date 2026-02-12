import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

export function handleMessages(
  request: Request,
  store: Store,
  _config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const { userId } = auth;

  const { roomId } = params;
  const room = store.getRoom(roomId);
  if (!room) {
    return matrixError('M_NOT_FOUND', 'Room not found', 404);
  }

  const membership = store.getMembership(roomId, userId);
  if (membership !== 'join') {
    return matrixError('M_FORBIDDEN', 'You are not in this room', 403);
  }

  const url = new URL(request.url);
  const from = url.searchParams.get('from') ?? undefined;
  const to = url.searchParams.get('to');
  const limit = parseInt(url.searchParams.get('limit') ?? '10', 10);
  const dir = url.searchParams.get('dir'); // 'f' for forward, 'b' for backward

  // Get all timeline events
  let events = store.getTimelineEvents(roomId);

  // Determine if we're doing reverse (backward) pagination
  const reverse = dir === 'b' || dir === null;

  // Handle 'from' token - for pagination, this is the anchor point
  // For backward pagination (dir='b'), 'from' should be the newest event we already have
  // We return events OLDER than 'from'
  // For forward pagination (dir='f'), 'from' should be the oldest event we already have
  // We return events NEWER than 'from'
  if (from) {
    const fromIndex = events.findIndex(e => e.event_id === from);
    if (fromIndex === -1) {
      // from token not found - could be from a different server or invalid
      // Return empty result rather than error
      return jsonResponse({
        start: from,
        end: from,
        chunk: [],
      });
    }

    if (reverse) {
      // Backward pagination: return events BEFORE from (older events)
      events = events.slice(0, fromIndex);
    } else {
      // Forward pagination: return events AFTER from (newer events)
      events = events.slice(fromIndex + 1);
    }
  }

  // Handle 'to' token - limits the range
  if (to) {
    const toIndex = events.findIndex(e => e.event_id === to);
    if (toIndex === -1) {
      return matrixError('M_NOT_FOUND', 'to token not found', 404);
    }
    events = events.slice(0, toIndex + 1);
  }

  // Apply limit
  // For backward pagination, we want newest events first (reverse at end)
  // For forward pagination, we want oldest events first (normal order)
  if (reverse) {
    // Take the last 'limit' events and reverse them
    events = events.slice(-limit).reverse();
  } else {
    events = events.slice(0, limit);
  }

  // Build response (Matrix CS API v3 format)
  const response: Record<string, unknown> = {
    start: from || events[0]?.event_id || 's0',
    end: events[events.length - 1]?.event_id || from || 's0',
    chunk: [
      {
        room_id: roomId,
        events: events.map(e => {
          const { _seq, unsigned, ...rest } = e;
          return {
            ...rest,
            unsigned: {
              ...unsigned,
              age: Date.now() - e.origin_server_ts,
            },
          };
        }),
      },
    ],
  };

  return jsonResponse(response);
}
