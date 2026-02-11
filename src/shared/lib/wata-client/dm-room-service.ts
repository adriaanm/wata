/**
 * DMRoomService: Encapsulates all DM room management logic
 *
 * This service handles:
 * - DM room lookup (userId → roomId mapping)
 * - Deterministic room selection when multiple rooms exist
 * - m.direct account data management
 * - DM room creation (rare, only when no room exists)
 *
 * See PLAN.md for design rationale.
 */

import type { MatrixApi } from './matrix-api';
import type { SyncEngine, RoomState } from './sync-engine';
import type { Logger, Contact } from './types';

// ============================================================================
// No-op logger (default when no logger provided)
// ============================================================================

const noopLogger: Logger = {
  log: () => {},
  warn: () => {},
  error: () => {},
};

// ============================================================================
// DMRoomService Implementation
// ============================================================================

export class DMRoomService {
  private api: MatrixApi;
  private syncEngine: SyncEngine;
  private userId: string;
  private logger: Logger;

  /**
   * Primary room ID for each contact (deterministically selected).
   * This is THE room to use when sending messages.
   */
  private primaryRoomByContact: Map<string, string> = new Map();

  /**
   * All known room IDs for each contact (may be multiple due to race conditions).
   * Used for message consolidation and room detection.
   */
  private allRoomsByContact: Map<string, Set<string>> = new Map();

  /**
   * Reverse lookup: room ID → contact user ID.
   * Used for quick isDMRoom() checks and getContactForRoom().
   */
  private contactByRoom: Map<string, string> = new Map();

  constructor(
    api: MatrixApi,
    syncEngine: SyncEngine,
    userId: string,
    logger?: Logger
  ) {
    this.api = api;
    this.syncEngine = syncEngine;
    this.userId = userId;
    this.logger = logger ?? noopLogger;
  }

  // ==========================================================================
  // Lookup Methods (synchronous, cached)
  // ==========================================================================

  /**
   * Get the primary DM room ID for a contact (if known).
   * Returns null if no DM room exists in our cache.
   * Does NOT create a room or make network calls.
   */
  getDMRoomId(contactUserId: string): string | null {
    return this.primaryRoomByContact.get(contactUserId) ?? null;
  }

  /**
   * Get all known DM room IDs for a contact.
   * Returns empty array if none known.
   * Useful for message consolidation across duplicate rooms.
   */
  getAllDMRoomIds(contactUserId: string): string[] {
    const roomIds = this.allRoomsByContact.get(contactUserId);
    return roomIds ? Array.from(roomIds) : [];
  }

  /**
   * Check if a room ID is a known DM room.
   */
  isDMRoom(roomId: string): boolean {
    return this.contactByRoom.has(roomId);
  }

  /**
   * Get the contact user ID for a DM room (reverse lookup).
   * Returns null if the room isn't a known DM room.
   */
  getContactUserId(roomId: string): string | null {
    return this.contactByRoom.get(roomId) ?? null;
  }

  /**
   * Get Contact object for a DM room.
   * Returns null if not a DM room or contact not found.
   */
  getContactForRoom(roomId: string): Contact | null {
    // First try the cached mapping
    const contactUserId = this.contactByRoom.get(roomId);
    if (contactUserId) {
      // Cache hit - buildContactFromRoom always returns a Contact now
      return this.buildContactFromRoom(roomId, contactUserId);
    }

    // Fallback: Infer from room membership (recipient-side issue)
    // A DM room has exactly 2 members: current user and contact
    const room = this.syncEngine.getRoom(roomId);
    if (!room) return null;

    const joinedMembers = Array.from(room.members.values()).filter(
      (m) => m.membership === 'join'
    );

    if (joinedMembers.length !== 2) return null;

    const otherMember = joinedMembers.find((m) => m.userId !== this.userId);
    if (!otherMember) return null;

    // Verify this looks like a DM by checking is_direct flag
    if (!this.hasIsDirectFlag(room)) return null;

    return {
      user: {
        id: otherMember.userId,
        displayName: otherMember.displayName,
        avatarUrl: otherMember.avatarUrl,
      },
    };
  }

