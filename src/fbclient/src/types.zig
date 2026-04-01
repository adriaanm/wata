/// Domain types for the wata client: events, actions, and Matrix data models.
const std = @import("std");

// ---------------------------------------------------------------------------
// String IDs — all slices pointing into arena-managed memory
// ---------------------------------------------------------------------------

pub const RoomId = []const u8; // "!abc:server"
pub const UserId = []const u8; // "@alice:server"
pub const EventId = []const u8; // "$eventid"
pub const MxcUrl = []const u8; // "mxc://server/mediaid"
pub const TxnId = u32; // monotonic counter

// ---------------------------------------------------------------------------
// Connection state
// ---------------------------------------------------------------------------

pub const ConnectionState = enum {
    disconnected,
    connecting,
    connected, // logged in, not yet syncing
    syncing, // sync loop running
    err,
};

// ---------------------------------------------------------------------------
// Domain types (live in StateSnapshot, arena-allocated)
// ---------------------------------------------------------------------------

pub const User = struct {
    id: UserId,
    display_name: []const u8,
};

pub const Contact = struct {
    user: User,
};

pub const VoiceMessage = struct {
    id: EventId,
    sender: User,
    audio_url: []const u8, // HTTP download URL
    mxc_url: MxcUrl,
    duration: f64, // seconds
    timestamp: i64, // unix ms
    is_played: bool,
};

pub const ConversationType = enum { dm, family };

pub const Conversation = struct {
    room_id: RoomId,
    conv_type: ConversationType,
    contact: ?Contact, // null for family
    messages: []const VoiceMessage,
    unplayed_count: u32,
};

pub const Family = struct {
    id: RoomId,
    name: []const u8,
    members: []const Contact,
};

// ---------------------------------------------------------------------------
// StateSnapshot — immutable view of current state, built by sync thread
// ---------------------------------------------------------------------------

pub const StateSnapshot = struct {
    connection: ConnectionState,
    self_user: ?User,
    contacts: []const Contact,
    conversations: []const Conversation,
    family: ?Family,
};

// ---------------------------------------------------------------------------
// UiEvent — pushed from net/audio threads TO the main/UI thread
// ---------------------------------------------------------------------------

pub const UiEvent = union(enum) {
    // Connection lifecycle
    connection_state: ConnectionState,

    // Sync-derived domain events
    contacts_updated,
    message_received: struct {
        room_id_hash: u64, // for quick matching (full data in snapshot)
    },
    message_played: struct {
        room_id_hash: u64,
        event_id_hash: u64,
    },
    family_updated,
    snapshot_ready, // new StateSnapshot published to StateStore

    // Request completion
    send_complete: struct { txn_id: TxnId },
    send_failed: struct { txn_id: TxnId },

    // Audio
    recording_complete,
    playback_complete,
    audio_error,
};

// ---------------------------------------------------------------------------
// Action — pushed from UI thread TO the request thread
// ---------------------------------------------------------------------------

pub const Action = union(enum) {
    send_read_receipt: struct {
        room_id_buf: [128]u8,
        room_id_len: u8,
        event_id_buf: [128]u8,
        event_id_len: u8,
    },
    /// Upload Ogg audio and send as voice message.
    /// ogg_data points to allocator-owned memory; receiver must free via page_allocator.
    upload_and_send_voice: struct {
        room_id_buf: [128]u8,
        room_id_len: u8,
        ogg_data: [*]const u8,
        ogg_len: u32,
        duration_ms: u64,
    },
};

// ---------------------------------------------------------------------------
// AudioCommand — pushed from UI thread TO the audio thread
// ---------------------------------------------------------------------------

pub const AudioCommand = enum {
    start_recording,
    stop_recording,
    stop_playback,
    // Future: play with data pointer
};

// ---------------------------------------------------------------------------
// StateStore — atomic snapshot exchange between sync and UI threads
// ---------------------------------------------------------------------------

pub const StateStore = struct {
    /// Pointer to the current snapshot, or null.
    /// Sync thread stores (release), UI thread swaps to null (acquire).
    current: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

    /// Publish a new snapshot. Called by sync thread.
    pub fn publish(self: *StateStore, snapshot: *const StateSnapshot) void {
        self.current.store(@intFromPtr(snapshot), .release);
    }

    /// Acquire the latest snapshot, if any. Called by UI thread.
    /// Returns null if no new snapshot since last acquire.
    pub fn acquire(self: *StateStore) ?*const StateSnapshot {
        const ptr = self.current.swap(0, .acquire);
        if (ptr == 0) return null;
        return @ptrFromInt(ptr);
    }
};
