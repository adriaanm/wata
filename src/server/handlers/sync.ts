import type { ServerConfig } from '../config.js';
import type { Store, MatrixEvent, Receipt } from '../store.js';
import { authenticate, jsonResponse, Debug } from '../utils.js';

function stripSeqAndAddAge(event: MatrixEvent): Record<string, unknown> {
  const { _seq, ...rest } = event;
  return {
    ...rest,
    unsigned: {
      ...rest.unsigned,
      age: Date.now() - event.origin_server_ts,
    },
  };
}

function formatReceipts(receipts: Receipt[]): Record<string, unknown>[] {
  if (receipts.length === 0) return [];

  // Group by eventId, then receiptType, then userId
  const grouped: Record<string, Record<string, Record<string, { ts: number }>>> = {};
  for (const r of receipts) {
    if (!grouped[r.eventId]) grouped[r.eventId] = {};
    if (!grouped[r.eventId][r.receiptType]) grouped[r.eventId][r.receiptType] = {};
    grouped[r.eventId][r.receiptType][r.userId] = { ts: r.ts };
  }

  return [{ type: 'm.receipt', content: grouped }];
}

function getHeroes(store: Store, roomId: string, userId: string): string[] {
  const room = store.getRoom(roomId);
  if (!room) return [];
  const heroes: string[] = [];
  for (const ev of room.state.values()) {
    if (
      ev.type === 'm.room.member' &&
      (ev.content.membership === 'join' || ev.content.membership === 'invite') &&
      ev.state_key !== userId
    ) {
      heroes.push(ev.state_key!);
      if (heroes.length >= 5) break;
    }
  }
  return heroes;
}

function getMemberCounts(store: Store, roomId: string): { joined: number; invited: number } {
  const room = store.getRoom(roomId);
  if (!room) return { joined: 0, invited: 0 };
  let joined = 0;
  let invited = 0;
  for (const ev of room.state.values()) {
    if (ev.type === 'm.room.member') {
      if (ev.content.membership === 'join') joined++;
      else if (ev.content.membership === 'invite') invited++;
    }
  }
  return { joined, invited };
}