  // ==========================================================================
  // Creation Methods (async, makes network calls)
  // ==========================================================================

  /**
   * Ensure a DM room exists with the contact.
   * - First tries to find existing room via sync state
   * - Creates new room only if none found
   * - Updates m.direct and internal caches
   *
   * This is the ONLY method that should create DM rooms.
   */
  async ensureDMRoom(contactUserId: string): Promise<string> {
    this.logger.log(`[DMRoomService] ensureDMRoom for ${contactUserId}`);

    // Step 1: Check cache first (fast path)
    const cached = this.primaryRoomByContact.get(contactUserId);
    if (cached) {
      // Verify the room still exists and we're joined
      const room = this.syncEngine.getRoom(cached);
      if (room && room.members.get(this.userId)?.membership === 'join') {
        this.logger.log(`[DMRoomService] Using cached room ${cached}`);
        return cached;
      }
      // Room no longer valid, remove from cache
      this.removeRoomFromCache(cached);
    }

    // Step 2: Scan sync state for existing DM rooms
    const existingRoom = this.findExistingDMRoom(contactUserId);
    if (existingRoom) {
      this.logger.log(`[DMRoomService] Found existing room ${existingRoom}`);
      await this.updateMDirectForRoom(contactUserId, existingRoom);
      return existingRoom;
    }

    // Step 3: No existing room found, create new one
    this.logger.log(`[DMRoomService] Creating new DM room with ${contactUserId}`);
    const roomId = await this.createDMRoom(contactUserId);

    return roomId;
  }

  // ==========================================================================
  // Cache Management
  // ==========================================================================

  /**
   * Handle m.direct account data update.
   * Called by WataClient when account data changes.
   */
  handleMDirectUpdate(content: Record<string, string[]>): void {
    // m.direct format: { "@user:server": ["!roomId1", "!roomId2", ...] }
    for (const [contactUserId, roomIds] of Object.entries(content)) {
      if (!Array.isArray(roomIds) || roomIds.length === 0) continue;

      // Update all rooms mapping
      this.allRoomsByContact.set(contactUserId, new Set(roomIds));

      // Update reverse mapping
      for (const roomId of roomIds) {
        this.contactByRoom.set(roomId, contactUserId);
      }

      // Determine primary room (oldest by creation timestamp)
      const primaryRoomId = this.selectPrimaryRoom(roomIds);
      if (primaryRoomId) {
        this.primaryRoomByContact.set(contactUserId, primaryRoomId);
      }
    }
  }

  /**
   * Refresh cache from current sync state.
   * Called after sync batches to discover new DM rooms.
   */
  refreshFromSync(): void {
    const rooms = this.syncEngine.getRooms();

    for (const room of rooms) {
      // Skip rooms we're not joined to
      if (room.members.get(this.userId)?.membership !== 'join') continue;

      // Check if this is a 2-person DM room
      const joinedMembers = Array.from(room.members.values()).filter(
        (m) => m.membership === 'join'
      );
      if (joinedMembers.length !== 2) continue;

      const otherMember = joinedMembers.find((m) => m.userId !== this.userId);
      if (!otherMember) continue;

      // Verify is_direct flag
      if (!this.hasIsDirectFlag(room)) continue;

      // Add to cache
      const contactUserId = otherMember.userId;
      this.addRoomToCache(contactUserId, room.roomId);
    }
  }

  /**
   * Clear all cached state (on logout).
   */
  clear(): void {
    this.primaryRoomByContact.clear();
    this.allRoomsByContact.clear();
    this.contactByRoom.clear();
  }

  // ==========================================================================
  // Private Helpers
  // ==========================================================================

