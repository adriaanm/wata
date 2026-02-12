import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import {
  authenticate,
  matrixError,
  jsonResponse,
} from '../utils.js';

function notifyRoomMembers(store: Store, roomId: string): void {
  const room = store.getRoom(roomId);
  if (!room) return;
  for (const [, event] of room.state) {
    if (
      event.type === 'm.room.member' &&
      event.content.membership === 'join'
    ) {
      store.notifyUser(event.state_key!);
    }
  }
}

export function handleSendEvent(
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return Promise.resolve(auth);
  const { userId, deviceId } = auth;

  const { roomId, eventType, txnId } = params;

  const membership = store.getMembership(roomId, userId);

  if (membership !== 'join') {
    return Promise.resolve(
      matrixError('M_FORBIDDEN', `User ${userId} is not in the room (${membership})`, 403),
    );
  }

  // Check txnId idempotency
  const existing = store.getDeviceTxnId(deviceId, txnId);
  if (existing) {
    return Promise.resolve(jsonResponse({ event_id: existing }));
  }

  return request.json().then((body: Record<string, unknown>) => {
    const event = store.addEvent(roomId, {
      type: eventType,
      sender: userId,
      room_id: roomId,
      origin_server_ts: Date.now(),
      content: body,
      unsigned: { transaction_id: txnId },
    });

    notifyRoomMembers(store, roomId);
    store.setDeviceTxnId(deviceId, txnId, event.event_id);

    return jsonResponse({ event_id: event.event_id });
  });
}

export function handleRedactEvent(
  request: Request,
  store: Store,
  _config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return Promise.resolve(auth);
  const { userId } = auth;

  const { roomId, eventId, txnId } = params;

  if (store.getMembership(roomId, userId) !== 'join') {
    return Promise.resolve(
      matrixError('M_FORBIDDEN', 'User is not in the room', 403),
    );
  }

  return request.json().then((body: Record<string, unknown>) => {
    const target = store.getEventById(roomId, eventId);
    if (!target) {
      return matrixError('M_NOT_FOUND', 'Event not found', 404);
    }

    // Add the redaction event to the timeline
    const redactionEvent = store.addEvent(roomId, {
      type: 'm.room.redaction',
      sender: userId,
      room_id: roomId,
      origin_server_ts: Date.now(),
      content: {
        reason: body.reason,
      },
      unsigned: { transaction_id: txnId },
    });

    // Redact the target event
    target.content = {};
    target.unsigned = {
      ...target.unsigned,
      redacted_because: redactionEvent,
    };

    notifyRoomMembers(store, roomId);

    return jsonResponse({ event_id: redactionEvent.event_id });
  });
}
