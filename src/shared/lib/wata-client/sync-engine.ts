/**
 * Sync Engine for WataClient
 *
 * Manages the Matrix sync loop and maintains in-memory state for rooms,
 * members, timeline events, and read receipts. Emits typed events for
 * state changes.
 *
 * This is the low-level sync layer - it maintains raw Matrix state without
 * mapping to domain concepts (that's WataClient's job).
 */

import type {
  MatrixApi,
  SyncResponse,
  JoinedRoomSync,
  InvitedRoomSync,
  LeftRoomSync,
  MatrixEvent,
  RoomSummary,
} from './matrix-api';
import type { Logger } from './types';

// ============================================================================
// State Types
// ============================================================================

/**
 * Information about a room member
 */
export interface MemberInfo {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  membership: 'join' | 'invite' | 'leave' | 'ban' | 'knock';
  /** True if this member event indicates the room is a direct message */
  isDirect?: boolean;
}

/**
 * In-memory state for a single room
 */
export interface RoomState {
  roomId: string;
  name: string;
  avatarUrl: string | null;
  /** Canonical alias for the room (e.g., #family:server) */
  canonicalAlias: string | null;
  /** Room summary (heroes, member counts) */
  summary?: RoomSummary;
  /** Unread notification counts */
  unreadNotifications?: {
    highlight_count: number;
    notification_count: number;
  };
  /** Map of userId -> member info */
  members: Map<string, MemberInfo>;
  /** Timeline events (chronological order, oldest to newest) */
  timeline: MatrixEvent[];
  /** Account data events for this room (type -> content) */
  accountData: Map<string, Record<string, any>>;
  /** Read receipts: eventId -> Set of userIds who read it */
  readReceipts: Map<string, Set<string>>;
  /** Pagination token for fetching older messages (from timeline.prev_batch) */
  prevBatch: string | null;
}

// ============================================================================
// Event Types
// ============================================================================

export interface SyncEngineEvents {
  /** Emitted after each successful sync cycle */
  synced: (nextBatch: string) => void;
  /** Emitted when a room's state is updated */
  roomUpdated: (roomId: string, room: RoomState) => void;
  /** Emitted when a new timeline event arrives */
  timelineEvent: (roomId: string, event: MatrixEvent) => void;
  /** Emitted when read receipts are updated */
  receiptUpdated: (roomId: string, eventId: string, userIds: Set<string>) => void;
  /** Emitted when a membership change occurs */
  membershipChanged: (roomId: string, userId: string, membership: string) => void;
  /** Emitted when global account data is updated */
  accountDataUpdated: (type: string, content: Record<string, any>) => void;
  /** Emitted when sync encounters an error */
  error: (error: Error) => void;
}

type SyncEngineEventName = keyof SyncEngineEvents;
type SyncEngineEventHandler = SyncEngineEvents[SyncEngineEventName];

// ============================================================================
// Sync Engine
// ============================================================================

// No-op logger (default when no logger provided)
const noopLogger: Logger = {
  log: () => {},
  warn: () => {},
  error: () => {},
};

export interface SyncEngineOptions {
  /** Long-poll timeout in milliseconds (default: 30000) */
  syncTimeoutMs?: number;
}

export class SyncEngine {
  private api: MatrixApi;
  private rooms: Map<string, RoomState> = new Map();
  private userId: string | null = null;
  private nextBatch: string | null = null;
  private isRunning = false;
  private syncLoopPromise: Promise<void> | null = null;
  private eventHandlers: Map<SyncEngineEventName, Set<Function>> = new Map();
  private logger: Logger;
  private syncTimeoutMs: number;

  constructor(api: MatrixApi, logger?: Logger, options?: SyncEngineOptions) {
    this.api = api;
    this.logger = logger ?? noopLogger;
    this.syncTimeoutMs = options?.syncTimeoutMs ?? 30000;
  }

  // ==========================================================================
  // Event Emitter
  // ==========================================================================

