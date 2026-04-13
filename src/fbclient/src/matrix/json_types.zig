/// Zig structs matching Matrix Client-Server API JSON shapes.
/// Used with std.json.parseFromSlice for typed deserialization.
const std = @import("std");

// ---------------------------------------------------------------------------
// Login
// ---------------------------------------------------------------------------

pub const LoginResponse = struct {
    user_id: []const u8,
    access_token: []const u8,
    device_id: []const u8,
};

// ---------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------

pub const SyncResponse = struct {
    next_batch: []const u8,
    rooms: ?Rooms = null,
    account_data: ?EventList = null,

    pub const Rooms = struct {
        join: ?std.json.ArrayHashMap(JoinedRoom) = null,
        invite: ?std.json.ArrayHashMap(InvitedRoom) = null,
        leave: ?std.json.ArrayHashMap(LeftRoom) = null,
    };
};

pub const JoinedRoom = struct {
    summary: ?RoomSummary = null,
    state: ?EventList = null,
    timeline: ?Timeline = null,
    ephemeral: ?EventList = null,
    account_data: ?EventList = null,
    unread_notifications: ?UnreadNotifications = null,
};

pub const InvitedRoom = struct {
    invite_state: ?EventList = null,
};

pub const LeftRoom = struct {
    state: ?EventList = null,
    timeline: ?Timeline = null,
};

pub const Timeline = struct {
    events: ?[]const MatrixEvent = null,
    limited: ?bool = null,
    prev_batch: ?[]const u8 = null,
};

pub const EventList = struct {
    events: ?[]const MatrixEvent = null,
};

pub const MatrixEvent = struct {
    type: ?[]const u8 = null,
    event_id: ?[]const u8 = null,
    sender: ?[]const u8 = null,
    origin_server_ts: ?i64 = null,
    state_key: ?[]const u8 = null,
    content: ?std.json.Value = null,
    unsigned: ?std.json.Value = null,
    /// Populated for m.room.redaction events (top-level per v1.10, previously
    /// nested under content; we accept the top-level shape Conduit emits).
    redacts: ?[]const u8 = null,
};

pub const RoomSummary = struct {
    @"m.joined_member_count": ?u32 = null,
    @"m.invited_member_count": ?u32 = null,
    @"m.heroes": ?[]const []const u8 = null,
};

pub const UnreadNotifications = struct {
    highlight_count: ?u32 = null,
    notification_count: ?u32 = null,
};

// ---------------------------------------------------------------------------
// Send message
// ---------------------------------------------------------------------------

pub const SendMessageResponse = struct {
    event_id: []const u8,
};

// ---------------------------------------------------------------------------
// Upload
// ---------------------------------------------------------------------------

pub const UploadResponse = struct {
    content_uri: []const u8, // mxc:// URL
};

// ---------------------------------------------------------------------------
// Room creation / join
// ---------------------------------------------------------------------------

pub const CreateRoomResponse = struct {
    room_id: []const u8,
};

pub const JoinRoomResponse = struct {
    room_id: []const u8,
};

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

pub const MatrixError = struct {
    errcode: ?[]const u8 = null,
    @"error": ?[]const u8 = null,
};
