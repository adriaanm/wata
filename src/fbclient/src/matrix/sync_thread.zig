/// Sync thread: login, then long-poll /sync in a loop.
/// Publishes StateSnapshots for the UI thread via atomic swap.
const std = @import("std");
const build_options = @import("build_options");
const Io = std.Io;
const http = @import("http.zig");
const json_types = @import("json_types.zig");
const sync_engine = @import("sync_engine.zig");
const types = @import("../types.zig");
const queue = @import("../queue.zig");
const mailbox_mod = @import("../mailbox.zig");
const config = @import("../config.zig");
const audio_thread = if (build_options.use_audio) @import("../audio_thread.zig") else struct {
    pub const CommandQueue = void;
};

pub const Config = struct {
    homeserver: []const u8,
    username: []const u8,
    password: []const u8,
    sync_timeout_ms: u32 = 30_000,
};

pub const DEFAULT_CONFIG = Config{
    .homeserver = "http://192.168.179.41:8008",
    .username = "bob",
    .password = "testpass123",
};

pub const ActionQueue = mailbox_mod.Mailbox(types.Action, 64);

/// Auth credentials published by sync thread after login.
/// Action thread waits on auth_ready before processing actions.
pub const AuthStore = struct {
    /// Packed pointer to AuthCredentials, or 0 if not yet ready.
    ptr: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

    pub fn publish(self: *AuthStore, creds: *const AuthCredentials) void {
        self.ptr.store(@intFromPtr(creds), .release);
    }

    pub fn load(self: *AuthStore) ?*const AuthCredentials {
        const p = self.ptr.load(.acquire);
        if (p == 0) return null;
        return @ptrFromInt(p);
    }
};

pub const AuthCredentials = struct {
    access_token: []const u8,
    user_id: []const u8,
};

pub const SyncThreadContext = struct {
    config: Config,
    ui_queue: *queue.BoundedQueue(types.UiEvent, 256),
    action_queue: *ActionQueue,
    audio_cmd_queue: ?*audio_thread.CommandQueue,
    state_store: *types.StateStore,
    should_stop: *std.atomic.Value(bool),
    auth_store: *AuthStore,
    allocator: std.mem.Allocator,
    io: Io,
};

pub const ActionThreadContext = struct {
    config: Config,
    ui_queue: *queue.BoundedQueue(types.UiEvent, 256),
    action_queue: *ActionQueue,
    audio_cmd_queue: ?*audio_thread.CommandQueue,
    auth_store: *AuthStore,
    allocator: std.mem.Allocator,
    io: Io,
};

fn sleepMs(io: Io, ms: u64) void {
    io.sleep(.fromMilliseconds(@intCast(ms)), .awake) catch {};
}

pub fn syncThreadMain(ctx_ptr: *SyncThreadContext) void {
    syncThreadMainInner(ctx_ptr) catch |err| {
        _ = err;
        // Push error state to UI
        _ = ctx_ptr.ui_queue.push(.{ .connection_state = .err });
    };
}

