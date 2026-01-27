import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

function notifyRoomMembers(store: Store, roomId: string): void {
  const room = store.getRoom(roomId);
  if (!room) return;
  for (const [, event] of room.state) {
    if (event.type === 'm.room.member' && event.content.membership === 'join') {
      store.notifyUser(event.state_key!);
    }
  }
}

export async function handleReceipt(
  request: Request,
  store: Store,
  _config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const { roomId, receiptType, eventId } = params;

  const membership = store.getMembership(roomId, auth.userId);
  if (membership !== 'join') {
    return matrixError('M_FORBIDDEN', 'User is not joined to room', 403);
  }

  store.setReceipt(roomId, receiptType, auth.userId, eventId);
  notifyRoomMembers(store, roomId);

  return jsonResponse({});
}
