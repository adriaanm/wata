# MatrixApi - Protocol Layer HTTP Client

## Overview

MatrixApi is a typed HTTP client wrapper for the Matrix Client-Server API. It provides low-level protocol access without any domain logic or state management. This is Layer 1 of the three-layer architecture.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should login with valid credentials"
  - Tests: login() endpoint with username/password
  - Verifies: access token storage, user ID returned

- "should fail login with invalid password"
  - Tests: login() error handling
  - Verifies: throws error on M_FORBIDDEN

- "should fail login with non-existent user"
  - Tests: login() error handling
  - Verifies: throws error on unknown user

**test/integration/voice-message-flow.test.ts**
- Implicitly tests uploadMedia() and sendMessage() via TestOrchestrator

**test/integration/read-receipts.test.ts**
- Implicitly tests sendReadReceipt() via markMessageAsPlayed

## Responsibilities

- Execute HTTP requests to Matrix homeserver
- Handle authentication (store and inject access token)
- Serialize request bodies to JSON
- Deserialize response bodies from JSON
- Provide typed interfaces for all Matrix C-S API endpoints used by WataClient
- Generate transaction IDs for idempotent operations (send, redact)
- Convert errors to JavaScript Error objects with descriptive messages

## API/Interface

### Constructor

```typescript
constructor(baseUrl: string)
```
- **baseUrl**: Homeserver URL (e.g., "http://localhost:8008")
- Normalizes URL by removing trailing slash
- Does not validate URL or make network requests

### Authentication

```typescript
async login(username: string, password: string, deviceDisplayName?: string): Promise<LoginResponse>
```
- **Invariant**: After successful login, accessToken is set
- **Returns**: { user_id, access_token, device_id }
- **Errors**: Throws on network error, invalid credentials (M_FORBIDDEN), unknown user (M_USER_DEACTIVATED)

```typescript
async logout(): Promise<void>
```
- **Invariant**: Clears accessToken after successful logout
- **Precondition**: Must be authenticated
- **Errors**: Throws if not authenticated

```typescript
async whoami(): Promise<WhoamiResponse>
```
- **Returns**: { user_id, device_id?, is_guest? }
- **Precondition**: Must be authenticated

```typescript
setAccessToken(token: string): void
getAccessToken(): string | null
```
- Manual access token management for session resumption

### Sync

```typescript
async sync(params: SyncParams = {}): Promise<SyncResponse>
```
- **params.timeout**: Long-poll timeout in milliseconds (default: none, Matrix server default is 0)
- **params.since**: Token from previous sync for incremental updates
- **params.filter**: Filter ID or inline filter JSON
- **Returns**: Full sync response with rooms, account_data, presence
- **Precondition**: Must be authenticated

### Rooms

