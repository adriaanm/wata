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

## Trust Model

Wata runs **one homeserver per family**, gated at the network layer (typically wireguard). Anyone who can reach the server is already authorized family — there is no untrusted population sharing the homeserver. This is a deliberate design choice: wata is not a Signal/WhatsApp competitor, it's a dedicated walkie-talkie for close-knit, trusted groups.

The implication for room configuration:

- **Family room** is `public_chat` / `visibility: public` / `join_rule: public`. Everyone on the server *is* family, so there's no reason to gate the broadcast channel behind invites. This also lets third-party Matrix clients (FluffyChat, Element, etc.) discover and join the family room through the standard public-rooms directory.
- **1:1 DM rooms** stay `trusted_private_chat` / `visibility: private` / `join_rule: invite`. Even inside the family, a DM between Alice and Bob shouldn't be visible to Charlie.

In other words: privacy between family members (DMs) is still enforced, but the server boundary *is* the family boundary.

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
- `preset: public_chat` (anyone on the server can join)
- `visibility: public` (listed in the public-rooms directory)
- `join_rule: public`
- Alias: `#family:<server>` (canonical handle)
- Name: "Family" (shown in UI)

The server network boundary (wireguard) *is* the trust boundary — see [Trust Model](#trust-model).

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
  preset: 'public_chat',
  visibility: 'public',
  room_alias_name: 'family',
});
```

### Adding a Family Member

```typescript
// 1. Create user account (via Conduit admin room or registration)
// 2. Invite to family room
await client.invite(familyRoomId, '@newmember:server.local');
// 3. New member auto-joins (invite is accepted automatically)
// 4. New member appears in everyone's contact list
```

**Auto-join behavior:** Since Wata runs in a trusted environment (family-owned server with controlled accounts), **all room invites are automatically accepted**. When a family member receives an invite, the client immediately joins without user interaction. This includes:
- Family room invites - for joining the family broadcast channel
- DM room invites - prevents duplicate room creation when another member initiates a DM

This simplifies onboarding - admins just need to invite new members, and they appear in everyone's contact list automatically. It also ensures DM conversations work bidirectionally without creating duplicate rooms.

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
// When user's membership changes, handle DM room tracking
client.on(RoomMemberEvent.Membership, async (event, member) => {
  if (member.userId !== client.getUserId()) return;

  // Auto-join all invites (trusted family environment)
  if (member.membership === 'invite') {
    await client.joinRoom(event.getRoomId());
    return;
  }

  // Update m.direct for DM rooms when joining
  if (member.membership === 'join') {
    const isDirect = event.getPrevContent()?.is_direct;
    if (isDirect) {
      const inviterId = event.getSender();
      await updateDirectRoomData(inviterId, event.getRoomId());
    }
  }
});
```

This ensures both parties recognize the room as a DM and prevents duplicate room creation. The membership handler auto-joins all invites in this trusted environment.

### The Race Condition Reality

**Note:** Even with the `m.direct` update logic above, duplicate DM rooms can still occur due to timing:

```
Alice creates DM → invites Bob → updates her m.direct
Bob calls getOrCreateDmRoom(alice) BEFORE invite arrives via sync
Bob's m.direct is empty → creates NEW room
Result: Two separate DM rooms exist
```

This is a **known Matrix protocol limitation** - `m.direct` is client-side data with no server-side enforcement. Even Element Web has this issue (see [matrix-js-sdk #2672](https://github.com/matrix-org/matrix-js-sdk/issues/2672)).

**Our mitigation:**
1. Auto-join all DM invites (reduces window for duplicates)
2. Scan for existing 2-person `is_direct` rooms before creating
3. Post-sync cleanup of `m.direct` entries
4. Tests use `waitForRoom()` to ensure invite propagation

For the walkie-talkie use case, occasional duplicate DMs are acceptable - users can use the family room broadcast if a 1:1 DM has issues. See [client-lib.md](./planning/client-lib.md#dm-room-idempotency-a-known-matrix-protocol-limitation) for detailed analysis.

## Future Considerations

### Shared / Multi-Family Hosting

The current model is **one server per family**, network-gated. If we ever need to host multiple families on a single shared server (e.g., a paid `wata.example.com` tier), the public-family-room assumption breaks — an untrusted population would share the server. In that case:

- Family rooms would need to flip back to `private_chat` / `join_rule: invite`.
- Room alias would need to be unique per family: `#family-smith:wata.example.com`.
- The wata-server's "list every public room" behavior (see `src/server/handlers/rooms.ts` → `handlePublicRooms`) would need per-family scoping.
- User registration would need to be controlled (invite-only or admin-approved).

Not planned — called out here so the assumption doesn't get accidentally broken by someone reading the one-server-per-family docs out of context.

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
