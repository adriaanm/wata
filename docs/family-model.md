# Family Model Architecture

## Overview

Wata presents a simple walkie-talkie interface: talk to a family member, or talk to everyone. This document describes how that experience maps to Matrix protocol concepts.

## Presentation Layer

What users see:

```
┌─────────────────────────┐
│  Contacts               │
├─────────────────────────┤
│  👨 Dad                 │
│  👩 Mom                 │
│  👧 Sister              │
├─────────────────────────┤
│  👨‍👩‍👧 Family            │
└─────────────────────────┘
```

- **Contacts**: Family members to send 1:1 voice messages
- **Family**: Broadcast channel for messages to everyone
- **Action**: Hold PTT → record → release → message sent

Users never see: room IDs, Matrix IDs, invitations, or sync state.

## Implementation Layer

### Matrix Concepts Used

| Wata Concept | Matrix Implementation |
|--------------|----------------------|
| Family membership | Room membership in family room |
| Contact name | Matrix profile display name |
| 1:1 conversation | DM room (created on-demand) |
| Broadcast | Message to family room |
| Voice message | `m.audio` event |

### Room Structure

```
Family Room (#family:server.local)
├── @alice:server.local (admin)
├── @bob:server.local
└── @charlie:server.local

DM Rooms (created on-demand)
├── !abc123 (alice ↔ bob)
├── !def456 (alice ↔ charlie)
└── !ghi789 (bob ↔ charlie)
```

### Family Room

The family room serves two purposes:

1. **Membership roster**: All family members are in this room. The app queries room membership to build the contact list.

2. **Broadcast channel**: Sending a voice message to "Family" sends an `m.audio` event to this room. All members receive it.

Room configuration:
- `preset: private_chat` (invite-only)
- `visibility: private` (not listed in directory)
- Name: "Family" (shown in UI)

### DM Rooms

Created on-demand when a user first messages another family member.

```typescript
// On first message to Bob:
const room = await client.createRoom({
  is_direct: true,
  invite: ['@bob:server.local'],
  preset: 'trusted_private_chat',
});
// Store in m.direct account data
// Send voice message to room
```

Subsequent messages reuse the existing DM room (looked up via `m.direct` account data).

### Contact List Derivation

```typescript
async function getContacts(): Promise<Contact[]> {
  const familyRoom = getFamilyRoom();
  const members = familyRoom.getJoinedMembers();

  return members
    .filter(m => m.userId !== myUserId)  // Exclude self
    .map(m => ({
      userId: m.userId,
      displayName: m.name,  // From Matrix profile
      avatarUrl: m.avatarUrl,
    }));
}
```

### Voice Message Flow

**1:1 Message:**
```
User holds PTT → Record audio → Release
                      ↓
            Upload to Matrix media repo
                      ↓
            Get/create DM room with recipient
                      ↓
            Send m.audio event to DM room
                      ↓
            Recipient receives via /sync
```

**Broadcast:**
```
User holds PTT on "Family" → Record audio → Release
                      ↓
            Upload to Matrix media repo
                      ↓
            Send m.audio event to family room
                      ↓
            All family members receive via /sync
```

## Admin Operations

For v1, admin operations happen via TUI or scripts. Future versions may have dedicated admin UI.

### Creating the Family

First-time setup (run once per server):

```typescript
// 1. Create family room
const familyRoom = await client.createRoom({
  name: 'Family',
  preset: 'private_chat',
  visibility: 'private',
});

// 2. Store room ID in well-known location
// (TBD: room alias, account data, or config)
```

### Adding a Family Member

```typescript
// 1. Create user account (via Conduit admin room or registration)
// 2. Invite to family room
await client.invite(familyRoomId, '@newmember:server.local');
// 3. New member accepts invite (or auto-accept)
// 4. New member appears in everyone's contact list
```

### Removing a Family Member

```typescript
// Kick from family room
await client.kick(familyRoomId, '@member:server.local', 'Removed from family');
// Member disappears from contact lists
// Existing DM rooms remain (for history) but no new messages
```

## Discovery

### Finding the Family Room

The app needs to know which room is "the family room." Options:

1. **Room alias**: Create `#family:server.local`, app looks for this alias
2. **Account data**: Store `{ familyRoomId: '!abc' }` in user's account data during provisioning
3. **Room tag**: Tag the room with `m.family` (custom tag), search for it

Recommendation: **Room alias** is simplest and works across devices/re-logins.

```typescript
async function getFamilyRoom(): Promise<Room> {
  const result = await client.getRoomIdForAlias('#family:server.local');
  return client.getRoom(result.room_id);
}
```

### Finding DM Rooms

Use standard Matrix `m.direct` account data:

```typescript
async function getDmRoom(userId: string): Promise<Room | null> {
  const directRooms = client.getAccountData('m.direct')?.getContent();
  const roomIds = directRooms?.[userId] || [];
  return roomIds.length > 0 ? client.getRoom(roomIds[0]) : null;
}
```

### DM Room Recipient-Side Handling

**Important:** `m.direct` is per-user account data. When Alice creates a DM room with Bob:

1. Alice creates room with `is_direct: true`, invites Bob
2. Alice updates **her** `m.direct` → `{'@bob:localhost': ['!roomid']}`
3. Bob receives invite and joins
4. **Bob's `m.direct` is NOT automatically updated**

If the recipient doesn't update their `m.direct`, they won't recognize the room as a DM. This causes problems:
- `isDirectRoom()` returns `false` for Bob
- Bob's contact list doesn't show the existing DM room
- If Bob tries to message Alice, he creates a **new** room (duplicate!)

**Solution:** When joining a room, check if the invite event had `is_direct: true`. If so, update the local user's `m.direct`:

```typescript
// When user joins a room, check if it's a DM
client.on(RoomMemberEvent.Membership, async (event, member) => {
  if (member.userId !== client.getUserId()) return;
  if (member.membership !== 'join') return;

  // Check if the invite had is_direct flag
  const dominated = event.getPrevContent()?.is_direct;
  if (isDirect) {
    const inviterId = event.getSender();
    await updateDirectRoomData(inviterId, event.getRoomId());
  }
});
```

This ensures both parties recognize the room as a DM and prevents duplicate room creation.

## Future Considerations

### Public Matrix Server Hosting

If families are hosted on shared servers (e.g., `wata.example.com`):

- Family rooms must be private and invite-only (already the case)
- Room alias should be unique per family: `#family-smith:wata.example.com`
- User registration should be controlled (invite-only or admin-approved)
- No changes to room structure needed

### Sub-groups

If needed later (e.g., "Parents only" channel):

- Create additional rooms with subset of members
- App could show these as additional broadcast channels
- Or migrate to Spaces (family space containing multiple rooms)

### E2E Encryption

When enabling Olm/Megolm encryption:

- Family room: Enable encryption, all members share keys
- DM rooms: Enable encryption (already supported by `trusted_private_chat` preset)
- Key backup and cross-signing become important for device management

## Related Documents

- [Voice Architecture](./voice.md) - Audio recording and playback
- [TUI Architecture](./tui-architecture.md) - Desktop client design
- [Roadmap](./roadmap.md) - Future work and priorities
