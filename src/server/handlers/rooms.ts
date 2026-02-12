import type { ServerConfig } from '../config.js';
import type { Store, MatrixEvent } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

type Handler = (
  request: Request,
  store: Store,
  config: ServerConfig,
  params: Record<string, string>,
) => Promise<Response>;

// ── Helpers ───────────────────────────────────────────────────────

function getRoomMembers(
  store: Store,
  roomId: string,
  memberships: string[],
): string[] {
  const room = store.getRoom(roomId);
  if (!room) return [];
  const members: string[] = [];
  for (const [_key, ev] of room.state) {
    if (
      ev.type === 'm.room.member' &&
      memberships.includes(ev.content.membership as string)
    ) {
      members.push(ev.state_key!);
    }
  }
  return members;
}

function notifyRoomMembers(
  store: Store,
  roomId: string,
): void {
  const members = getRoomMembers(store, roomId, ['join', 'invite']);
  for (const uid of members) {
    store.notifyUser(uid);
  }
}

function addStateEvent(
  store: Store,
  roomId: string,
  sender: string,
  type: string,
  stateKey: string,
  content: Record<string, unknown>,
): MatrixEvent {
  return store.addEvent(roomId, {
    type,
    sender,
    room_id: roomId,
    origin_server_ts: Date.now(),
    content,
    state_key: stateKey,
  });
}

// ── handleCreateRoom ──────────────────────────────────────────────

export const handleCreateRoom: Handler = async (request, store, config) => {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const userId = auth.userId;

  const body = (await request.json()) as Record<string, unknown>;
  const visibility = body.visibility as string | undefined;
  const roomAliasName = body.room_alias_name as string | undefined;
  const name = body.name as string | undefined;
  const _topic = body.topic as string | undefined;
  const invite = body.invite as string[] | undefined;
  const preset = body.preset as string | undefined;
  const isDirect = body.is_direct as boolean | undefined;
  const initialState = body.initial_state as Array<{
    type: string;
    state_key: string;
    content: Record<string, unknown>;
  }> | undefined;
  const creationContent = body.creation_content as Record<string, unknown> | undefined;
  const powerLevelOverride = body.power_level_content_override as Record<string, unknown> | undefined;

  const roomId = store.createRoom(userId);

  // 1. m.room.create
  addStateEvent(store, roomId, userId, 'm.room.create', '', {
    creator: userId,
    room_version: '10',
    ...creationContent,
  });

  // 2. Apply preset
  const effectivePreset =
    preset ??
    (visibility === 'public' ? 'public_chat' : 'private_chat');

  if (
    effectivePreset === 'trusted_private_chat' ||
    effectivePreset === 'private_chat'
  ) {
    addStateEvent(store, roomId, userId, 'm.room.join_rules', '', {
      join_rule: 'invite',
    });
    addStateEvent(store, roomId, userId, 'm.room.history_visibility', '', {
      history_visibility: 'shared',
    });
    addStateEvent(store, roomId, userId, 'm.room.guest_access', '', {
      guest_access: 'can_join',
    });
  } else if (effectivePreset === 'public_chat') {
    addStateEvent(store, roomId, userId, 'm.room.join_rules', '', {
      join_rule: 'public',
    });
    addStateEvent(store, roomId, userId, 'm.room.history_visibility', '', {
      history_visibility: 'shared',
    });
    addStateEvent(store, roomId, userId, 'm.room.guest_access', '', {
      guest_access: 'forbidden',
    });
  }

  // 3. Power levels
  const usersMap: Record<string, number> = { [userId]: 100 };
  if (effectivePreset === 'trusted_private_chat' && invite) {
    for (const inv of invite) {
      usersMap[inv] = 100;
    }
  }
  let powerLevelContent: Record<string, unknown> = {
    users: usersMap,
    users_default: 0,
    events_default: 0,
    state_default: 50,
    ban: 50,
    kick: 50,
    redact: 50,
    invite: 0,
  };
  if (powerLevelOverride) {
    powerLevelContent = { ...powerLevelContent, ...powerLevelOverride };
  }
  addStateEvent(store, roomId, userId, 'm.room.power_levels', '', powerLevelContent);

  // 4. Creator join
  const localpart = userId.split(':')[0].slice(1);
  const userConfig = config.users.find((u) => u.localpart === localpart);
  addStateEvent(store, roomId, userId, 'm.room.member', userId, {
    membership: 'join',
    displayname: userConfig?.displayName ?? localpart,
    // Include is_direct flag for creator if room is created as direct
    ...(isDirect ? { is_direct: true } : {}),
  });

  // 5. Room name
  if (name) {
    addStateEvent(store, roomId, userId, 'm.room.name', '', { name });
  }

  // 6. Room alias
  if (roomAliasName) {
    const alias = `#${roomAliasName}:${config.serverName}`;
    store.setAlias(alias, roomId);
    addStateEvent(store, roomId, userId, 'm.room.canonical_alias', '', {
      alias,
    });
  }

  // 7. Initial state
  if (initialState) {
    for (const s of initialState) {
      addStateEvent(store, roomId, userId, s.type, s.state_key, s.content);
    }
  }

  // 8. Invites
  if (invite) {
    for (const invitedUserId of invite) {
      addStateEvent(store, roomId, userId, 'm.room.member', invitedUserId, {
        membership: 'invite',
        is_direct: isDirect || false,
      });
    }
  }

  // 9. Notify creator (so they see the new room in sync)
  store.notifyUser(userId);

  // 10. Notify invited users
  if (invite) {
    for (const invitedUserId of invite) {
      store.notifyUser(invitedUserId);
    }
  }

  return jsonResponse({ room_id: roomId });
};