  on<K extends SyncEngineEventName>(
    event: K,
    handler: SyncEngineEvents[K]
  ): void {
    if (!this.eventHandlers.has(event)) {
      this.eventHandlers.set(event, new Set());
    }
    this.eventHandlers.get(event)!.add(handler as Function);
  }

  off<K extends SyncEngineEventName>(
    event: K,
    handler: SyncEngineEvents[K]
  ): void {
    const handlers = this.eventHandlers.get(event);
    if (handlers) {
      handlers.delete(handler as Function);
    }
  }

  private emit<K extends SyncEngineEventName>(
    event: K,
    ...args: Parameters<SyncEngineEvents[K]>
  ): void {
    const handlers = this.eventHandlers.get(event);
    if (handlers) {
      handlers.forEach((handler) => {
        try {
          (handler as any)(...args);
        } catch (error) {
          this.logger.error(`[SyncEngine] Error in ${event} handler: ${error}`);
        }
      });
    }
  }

  // ==========================================================================
  // Lifecycle
  // ==========================================================================

  /**
   * Set the current user ID (must be called after login)
   */
  setUserId(userId: string): void {
    this.userId = userId;
  }

  /**
   * Start the sync loop
   * Performs an initial sync before starting the background loop
   */
  async start(): Promise<void> {
    if (this.isRunning) {
      throw new Error('Sync engine already running');
    }

    if (!this.userId) {
      throw new Error('User ID not set - call setUserId() after login');
    }

    this.isRunning = true;
    this.logger.log('[SyncEngine] Starting initial sync');

    // Perform initial sync with a short timeout to get started quickly
    try {
      const response = await this.api.sync({
        timeout: 5000, // 5 second timeout for initial sync
      });
      this.processSyncResponse(response);
      this.nextBatch = response.next_batch;
      const roomCount = this.rooms.size;
      this.logger.log(`[SyncEngine] Initial sync complete: ${roomCount} rooms`);
      this.emit('synced', response.next_batch);
    } catch (error) {
      // If initial sync fails, we'll retry in the background loop
      this.logger.error(`[SyncEngine] Initial sync failed: ${error}`);
      const err = error instanceof Error ? error : new Error(String(error));
      this.emit('error', err);
    }

    // Start the background sync loop
    this.logger.log('[SyncEngine] Starting background sync loop');
    this.syncLoopPromise = this.runSyncLoop();
  }

  /**
   * Stop the sync loop
   */
  async stop(): Promise<void> {
    if (!this.isRunning) {
      return;
    }

    this.logger.log('[SyncEngine] Stopping sync loop');
    this.isRunning = false;

    // Don't wait for syncLoopPromise - it will exit on its own when isRunning is false
    // The sync loop checks isRunning at the start of each iteration
    // and will exit after the current long-poll times out or completes
    this.syncLoopPromise = null;
  }