fn syncThreadMainInner(ctx: *SyncThreadContext) !void {
    const allocator = ctx.allocator;

    _ = ctx.ui_queue.push(.{ .connection_state = .connecting });

    var client = http.MatrixHttpClient.init(allocator, ctx.io, ctx.config.homeserver);
    defer client.deinit();

    // Try to restore session from stored credentials
    var stored_session = config.loadSession(allocator);
    var access_token: []const u8 = undefined;
    var user_id: []const u8 = undefined;
    var owns_token = false;
    var owns_uid = false;

    if (stored_session) |*ss| {
        // Validate the stored token with a test sync (timeout=0)
        client.access_token = ss.session.access_token;
        if (client.sync(null, 0)) |test_resp_val| {
            var test_resp = test_resp_val;
            test_resp.deinit();
            // Token works — use stored session
            access_token = ss.session.access_token;
            user_id = ss.session.user_id;
        } else |_| {
            // Token expired — fall back to password login
            client.access_token = null;
            ss.deinit();
            stored_session = null;
        }
    }

    if (stored_session == null) {
        // Fresh login with password
        var login_resp = client.login(ctx.config.username, ctx.config.password) catch {
            _ = ctx.ui_queue.push(.{ .connection_state = .err });
            return;
        };

        const parsed_login = std.json.parseFromSlice(
            json_types.LoginResponse,
            allocator,
            login_resp.body,
            .{ .ignore_unknown_fields = true },
        ) catch {
            login_resp.deinit();
            _ = ctx.ui_queue.push(.{ .connection_state = .err });
            return;
        };

        access_token = allocator.dupe(u8, parsed_login.value.access_token) catch return;
        owns_token = true;
        user_id = allocator.dupe(u8, parsed_login.value.user_id) catch return;
        owns_uid = true;

        // Persist the new session
        config.saveSession(.{
            .homeserver = ctx.config.homeserver,
            .username = ctx.config.username,
            .access_token = access_token,
            .user_id = user_id,
            .device_id = parsed_login.value.device_id,
        });

        parsed_login.deinit();
        login_resp.deinit();
    }

    defer {
        if (owns_token) allocator.free(@constCast(access_token));
        if (owns_uid) allocator.free(@constCast(user_id));
        if (stored_session) |*ss| ss.deinit();
    }

    client.access_token = access_token;
    _ = ctx.ui_queue.push(.{ .connection_state = .connected });

    // Publish auth credentials so the action thread (spawned from main) can start.
    var auth_creds = AuthCredentials{
        .access_token = access_token,
        .user_id = user_id,
    };
    ctx.auth_store.publish(&auth_creds);

    // Init processor
    var processor = sync_engine.SyncProcessor.init(allocator);
    defer processor.deinit();
    processor.self_user_id = user_id;

    // Sync loop
    var retry_delay_ms: u64 = 1000;
    const max_retry_delay_ms: u64 = 60_000;

    while (!ctx.should_stop.load(.acquire)) {
        // Perform sync
        const sync_result = client.sync(processor.next_batch, ctx.config.sync_timeout_ms) catch {
            _ = ctx.ui_queue.push(.{ .connection_state = .err });

            if (ctx.should_stop.load(.acquire)) break;

            // Exponential backoff
            sleepMs(ctx.io, retry_delay_ms);
            retry_delay_ms = @min(retry_delay_ms * 2, max_retry_delay_ms);
            continue;
        };

        // Parse sync response
        var sync_resp = sync_result;
        const parsed = std.json.parseFromSlice(
            json_types.SyncResponse,
            allocator,
            sync_resp.body,
            .{ .ignore_unknown_fields = true },
        ) catch {
            sync_resp.deinit();
            _ = ctx.ui_queue.push(.{ .connection_state = .err });
            continue;
        };

        // Process sync response
        var event_arena = std.heap.ArenaAllocator.init(allocator);
        _ = processor.process(parsed.value, event_arena.allocator()) catch {};
        event_arena.deinit();

        // Auto-join invited rooms (trusted family environment — accept all invites).
        // Must happen after process() so the next sync picks up the joined room state.
        if (parsed.value.rooms) |rooms| {
            if (rooms.invite) |invite_map| {
                var invite_it = invite_map.map.iterator();
                while (invite_it.next()) |entry| {
                    client.joinRoom(entry.key_ptr.*) catch {};
                }
            }
        }

        // Backfill rooms with limited timelines (sync gap — missed messages).
        // Uses prev_batch token stored by the processor to paginate backward.
        if (parsed.value.rooms) |rooms| {
            if (rooms.join) |join_map| {
                var join_it = join_map.map.iterator();
                while (join_it.next()) |entry| {
                    const joined = entry.value_ptr;
                    if (joined.timeline) |tl| {
                        if (tl.limited != null and tl.limited.?) {
                            backfillRoom(allocator, &client, &processor, entry.key_ptr.*);
                        }
                    }
                }
            }
        }

        parsed.deinit();
        sync_resp.deinit();

        // Build and publish snapshot (OwnedSnapshot bundles arena for proper lifecycle)
        var snapshot_arena = std.heap.ArenaAllocator.init(allocator);
        if (processor.buildSnapshot(snapshot_arena.allocator())) |snapshot| {
            const owned = allocator.create(types.OwnedSnapshot) catch {
                snapshot_arena.deinit();
                continue;
            };
            owned.* = .{
                .snapshot = snapshot,
                .arena = snapshot_arena,
                .alloc = allocator,
            };
            ctx.state_store.publish(owned);
            _ = ctx.ui_queue.push(.{ .connection_state = .syncing });
            _ = ctx.ui_queue.push(.snapshot_ready);
        } else |_| {
            snapshot_arena.deinit();
        }

        // Reset retry delay on success
        retry_delay_ms = 1000;
    }
}