  /**
   * Find existing DM room with contact by scanning sync state.
   * Returns the primary room ID if found, null otherwise.
   */
  private findExistingDMRoom(contactUserId: string): string | null {
    const candidateRooms: { roomId: string; creationTs: number; messageCount: number }[] = [];
    const rooms = this.syncEngine.getRooms();

    for (const room of rooms) {
      // Skip if not joined
      if (room.members.get(this.userId)?.membership !== 'join') continue;

      // Check if this is a 2-person room with the target user
      const joinedMembers = Array.from(room.members.values()).filter(
        (m) => m.membership === 'join'
      );
      if (joinedMembers.length !== 2) continue;

      const hasTargetUser = joinedMembers.some((m) => m.userId === contactUserId);
      if (!hasTargetUser) continue;

      // Check is_direct flag and get creation timestamp
      const { isDirectRoom, creationTs } = this.getRoomDMInfo(room);

      // Only include valid DM rooms with timestamps
      if (isDirectRoom && creationTs !== null && creationTs > 0) {
        const messageCount = room.timeline.filter(
          (e) => e.type === 'm.room.message' && e.content?.msgtype === 'm.audio'
        ).length;
        candidateRooms.push({ roomId: room.roomId, creationTs, messageCount });
        this.logger.log(
          `[DMRoomService] Found candidate room ${room.roomId} (created: ${new Date(creationTs).toISOString()}, ${messageCount} msgs)`
        );
      }
    }

    if (candidateRooms.length === 0) return null;

    // Log warning if multiple rooms found
    if (candidateRooms.length > 1) {
      const roomList = candidateRooms
        .map((r) => `${r.roomId.slice(-12)} (${new Date(r.creationTs).toISOString().slice(0, 10)}, ${r.messageCount} msgs)`)
        .join(', ');
      this.logger.warn(
        `[DMRoomService] Multiple DM rooms with ${contactUserId}: ${roomList}. Selecting oldest.`
      );
    }

    // Sort by creation timestamp (oldest first), room ID as tiebreaker
    candidateRooms.sort((a, b) => {
      if (a.creationTs !== b.creationTs) return a.creationTs - b.creationTs;
      return a.roomId.localeCompare(b.roomId);
    });

    const primaryRoom = candidateRooms[0];
    this.logger.log(
      `[DMRoomService] Selected primary room ${primaryRoom.roomId} (${candidateRooms.length} candidates)`
    );

    // Update cache with all candidate rooms
    for (const room of candidateRooms) {
      this.addRoomToCache(contactUserId, room.roomId);
    }
    this.primaryRoomByContact.set(contactUserId, primaryRoom.roomId);

    return primaryRoom.roomId;
  }

  /**
   * Create a new DM room with the contact.
   */
  private async createDMRoom(contactUserId: string): Promise<string> {
    const response = await this.api.createRoom({
      is_direct: true,
      invite: [contactUserId],
      preset: 'trusted_private_chat',
      visibility: 'private',
    });

    const roomId = response.room_id;

    // Update m.direct account data
    await this.updateMDirectForRoom(contactUserId, roomId);

    // Add to cache
    this.addRoomToCache(contactUserId, roomId);
    this.primaryRoomByContact.set(contactUserId, roomId);

    return roomId;
  }

  /**
   * Update m.direct account data with a DM room.
   */
  private async updateMDirectForRoom(
    contactUserId: string,
    roomId: string
  ): Promise<void> {
    try {
      // Get current m.direct data
      let directData: Record<string, string[]> = {};
      try {
        directData = await this.api.getAccountData(this.userId, 'm.direct');
      } catch {
        // No existing data
      }

      // Add room to contact's list if not already there
      if (!directData[contactUserId]) {
        directData[contactUserId] = [];
      }
      if (!directData[contactUserId].includes(roomId)) {
        directData[contactUserId].push(roomId);
        await this.api.setAccountData(this.userId, 'm.direct', directData);
      }
    } catch (error) {
      this.logger.error(`[DMRoomService] Failed to update m.direct: ${error}`);
    }
  }

