# Onboarding & Contact Discovery - Research Notes

## Problem Statement

Wata is a private walkie-talkie app for small groups (families). When running on a fresh server:
- Users need to see each other in their contact lists
- Primary communication is 1:1 DMs
- The server is not shared with users outside the group
- New members added to the group should appear in everyone's contact list

## Matrix Protocol Constraints

### No Native Contacts Concept
Matrix is room-based, not contact-based. There's no built-in "friends list" or "roster" like XMPP. Contacts must be modeled on top of rooms.

### User Discovery is Limited
- `/_matrix/client/v3/user_directory/search` requires a search query
- **Cannot list all users on a homeserver** - no bulk endpoint exists
- Users only appear in directory if they share a room with the searcher
- Privacy-conscious design prevents user enumeration

### DMs are Just Rooms
- A DM is a room with `is_direct: true` in account data
- Stored per-user via `m.direct` account data event
- Not enforced by server - purely client interpretation

### Conduit Admin is Minimal
- Uses "Admin Room" for commands, not REST API
- No built-in endpoint to list all users
- First registered user becomes admin automatically

---

## Architectural Options

### Option A: Family Space as Contact Source

Use a Matrix Space as the membership roster.

```
Family Space (private, invite-only)
├── Alice (member)
├── Bob (member)
└── Charlie (member)
```

**How it works:**
1. Admin creates "Family Space" on first run
2. Admin invites new family members to the space
3. App queries space membership to build contact list
4. App creates DM rooms on-demand when user selects a contact

**Pros:**
- Uses standard Matrix primitives
- Natural hierarchy (could have sub-spaces: "Kids", "Parents")
- Space can also contain group chats if needed later
- Membership changes sync automatically via Matrix

**Cons:**
- Requires understanding Matrix Spaces
- Extra step to "start conversation" vs pre-created DMs
- Space invites need to be accepted

---

### Option B: Family Room as Hidden Roster

Create a hidden "family coordination room" that everyone joins.

```
#family-roster:server.local (private, hidden from UI)
├── Alice (member)
├── Bob (member)
└── Charlie (member)
```

**How it works:**
1. Admin creates hidden room on first run
2. New members get invited to this room
3. App reads room membership as contact list
4. DMs created on-demand

**Pros:**
- Simpler than Spaces (just a room)
- Room membership = contact list
- Could also use this room for admin announcements

**Cons:**
- Hack - using rooms for roster isn't idiomatic Matrix
- Still need invitation flow

---

### Option C: Pre-Provisioned DMs

Admin pre-creates all DM rooms before distributing devices.

```
Alice ←→ Bob (DM room pre-created)
Alice ←→ Charlie (DM room pre-created)
Bob ←→ Charlie (DM room pre-created)
```

**How it works:**
1. Admin creates all user accounts
2. Admin runs provisioning script that creates all DM pairs
3. Devices get credentials and see all contacts immediately
4. Adding new user = admin runs script again

**Pros:**
- Simplest for end users (zero config)
- Contacts appear immediately
- Works with current codebase structure

**Cons:**
- Admin burden: n(n-1)/2 rooms for n users
- Adding one user requires creating DMs with all existing users
- Less "natural" Matrix usage

---

### Option D: Auto-Connect on Registration

Server-side or admin-triggered automation.

**How it works:**
1. When new user registers/is created
2. Appservice or admin script:
   - Queries all existing "family" users
   - Creates DMs between new user and each existing user
3. New user sees all contacts on first login

**Pros:**
- Automatic - no manual DM creation
- User experience is seamless

**Cons:**
- Requires custom tooling (appservice or script)
- Need way to identify "family" vs "system" users
- Conduit doesn't have great appservice support

---

### Option E: Hybrid - Space + Auto-DM

Combine Space membership with automatic DM creation.

**How it works:**
1. Admin creates Family Space
2. Admin invites new member to Space
3. On joining Space, automation creates DMs with all Space members
4. Contact list = Space members (with pre-created DM rooms)

**Pros:**
- Best of both worlds
- Space provides clear membership
- DMs ready immediately
- Could extend to multiple spaces/groups later

**Cons:**
- Most complex to implement
- Requires automation layer

---

## Questions for Clarification

### Admin Experience

1. **Who provisions new users?** Is there a designated "family admin" (parent), or should any member be able to invite others?

2. **What's the admin interface?** Options:
   - Command-line tool (dev-friendly, quick to build)
   - Web dashboard (more accessible, more work)
   - TUI admin mode (consistent with existing TUI)
   - Element client (already works, requires Matrix knowledge)

3. **User creation flow:** Should admin:
   - Create accounts with passwords, then distribute credentials?
   - Create accounts, generate QR codes for device provisioning?
   - Generate invite links that allow self-registration?

### User Experience

4. **First boot experience:** When a new user first opens the app:
   - Should they see all family contacts immediately?
   - Or go through a "joining" flow (accept invites, etc)?

5. **Starting conversations:** Should:
   - All DM rooms be pre-created (open app → see all chats)?
   - Users select contact → DM created on-demand?
   - First message to contact = creation of DM?

6. **Contact naming:** Should users:
   - See Matrix display names (global, set by each user)?
   - See admin-assigned names ("Mom", "Dad", "Grandma")?
   - Be able to set their own nicknames for contacts?

### Device Management

7. **Multi-device:** Can one family member have multiple devices?
   - Same account on phone + handheld?
   - Or one account per device?

8. **Device loss/replacement:** How to handle:
   - Lost device (revoke access?)
   - New device for existing user (re-provision?)

### Group Dynamics

9. **Group chat:** Besides 1:1 DMs, should there be:
   - A single "Family Group" chat?
   - Multiple group chats (Parents, Kids, Everyone)?
   - No group chats initially?

10. **Visibility:** Should all members see all other members?
    - Or could there be hidden members (admin accounts)?
    - Parent-only groups hidden from kids?

### Server Model

11. **Deployment assumption:** Is the server:
    - Self-hosted (Conduit on home server, Raspberry Pi)?
    - VPS (DigitalOcean, etc)?
    - Could it eventually be a hosted service?

12. **Federation:** Should the family server:
    - Be isolated (no federation)?
    - Federate with matrix.org (interop with Element)?
    - Federate only with other Wata servers?

---

## Recommendation

Based on the constraints (small group, private server, 1:1 focus), I lean toward:

**Option E (Hybrid)** for v1, simplified:

1. **Family Space** as the membership source of truth
2. **Admin CLI tool** for user/space management
3. **Auto-DM creation** when users join the space
4. **Minimal UI** - app just shows contacts derived from space membership

This gives us:
- Clean membership model (space = family)
- Immediate contact availability (auto-created DMs)
- Room for growth (sub-spaces, group chats)
- Standard Matrix primitives (interop with Element possible)

For the prototype/v0, we could start with **Option C (Pre-Provisioned)** since the test infrastructure already creates DMs this way.

---

## Next Steps

1. Clarify questions above with stakeholder
2. Draft architecture doc based on decisions
3. Design admin tool/CLI
4. Design UI flows for end users
5. Implement incrementally

---

## References

- [Matrix Spaces spec](https://spec.matrix.org/latest/client-server-api/#spaces)
- [Conduit admin room docs](https://docs.conduit.rs/)
- Current test setup: `test/integration/helpers/TestOrchestrator.ts`
- Existing Matrix service: `src/shared/services/MatrixService.ts`