  /**
   * Main sync loop - continuously polls the sync endpoint
   */
  private async runSyncLoop(): Promise<void> {
    let retryDelay = 1000; // Start with 1 second
    const maxRetryDelay = 60000; // Max 60 seconds

    while (this.isRunning) {
      try {
        const response = await this.api.sync({
          timeout: this.syncTimeoutMs,
          since: this.nextBatch ?? undefined,
        });

        // Process the sync response
        this.processSyncResponse(response);

        // Store next batch token for incremental sync
        this.nextBatch = response.next_batch;

        // Emit synced event
        this.emit('synced', response.next_batch);

        // Reset retry delay on success
        retryDelay = 1000;
      } catch (error) {
        // Emit error event
        const err = error instanceof Error ? error : new Error(String(error));
        this.logger.error(`[SyncEngine] Sync error: ${err.message}`);
        this.emit('error', err);

        // Stop if we're no longer running
        if (!this.isRunning) {
          break;
        }

        // Exponential backoff with jitter
        const jitter = Math.random() * 1000;
        await this.sleep(retryDelay + jitter);
        retryDelay = Math.min(retryDelay * 2, maxRetryDelay);
      }
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  // ==========================================================================
  // Sync Response Processing
  // ==========================================================================

  /**
   * Process a sync response and update internal state
   */
  processSyncResponse(response: SyncResponse): void {
    // Process global account data
    if (response.account_data?.events) {
      response.account_data.events.forEach((event) => {
        // Emit account data updated event for global account data
        this.emit('accountDataUpdated', event.type, event.content);
      });
    }

    // Process rooms
    if (response.rooms) {
      // Joined rooms
      if (response.rooms.join) {
        Object.entries(response.rooms.join).forEach(([roomId, roomData]) => {
          this.processJoinedRoom(roomId, roomData);
        });
      }

      // Invited rooms
      if (response.rooms.invite) {
        Object.entries(response.rooms.invite).forEach(([roomId, roomData]) => {
          this.processInvitedRoom(roomId, roomData);
        });
      }

      // Left rooms
      if (response.rooms.leave) {
        Object.entries(response.rooms.leave).forEach(([roomId, roomData]) => {
          this.processLeftRoom(roomId, roomData);
        });
      }
    }
  }

  /**
   * Process a joined room from sync response
   */
  private processJoinedRoom(roomId: string, roomData: JoinedRoomSync): void {
    // Get or create room state
    let room = this.rooms.get(roomId);
    const isNewRoom = !room;
    if (!room) {
      room = this.createEmptyRoom(roomId);
      this.rooms.set(roomId, room);
      this.logger.log(`[SyncEngine] New room discovered: ${roomId}`);
    }

    // Process state events (m.room.name, m.room.avatar, m.room.member, etc.)
    if (roomData.state?.events) {
      roomData.state.events.forEach((event) => {
        this.processStateEvent(room!, event);
      });
    }

    // Process state_after events (state changes between since and end of timeline)
    if (roomData.state_after?.events) {
      roomData.state_after.events.forEach((event) => {
        this.processStateEvent(room!, event);
      });
    }

    // Capture room summary
    if (roomData.summary) {
      room.summary = roomData.summary;
    }

    // Capture unread notifications
    if (roomData.unread_notifications) {
      room.unreadNotifications = roomData.unread_notifications;
    }

    // Process timeline events (new messages)
    if (roomData.timeline?.events) {
      this.logger.log(`[SyncEngine] Processing ${roomData.timeline.events.length} timeline events for room ${roomId}`);

      // Store prev_batch token for pagination
      if (roomData.timeline.prev_batch) {
        room.prevBatch = roomData.timeline.prev_batch;
      }

      // Log if timeline is limited (indicating potential message gap)
      if (roomData.timeline.limited) {
        this.logger.log(`[SyncEngine] Timeline limited for room ${roomId}, prev_batch: ${room.prevBatch?.slice(-12)}`);
      }

      roomData.timeline.events.forEach((event) => {
        // Skip if event already exists in timeline (prevent duplicates from incremental syncs)
        const exists = room!.timeline.some(e => e.event_id === event.event_id);
        if (exists) {
          this.logger.log(`[SyncEngine] Skipping duplicate event ${event.event_id?.slice(-12)}`);
          return;
        }

        // Add to timeline
        room!.timeline.push(event);

        // Process state events in timeline
        if (event.state_key !== undefined) {
          this.processStateEvent(room!, event);
        }

        // Log message events
        if (event.type === 'm.room.message') {
          this.logger.log(`[SyncEngine] Message event: ${event.content?.msgtype} from ${event.sender}`);
        }

        // Emit timeline event
        this.emit('timelineEvent', roomId, event);
      });
    }

    // Process ephemeral events (receipts, typing, etc.)
    if (roomData.ephemeral?.events) {
      roomData.ephemeral.events.forEach((event) => {
        if (event.type === 'm.receipt') {
          this.processReceiptEvent(room!, event);
        }
      });
    }

    // Process room account data
    if (roomData.account_data?.events) {
      roomData.account_data.events.forEach((event) => {
        room!.accountData.set(event.type, event.content);
      });
    }

    // Emit room updated event
    this.emit('roomUpdated', roomId, room);
  }

  /**
   * Process an invited room from sync response
   */
  private processInvitedRoom(
    roomId: string,
    roomData: InvitedRoomSync
  ): void {
    // Get or create room state
    let room = this.rooms.get(roomId);
    if (!room) {
      room = this.createEmptyRoom(roomId);
      this.rooms.set(roomId, room);
    }

    // Process stripped state events (limited info for invites)
    if (roomData.invite_state?.events) {
      roomData.invite_state.events.forEach((event) => {
        // Convert stripped state to full event format
        const fullEvent: MatrixEvent = {
          type: event.type,
          state_key: event.state_key,
          content: event.content,
          sender: event.sender,
        };
        this.processStateEvent(room!, fullEvent);
      });
    }

    // Emit room updated event
    this.emit('roomUpdated', roomId, room);
  }

  /**
   * Process a left room from sync response
   */
  private processLeftRoom(roomId: string, roomData: LeftRoomSync): void {
    // We still track left rooms to show final state
    let room = this.rooms.get(roomId);
    if (!room) {
      room = this.createEmptyRoom(roomId);
      this.rooms.set(roomId, room);
    }

    // Process state events
    if (roomData.state?.events) {
      roomData.state.events.forEach((event) => {
        this.processStateEvent(room!, event);
      });
    }

    // Process timeline events
    if (roomData.timeline?.events) {
      roomData.timeline.events.forEach((event) => {
        // Skip if event already exists in timeline (prevent duplicates from incremental syncs)
        const exists = room!.timeline.some(e => e.event_id === event.event_id);
        if (exists) {
          return;
        }

        room!.timeline.push(event);

        if (event.state_key !== undefined) {
          this.processStateEvent(room!, event);
        }

        this.emit('timelineEvent', roomId, event);
      });
    }

    // Emit room updated event
    this.emit('roomUpdated', roomId, room);
  }

  /**
   * Process a state event and update room state
   */
  private processStateEvent(room: RoomState, event: MatrixEvent): void {
    switch (event.type) {
      case 'm.room.name':
        room.name = event.content.name || '';
        break;

      case 'm.room.avatar':
        room.avatarUrl = event.content.url || null;
        break;

      case 'm.room.canonical_alias':
        room.canonicalAlias = event.content.alias || null;
        break;

      case 'm.room.member':
        if (event.state_key) {
          const userId = event.state_key;
          const membership = event.content.membership as MemberInfo['membership'];

          const member: MemberInfo = {
            userId,
            displayName: event.content.displayname || userId,
            avatarUrl: event.content.avatar_url || null,
            membership,
            isDirect: event.content.is_direct === true,
          };

          room.members.set(userId, member);

          // Emit membership change event
          this.emit('membershipChanged', room.roomId, userId, membership);
        }
        break;
    }
  }

  /**
   * Process read receipt events
   */
  private processReceiptEvent(room: RoomState, event: MatrixEvent): void {
    // Receipt event format:
    // {
    //   type: 'm.receipt',
    //   content: {
    //     '$eventId': {
    //       'm.read': {
    //         '@userId': { ts: 1234567890 }
    //       }
    //     }
    //   }
    // }

    Object.entries(event.content).forEach(([eventId, receiptData]) => {
      const readReceipts = (receiptData as any)['m.read'];
      if (readReceipts) {
        // Get or create receipt set for this event
        let receipts = room.readReceipts.get(eventId);
        if (!receipts) {
          receipts = new Set<string>();
          room.readReceipts.set(eventId, receipts);
        }

        // Add user IDs who read this event
        Object.keys(readReceipts).forEach((userId) => {
          receipts!.add(userId);
        });

        // Emit receipt updated event
        this.emit('receiptUpdated', room.roomId, eventId, receipts);
      }
    });
  }

  /**
   * Create an empty room state object
   */
  private createEmptyRoom(roomId: string): RoomState {
    return {
      roomId,
      name: '',
      avatarUrl: null,
      canonicalAlias: null,
      members: new Map(),
      timeline: [],
      accountData: new Map(),
      readReceipts: new Map(),
      prevBatch: null,
    };
  }

  // ==========================================================================
  // Timeline Pagination
  // ==========================================================================

  /**
   * Backfill older messages for a room using the /messages endpoint
   *
   * @param roomId - The room to backfill
   * @param limit - Maximum number of events to fetch (default: 50)
   * @returns Number of new events added to the timeline
   */
  async backfillRoom(roomId: string, limit = 50): Promise<number> {
    const room = this.rooms.get(roomId);
    if (!room) {
      throw new Error(`Room ${roomId} not found`);
    }

    if (!room.prevBatch) {
      this.logger.log(`[SyncEngine] No prev_batch token for room ${roomId}, cannot backfill`);
      return 0;
    }

    this.logger.log(`[SyncEngine] Backfilling room ${roomId} from ${room.prevBatch.slice(-12)} (limit: ${limit})`);

    try {
      const response = await this.api.getMessages(roomId, {
        from: room.prevBatch,
        dir: 'b', // Backward (older messages)
        limit,
      });

      this.logger.log(`[SyncEngine] Backfill response: ${response.chunk.length} events`);

      // Track how many new events were added
      let newEventsCount = 0;

      // Collect non-duplicate events to insert
      const eventsToInsert: MatrixEvent[] = [];

      // Process events in reverse order (the API returns them newest-first when dir=b)
      // We reverse to get oldest-first for proper chronological insertion
      const eventsOldestFirst = response.chunk.reverse();

      for (const event of eventsOldestFirst) {
        // Skip if event already exists in timeline
        const exists = room.timeline.some(e => e.event_id === event.event_id);
        if (exists) {
          this.logger.log(`[SyncEngine] Skipping duplicate backfilled event ${event.event_id?.slice(-12)}`);
          continue;
        }

        eventsToInsert.push(event);
        newEventsCount++;

        // Process state events
        if (event.state_key !== undefined) {
          this.processStateEvent(room, event);
        }
      }

      // Insert all events at the beginning in one operation
      // This maintains chronological order: [oldest backfilled...newest backfilled, ...existing timeline]
      room.timeline.unshift(...eventsToInsert);

      // Emit timeline events
      for (const event of eventsToInsert) {
        this.emit('timelineEvent', roomId, event);
      }

      // Update prev_batch for further pagination
      room.prevBatch = response.end || null;

      this.logger.log(`[SyncEngine] Backfill complete: ${newEventsCount} new events added, new prev_batch: ${room.prevBatch?.slice(-12) || 'none'}`);

      // Emit room updated event
      this.emit('roomUpdated', roomId, room);

      return newEventsCount;
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error));
      this.logger.error(`[SyncEngine] Backfill error for room ${roomId}: ${err.message}`);
      throw err;
    }
  }

  // ==========================================================================
  // State Access
  // ==========================================================================

  /**
   * Get the current user ID
   */
  getUserId(): string | null {
    return this.userId;
  }

  /**
   * Get a specific room's state
   */
  getRoom(roomId: string): RoomState | null {
    return this.rooms.get(roomId) ?? null;
  }

  /**
   * Get all rooms
   */
  getRooms(): RoomState[] {
    return Array.from(this.rooms.values());
  }

  /**
   * Get the next batch token (for resuming sync)
   */
  getNextBatch(): string | null {
    return this.nextBatch;
  }

  /**
   * Set the next batch token (for resuming sync)
   */
  setNextBatch(token: string): void {
    this.nextBatch = token;
  }

  /**
   * Clear all state (useful for logout)
   */
  clear(): void {
    this.rooms.clear();
    this.userId = null;
    this.nextBatch = null;
  }
}