// ── handleJoinRoom ────────────────────────────────────────────────

export const handleJoinRoom: Handler = async (request, store, config, params) => {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const userId = auth.userId;

  const roomIdOrAlias = params.roomIdOrAlias;
  let roomId: string;

  if (roomIdOrAlias.startsWith('#')) {
    const resolved = store.getRoomIdByAlias(roomIdOrAlias);
    if (!resolved) {
      return matrixError('M_NOT_FOUND', 'Room alias not found', 404);
    }
    roomId = resolved;
  } else {
    roomId = roomIdOrAlias;
  }

  return joinRoom(store, config, userId, roomId);
};

// ── handleJoinRoomById ────────────────────────────────────────────

export const handleJoinRoomById: Handler = async (request, store, config, params) => {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const userId = auth.userId;

  return joinRoom(store, config, userId, params.roomId);
};

async function joinRoom(
  store: Store,
  config: ServerConfig,
  userId: string,
  roomId: string,
): Promise<Response> {
  const room = store.getRoom(roomId);
  if (!room) {
    return matrixError('M_NOT_FOUND', 'Room not found', 404);
  }

  const membership = store.getMembership(roomId, userId);

  // Already joined
  if (membership === 'join') {
    return jsonResponse({ room_id: roomId });
  }

  // Check if user is invited or room is public
  if (membership !== 'invite') {
    const joinRulesEvent = room.state.get('m.room.join_rules\0');
    const joinRule = joinRulesEvent?.content?.join_rule;
    if (joinRule !== 'public') {
      return matrixError('M_FORBIDDEN', 'You are not invited to this room', 403);
    }
  }

  const localpart = userId.split(':')[0].slice(1);
  const userConfig = config.users.find((u) => u.localpart === localpart);

  addStateEvent(store, roomId, userId, 'm.room.member', userId, {
    membership: 'join',
    displayname: userConfig?.displayName ?? localpart,
  });

  notifyRoomMembers(store, roomId);

  return jsonResponse({ room_id: roomId });
}

// ── handleInvite ──────────────────────────────────────────────────

export const handleInvite: Handler = async (request, store, config, params) => {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const userId = auth.userId;
  const roomId = params.roomId;

  const membership = store.getMembership(roomId, userId);
  if (membership !== 'join') {
    return matrixError('M_FORBIDDEN', 'You are not in this room', 403);
  }

  const body = (await request.json()) as Record<string, unknown>;
  const targetUserId = body.user_id as string;
  if (!targetUserId) {
    return matrixError('M_BAD_JSON', 'Missing user_id', 400);
  }

  addStateEvent(store, roomId, userId, 'm.room.member', targetUserId, {
    membership: 'invite',
    ...(body.reason ? { reason: body.reason } : {}),
  });

  store.notifyUser(targetUserId);

  return jsonResponse({});
};

// ── handleResolveAlias ────────────────────────────────────────────

export const handleResolveAlias: Handler = async (_request, store, config, params) => {
  const alias = params.roomAlias;
  const roomId = store.getRoomIdByAlias(alias);
  if (!roomId) {
    return matrixError('M_NOT_FOUND', 'Room alias not found', 404);
  }

  return jsonResponse({
    room_id: roomId,
    servers: [config.serverName],
  });
};
