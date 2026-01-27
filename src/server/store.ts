import type { ServerConfig, UserConfig } from './config.js';
import {
  generateRoomId,
  generateEventId,
  generateDeviceId,
  generateAccessToken,
  generateMediaId,
} from './utils.js';

// ── Types ──────────────────────────────────────────────────────────

export interface MatrixEvent {
  event_id: string;
  type: string;
  sender: string;
  room_id: string;
  origin_server_ts: number;
  content: Record<string, unknown>;
  state_key?: string;
  unsigned?: Record<string, unknown>;
  /** Internal sequence number for sync ordering */
  _seq: number;
}

export interface Room {
  roomId: string;
  version: string;
  /** State map keyed by `${type}\0${stateKey}` */
  state: Map<string, MatrixEvent>;
  timeline: MatrixEvent[];
}

export interface Device {
  deviceId: string;
  userId: string;
  accessToken: string;
  displayName?: string;
}

export interface MediaItem {
  mediaId: string;
  data: ArrayBuffer;
  contentType: string;
  filename?: string;
}

export interface Receipt {
  userId: string;
  eventId: string;
  ts: number;
  receiptType: string;
  _seq: number;
}

export interface AccountDataItem {
  userId: string;
  roomId: string | null;
  type: string;
  content: Record<string, unknown>;
  _seq: number;
}

// ── Store ──────────────────────────────────────────────────────────

export class Store {
  private serverName: string;
  private users: Map<string, UserConfig> = new Map();
  private devices: Map<string, Device> = new Map(); // deviceId → Device
  private tokenIndex: Map<string, Device> = new Map(); // accessToken → Device
  private rooms: Map<string, Room> = new Map(); // roomId → Room
  private aliases: Map<string, string> = new Map(); // alias → roomId
  private media: Map<string, MediaItem> = new Map(); // mediaId → MediaItem
  private accountData: AccountDataItem[] = [];
  private receipts: Map<string, Receipt[]> = new Map(); // roomId → Receipt[]
  private globalSeq = 0;
  private waiters: Map<string, Array<() => void>> = new Map(); // userId → callbacks

  constructor(config: ServerConfig) {
    this.serverName = config.serverName;
    for (const user of config.users) {
      this.users.set(user.localpart, user);
    }
  }

  // ── User lookups ───────────────────────────────────────────────

  getUserByLocalpart(localpart: string): UserConfig | undefined {
    return this.users.get(localpart);
  }

  getUserId(localpart: string): string {
    return `@${localpart}:${this.serverName}`;
  }

  // ── Device / Auth ──────────────────────────────────────────────

  createDevice(userId: string, displayName?: string): Device {
    const deviceId = generateDeviceId();
    // extract localpart from userId
    const localpart = userId.split(':')[0].slice(1);
    const accessToken = generateAccessToken(localpart);
    const device: Device = { deviceId, userId, accessToken, displayName };
    this.devices.set(deviceId, device);
    this.tokenIndex.set(accessToken, device);
    return device;
  }

  getDeviceByToken(token: string): Device | undefined {
    return this.tokenIndex.get(token);
  }

  removeDevice(deviceId: string): void {
    const device = this.devices.get(deviceId);
    if (device) {
      this.tokenIndex.delete(device.accessToken);
      this.devices.delete(deviceId);
    }
  }

  // ── Room ops ───────────────────────────────────────────────────

  createRoom(_creatorId: string): string {
    const roomId = generateRoomId(this.serverName);
    const room: Room = {
      roomId,
      version: '10',
      state: new Map(),
      timeline: [],
    };
    this.rooms.set(roomId, room);
    return roomId;
  }

  getRoom(roomId: string): Room | undefined {
    return this.rooms.get(roomId);
  }

  getRoomsForUser(userId: string, membership: string): Room[] {
    const result: Room[] = [];
    for (const room of this.rooms.values()) {
      const m = this.getMembership(room.roomId, userId);
      if (m === membership) {
        result.push(room);
      }
    }
    return result;
  }

  getMembership(roomId: string, userId: string): string | null {
    const room = this.rooms.get(roomId);
    if (!room) return null;
    const key = `m.room.member\0${userId}`;
    const ev = room.state.get(key);
    if (!ev) return null;
    return (ev.content.membership as string) ?? null;
  }

  // ── Event ops ──────────────────────────────────────────────────

  addEvent(
    roomId: string,
    event: Omit<MatrixEvent, 'event_id' | '_seq'>,
  ): MatrixEvent {
    const room = this.rooms.get(roomId);
    if (!room) throw new Error(`Room not found: ${roomId}`);

    this.globalSeq++;
    const fullEvent: MatrixEvent = {
      ...event,
      event_id: generateEventId(this.serverName),
      _seq: this.globalSeq,
    };

    room.timeline.push(fullEvent);

    // Update state map if this is a state event
    if (fullEvent.state_key !== undefined) {
      const key = `${fullEvent.type}\0${fullEvent.state_key}`;
      room.state.set(key, fullEvent);
    }

    return fullEvent;
  }