// ---------------------------------------------------------------------------
// Action thread — executes uploads/sends immediately, independent of sync.
// Spawned from main. Waits for auth credentials from sync thread before
// processing actions.
// ---------------------------------------------------------------------------

pub fn actionThreadMain(actx: *ActionThreadContext) void {
    // Wait for sync thread to publish auth credentials after login.
    // While waiting, drain the mailbox to stay responsive to close().
    var creds: ?*const AuthCredentials = null;
    while (creds == null) {
        creds = actx.auth_store.load();
        if (creds != null) break;
        // Not ready yet — check if mailbox is closing
        if (actx.action_queue.isClosed()) return;
        // Brief sleep to avoid spinning
        var ts = std.os.linux.timespec{ .sec = 0, .nsec = 50_000_000 }; // 50ms
        _ = std.os.linux.nanosleep(&ts, null);
    }

    const auth = creds.?;
    var client = http.MatrixHttpClient.init(actx.allocator, actx.io, actx.config.homeserver);
    defer client.deinit();
    client.access_token = auth.access_token;

    // Block on mailbox receive — no polling, no sleep
    while (actx.action_queue.receive()) |action| {
        executeAction(actx, &client, action, auth.user_id);
    }
    // receive() returned null → mailbox closed → clean exit
}

/// Execute a single action from the UI thread.
fn executeAction(actx: *ActionThreadContext, client: *http.MatrixHttpClient, action: types.Action, self_user_id: []const u8) void {
    const S = struct {
        var txn_counter: u32 = 0;
    };

    switch (action) {
        .send_read_receipt => |rr| {
            const room_id = rr.room_id_buf[0..rr.room_id_len];
            const event_id = rr.event_id_buf[0..rr.event_id_len];
            client.sendReadReceipt(room_id, event_id) catch {};
        },
        .upload_and_send_voice => |msg| {
            const ogg_data = msg.ogg_data[0..msg.ogg_len];
            var room_id = msg.room_id_buf[0..msg.room_id_len];

            S.txn_counter += 1;
            const txn_id = S.txn_counter;

            // If no room yet: reuse an existing DM for this contact from
            // m.direct (TS dm-room-service.getOrCreateDmRoom parity), or
            // create a fresh one and tag it.
            var create_resp: ?http.RawResponse = null;
            var get_resp: ?http.RawResponse = null;
            if (room_id.len == 0) {
                const contact_id = msg.contact_id_buf[0..msg.contact_id_len];
                if (contact_id.len == 0) {
                    _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                    return;
                }

                if (client.getAccountData(self_user_id, "m.direct")) |resp| {
                    get_resp = resp;
                    if (findRoomForContact(resp.body, contact_id)) |existing| {
                        room_id = existing;
                    }
                } else |_| {}

                if (room_id.len == 0) {
                    var cr = client.createRoom(contact_id) catch {
                        _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                        return;
                    };

                    room_id = parseRoomId(cr.body) orelse {
                        cr.deinit();
                        _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                        return;
                    };

                    updateMDirect(client, self_user_id, contact_id, room_id);
                    create_resp = cr;
                }
            }
            defer if (create_resp) |*cr| cr.deinit();
            defer if (get_resp) |*r| r.deinit();

            // Upload media
            var upload_resp = client.uploadMedia(ogg_data) catch {
                _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                return;
            };

            const mxc_url = parseMxcUrl(upload_resp.body) orelse {
                upload_resp.deinit();
                _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                return;
            };

            client.sendVoiceMessage(room_id, mxc_url, msg.duration_ms, txn_id) catch {
                upload_resp.deinit();
                _ = actx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                return;
            };

            upload_resp.deinit();
            _ = actx.ui_queue.push(.{ .send_complete = .{ .txn_id = txn_id } });
        },
        .download_and_play => |dl| {
            const mxc_url = dl.mxc_url_buf[0..dl.mxc_url_len];
            var resp = client.downloadMedia(mxc_url) catch {
                _ = actx.ui_queue.push(.playback_error);
                return;
            };
            const ogg_copy = actx.allocator.dupe(u8, resp.body) catch {
                resp.deinit();
                _ = actx.ui_queue.push(.playback_error);
                return;
            };
            resp.deinit();
            if (build_options.use_audio) {
                if (actx.audio_cmd_queue) |acq| {
                    _ = acq.send(.{ .play = .{
                        .ogg_data = ogg_copy,
                        .allocator = actx.allocator,
                    } });
                }
            } else {
                actx.allocator.free(ogg_copy);
                _ = actx.ui_queue.push(.playback_error);
            }
        },
        .set_display_name => |dn| {
            const name = dn.name_buf[0..dn.name_len];
            client.setDisplayName(self_user_id, name) catch {};
        },
        .delete_message => |dm| {
            const room_id = dm.room_id_buf[0..dm.room_id_len];
            const event_id = dm.event_id_buf[0..dm.event_id_len];
            S.txn_counter += 1;
            client.redactEvent(room_id, event_id, S.txn_counter) catch {};
        },
    }
}

