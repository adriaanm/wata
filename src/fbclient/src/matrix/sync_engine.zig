/// Sync engine: processes Matrix /sync responses and maintains room state.
///
/// Split into two layers for testability:
/// - SyncProcessor: pure state machine. Takes parsed SyncResponse, updates
///   internal room state, returns a list of emitted events. No I/O, no threads.
///   Fully testable with constructed input data.
/// - syncLoop(): thin thread entry point that calls HTTP sync, feeds responses
///   to the processor, and publishes snapshots. Lives in main.zig.
const std = @import("std");
const Allocator = std.mem.Allocator;
const json_types = @import("json_types.zig");
const types = @import("../types.zig");

// ---------------------------------------------------------------------------
// Room state (owned by the processor, long-lived)
// ---------------------------------------------------------------------------

pub const MemberInfo = struct {
    user_id: []const u8,
    display_name: []const u8,
    membership: []const u8, // "join", "invite", "leave", "ban"
    is_direct: bool,
};

pub const RoomState = struct {
    room_id: []const u8,
    name: []const u8,
    canonical_alias: ?[]const u8,
    members: std.StringArrayHashMapUnmanaged(MemberInfo),
    /// Timeline event IDs for dedup
    timeline_event_ids: std.StringArrayHashMapUnmanaged(void),
    /// Voice messages extracted from timeline
    voice_messages: std.ArrayListUnmanaged(VoiceMessageRaw),
    /// Read receipts: event_id → list of user_ids
    receipt_user_ids: std.StringArrayHashMapUnmanaged(std.ArrayListUnmanaged([]const u8)),
    prev_batch: ?[]const u8,

    pub fn init(room_id: []const u8) RoomState {
        return .{
            .room_id = room_id,
            .name = "",
            .canonical_alias = null,
            .members = .empty,
            .timeline_event_ids = .empty,
            .voice_messages = .empty,
            .receipt_user_ids = .empty,
            .prev_batch = null,
        };
    }

    pub fn deinit(self: *RoomState, gpa: Allocator) void {
        self.members.deinit(gpa);
        self.timeline_event_ids.deinit(gpa);
        self.voice_messages.deinit(gpa);
        {
            var it = self.receipt_user_ids.iterator();
            while (it.next()) |entry| {
                entry.value_ptr.deinit(gpa);
            }
        }
        self.receipt_user_ids.deinit(gpa);
    }

    pub fn joinedMemberCount(self: *const RoomState) u32 {
        var count: u32 = 0;
        var it = self.members.iterator();
        while (it.next()) |entry| {
            if (std.mem.eql(u8, entry.value_ptr.membership, "join")) count += 1;
        }
        return count;
    }
};

/// Raw voice message data extracted from Matrix timeline events.
pub const VoiceMessageRaw = struct {
    event_id: []const u8,
    sender: []const u8,
    mxc_url: []const u8,
    duration_ms: u64,
    timestamp: i64, // origin_server_ts
};

// ---------------------------------------------------------------------------
// Emitted events (returned from process(), consumed by caller)
// ---------------------------------------------------------------------------

pub const SyncEvent = union(enum) {
    room_updated: []const u8, // room_id
    timeline_event: struct { room_id: []const u8, event_id: []const u8 },
    membership_changed: struct { room_id: []const u8, user_id: []const u8, membership: []const u8 },
    receipt_updated: struct { room_id: []const u8, event_id: []const u8 },
    account_data_updated: struct { data_type: []const u8 },
};

// ---------------------------------------------------------------------------
// SyncProcessor — the testable state machine
// ---------------------------------------------------------------------------