function buildSyncResponse(
  store: Store,
  userId: string,
  sinceSeq: number | undefined,
  fullState: boolean,
): Record<string, unknown> {
  const join: Record<string, unknown> = {};
  const invite: Record<string, unknown> = {};

  if (sinceSeq === undefined || fullState) {
    // Initial sync
    const joinedRooms = store.getRoomsForUser(userId, 'join');
    for (const room of joinedRooms) {
      const counts = getMemberCounts(store, room.roomId);
      const heroes = getHeroes(store, room.roomId, userId);
      const stateEvents = Array.from(room.state.values()).map(stripSeqAndAddAge);
      const timelineEvents = room.timeline.map(stripSeqAndAddAge);
      const receipts = store.getReceipts(room.roomId);
      const roomAccountData = store.getAllAccountData(userId, room.roomId);

      join[room.roomId] = {
        summary: {
          'm.heroes': heroes,
          'm.joined_member_count': counts.joined,
          'm.invited_member_count': counts.invited,
        },
        state: { events: stateEvents },
        timeline: { events: timelineEvents, limited: false, prev_batch: 's0' },
        ephemeral: { events: formatReceipts(receipts) },
        account_data: {
          events: roomAccountData.map((a) => ({ type: a.type, content: a.content })),
        },
        unread_notifications: { highlight_count: 0, notification_count: 0 },
      };
    }

    const invitedRooms = store.getRoomsForUser(userId, 'invite');
    for (const room of invitedRooms) {
      const strippedEvents = Array.from(room.state.values()).map((ev) => ({
        type: ev.type,
        state_key: ev.state_key,
        content: ev.content,
        sender: ev.sender,
      }));
      invite[room.roomId] = {
        invite_state: { events: strippedEvents },
      };
    }

    const globalAccountData = store.getAllAccountData(userId);
    return {
      next_batch: 's' + store.getGlobalSeq(),
      rooms: { join, invite, leave: {} },
      account_data: {
        events: globalAccountData.map((a) => ({ type: a.type, content: a.content })),
      },
    };
  }

  // Incremental sync
  const joinedRooms = store.getRoomsForUser(userId, 'join');

  for (const room of joinedRooms) {
    const newEvents = store.getTimelineEvents(room.roomId, sinceSeq);
    // Get ALL current receipts, not just new ones - receipts should always be included
    // in ephemeral events even if unchanged, per spec
    const allReceipts = store.getReceipts(room.roomId);

    if (newEvents.length === 0 && allReceipts.length === 0) continue;

    // For full_state, include all state events; otherwise only state events that changed
    let stateEvents: ReturnType<typeof stripSeqAndAddAge>[];
    if (fullState) {
      const roomState = store.getRoom(room.roomId);
      stateEvents = roomState ? Array.from(roomState.state.values()).map(stripSeqAndAddAge) : [];
    } else {
      stateEvents = newEvents
        .filter((e) => e.state_key !== undefined)
        .map(stripSeqAndAddAge);
    }
    const timelineEvents = newEvents.map(stripSeqAndAddAge);
    const counts = getMemberCounts(store, room.roomId);
    const heroes = getHeroes(store, room.roomId, userId);

    join[room.roomId] = {
      summary: {
        'm.heroes': heroes,
        'm.joined_member_count': counts.joined,
        'm.invited_member_count': counts.invited,
      },
      state: { events: stateEvents },
      timeline: {
        events: timelineEvents,
        limited: false,
        prev_batch: 's' + sinceSeq,
      },
      ephemeral: { events: formatReceipts(allReceipts) },
      account_data: { events: [] },
      unread_notifications: { highlight_count: 0, notification_count: 0 },
    };
  }

  // Newly invited rooms
  const invitedRooms = store.getRoomsForUser(userId, 'invite');
  for (const room of invitedRooms) {
    // Check if the invite event is new (after sinceSeq)
    const memberKey = `m.room.member\0${userId}`;
    const inviteEvent = room.state.get(memberKey);
    if (!inviteEvent || inviteEvent._seq <= sinceSeq) continue;

    const strippedEvents = Array.from(room.state.values()).map((ev) => ({
      type: ev.type,
      state_key: ev.state_key,
      content: ev.content,
      sender: ev.sender,
    }));
    invite[room.roomId] = {
      invite_state: { events: strippedEvents },
    };
  }

  const newAccountData = store
    .getAccountDataSince(userId, sinceSeq)
    .filter((a) => a.roomId === null);

  return {
    next_batch: 's' + store.getGlobalSeq(),
    rooms: { join, invite, leave: {} },
    account_data: {
      events: newAccountData.map((a) => ({ type: a.type, content: a.content })),
    },
  };
}

function hasChanges(response: Record<string, unknown>): boolean {
  const rooms = response.rooms as Record<string, Record<string, unknown>>;
  const join = rooms.join as Record<string, unknown>;
  const invite = rooms.invite as Record<string, unknown>;
  const accountData = response.account_data as { events: unknown[] };
  return (
    Object.keys(join).length > 0 ||
    Object.keys(invite).length > 0 ||
    accountData.events.length > 0
  );
}

export async function handleSync(
  request: Request,
  store: Store,
  _config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;
  const { userId } = auth;

  const url = new URL(request.url);
  const since = url.searchParams.get('since') ?? undefined;
  const timeout = parseInt(url.searchParams.get('timeout') ?? '0', 10) || 0;
  const fullState = url.searchParams.get('full_state') === 'true';
  const _setPresence = url.searchParams.get('set_presence') ?? undefined;
  const _filter = url.searchParams.get('filter') ?? undefined;

  const sinceSeq = since !== undefined ? parseInt(since.replace(/^s/, ''), 10) : undefined;

  Debug.log('SYNC', `Sync request for ${userId}, since: ${since}, timeout: ${timeout}, full_state: ${fullState}`);

  let response = buildSyncResponse(store, userId, sinceSeq, fullState);

  // Long-poll: if incremental sync with no changes and timeout > 0
  if (sinceSeq !== undefined && !hasChanges(response) && timeout > 0) {
    Debug.log('SYNC', `Long-polling for ${userId}, timeout: ${timeout}ms`);
    await store.waitForEvents(userId, timeout);
    response = buildSyncResponse(store, userId, sinceSeq, fullState);
  }

  const joinedRooms = Object.keys((response.rooms as Record<string, unknown>)?.join as Record<string, unknown> ?? {});
  Debug.log('SYNC', `Sync response for ${userId}: next_batch=${response.next_batch}, joined_rooms=${joinedRooms.length}`);

  return jsonResponse(response);
}