/// Backfill missed messages for a room with a limited timeline.
/// Fetches older messages using GET /messages with the room's prev_batch token,
/// then feeds them through the sync processor for dedup and extraction.
fn backfillRoom(allocator: std.mem.Allocator, client: *http.MatrixHttpClient, processor: *sync_engine.SyncProcessor, room_id: []const u8) void {
    const room = processor.rooms.getPtr(room_id) orelse return;
    const prev_batch = room.prev_batch orelse return;

    var resp = client.getMessages(room_id, prev_batch, 50) catch return;
    defer resp.deinit();

    // Parse the /messages response — we only need the chunk (array of events)
    // Response format: {"chunk":[...], "end":"token", "start":"token"}
    const parsed = std.json.parseFromSlice(
        struct { chunk: ?[]const json_types.MatrixEvent = null },
        allocator,
        resp.body,
        .{ .ignore_unknown_fields = true },
    ) catch return;
    defer parsed.deinit();

    const chunk = parsed.value.chunk orelse return;

    // Process backfilled events (oldest first — API returns newest first with dir=b,
    // but we iterate forward since dedup handles ordering)
    for (chunk) |event| {
        // Dedup against existing timeline
        if (event.event_id) |eid| {
            const dup = room.timeline_event_ids.getOrPut(processor.gpa, eid) catch continue;
            if (dup.found_existing) continue;
            dup.key_ptr.* = processor.dupe(eid) catch continue;
        }

        // Extract voice messages
        if (event.type) |evt_type| {
            if (std.mem.eql(u8, evt_type, "m.room.message")) {
                if (processor.extractVoiceMessageOwned(event) catch null) |vm| {
                    room.voice_messages.append(processor.gpa, vm) catch {};
                }
            }
        }
    }
}

// Make parse helpers accessible to tests within this file
pub fn parseMxcUrl(json_body: []const u8) ?[]const u8 {
    // Simple extraction of "content_uri":"mxc://..."
    const key = "\"content_uri\":\"";
    const start = std.mem.indexOf(u8, json_body, key) orelse return null;
    const val_start = start + key.len;
    const end = std.mem.indexOfPos(u8, json_body, val_start, "\"") orelse return null;
    return json_body[val_start..end];
}

/// Scan an m.direct JSON body for the first room_id mapped to `contact_id`.
/// Returns a slice into `json_body` — caller must ensure the buffer outlives
/// the returned slice (or copy it).
pub fn findRoomForContact(json_body: []const u8, contact_id: []const u8) ?[]const u8 {
    var search_buf: [256]u8 = undefined;
    const contact_key = std.fmt.bufPrint(&search_buf, "\"{s}\":", .{contact_id}) catch return null;
    const key_pos = std.mem.indexOf(u8, json_body, contact_key) orelse return null;
    const after_key = key_pos + contact_key.len;
    // Expect an array — find the first quoted string inside it.
    const bracket_open = std.mem.indexOfPos(u8, json_body, after_key, "[") orelse return null;
    const first_quote = std.mem.indexOfPos(u8, json_body, bracket_open, "\"") orelse return null;
    const val_start = first_quote + 1;
    const end = std.mem.indexOfPos(u8, json_body, val_start, "\"") orelse return null;
    if (end == val_start) return null;
    return json_body[val_start..end];
}