```typescript
async createRoom(request: CreateRoomRequest): Promise<CreateRoomResponse>
```
- **request.is_direct**: Mark as DM room
- **request.invite**: Array of user IDs to invite
- **request.preset**: "trusted_private_chat" for DMs, "private_chat" for family
- **request.room_alias_name**: Local alias (e.g., "family" → #family:server)
- **Returns**: { room_id }

```typescript
async joinRoom(roomIdOrAlias: string, request: JoinRoomRequest = {}): Promise<JoinRoomResponse>
```
- Accepts room ID (!xxx:server) or alias (#xxx:server)
- **Returns**: { room_id }

```typescript
async inviteToRoom(roomId: string, request: InviteRequest): Promise<InviteResponse>
```
- **request.user_id**: User to invite
- **request.reason**: Optional invite reason

### Messages

```typescript
async sendMessage(roomId: string, eventType: string, content: SendMessageRequest, txnId?: string): Promise<SendMessageResponse>
```
- **eventType**: "m.room.message" for voice messages
- **content.msgtype**: "m.audio" for voice messages
- **content.url**: MXC URL from uploadMedia()
- **content.info**: { duration (ms), mimetype, size }
- **txnId**: Auto-generated if not provided (ensures idempotency)
- **Returns**: { event_id }
- **Invariant**: Same txnId never reused by auto-generator

```typescript
async redactEvent(roomId: string, eventId: string, reason?: string, txnId?: string): Promise<RedactResponse>
```
- Deletes event content, leaving tombstone
- **Returns**: { event_id } of the redaction event

```typescript
async sendReadReceipt(roomId: string, eventId: string, threadId?: string): Promise<void>
```
- Sends m.read receipt for an event
- **Invariant**: Receipt applies to eventId and all events before it in room timeline

### Media

```typescript
async uploadMedia(data: ArrayBuffer, contentType: string, filename?: string): Promise<UploadResponse>
```
- **contentType**: MIME type (e.g., "audio/mp4")
- **Returns**: { content_uri } - MXC URL (mxc://server/mediaId)
- **Invariant**: MXC URL is globally unique and immutable

```typescript
async downloadMedia(mxcUrl: string): Promise<ArrayBuffer>
```
- **mxcUrl**: MXC URL from message content or uploadMedia response
- **Returns**: Raw file data as ArrayBuffer
- **Errors**: Throws if MXC URL is invalid format or media not found

### Profile

```typescript
async getProfile(userId: string): Promise<ProfileResponse>
async setDisplayName(userId: string, displayName: string): Promise<void>
async setAvatarUrl(userId: string, avatarUrl: string): Promise<void>
```

### Account Data

```typescript
async getAccountData(userId: string, type: string): Promise<AccountDataResponse>
async setAccountData(userId: string, type: string, content: Record<string, any>): Promise<void>
```
- **type**: "m.direct" for DM room mapping

## Invariants

1. **Authentication State**: Once login() succeeds, all subsequent requests include `Authorization: Bearer {token}` header
2. **Access Token Lifecycle**: Token is set on login, cleared on logout, persists until logout or error
3. **Transaction ID Uniqueness**: Auto-generated txnIds are monotonically increasing and never repeat
4. **Error Propagation**: All HTTP errors (4xx, 5xx) are converted to Error objects with Matrix errcode if available
5. **URL Normalization**: Base URL always has no trailing slash, all paths start with /
6. **Content-Type Handling**: Automatically sets JSON or octet-stream based on request body type

## State

### Internal State
- **baseUrl**: Homeserver URL (immutable after construction)
- **accessToken**: Current session token (null when logged out)
- **txnCounter**: Monotonic counter for transaction ID generation

### State Transitions
- **Not Authenticated** → login() → **Authenticated**
- **Authenticated** → logout() → **Not Authenticated**

## Events

MatrixApi does not emit events. It is a stateless HTTP client (except for access token).

## Error Handling

### Error Types
1. **Network Errors**: fetch() failures (timeout, no connection)
2. **HTTP Errors**: Non-2xx status codes
3. **Matrix Errors**: Structured errors with errcode and error message
   - M_FORBIDDEN: Invalid credentials, unauthorized
   - M_NOT_FOUND: Resource not found
   - M_UNKNOWN: Unknown error

### Error Format
```typescript
throw new Error("M_FORBIDDEN: Invalid password")
throw new Error("HTTP 404: Not Found - Room not found")
```

### Recovery Strategy
- MatrixApi does NOT retry failed requests
- Caller is responsible for retry logic (e.g., SyncEngine's exponential backoff)
- Access token is NOT cleared on failed requests (only on explicit logout)

## Known Limitations

1. **No Retry Logic**: Callers must implement retry/backoff (SyncEngine does this for sync)
2. **No Request Queuing**: Concurrent requests are sent in parallel, no ordering guarantee
3. **No Rate Limiting**: No client-side rate limit handling
4. **Minimal Validation**: Does not validate request schemas before sending (relies on server validation)
5. **No Response Caching**: Every call makes a network request

## Related Specs

- [SyncEngine](./sync-engine.md) - Uses MatrixApi for sync loop
- [DMRoomService](./dm-room-service.md) - Uses MatrixApi for room creation and account data
- [Initial Sync Flow](../flows/initial-sync.md) - How MatrixApi is used for first sync
