# Matrix Client-Server API for Wata

This document extracts the portions of the [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/) relevant to wata-client, based on the SDK usage analysis in `docs/planning/client-lib.md`.

## Scope

**Included:**
- Authentication (login, logout, whoami)
- Synchronization (`/sync`)
- Room management (create, join, invite, alias resolution)
- Messaging (send, redact)
- Receipts (read receipts)
- Media repository (upload, download)
- Profile & account data (displayname, `m.direct`)
- Core event schemas

**Excluded:**
- E2EE (encryption, device keys, etc.)
- Push notifications
- Presence
- Voice/video calls (WebRTC)
- Spaces, threading, reactions, edits
- Third-party invites
- Server administration

## Base URL

All endpoints are relative to:
```
/_matrix/client/v3
```

For media endpoints (upload), the base URL is:
```
/_matrix/media/v3
```

For authenticated media endpoints (download), the base URL is:
```
/_matrix/client/v1/media
```

## Authentication

### GET /login

Gets the homeserver's supported login types.

**Request:** No authentication required.

**Response:**
```json
{
  "flows": [
    {
      "type": "m.login.password"
    },
    {
      "type": "m.login.token",
      "get_login_token": true
    }
  ]
}
```

### POST /login

Authenticates the user and issues an access token.

**Request:** No authentication required.