pub const SyncProcessor = struct {
    rooms: std.StringArrayHashMapUnmanaged(RoomState),
    self_user_id: ?[]const u8,
    next_batch: ?[]const u8,
    /// m.direct account data: user_id → list of room_ids
    m_direct: std.StringArrayHashMapUnmanaged(std.ArrayListUnmanaged([]const u8)),
    gpa: Allocator,

    pub fn init(gpa: Allocator) SyncProcessor {
        return .{
            .rooms = .empty,
            .self_user_id = null,
            .next_batch = null,
            .m_direct = .empty,
            .gpa = gpa,
        };
    }

    pub fn deinit(self: *SyncProcessor) void {
        {
            var it = self.rooms.iterator();
            while (it.next()) |entry| {
                entry.value_ptr.deinit(self.gpa);
            }
        }
        self.rooms.deinit(self.gpa);
        {
            var it = self.m_direct.iterator();
            while (it.next()) |entry| {
                entry.value_ptr.deinit(self.gpa);
            }
        }
        self.m_direct.deinit(self.gpa);
    }

    /// Process a parsed sync response. Returns emitted events.
    /// Events are allocated with `event_arena` and valid for the caller's scope.
    pub fn process(
        self: *SyncProcessor,
        response: json_types.SyncResponse,
        event_arena: Allocator,
    ) ![]const SyncEvent {
        var events: std.ArrayListUnmanaged(SyncEvent) = .empty;

        // Update next_batch
        self.next_batch = response.next_batch;

        // Global account data
        if (response.account_data) |ad| {
            if (ad.events) |ad_events| {
                for (ad_events) |event| {
                    try self.processAccountData(event, &events, event_arena);
                }
            }
        }

        // Joined rooms
        if (response.rooms) |rooms| {
            if (rooms.join) |join_map| {
                var join_it = join_map.map.iterator();
                while (join_it.next()) |entry| {
                    try self.processJoinedRoom(entry.key_ptr.*, entry.value_ptr.*, &events, event_arena);
                }
            }
        }

        return events.items;
    }

    fn getOrCreateRoom(self: *SyncProcessor, room_id: []const u8) !*RoomState {
        const result = try self.rooms.getOrPut(self.gpa, room_id);
        if (!result.found_existing) {
            result.value_ptr.* = RoomState.init(room_id);
        }
        return result.value_ptr;
    }

    fn processJoinedRoom(
        self: *SyncProcessor,
        room_id: []const u8,
        data: json_types.JoinedRoom,
        events: *std.ArrayListUnmanaged(SyncEvent),
        arena: Allocator,
    ) !void {
        const room = try self.getOrCreateRoom(room_id);

        // State events
        if (data.state) |state| {
            if (state.events) |state_events| {
                for (state_events) |event| {
                    try self.processStateEvent(room, room_id, event, events, arena);
                }
            }
        }

        // Timeline events
        if (data.timeline) |timeline| {
            if (timeline.prev_batch) |pb| {
                room.prev_batch = pb;
            }

            if (timeline.events) |tl_events| {
                for (tl_events) |event| {
                    // Dedup
                    if (event.event_id) |eid| {
                        const dup = try room.timeline_event_ids.getOrPut(self.gpa, eid);
                        if (dup.found_existing) continue;
                    }

                    // State events in timeline
                    if (event.state_key != null) {
                        try self.processStateEvent(room, room_id, event, events, arena);
                    }

                    // Extract voice messages (m.room.message with msgtype m.audio)
                    if (event.type) |evt_type| {
                        if (std.mem.eql(u8, evt_type, "m.room.message")) {
                            if (extractVoiceMessage(event)) |vm| {
                                try room.voice_messages.append(self.gpa, vm);
                            }
                        }
                    }

                    if (event.event_id) |eid| {
                        try events.append(arena, .{ .timeline_event = .{
                            .room_id = room_id,
                            .event_id = eid,
                        } });
                    }
                }
            }
        }

        // Ephemeral events (receipts)
        if (data.ephemeral) |ephemeral| {
            if (ephemeral.events) |eph_events| {
                for (eph_events) |event| {
                    if (event.type) |t| {
                        if (std.mem.eql(u8, t, "m.receipt")) {
                            try self.processReceiptEvent(room, room_id, event, events, arena);
                        }
                    }
                }
            }
        }

        try events.append(arena, .{ .room_updated = room_id });
    }

    fn processStateEvent(
        self: *SyncProcessor,
        room: *RoomState,
        room_id: []const u8,
        event: json_types.MatrixEvent,
        events: *std.ArrayListUnmanaged(SyncEvent),
        arena: Allocator,
    ) !void {
        const event_type = event.type orelse return;

        if (std.mem.eql(u8, event_type, "m.room.name")) {
            if (event.content) |content| {
                if (getJsonString(content, "name")) |name| {
                    room.name = name;
                }
            }
        } else if (std.mem.eql(u8, event_type, "m.room.canonical_alias")) {
            if (event.content) |content| {
                room.canonical_alias = getJsonString(content, "alias");
            }
        } else if (std.mem.eql(u8, event_type, "m.room.member")) {
            const user_id = event.state_key orelse return;
            if (event.content) |content| {
                const membership = getJsonString(content, "membership") orelse "leave";
                const display_name = getJsonString(content, "displayname") orelse user_id;
                const is_direct = getJsonBool(content, "is_direct") orelse false;

                try room.members.put(self.gpa, user_id, .{
                    .user_id = user_id,
                    .display_name = display_name,
                    .membership = membership,
                    .is_direct = is_direct,
                });

                try events.append(arena, .{ .membership_changed = .{
                    .room_id = room_id,
                    .user_id = user_id,
                    .membership = membership,
                } });
            }
        }
    }

    fn processReceiptEvent(
        self: *SyncProcessor,
        room: *RoomState,
        room_id: []const u8,
        event: json_types.MatrixEvent,
        events: *std.ArrayListUnmanaged(SyncEvent),
        arena: Allocator,
    ) !void {
        // Receipt content: { "$event_id": { "m.read": { "@user:server": { "ts": 123 } } } }
        const content = event.content orelse return;
        switch (content) {
            .object => |obj| {
                var ev_it = obj.iterator();
                while (ev_it.next()) |ev_entry| {
                    const event_id = ev_entry.key_ptr.*;
                    switch (ev_entry.value_ptr.*) {
                        .object => |read_obj| {
                            if (read_obj.get("m.read")) |read_val| {
                                switch (read_val) {
                                    .object => |users_obj| {
                                        const result = try room.receipt_user_ids.getOrPut(self.gpa, event_id);
                                        if (!result.found_existing) {
                                            result.value_ptr.* = .empty;
                                        }
                                        var user_it = users_obj.iterator();
                                        while (user_it.next()) |user_entry| {
                                            try result.value_ptr.append(self.gpa, user_entry.key_ptr.*);
                                        }
                                        try events.append(arena, .{ .receipt_updated = .{
                                            .room_id = room_id,
                                            .event_id = event_id,
                                        } });
                                    },
                                    else => {},
                                }
                            }
                        },
                        else => {},
                    }
                }
            },
            else => {},
        }
    }

    fn processAccountData(
        self: *SyncProcessor,
        event: json_types.MatrixEvent,
        events: *std.ArrayListUnmanaged(SyncEvent),
        arena: Allocator,
    ) !void {
        const event_type = event.type orelse return;

        if (std.mem.eql(u8, event_type, "m.direct")) {
            // m.direct content: { "@user:server": ["!room1:server", "!room2:server"] }
            if (event.content) |content| {
                switch (content) {
                    .object => |obj| {
                        // Clear and rebuild
                        var md_it = self.m_direct.iterator();
                        while (md_it.next()) |entry| {
                            entry.value_ptr.deinit(self.gpa);
                        }
                        self.m_direct.clearRetainingCapacity();

                        var user_it = obj.iterator();
                        while (user_it.next()) |entry| {
                            const user_id = entry.key_ptr.*;
                            switch (entry.value_ptr.*) {
                                .array => |arr| {
                                    var room_list: std.ArrayListUnmanaged([]const u8) = .empty;
                                    for (arr.items) |item| {
                                        switch (item) {
                                            .string => |s| try room_list.append(self.gpa, s),
                                            else => {},
                                        }
                                    }
                                    try self.m_direct.put(self.gpa, user_id, room_list);
                                },
                                else => {},
                            }
                        }
                    },
                    else => {},
                }
            }
            try events.append(arena, .{ .account_data_updated = .{ .data_type = "m.direct" } });
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot builder
    // -----------------------------------------------------------------------

    /// Build a StateSnapshot from current processor state.
    /// Allocates into the provided arena.
    pub fn buildSnapshot(self: *const SyncProcessor, arena: Allocator) !types.StateSnapshot {
        var contacts: std.ArrayListUnmanaged(types.Contact) = .empty;

        // Contacts from m.direct
        var md_it = self.m_direct.iterator();
        while (md_it.next()) |entry| {
            const user_id = entry.key_ptr.*;
            if (self.self_user_id) |self_id| {
                if (std.mem.eql(u8, user_id, self_id)) continue;
            }
            // Resolve display name from room membership
            var display_name: []const u8 = user_id;
            for (entry.value_ptr.items) |room_id| {
                if (self.rooms.get(room_id)) |room| {
                    if (room.members.get(user_id)) |member| {
                        display_name = member.display_name;
                        break;
                    }
                }
            }
            try contacts.append(arena, .{ .user = .{
                .id = user_id,
                .display_name = display_name,
            } });
        }

        // Conversations from m.direct
        var conversations: std.ArrayListUnmanaged(types.Conversation) = .empty;
        md_it = self.m_direct.iterator();
        while (md_it.next()) |entry| {
            const user_id = entry.key_ptr.*;
            if (self.self_user_id) |self_id| {
                if (std.mem.eql(u8, user_id, self_id)) continue;
            }
            if (entry.value_ptr.items.len == 0) continue;
            const room_id = entry.value_ptr.items[0]; // primary room
            const room = self.rooms.get(room_id) orelse continue;

            var messages: std.ArrayListUnmanaged(types.VoiceMessage) = .empty;
            for (room.voice_messages.items) |vm| {
                const sender_name = if (room.members.get(vm.sender)) |m| m.display_name else vm.sender;
                const is_played = if (room.receipt_user_ids.get(vm.event_id)) |users| blk: {
                    if (self.self_user_id) |self_id| {
                        for (users.items) |uid| {
                            if (std.mem.eql(u8, uid, self_id)) break :blk true;
                        }
                    }
                    break :blk false;
                } else false;

                try messages.append(arena, .{
                    .id = vm.event_id,
                    .sender = .{ .id = vm.sender, .display_name = sender_name },
                    .audio_url = vm.mxc_url,
                    .mxc_url = vm.mxc_url,
                    .duration = @as(f64, @floatFromInt(vm.duration_ms)) / 1000.0,
                    .timestamp = vm.timestamp,
                    .is_played = is_played,
                });
            }

            var unplayed: u32 = 0;
            for (messages.items) |m| {
                if (!m.is_played) unplayed += 1;
            }

            // Find matching contact
            var contact: ?types.Contact = null;
            for (contacts.items) |c| {
                if (std.mem.eql(u8, c.user.id, user_id)) {
                    contact = c;
                    break;
                }
            }

            try conversations.append(arena, .{
                .room_id = room_id,
                .conv_type = .dm,
                .contact = contact,
                .messages = messages.items,
                .unplayed_count = unplayed,
            });
        }

        return .{
            .connection = .syncing,
            .self_user = if (self.self_user_id) |uid| .{
                .id = uid,
                .display_name = uid,
            } else null,
            .contacts = contacts.items,
            .conversations = conversations.items,
            .family = null,
        };
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    pub fn roomCount(self: *const SyncProcessor) usize {
        return self.rooms.count();
    }

    pub fn getRoom(self: *const SyncProcessor, room_id: []const u8) ?*const RoomState {
        return if (self.rooms.getPtr(room_id)) |ptr| ptr else null;
    }
};

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

fn getJsonString(value: std.json.Value, key: []const u8) ?[]const u8 {
    switch (value) {
        .object => |obj| {
            if (obj.get(key)) |v| {
                switch (v) {
                    .string => |s| return s,
                    else => return null,
                }
            }
            return null;
        },
        else => return null,
    }
}

fn getJsonBool(value: std.json.Value, key: []const u8) ?bool {
    switch (value) {
        .object => |obj| {
            if (obj.get(key)) |v| {
                switch (v) {
                    .bool => |b| return b,
                    else => return null,
                }
            }
            return null;
        },
        else => return null,
    }
}

fn getJsonValue(value: std.json.Value, key: []const u8) ?std.json.Value {
    switch (value) {
        .object => |obj| return obj.get(key),
        else => return null,
    }
}

fn getJsonInt(value: std.json.Value, key: []const u8) ?i64 {
    switch (value) {
        .object => |obj| {
            if (obj.get(key)) |v| {
                switch (v) {
                    .integer => |i| return i,
                    .float => |f| return @intFromFloat(f),
                    else => return null,
                }
            }
            return null;
        },
        else => return null,
    }
}

fn extractVoiceMessage(event: json_types.MatrixEvent) ?VoiceMessageRaw {
    const content = event.content orelse return null;
    const msgtype = getJsonString(content, "msgtype") orelse return null;
    if (!std.mem.eql(u8, msgtype, "m.audio")) return null;

    const mxc_url = getJsonString(content, "url") orelse return null;
    const event_id = event.event_id orelse return null;
    const sender = event.sender orelse return null;
    const timestamp = event.origin_server_ts orelse 0;

    var duration_ms: u64 = 0;
    if (getJsonValue(content, "info")) |info| {
        if (getJsonInt(info, "duration")) |d| {
            duration_ms = if (d > 0) @intCast(d) else 0;
        }
    }

    return .{
        .event_id = event_id,
        .sender = sender,
        .mxc_url = mxc_url,
        .duration_ms = duration_ms,
        .timestamp = timestamp,
    };
}

// ===========================================================================
// Tests
// ===========================================================================

fn parseSyncResponse(allocator: Allocator, json_str: []const u8) !std.json.Parsed(json_types.SyncResponse) {
    return std.json.parseFromSlice(json_types.SyncResponse, allocator, json_str, .{ .ignore_unknown_fields = true });
}

test "process empty sync response" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();

    const response = json_types.SyncResponse{ .next_batch = "batch_1" };

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const events = try proc.process(response, arena.allocator());

    try std.testing.expectEqual(0, events.len);
    try std.testing.expectEqualStrings("batch_1", proc.next_batch.?);
    try std.testing.expectEqual(0, proc.roomCount());
}

test "process sync with joined room and member" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();
    proc.self_user_id = "@alice:test";

    const json_str =
        \\{"next_batch":"batch_2","rooms":{"join":{"!room1:test":{
        \\  "state":{"events":[
        \\    {"type":"m.room.name","state_key":"","content":{"name":"Test Room"}},
        \\    {"type":"m.room.member","state_key":"@bob:test","sender":"@bob:test",
        \\     "content":{"membership":"join","displayname":"Bob"}}
        \\  ]}
        \\}}}}
    ;

    const parsed = try parseSyncResponse(allocator, json_str);
    defer parsed.deinit();

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const events = try proc.process(parsed.value, arena.allocator());

    try std.testing.expect(events.len >= 2);
    try std.testing.expectEqual(1, proc.roomCount());

    const room = proc.getRoom("!room1:test").?;
    try std.testing.expectEqualStrings("Test Room", room.name);
    try std.testing.expectEqual(@as(u32, 1), room.joinedMemberCount());

    const bob = room.members.get("@bob:test").?;
    try std.testing.expectEqualStrings("Bob", bob.display_name);
    try std.testing.expectEqualStrings("join", bob.membership);
}

test "process sync with voice message" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();
    proc.self_user_id = "@alice:test";

    const json_str =
        \\{"next_batch":"batch_3","rooms":{"join":{"!room1:test":{
        \\  "timeline":{"events":[
        \\    {"type":"m.room.message","event_id":"$msg1","sender":"@bob:test",
        \\     "origin_server_ts":1700000000000,
        \\     "content":{"msgtype":"m.audio","url":"mxc://test/audio1","body":"voice",
        \\       "info":{"duration":3500,"mimetype":"audio/ogg"}}}
        \\  ]}
        \\}}}}
    ;

    const parsed = try parseSyncResponse(allocator, json_str);
    defer parsed.deinit();

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    _ = try proc.process(parsed.value, arena.allocator());

    const room = proc.getRoom("!room1:test").?;
    try std.testing.expectEqual(1, room.voice_messages.items.len);

    const vm = room.voice_messages.items[0];
    try std.testing.expectEqualStrings("$msg1", vm.event_id);
    try std.testing.expectEqualStrings("@bob:test", vm.sender);
    try std.testing.expectEqualStrings("mxc://test/audio1", vm.mxc_url);
    try std.testing.expectEqual(@as(u64, 3500), vm.duration_ms);
    try std.testing.expectEqual(@as(i64, 1700000000000), vm.timestamp);
}

