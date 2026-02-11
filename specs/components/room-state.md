# RoomState - Room State Model

## Overview

RoomState is the in-memory representation of a Matrix room maintained by SyncEngine. It aggregates room metadata (name, avatar, alias), membership, timeline events, and read receipts. This is a data structure spec, not a service.

## Current Test Coverage

**test/integration/matrix.test.ts**
- Implicitly tests RoomState via getDirectRooms(), getMessageCount()
- Tests: room.name, room.isDirect, room.roomId

**test/integration/voice-message-flow.test.ts**
- Implicitly tests timeline storage via getVoiceMessages()

**test/integration/read-receipts.test.ts**
- Implicitly tests readReceipts map via playedBy field

**test/integration/edge-cases.test.ts**
- Tests members map via membership queries

## Responsibilities

- Store room metadata (name, avatar, canonical alias)
- Store room membership (user ID → MemberInfo)
- Store timeline events in chronological order
- Store read receipts (event ID → set of user IDs)
- Store room-specific account data
- Provide efficient lookup for members, events, receipts

## Data Structure

```typescript
interface RoomState {
  roomId: string
  name: string
  avatarUrl: string | null
  canonicalAlias: string | null
  summary?: RoomSummary
  unreadNotifications?: {
    highlight_count: number
    notification_count: number
  }
  members: Map<string, MemberInfo>
  timeline: MatrixEvent[]
  accountData: Map<string, Record<string, any>>
  readReceipts: Map<string, Set<string>>
}

interface MemberInfo {
  userId: string
  displayName: string
  avatarUrl: string | null
  membership: 'join' | 'invite' | 'leave' | 'ban' | 'knock'
}

interface RoomSummary {
  'm.heroes'?: string[]
  'm.joined_member_count'?: number
  'm.invited_member_count'?: number
}
```

## Invariants

1. **Room ID Uniqueness**: roomId is unique across all RoomState instances
2. **Member Map Consistency**: members.get(userId).userId === userId
3. **Timeline Ordering**: timeline events are chronologically ordered (oldest to newest)
4. **Event ID Uniqueness**: No duplicate event_id in timeline array
5. **Receipt Accumulation**: readReceipts.get(eventId) is append-only (users only added, never removed)
6. **Membership State**: Only one membership state per user (latest state wins)
7. **State Event Idempotency**: Applying same state event twice has no effect

## State Updates

### Metadata Updates (State Events)

**m.room.name:**
- Updates `room.name`
- Latest event wins (no merge)

**m.room.avatar:**
- Updates `room.avatarUrl`
- Latest event wins

**m.room.canonical_alias:**
- Updates `room.canonicalAlias`
- Used for family room detection

### Membership Updates (State Events)

**m.room.member:**
- **state_key**: User ID
- **content.membership**: 'join' | 'invite' | 'leave' | 'ban' | 'knock'
- **content.displayname**: Display name (optional)
- **content.avatar_url**: Avatar URL (optional)
- **Update**: members.set(userId, memberInfo)
- **No Removal**: Left/banned members remain in map (membership field updated)

### Timeline Updates

**New Event Arrival:**
1. Check if event.event_id already in timeline
2. If duplicate, skip (deduplication)
3. If new, append to timeline array
4. If state event (has state_key), also update room state

### Receipt Updates

**m.receipt Event:**
- **Format**: { eventId: { 'm.read': { userId: { ts } } } }
- **Update**: readReceipts.get(eventId).add(userId)
- **No Removal**: Receipts never removed

### Account Data Updates

**Room Account Data:**
- Stored in `room.accountData` map
- Key: event type (e.g., "m.fully_read")
- Value: event content
- **Update**: accountData.set(type, content)

## State Lifecycle

### Room Creation (Empty State)

```typescript
{
  roomId: "!xyz:server",
  name: "",
  avatarUrl: null,
  canonicalAlias: null,
  members: new Map(),
  timeline: [],
  accountData: new Map(),
  readReceipts: new Map()
}
```

### Room Population (During Sync)

1. **state.events**: Initial room state (m.room.name, m.room.member, etc.)
2. **state_after.events**: State changes after timeline (membership updates)
3. **timeline.events**: New messages and state events
4. **ephemeral.events**: Read receipts (not persisted in timeline)
5. **account_data.events**: Room-specific settings

### Room Removal

Rooms are NOT removed from SyncEngine.rooms map, even after leaving. The membership state is updated to 'leave', but room state is preserved.

## Query Patterns

### Member Lookup

```typescript
const member = room.members.get(userId)
if (member && member.membership === 'join') {
  // User is joined
}
```

### Find Event

```typescript
const event = room.timeline.find(e => e.event_id === targetEventId)
```

### Get Read Receipts

```typescript
const userIds = room.readReceipts.get(eventId) || new Set()
const hasRead = userIds.has(userId)
```

### Filter Voice Messages

```typescript
const voiceMessages = room.timeline.filter(e =>
  e.type === 'm.room.message' &&
  e.content.msgtype === 'm.audio' &&
  !e.unsigned?.redacted_because
)
```

### Get Joined Members

```typescript
const joinedMembers = Array.from(room.members.values())
  .filter(m => m.membership === 'join')
```

## Known Limitations

1. **Unbounded Growth**: Timeline grows without limit (no automatic pruning)
2. **No Backfill**: Only forward sync, no historical message loading
3. **No State Reset**: No handling of state resets (rare server-side operation)
4. **Receipt Storage**: Receipts stored for all events, even deleted ones
5. **Member List Growth**: Left/banned members never removed from map
6. **No Lazy Loading**: All members loaded into memory (inefficient for large rooms)

## Related Specs

- [SyncEngine](./sync-engine.md) - Creates and maintains RoomState objects
- [WataClient](./wata-client.md) - Queries RoomState to build domain objects
- [Incremental Sync Flow](../flows/incremental-sync.md) - How RoomState is updated
