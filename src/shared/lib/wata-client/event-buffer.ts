/**
 * EventBuffer: Buffers timeline events until room classification is known
 *
 * Handles the case where events arrive before we know if a room is a DM.
 * Events are buffered and processed once the room is classified.
 *
 * Lifecycle:
 * 1. Event arrives for unknown room type → buffer()
 * 2. Room classified as DM → flush() all buffered events
 * 3. Periodic cleanup → prune() old events
 */

import type { MatrixEvent } from './matrix-api';

export interface BufferedEvent {
  roomId: string;
  event: MatrixEvent;
  bufferedAt: number; // timestamp
}

export interface EventBufferStats {
  roomCount: number;
  eventCount: number;
  oldestEventAge: number; // ms
}

/**
 * Callback type for processing flushed events
 */
export type FlushCallback = (roomId: string, event: MatrixEvent) => void;

export class EventBuffer {
  private eventsByRoom: Map<string, BufferedEvent[]> = new Map();
  private maxAgeMs: number;
  private maxEventsPerRoom: number;

  /**
   * @param maxAgeMs - Maximum age of buffered events before pruning (default: 5 minutes)
   * @param maxEventsPerRoom - Maximum events to buffer per room (default: 100)
   */
  constructor(options?: { maxAgeMs?: number; maxEventsPerRoom?: number }) {
    this.maxAgeMs = options?.maxAgeMs ?? 5 * 60 * 1000; // 5 minutes
    this.maxEventsPerRoom = options?.maxEventsPerRoom ?? 100;
  }

  /**
   * Buffer an event for later processing.
   * Returns true if buffered, false if room already at capacity.
   */
  buffer(roomId: string, event: MatrixEvent): boolean {
    const now = Date.now();

    // Get or create room's event list
    let roomEvents = this.eventsByRoom.get(roomId);
    if (!roomEvents) {
      roomEvents = [];
      this.eventsByRoom.set(roomId, roomEvents);
    }

    // Check capacity
    if (roomEvents.length >= this.maxEventsPerRoom) {
      return false;
    }

    // Add event
    roomEvents.push({
      roomId,
      event,
      bufferedAt: now,
    });

    return true;
  }

  /**
   * Check if a room has buffered events.
   */
  hasBufferedEvents(roomId: string): boolean {
    const events = this.eventsByRoom.get(roomId);
    return events !== undefined && events.length > 0;
  }

  /**
   * Get count of buffered events for a room.
   */
  getBufferedCount(roomId: string): number {
    return this.eventsByRoom.get(roomId)?.length ?? 0;
  }

  /**
   * Flush all buffered events for a room.
   * Calls the provided callback for each event in order.
   * Returns the number of events flushed.
   */
  flush(roomId: string, callback: FlushCallback): number {
    const events = this.eventsByRoom.get(roomId);
    if (!events || events.length === 0) {
      return 0;
    }

    // Process events in order (oldest first - they're already sorted by arrival time)
    for (const buffered of events) {
      callback(buffered.roomId, buffered.event);
    }

    // Clear buffer for this room
    this.eventsByRoom.delete(roomId);

    return events.length;
  }

  /**
   * Discard all buffered events for a room without processing.
   * Returns the number of events discarded.
   */
  discard(roomId: string): number {
    const count = this.eventsByRoom.get(roomId)?.length ?? 0;
    this.eventsByRoom.delete(roomId);
    return count;
  }

  /**
   * Prune events older than maxAgeMs.
   * Returns the number of events pruned.
   */
  prune(): number {
    const now = Date.now();
    let prunedCount = 0;

    for (const [roomId, events] of this.eventsByRoom) {
      // Filter out old events
      const fresh = events.filter((e) => now - e.bufferedAt < this.maxAgeMs);
      prunedCount += events.length - fresh.length;

      if (fresh.length === 0) {
        this.eventsByRoom.delete(roomId);
      } else if (fresh.length !== events.length) {
        this.eventsByRoom.set(roomId, fresh);
      }
    }

    return prunedCount;
  }

  /**
   * Get statistics about the buffer.
   */
  getStats(): EventBufferStats {
    let eventCount = 0;
    let oldestAge = 0;
    const now = Date.now();

    for (const events of this.eventsByRoom.values()) {
      eventCount += events.length;
      if (events.length > 0) {
        const age = now - events[0].bufferedAt;
        if (age > oldestAge) {
          oldestAge = age;
        }
      }
    }

    return {
      roomCount: this.eventsByRoom.size,
      eventCount,
      oldestEventAge: oldestAge,
    };
  }

  /**
   * Clear all buffered events.
   */
  clear(): void {
    this.eventsByRoom.clear();
  }

  /**
   * Get all room IDs with buffered events.
   */
  getBufferedRoomIds(): string[] {
    return Array.from(this.eventsByRoom.keys());
  }
}