test "process sync with m.direct account data" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();
    proc.self_user_id = "@alice:test";

    const json_str =
        \\{"next_batch":"batch_4","account_data":{"events":[
        \\  {"type":"m.direct","content":{
        \\    "@bob:test":["!dm1:test","!dm2:test"],
        \\    "@charlie:test":["!dm3:test"]
        \\  }}
        \\]}}
    ;

    const parsed = try parseSyncResponse(allocator, json_str);
    defer parsed.deinit();

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const events = try proc.process(parsed.value, arena.allocator());

    var found_ad = false;
    for (events) |ev| {
        switch (ev) {
            .account_data_updated => |ad| {
                if (std.mem.eql(u8, ad.data_type, "m.direct")) found_ad = true;
            },
            else => {},
        }
    }
    try std.testing.expect(found_ad);
    try std.testing.expectEqual(2, proc.m_direct.count());

    const bob_rooms = proc.m_direct.get("@bob:test").?;
    try std.testing.expectEqual(2, bob_rooms.items.len);
    try std.testing.expectEqualStrings("!dm1:test", bob_rooms.items[0]);

    const charlie_rooms = proc.m_direct.get("@charlie:test").?;
    try std.testing.expectEqual(1, charlie_rooms.items.len);
}

test "buildSnapshot produces contacts from m.direct" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();
    proc.self_user_id = "@alice:test";

    // Set up m.direct
    var bob_rooms: std.ArrayListUnmanaged([]const u8) = .empty;
    try bob_rooms.append(allocator, "!dm1:test");
    try proc.m_direct.put(allocator, "@bob:test", bob_rooms);

    // Set up the room with Bob as a member
    var room = RoomState.init("!dm1:test");
    try room.members.put(allocator, "@bob:test", .{
        .user_id = "@bob:test",
        .display_name = "Bob",
        .membership = "join",
        .is_direct = true,
    });
    try proc.rooms.put(allocator, "!dm1:test", room);

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const snapshot = try proc.buildSnapshot(arena.allocator());

    try std.testing.expectEqual(1, snapshot.contacts.len);
    try std.testing.expectEqualStrings("Bob", snapshot.contacts[0].user.display_name);
    try std.testing.expectEqual(1, snapshot.conversations.len);
    try std.testing.expectEqualStrings("!dm1:test", snapshot.conversations[0].room_id);
}

test "timeline event dedup" {
    const allocator = std.testing.allocator;
    var proc = SyncProcessor.init(allocator);
    defer proc.deinit();

    const json_str =
        \\{"next_batch":"batch_5","rooms":{"join":{"!room1:test":{
        \\  "timeline":{"events":[
        \\    {"type":"m.room.message","event_id":"$msg1","sender":"@bob:test",
        \\     "origin_server_ts":1700000000000,
        \\     "content":{"msgtype":"m.audio","url":"mxc://test/audio1",
        \\       "info":{"duration":1000}}}
        \\  ]}
        \\}}}}
    ;

    const parsed = try parseSyncResponse(allocator, json_str);
    defer parsed.deinit();

    var arena1 = std.heap.ArenaAllocator.init(allocator);
    defer arena1.deinit();
    _ = try proc.process(parsed.value, arena1.allocator());

    // Process same response again (simulate duplicate)
    var arena2 = std.heap.ArenaAllocator.init(allocator);
    defer arena2.deinit();
    _ = try proc.process(parsed.value, arena2.allocator());

    const room = proc.getRoom("!room1:test").?;
    try std.testing.expectEqual(1, room.voice_messages.items.len);
}