  getEventById(roomId: string, eventId: string): MatrixEvent | undefined {
    const room = this.rooms.get(roomId);
    if (!room) return undefined;
    return room.timeline.find((e) => e.event_id === eventId);
  }

  // ── Timeline ───────────────────────────────────────────────────

  getTimelineEvents(roomId: string, sinceSeq?: number): MatrixEvent[] {
    const room = this.rooms.get(roomId);
    if (!room) return [];
    if (sinceSeq === undefined) return room.timeline;
    return room.timeline.filter((e) => e._seq > sinceSeq);
  }

  // ── Aliases ────────────────────────────────────────────────────

  setAlias(alias: string, roomId: string): void {
    this.aliases.set(alias, roomId);
  }

  getRoomIdByAlias(alias: string): string | undefined {
    return this.aliases.get(alias);
  }

  // ── Media ──────────────────────────────────────────────────────

  storeMedia(
    data: ArrayBuffer,
    contentType: string,
    filename?: string,
  ): string {
    const mediaId = generateMediaId();
    this.media.set(mediaId, { mediaId, data, contentType, filename });
    return mediaId;
  }

  getMedia(mediaId: string): MediaItem | null {
    return this.media.get(mediaId) ?? null;
  }

  // ── Account Data ───────────────────────────────────────────────

  setAccountData(
    userId: string,
    type: string,
    content: Record<string, unknown>,
    roomId?: string,
  ): void {
    this.globalSeq++;
    const rid = roomId ?? null;

    // Replace existing if present
    const idx = this.accountData.findIndex(
      (a) => a.userId === userId && a.type === type && a.roomId === rid,
    );
    const item: AccountDataItem = {
      userId,
      roomId: rid,
      type,
      content,
      _seq: this.globalSeq,
    };
    if (idx >= 0) {
      this.accountData[idx] = item;
    } else {
      this.accountData.push(item);
    }
  }

  getAccountData(
    userId: string,
    type: string,
    roomId?: string,
  ): AccountDataItem | undefined {
    const rid = roomId ?? null;
    return this.accountData.find(
      (a) => a.userId === userId && a.type === type && a.roomId === rid,
    );
  }

  getAllAccountData(
    userId: string,
    roomId?: string,
  ): AccountDataItem[] {
    const rid = roomId ?? null;
    return this.accountData.filter(
      (a) => a.userId === userId && a.roomId === rid,
    );
  }

  getAccountDataSince(userId: string, sinceSeq: number): AccountDataItem[] {
    return this.accountData.filter(
      (a) => a.userId === userId && a._seq > sinceSeq,
    );
  }

  // ── Receipts ───────────────────────────────────────────────────

  setReceipt(
    roomId: string,
    receiptType: string,
    userId: string,
    eventId: string,
  ): void {
    this.globalSeq++;
    if (!this.receipts.has(roomId)) {
      this.receipts.set(roomId, []);
    }
    const list = this.receipts.get(roomId)!;

    // Replace existing receipt of same type for same user
    const idx = list.findIndex(
      (r) => r.userId === userId && r.receiptType === receiptType,
    );
    const receipt: Receipt = {
      userId,
      eventId,
      ts: Date.now(),
      receiptType,
      _seq: this.globalSeq,
    };
    if (idx >= 0) {
      list[idx] = receipt;
    } else {
      list.push(receipt);
    }
  }

  getReceipts(roomId: string): Receipt[] {
    return this.receipts.get(roomId) ?? [];
  }

  getReceiptsSince(roomId: string, sinceSeq: number): Receipt[] {
    return (this.receipts.get(roomId) ?? []).filter(
      (r) => r._seq > sinceSeq,
    );
  }

  // ── Sync ───────────────────────────────────────────────────────

  getGlobalSeq(): number {
    return this.globalSeq;
  }

  waitForEvents(userId: string, timeoutMs: number): Promise<void> {
    return new Promise<void>((resolve) => {
      let settled = false;

      const done = () => {
        if (settled) return;
        settled = true;
        // Remove this callback from waiters
        const list = this.waiters.get(userId);
        if (list) {
          const idx = list.indexOf(callback);
          if (idx >= 0) list.splice(idx, 1);
        }
        resolve();
      };

      const callback = done;

      if (!this.waiters.has(userId)) {
        this.waiters.set(userId, []);
      }
      this.waiters.get(userId)!.push(callback);

      setTimeout(done, timeoutMs);
    });
  }

  notifyUser(userId: string): void {
    const list = this.waiters.get(userId);
    if (list) {
      const callbacks = [...list];
      list.length = 0;
      for (const cb of callbacks) {
        cb();
      }
    }
  }
}
