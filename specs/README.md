# WataClient Specifications

This directory contains formal specifications for the WataClient library and its core flows. These specs serve as the authoritative source of truth for understanding system behavior, API contracts, and test coverage.

## Purpose

1. **Reverse-engineered from existing tests and implementation** to document current behavior
2. **Map test coverage** - Each spec lists which tests verify which behaviors
3. **Document known workarounds** - Captures test hacks (polling, exponential backoff, artificial delays)
4. **Guide future refactoring** - Tests should align with specs, not implementation details
5. **Clarify component boundaries** - Shows which layer is responsible for what

## Directory Structure

```
specs/
├── README.md                        # This file
├── components/                      # API contracts for each architectural layer
│   ├── matrix-api.md               # Layer 1: HTTP client for Matrix C-S API
│   ├── sync-engine.md              # Layer 2: Real-time state synchronization
│   ├── dm-room-service.md          # Layer 2: DM room management
│   ├── room-state.md               # Cross-cutting: Room state model
│   └── wata-client.md              # Layer 3: High-level walkie-talkie API
└── flows/                          # End-to-end behavior specifications
    ├── initial-setup.md            # Family room creation and first invite
    ├── first-dm.md                 # Creating first DM with a contact
    ├── repeat-dm.md                # Sending to existing DM room
    ├── dm-deduplication.md         # Handling multiple DM rooms
    ├── voice-message-send.md       # Complete send flow
    ├── voice-message-receive.md    # Complete receive flow
    ├── read-receipt.md             # Read receipt propagation
    ├── initial-sync.md             # First sync after login
    ├── incremental-sync.md         # Ongoing sync loop
    ├── auto-join-invites.md        # Automatic invite acceptance
    └── concurrent-dm-creation.md   # Race condition handling
```

## Component Specifications

Component specs document **API contracts** for each architectural layer:

| Component | Layer | Responsibility |
|-----------|-------|----------------|
| [MatrixApi](components/matrix-api.md) | 1 - Protocol | HTTP client wrapper for Matrix C-S API |
| [SyncEngine](components/sync-engine.md) | 2 - State | Real-time sync loop and state management |
| [DMRoomService](components/dm-room-service.md) | 2 - State | DM room lookup, creation, deduplication |
| [RoomState](components/room-state.md) | Cross-cutting | In-memory room state model |
| [WataClient](components/wata-client.md) | 3 - Domain | High-level walkie-talkie API |

### Architecture: Three Layers

```
┌─────────────────────────────────────────┐
│  Layer 3: Domain Layer (WataClient)     │  ← Families, Contacts, VoiceMessages
├─────────────────────────────────────────┤
│  Layer 2: State Management              │
│  - SyncEngine: Timeline, Members        │  ← Matrix state, events, receipts
│  - DMRoomService: DM room mapping       │
├─────────────────────────────────────────┤
│  Layer 1: Protocol (MatrixApi)          │  ← HTTP requests to Matrix server
└─────────────────────────────────────────┘
```

## Flow Specifications

Flow specs document **end-to-end behavior** across multiple components:

### Setup Flows

- [Initial Setup](flows/initial-setup.md) - Family room creation, first member invitation

### Messaging Flows

- [First DM](flows/first-dm.md) - Creating first DM with a contact
- [Repeat DM](flows/repeat-dm.md) - Sending to existing DM room
- [DM Deduplication](flows/dm-deduplication.md) - Handling multiple DM rooms
- [Voice Message Send](flows/voice-message-send.md) - Upload, send, optimistic update
- [Voice Message Receive](flows/voice-message-receive.md) - Sync delivery, conversion, UI update
- [Read Receipt](flows/read-receipt.md) - Mark as played, receipt propagation

### Sync Flows

- [Initial Sync](flows/initial-sync.md) - First sync after login (full state)
- [Incremental Sync](flows/incremental-sync.md) - Ongoing sync loop (deltas only)

### Edge Case Flows

- [Auto-Join Invites](flows/auto-join-invites.md) - Automatic invite acceptance
- [Concurrent DM Creation](flows/concurrent-dm-creation.md) - Race condition handling

## Specification Template

Each spec follows a consistent structure:

```markdown
# [Component/Flow Name]

## Overview
Brief description and purpose

## Current Test Coverage
- Test file + test names
- What they verify
- Known workarounds/hacks

## [For Components]
- Responsibilities
- API/Interface (typed methods)
- Invariants
- State
- Events
- Error Handling

## [For Flows]
- Preconditions
- Step-by-step sequence
- Component boundaries
- Postconditions
- Error paths

## Known Limitations
Implementation gaps, workarounds

## Related Specs
Cross-references
```

## Test Coverage Mapping

Each spec includes a **Current Test Coverage** section that lists:
- Test file names
- Specific test names (from describe/it blocks)
- What aspect of the spec they verify
- What workarounds/hacks they use

### Example Mapping

From [read-receipt.md](flows/read-receipt.md):

```markdown
## Current Test Coverage

**test/integration/read-receipts.test.ts**
- "bob marks message as played, alice sees readBy update"
  - Tests: Complete receipt flow
  - Verifies: Bob sends receipt, Alice sees Bob in playedBy
  - Workaround: 15s polling for onReceiptUpdate callback
  - Workaround: Extra waitForSync() call after receipt
```

## Common Test Workarounds

The specs document these recurring test patterns:

1. **Polling for State**: `waitForCondition()` with exponential backoff instead of event-driven waits
2. **Fixed Delays**: `await sleep(2000)` assuming sync will complete
3. **Event ID Polling**: `waitForEventIds()` checking for specific messages
4. **Extra Sync Waits**: `waitForSync()` calls to ensure state propagation
5. **Membership Polling**: Checking `isRoomMember()` repeatedly
6. **Long Timeouts**: 15-35 second timeouts for operations that should be instant

## Using These Specs

### For Test Refactoring

1. **Read the spec** for the behavior you're testing
2. **Check current coverage** - What's already tested? What's missing?
3. **Identify workarounds** - What hacks can be eliminated?
4. **Write clean tests** - Use spec as contract, not implementation as guide

### For New Features

1. **Update affected specs** first
2. **Add new flow specs** for new user-facing features
3. **Document test coverage** as you write tests
4. **Cross-reference** related specs

### For Debugging

1. **Find the relevant spec** (component or flow)
2. **Check invariants** - What guarantees should hold?
3. **Review error paths** - Is this a known failure mode?
4. **Check related specs** - Is the issue in a different layer?

## Verification Checklist

✅ **5 component specs** - One per major component
✅ **11 flow specs** - All major user flows covered
✅ **All 61 tests mapped** - Every test referenced in at least one spec
✅ **Consistent template** - All specs follow same structure
✅ **Cross-references** - Related specs linked together
✅ **Known workarounds** - All test hacks documented

## Next Steps (Not in Current Scope)

After specs are reviewed and approved:

1. **Refactor tests** to align with specs
   - Remove workarounds (polling, exponential backoff)
   - Use event-driven waits instead of polling
   - Focus each test on single component or flow
2. **Fill coverage gaps** identified in specs
3. **Add integration tests** for missing flows
4. **Simplify test setup** using spec-defined boundaries
5. **Update specs** as implementation evolves

## Spec Authorship

These specifications were reverse-engineered from:
- Implementation: `src/shared/lib/wata-client/`
- Tests: `test/integration/`
- Documentation: `docs/dm-room-service.md`, `docs/family-model.md`

Created: 2026-02-11