pub fn parseRoomId(json_body: []const u8) ?[]const u8 {
    const key = "\"room_id\":\"";
    const start = std.mem.indexOf(u8, json_body, key) orelse return null;
    const val_start = start + key.len;
    const end = std.mem.indexOfPos(u8, json_body, val_start, "\"") orelse return null;
    return json_body[val_start..end];
}

/// Update m.direct account data to include a new DM room for a contact.
/// GET current m.direct → append room → PUT back. Best-effort (errors ignored).
fn updateMDirect(client: *http.MatrixHttpClient, self_user_id: []const u8, contact_id: []const u8, room_id: []const u8) void {
    // GET current m.direct (may 404 if no DMs exist yet — that's fine)
    var existing: []const u8 = "{}";
    var get_resp: ?http.RawResponse = null;
    if (client.getAccountData(self_user_id, "m.direct")) |resp| {
        get_resp = resp;
        existing = resp.body;
    } else |_| {}
    defer if (get_resp) |*r| r.deinit();

    // Build updated m.direct JSON. Insert the new mapping before the closing '}'.
    // If contact already has rooms: {"@contact":[..."!existing"],...} → inject ,"!new" before ']'
    // If contact is new: inject "@contact":["!room"] before '}'
    var buf: [4096]u8 = undefined;

    // Check if this contact already has an entry
    var search_buf: [256]u8 = undefined;
    const contact_key = std.fmt.bufPrint(&search_buf, "\"{s}\":", .{contact_id}) catch return;

    const body = if (std.mem.indexOf(u8, existing, contact_key)) |_| blk: {
        // Contact exists — find their array's closing ']' and insert the new room_id
        const key_pos = std.mem.indexOf(u8, existing, contact_key).?;
        const after_key = key_pos + contact_key.len;
        const bracket = std.mem.indexOfPos(u8, existing, after_key, "]") orelse break :blk @as(?[]const u8, null);
        break :blk std.fmt.bufPrint(&buf, "{s}\"{s}\"{s}", .{
            existing[0..bracket],
            room_id,
            existing[bracket..],
        }) catch null;
    } else blk: {
        // Contact is new — insert before closing '}'
        const close = std.mem.lastIndexOf(u8, existing, "}") orelse break :blk @as(?[]const u8, null);
        const prefix = existing[0..close];
        // Add comma if there's existing content (not just "{}")
        const needs_comma = close > 1;
        break :blk std.fmt.bufPrint(&buf, "{s}{s}\"{s}\":[\"{s}\"]{s}", .{
            prefix,
            if (needs_comma) "," else "",
            contact_id,
            room_id,
            existing[close..],
        }) catch null;
    };

    if (body) |b| {
        client.setAccountData(self_user_id, "m.direct", b) catch {};
    }
}

// ---------------------------------------------------------------------------
// Tests — JSON parse helpers
// ---------------------------------------------------------------------------

const testing = std.testing;

test "parseMxcUrl: extracts mxc URL from upload response" {
    const body =
        \\{"content_uri":"mxc://wata.local/abc123"}
    ;
    const url = parseMxcUrl(body);
    try testing.expect(url != null);
    try testing.expectEqualStrings("mxc://wata.local/abc123", url.?);
}

test "parseMxcUrl: returns null for missing key" {
    try testing.expect(parseMxcUrl("{}") == null);
    try testing.expect(parseMxcUrl("") == null);
}

test "parseRoomId: extracts room ID from createRoom response" {
    const body =
        \\{"room_id":"!abc123:wata.local"}
    ;
    const id = parseRoomId(body);
    try testing.expect(id != null);
    try testing.expectEqualStrings("!abc123:wata.local", id.?);
}

test "parseRoomId: returns null for missing key" {
    try testing.expect(parseRoomId("{}") == null);
}

test "findRoomForContact: extracts first room for contact" {
    const body =
        \\{"@bob:test":["!first:test","!second:test"],"@charlie:test":["!c:test"]}
    ;
    try testing.expectEqualStrings("!first:test", findRoomForContact(body, "@bob:test").?);
    try testing.expectEqualStrings("!c:test", findRoomForContact(body, "@charlie:test").?);
    try testing.expect(findRoomForContact(body, "@nobody:test") == null);
}

test "findRoomForContact: empty body or no array" {
    try testing.expect(findRoomForContact("{}", "@bob:test") == null);
    try testing.expect(findRoomForContact(
        \\{"@bob:test":[]}
    , "@bob:test") == null);
}