  /**
   * Select primary room from a list of room IDs (oldest wins).
   */
  private selectPrimaryRoom(roomIds: string[]): string | null {
    const roomsWithTs: { roomId: string; creationTs: number }[] = [];

    for (const roomId of roomIds) {
      const room = this.syncEngine.getRoom(roomId);
      if (!room) continue;

      // Get creation timestamp
      for (const event of room.timeline) {
        if (event.type === 'm.room.create' && event.origin_server_ts) {
          roomsWithTs.push({ roomId, creationTs: event.origin_server_ts });
          break;
        }
      }
    }

    if (roomsWithTs.length === 0) {
      // Fall back to first room if no timestamps available
      return roomIds[0] ?? null;
    }

    // Sort by creation timestamp (oldest first)
    roomsWithTs.sort((a, b) => a.creationTs - b.creationTs);
    return roomsWithTs[0].roomId;
  }

  /**
   * Add a room to the cache for a contact.
   */
  private addRoomToCache(contactUserId: string, roomId: string): void {
    // Update allRoomsByContact
    if (!this.allRoomsByContact.has(contactUserId)) {
      this.allRoomsByContact.set(contactUserId, new Set());
    }
    this.allRoomsByContact.get(contactUserId)!.add(roomId);

    // Update reverse mapping
    this.contactByRoom.set(roomId, contactUserId);
  }

  /**
   * Remove a room from all caches.
   */
  private removeRoomFromCache(roomId: string): void {
    const contactUserId = this.contactByRoom.get(roomId);
    if (!contactUserId) return;

    // Remove from allRoomsByContact
    const rooms = this.allRoomsByContact.get(contactUserId);
    if (rooms) {
      rooms.delete(roomId);
      if (rooms.size === 0) {
        this.allRoomsByContact.delete(contactUserId);
      }
    }

    // Remove from primaryRoomByContact if it's the primary
    if (this.primaryRoomByContact.get(contactUserId) === roomId) {
      this.primaryRoomByContact.delete(contactUserId);
    }

    // Remove reverse mapping
    this.contactByRoom.delete(roomId);
  }

  /**
   * Check if a room has the is_direct flag set.
   */
  private hasIsDirectFlag(room: RoomState): boolean {
    // Check if our own member event has is_direct flag (set by server when room is created as DM)
    const myMember = room.members.get(this.userId);
    if (myMember?.isDirect === true) {
      return true;
    }

    // Fallback: A 2-member room (current user + one other) is a DM
    // This handles rooms where we joined (not created via is_direct invite)
    const joinedMembers = Array.from(room.members.values()).filter(
      (m) => m.membership === 'join'
    );
    if (joinedMembers.length === 2) {
      const otherMember = joinedMembers.find((m) => m.userId !== this.userId);
      if (otherMember) {
        return true;
      }
    }

    // Timeline scan for is_direct (for legacy deduplication)
    for (const event of room.timeline) {
      if (
        event.type === 'm.room.member' &&
        event.state_key === this.userId &&
        event.content?.is_direct === true
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get DM-related info from a room (is_direct flag and creation timestamp).
   */
  private getRoomDMInfo(room: RoomState): { isDirectRoom: boolean; creationTs: number | null } {
    let isDirectRoom = false;
    let creationTs: number | null = null;

    for (const event of room.timeline) {
      if (event.type === 'm.room.create') {
        creationTs = event.origin_server_ts ?? null;
        if (event.content?.is_direct === true) {
          isDirectRoom = true;
        }
      }
      if (
        event.type === 'm.room.member' &&
        event.state_key === this.userId &&
        event.content?.is_direct === true
      ) {
        isDirectRoom = true;
      }
    }

    return { isDirectRoom, creationTs };
  }

  /**
   * Build a Contact object from room membership info.
   * Works for joined members as well as invited members (DM room with pending invite).
   */
  private buildContactFromRoom(roomId: string, contactUserId: string): Contact | null {
    const room = this.syncEngine.getRoom(roomId);
    if (!room) {
      return null;
    }

    const member = room.members.get(contactUserId);
    if (member) {
      return {
        user: {
          id: contactUserId,
          displayName: member.displayName,
          avatarUrl: member.avatarUrl,
        },
      };
    }

    // Member not in room members - use contactUserId as fallback
    // This handles cases where the invite hasn't propagated yet
    return {
      user: {
        id: contactUserId,
        displayName: contactUserId.split(':')[0].substring(1), // @bob:server -> bob
        avatarUrl: null,
      },
    };
  }
}