**Request body:**
```json
{
  "type": "m.login.password",
  "identifier": {
    "type": "m.id.user",
    "user": "cheeky_monkey"
  },
  "password": "ilovebananas",
  "initial_device_display_name": "Jungle Phone"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `type` | Required | The login type being used (e.g., `m.login.password`, `m.login.token`) |
| `identifier` | Required | Object with `type: "m.id.user"` and `user` field (for password login) |
| `user` | Deprecated | Fully qualified user ID or local part (deprecated in favour of `identifier`) |
| `password` | Required (for `m.login.password`) | The user's password |
| `token` | Required (for `m.login.token`) | Token for token-based login |
| `device_id` | Optional | ID of the client device. Server will auto-generate if not specified. |
| `initial_device_display_name` | Optional | Display name to assign to the newly-created device |
| `refresh_token` | Optional | If true, the client supports refresh tokens |

**Response:**
```json
{
  "user_id": "@cheeky_monkey:matrix.org",
  "access_token": "abc123",
  "refresh_token": "def456",
  "expires_in_ms": 60000,
  "device_id": "GHTYAJCE",
  "well_known": {
    "m.homeserver": {
      "base_url": "https://example.org"
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `user_id` | The fully-qualified Matrix ID for the account |
| `access_token` | An access token for the account |
| `refresh_token` | A refresh token for obtaining new access tokens (optional, added in v1.3) |
| `expires_in_ms` | Lifetime of the access token in milliseconds (optional, added in v1.3) |
| `home_server` | The server_name of the homeserver (deprecated) |
| `device_id` | ID of the logged-in device |
| `well_known` | Optional client configuration provided by the server |

### POST /logout

Invalidates an existing access token. The device associated with the access token is also deleted.

**Request:** Authentication required (access token).

**Response:**
```json
{}
```

### GET /account/whoami

Gets information about the owner of an access token.

**Request:** Authentication required (access token).

**Response:**
```json
{
  "user_id": "@joe:example.org",
  "device_id": "ABC1234",
  "is_guest": false
}
```

| Field | Description |
|-------|-------------|
| `user_id` | The user ID that owns the access token |
| `device_id` | Device ID associated with the access token (optional, added in v1.1) |
| `is_guest` | When true, the user is a Guest User (optional, added in v1.2) |

## Synchronization

### GET /sync

Synchronises the client's state with the latest state on the server.

**Request:** Authentication required (access token).

**Query parameters:**

| Parameter | Description |
|-----------|-------------|
| `filter` | The ID of a filter created using the filter API or a filter JSON object encoded as a string |
| `since` | A point in time to continue a sync from (the `next_batch` token from a previous call) |
| `full_state` | Controls whether to include the full state for all rooms the user is a member of (default: `false`) |
| `set_presence` | Controls whether the client is automatically marked as online (`"offline"`, `"online"`, `"unavailable"`) |
| `timeout` | The maximum time to wait, in milliseconds, before returning this request (default: `0`) |
| `use_state_after` | Controls whether to receive state changes between previous sync and the **end** of the timeline (default: `false`, added in v1.16) |

**Response:**
```json
{
  "next_batch": "s72595_4483_1934",
  "rooms": {
    "join": {
      "!726s6s6q:example.com": {
        "summary": {
          "m.heroes": ["@alice:example.com", "@bob:example.com"],
          "m.joined_member_count": 2,
          "m.invited_member_count": 0
        },
        "state": {
          "events": [
            {
              "content": {
                "avatar_url": "mxc://example.org/SFHyPlCeYUSFFxlgbQYZmoEoe",
                "displayname": "Example user",
                "membership": "join"
              },
              "event_id": "$143273976499sgjks:example.org",
              "origin_server_ts": 1432735824653,
              "sender": "@example:example.org",
              "state_key": "@example:example.org",
              "type": "m.room.member",
              "unsigned": {
                "age": 45603
              }
            }
          ]
        },
        "state_after": {
          "events": []
        },
        "timeline": {
          "events": [
            {
              "content": {
                "body": "This is an example text message",
                "msgtype": "m.text"
              },
              "event_id": "$143273582443PhrSn:example.org",
              "origin_server_ts": 1432735824653,
              "sender": "@example:example.org",
              "type": "m.room.message",
              "unsigned": {
                "age": 1234
              }
            }
          ],
          "limited": true,
          "prev_batch": "t34-23535_0_0"
        },
        "ephemeral": {
          "events": [
            {
              "content": {
                "$event_id": {
                  "m.read": {
                    "@user_id": {
                      "ts": 1661384801651
                    }
                  }
                }
              },
              "room_id": "!room_id",
              "type": "m.receipt"
            }
          ]
        },
        "account_data": {
          "events": []
        },
        "unread_notifications": {
          "highlight_count": 1,
          "notification_count": 5
        }
      }
    },
    "invite": {},
    "knock": {},
    "leave": {}
  },
  "account_data": {
    "events": [
      {
        "type": "org.example.custom.config",
        "content": {
          "custom_config_key": "custom_config_value"
        }
      }
    ]
  }
}
```

**Response fields:**

| Field | Description |
|-------|-------------|
| `next_batch` | The batch token to supply in the `since` param of the next `/sync` request |
| `rooms` | Updates to rooms |
| `rooms.join` | The rooms that the user has joined |
| `rooms.invite` | The rooms that the user has been invited to |
| `rooms.leave` | The rooms that the user has left or been banned from |
| `account_data` | The global private data created by this user |

**Joined room fields:**

| Field | Description |
|-------|-------------|
| `summary` | Information about the room needed to correctly render it |
| `summary.m.heroes` | The users which can be used to generate a room name if the room does not have one |
| `summary.m.joined_member_count` | The number of users with `membership` of `join` |
| `summary.m.invited_member_count` | The number of users with `membership` of `invite` |
| `state` | Updates to the state between `since` and the **start** of the timeline |
| `state_after` | Updates to the state between `since` and the **end** of the timeline (added in v1.16) |
| `timeline` | The timeline of messages and state changes in the room |
| `timeline.events` | List of events |
| `timeline.limited` | If `true`, the timeline contains a gap and `prev_batch` should be used to backfill |
| `timeline.prev_batch` | The batch token for the start of the timeline |
| `ephemeral` | New ephemeral events (typing notifications, read receipts) |
| `account_data` | The private data for this room |
| `unread_notifications` | Counts of unread notifications for this room |
| `unread_notifications.notification_count` | The total number of unread notifications |
| `unread_notifications.highlight_count` | The number of unread notifications with the highlight flag |

## Room Management

### POST /createRoom

Create a new room with various configuration options.

**Request:** Authentication required (access token).

**Request body:**
```json
{
  "visibility": "private",
  "room_alias_name": "thepub",
  "name": "The Grand Duke Pub",
  "topic": "All about happy hour",
  "preset": "public_chat",
  "invite": ["@user:example.com"],
  "is_direct": true,
  "creation_content": {
    "m.federate": false
  },
  "initial_state": [],
  "room_version": "1"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `visibility` | Optional | The room's visibility in the published room directory (`"public"` or `"private"`, default: `"private"`) |
| `room_alias_name` | Optional | The desired room alias **local part** (e.g., `"foo"` for `#foo:example.com`) |
| `name` | Optional | Room name (sends `m.room.name` event) |
| `topic` | Optional | Room topic (sends `m.room.topic` event) |
| `invite` | Optional | A list of user IDs to invite to the room |
| `invite_3pid` | Optional | A list of objects representing third-party IDs to invite |
| `room_version` | Optional | The room version to set (default: homeserver's configured default) |
| `creation_content` | Optional | Extra keys to add to the `m.room.create` event (e.g., `m.federate`) |
| `initial_state` | Optional | A list of state events to set in the new room |
| `preset` | Optional | Convenience preset: `"private_chat"`, `"public_chat"`, `"trusted_private_chat"` |
| `is_direct` | Optional | Sets the `is_direct` flag on `m.room.member` events for invited users |

**Presets:**

| Preset | `join_rules` | `history_visibility` | `guest_access` | Other |
|--------|--------------|----------------------|----------------|-------|
| `private_chat` | `invite` | `shared` | `can_join` | |
| `trusted_private_chat` | `invite` | `shared` | `can_join` | All invitees get same power level as creator |
| `public_chat` | `public` | `shared` | `forbidden` | |

**Response:**
```json
{
  "room_id": "!sefiuhWgwghwWgh:example.com"
}
```

### POST /rooms/{roomId}/join

Join the requesting user to a particular room by ID.

**Request:** Authentication required (access token).

**Path parameters:**
- `roomId`: The room identifier (not alias) to join

**Request body (optional):**
```json
{
  "reason": "Looking for support",
  "third_party_signed": {
    "mxid": "@user:example.com",
    "token": "some_token",
    "signature": "signature"
  }
}
```

**Response:**
```json
{
  "room_id": "!d41d8cd:matrix.org"
}
```

### POST /join/{roomIdOrAlias}

Join the requesting user to a particular room by ID or alias.

**Request:** Authentication required (access token).

**Path parameters:**
- `roomIdOrAlias`: The room identifier or alias to join

**Query parameters:**
- `via`: The servers to attempt to join the room through (array of server names, added in v1.12)

**Request body:** Same as `/rooms/{roomId}/join`

**Response:** Same as `/rooms/{roomId}/join`

### POST /rooms/{roomId}/invite

Invite a user to participate in a particular room.

**Request:** Authentication required (access token).

**Path parameters:**
- `roomId`: The room identifier (not alias) to which to invite the user

**Request body:**
```json
{
  "user_id": "@cheeky_monkey:matrix.org",
  "reason": "Welcome to the team!"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `user_id` | Required | The fully qualified user ID of the invitee |
| `reason` | Optional | Optional reason to be included on the membership event (added in v1.1) |

**Response:**
```json
{}
```

### GET /directory/room/{roomAlias}

Get the room ID corresponding to this room alias.

**Request:** No authentication required (for public aliases), but authentication recommended.

**Path parameters:**
- `roomAlias`: The room alias (e.g., `#monkeys:matrix.org`)

**Response:**
```json
{
  "room_id": "!abnjk1jdasj98:capuchins.com",
  "servers": [
    "capuchins.com",
    "matrix.org",
    "another.com"
  ]
}
```

| Field | Description |
|-------|-------------|
| `room_id` | The room ID for this room alias |
| `servers` | A list of servers that are aware of this room alias |

## Messaging

### PUT /rooms/{roomId}/send/{eventType}/{txnId}

Send a message event to the given room.

**Request:** Authentication required (access token).

**Path parameters:**
- `roomId`: The room to send the event to
- `eventType`: The type of event to send (e.g., `m.room.message`)
- `txnId`: The transaction ID for this event (for idempotency)

**Request body:**
```json
{
  "msgtype": "m.text",
  "body": "hello"
}
```

For voice messages (audio):
```json
{
  "msgtype": "m.audio",
  "body": "voice message",
  "url": "mxc://example.org/AQwafuaFswefuhsfAFAgsw",
  "info": {
    "duration": 5000,
    "mimetype": "audio/mp4",
    "size": 12345
  }
}
```

**Response:**
```json
{
  "event_id": "$YUwRidLecu:example.com"
}
```

### PUT /rooms/{roomId}/redact/{eventId}/{txnId}

Strip all information out of an event (redaction).

**Request:** Authentication required (access token).

**Path parameters:**
- `roomId`: The room from which to redact the event
- `eventId`: The ID of the event to redact
- `txnId`: The transaction ID for this event

**Request body (optional):**
```json
{
  "reason": "Indecent material"
}
```

**Response:**
```json
{
  "event_id": "$YUwQidLecu:example.com"
}
```

**Authorization:** Any user with power level ≥ `m.room.redaction` may redact their own events. If also ≥ `redact` level, may redact events sent by other users. Server administrators may redact any events.

## Receipts

### POST /rooms/{roomId}/receipt/{receiptType}/{eventId}

Send a receipt for the given event ID.

**Request:** Authentication required (access token).

**Path parameters:**
- `roomId`: The room in which to send the event
- `receiptType`: The type of receipt (`m.read`, `m.read.private`, or `m.fully_read`)
- `eventId`: The event ID to acknowledge up to

**Request body (optional):**
```json
{
  "thread_id": "main"
}
```

| Field | Description |
|-------|-------------|
| `thread_id` | The root thread event's ID (or `"main"`) for threaded receipts (optional, added in v1.4) |

**Response:**
```json
{}
```

## Media Repository

### POST /media/v3/upload

Upload some content to the content repository.

**Request:** Authentication required (access token).

**Headers:**
- `Content-Type`: The content type of the file being uploaded (optional, defaults to `application/octet-stream`)

**Query parameters:**
- `filename`: The name of the file being uploaded

**Request body:** The raw bytes of the file

**Response:**
```json
{
  "content_uri": "mxc://example.com/AQwafuaFswefuhsfAFAgsw"
}
```

### GET /media/v1/download/{serverName}/{mediaId}

Download content from the content repository (deprecated - use authenticated version).

**Path parameters:**
- `serverName`: The server name from the `mxc://` URI
- `mediaId`: The media ID from the `mxc://` URI

**Query parameters:**
- `allow_remote`: Indicates to the server that it should not attempt to fetch remote media (default: `true`)
- `timeout_ms`: Maximum milliseconds to wait for content to be available (default: `20000`)
- `allow_redirect`: Indicates the server may return a 307/308 redirect (default: `false`)

**Response:** The content bytes with appropriate `Content-Type` and `Content-Disposition` headers.

### GET /client/v1/media/download/{serverName}/{mediaId}

Download content from the content repository (authenticated, added in v1.11).

**Request:** Authentication required (access token).

**Parameters:** Same as deprecated endpoint

### GET /media/v3/download/{serverName}/{mediaId}/{fileName}

Download content with a specific filename (deprecated).

### GET /client/v1/media/download/{serverName}/{mediaId}/{fileName}

Download content with a specific filename (authenticated, added in v1.11).

**Request:** Authentication required (access token).

## Profile

### GET /profile/{userId}

Get all profile information for a user.

**Request:** No authentication required for public profiles.

**Path parameters:**
- `userId`: The user whose profile information to get

**Response:**
```json
{
  "avatar_url": "mxc://matrix.org/SDGdghriugerRg",
  "displayname": "Alice Margatroid",
  "m.tz": "Europe/London"
}
```

### GET /profile/{userId}/{keyName}

Get a specific profile field for a user.

**Request:** No authentication required for public profiles.

**Path parameters:**
- `userId`: The user whose profile field should be returned
- `keyName`: The name of the profile field (`"displayname"`, `"avatar_url"`, `"m.tz"`, or custom field)

**Response:**
```json
{
  "displayname": "Alice"
}
```

### PUT /profile/{userId}/{keyName}

Set a profile field for a user.

**Request:** Authentication required (access token).

**Path parameters:**
- `userId`: The user whose profile field should be set (must match the access token's user)
- `keyName`: The name of the profile field (`"displayname"`, `"avatar_url"`, `"m.tz"`, or custom field)

**Request body:**
```json
{
  "displayname": "Alice Wonderland"
}
```

For `avatar_url`, the value must be an MXC URI string.

**Response:**
```json
{}
```

## Account Data

### GET /user/{userId}/account_data/{type}

Get some account data for the user.

**Request:** Authentication required (access token).

**Path parameters:**
- `userId`: The ID of the user to get account data for
- `type`: The event type of the account data to get

**Response:**
```json
{
  "custom_account_data_key": "custom_config_value"
}
```

**Error responses:**
- `403`: Not authorized to retrieve this user's account data
- `404`: No account data has been provided for this type

### PUT /user/{userId}/account_data/{type}

Set some account data for the user.

**Request:** Authentication required (access token).

**Path parameters:**
- `userId`: The ID of the user to set account data for
- `type`: The event type of the account data to set (custom types should be namespaced)

**Request body:**
```json
{
  "custom_account_data_key": "custom_config_value"
}
```

**Response:**
```json
{}
```

**Error responses:**
- `400`: The request body is not a JSON object
- `403`: Not authorized to modify this user's account data
- `405`: This `type` is controlled by the server (e.g., `m.fully_read`, `m.push_rules`)

### GET /user/{userId}/rooms/{roomId}/account_data/{type}

Get room-specific account data.

**Request:** Authentication required (access token).

**Path parameters:**
- `userId`: The ID of the user
- `roomId`: The ID of the room
- `type`: The event type of the account data

**Response:** Same as global account data

### PUT /user/{userId}/rooms/{roomId}/account_data/{type}

Set room-specific account data.

**Request:** Authentication required (access token).

**Path parameters:**
- `userId`: The ID of the user
- `roomId`: The ID of the room
- `type`: The event type of the account data

**Request body:** Same as global account data

## Event Schemas

### m.room.message

This event is used when sending messages in a room. The `msgtype` key outlines the type of message.

**Event type:** `m.room.message`

**Content:**
```json
{
  "msgtype": "m.text",
  "body": "Text message"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `msgtype` | Required | The type of message (`m.text`, `m.audio`, `m.image`, `m.file`, `m.video`, etc.) |
| `body` | Required | The textual representation of this message |

For audio messages:
```json
{
  "msgtype": "m.audio",
  "body": "voice message",
  "url": "mxc://example.org/AQwafuaFswefuhsfAFAgsw",
  "info": {
    "duration": 5000,
    "mimetype": "audio/mp4",
    "size": 12345
  },
  "filename": "voice.m4a",
  "format": "org.matrix.custom.html",
  "formatted_body": "<caption text>"
}
```

| Field | Description |
|-------|-------------|
| `url` | The MXC URI to the audio file |
| `info` | Metadata about the audio |
| `info.duration` | Duration of audio in milliseconds |
| `info.mimetype` | MIME type of the audio |
| `info.size` | Size of the audio in bytes |
| `filename` | Original filename |
| `format` | Must be `org.matrix.custom.html` for captions |
| `formatted_body` | Caption text (if different from filename) |

### m.room.member

Adjusts the membership state for a user in a room.

**Event type:** `m.room.member` (state event)

**State key:** The `user_id` this membership event relates to

**Content:**
```json
{
  "membership": "join",
  "displayname": "Alice",
  "avatar_url": "mxc://example.org/SEsfnsuifSDFSSEF",
  "is_direct": false,
  "reason": "Looking for support"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `membership` | Required | The membership state: `invite`, `join`, `leave`, `ban`, `knock` |
| `displayname` | Optional | The display name for this user |
| `avatar_url` | Optional | The avatar URL for this user (MXC URI) |
| `is_direct` | Optional | Flag indicating if the room was created as a direct chat |
| `reason` | Optional | User-supplied text for why membership changed (added in v1.1) |

**Membership state transitions:**

| From \ To | `invite` | `join` | `leave` | `ban` |
|-----------|----------|--------|---------|-------|
| `invite` | No change | User joined | Invite rejected or revoked | User banned |
| `join` | Never | Profile changed | User left or kicked | Kicked and banned |
| `leave` | New invite | User joined | No change | User banned |
| `ban` | Never | Never | User unbanned | No change |

### m.room.name

A human-friendly room name.

**Event type:** `m.room.name` (state event)

**State key:** Empty string `""`

**Content:**
```json
{
  "name": "Room Name"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `name` | Required | The name of the room |

### m.room.avatar

A picture associated with the room.

**Event type:** `m.room.avatar` (state event)

**State key:** Empty string `""`

**Content:**
```json
{
  "url": "mxc://example.org/abc123",
  "info": {
    "h": 396,
    "w": 394,
    "mimetype": "image/jpeg",
    "size": 36753
  }
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `url` | Optional | The MXC URI to the image (if absent, room has no avatar) |
| `info` | Optional | Metadata about the image |

### m.room.canonical_alias

Informs the room which alias is the canonical one.

**Event type:** `m.room.canonical_alias` (state event)

**State key:** Empty string `""`

**Content:**
```json
{
  "alias": "#room:example.com",
  "alt_aliases": ["#alt:example.com", "#another:example.com"]
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `alias` | Optional | The canonical alias for the room |
| `alt_aliases` | Optional | Alternative aliases the room advertises |

### m.room.create

The first event in a room (cannot be changed).

**Event type:** `m.room.create` (state event)

**State key:** Empty string `""`

**Content:**
```json
{
  "creator": "@user:server.com",
  "m.federate": true,
  "room_version": "1",
  "type": "m.space"
}
```

| Field | Required? | Description |
|-------|-----------|-------------|
| `creator` | Required (v1-10) | The `user_id` of the room creator |
| `m.federate` | Optional | Whether users on other servers can join this room (default: `true`) |
| `room_version` | Optional | The version of the room (default: `"1"`) |
| `type` | Optional | Optional room type (e.g., `"m.space"` for spaces) |

### m.receipt

Informs the client of new receipts (ephemeral event in sync).

**Event type:** `m.receipt` or `m.receipt.private`

**Content:**
```json
{
  "$event_id": {
    "m.read": {
      "@user1:example.com": {
        "ts": 1661384801651,
        "thread_id": "main"
      },
      "@user2:example.com": {
        "ts": 1661384801652
      }
    },
    "m.read.private": {
      "@user1:example.com": {
        "ts": 1661384801651
      }
    }
  }
}
```

The content maps event IDs to receipt types, which map user IDs to receipt data.

| Field | Description |
|-------|-------------|
| `ts` | The timestamp the receipt was sent at |
| `thread_id` | The thread root event ID or `"main"` for threaded receipts (optional) |

**Note:** `m.read.private` receipts are only sent to the user who created them, not to other users.

### m.direct

A map of which rooms are considered 'direct' rooms for specific users. Stored in account data.

**Event type:** `m.direct` (account data)

**Content:**
```json
{
  "@user1:example.com": ["!room1:example.com", "!room2:example.com"],
  "@user2:example.com": ["!room3:example.com"]
}
```

The content is an object where:
- Keys are user IDs
- Values are arrays of room IDs that are considered 'direct' rooms for that user

## Direct Messaging

All communication over Matrix happens within a room. Direct chats are a client-side concept marked via:

1. **`is_direct` flag**: When creating a room via `/createRoom`, setting `is_direct: true` causes the server to set the `is_direct` flag on the `m.room.member` events for invited users.

2. **`m.direct` account data**: Both the inviting client and the invitee's client should record the fact that the room is a direct chat by storing an `m.direct` event in account data.

**DM Room Idempotency Note:**

The `m.direct` account data is per-user and not server-enforced, which can lead to race conditions where both users create separate DM rooms. This is a known Matrix protocol limitation. See `docs/planning/client-lib.md` for mitigation strategies.

## Room Display Name Algorithm

Clients SHOULD use the following algorithm to choose a room name:

1. If the room has an `m.room.name` state event with a non-empty `name` field, use that name.
2. If the room has an `m.room.canonical_alias` state event with a valid `alias` field, use that alias.
3. Otherwise, compose a name based on the room members:
   - Use `m.heroes` from the room summary (first 5 members, excluding the current user)
   - Calculate display names for the heroes, disambiguating if necessary
   - If fewer heroes than total members, append a count (e.g., "Alice, Bob, and 1234 others")
   - If only one member, show "Empty Room (was Alice)" or similar

## User Display Name Algorithm

To calculate a disambiguated display name:

1. If the `m.room.member` event has no `displayname` field or it's `null`, use the raw user ID.
2. If the `displayname` is unique among joined/invited members, use it.
3. If not unique, disambiguate with the user ID (e.g., "display name (@id:homeserver.org)").

## Error Codes

Standard Matrix error responses include:

| Error Code | Description |
|-----------|-------------|
| `M_UNAUTHORIZED` | The request was not correctly authorized |
| `M_USER_DEACTIVATED` | The user ID associated with the request has been deactivated |
| `M_FORBIDDEN` | The user does not have permission to perform the operation |
| `M_UNKNOWN` | An unknown error has occurred |
| `M_BAD_JSON` | The request body is not valid JSON |
| `M_NOT_JSON` | The request body is not JSON |
| `M_TOO_LARGE` | The request or entity is too large |
| `M_LIMIT_EXCEEDED` | The request has been rate-limited |
| `M_NOT_FOUND` | The requested resource was not found |
| `M_INVALID_PARAM` | A parameter provided is invalid |
| `M_UNSUPPORTED_ROOM_VERSION` | The requested room version is not supported |

## Standard Error Response

```json
{
  "errcode": "M_FORBIDDEN",
  "error": "You are not invited to this room."
}
```

## MXC URI Format

Matrix Content (`mxc://`) URIs are used to identify media:

```
mxc://<server-name>/<media-id>
```

- `<server-name>`: The homeserver where the content originated (e.g., `matrix.org`)
- `<media-id>`: An opaque ID identifying the content (alphanumeric, `_`, `-`)

## Transaction IDs

Transaction IDs (`txnId`) are used for idempotency when sending events and redactions. Clients should generate a unique ID across requests with the same access token. The server will ensure idempotency based on this ID.

The transaction ID used will be included in the event's `unsigned` data as `transaction_id` when it arrives through the event stream.
