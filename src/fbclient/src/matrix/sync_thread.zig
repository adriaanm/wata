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
const audio_thread = if (build_options.use_audio) @import("../audio_thread.zig") else struct {
    pub const CommandQueue = void;
};

const Config = struct {
    homeserver: []const u8,
    username: []const u8,
    password: []const u8,
    sync_timeout_ms: u32 = 30_000,
};

pub const DEFAULT_CONFIG = Config{
    .homeserver = "http://localhost:8008",
    .username = "alice",
    .password = "testpass123",
};

pub const SyncThreadContext = struct {
    config: Config,
    ui_queue: *queue.BoundedQueue(types.UiEvent, 256),
    action_queue: *queue.BoundedQueue(types.Action, 64),
    audio_cmd_queue: ?*audio_thread.CommandQueue,
    state_store: *types.StateStore,
    should_stop: *std.atomic.Value(bool),
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

    // Login
    _ = ctx.ui_queue.push(.{ .connection_state = .connecting });

    var client = http.MatrixHttpClient.init(allocator, ctx.io, ctx.config.homeserver);
    var login_resp = client.login(ctx.config.username, ctx.config.password) catch {
        _ = ctx.ui_queue.push(.{ .connection_state = .err });
        return;
    };

    // Parse login response and dupe strings before freeing
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

    const access_token = allocator.dupe(u8, parsed_login.value.access_token) catch return;
    defer allocator.free(access_token);
    const user_id = allocator.dupe(u8, parsed_login.value.user_id) catch return;
    defer allocator.free(user_id);

    parsed_login.deinit();
    login_resp.deinit();

    client.access_token = access_token;
    _ = ctx.ui_queue.push(.{ .connection_state = .connected });

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

        parsed.deinit();
        sync_resp.deinit();

        // Build and publish snapshot
        var snapshot_arena = std.heap.ArenaAllocator.init(allocator);
        if (processor.buildSnapshot(snapshot_arena.allocator())) |snapshot| {
            // Allocate snapshot struct itself in the arena
            const snap_ptr = snapshot_arena.allocator().create(types.StateSnapshot) catch {
                snapshot_arena.deinit();
                continue;
            };
            snap_ptr.* = snapshot;
            ctx.state_store.publish(snap_ptr);
            _ = ctx.ui_queue.push(.{ .connection_state = .syncing });
            _ = ctx.ui_queue.push(.snapshot_ready);
        } else |_| {
            snapshot_arena.deinit();
        }

        // Reset retry delay on success
        retry_delay_ms = 1000;

        // Execute pending UI actions (read receipts, etc.)
        drainActions(ctx, &client);
    }
}

/// Execute queued actions from the UI thread.
fn drainActions(ctx: *SyncThreadContext, client: *http.MatrixHttpClient) void {
    const S = struct {
        var txn_counter: u32 = 0;
    };

    while (ctx.action_queue.pop()) |action| {
        switch (action) {
            .send_read_receipt => |rr| {
                const room_id = rr.room_id_buf[0..rr.room_id_len];
                const event_id = rr.event_id_buf[0..rr.event_id_len];
                client.sendReadReceipt(room_id, event_id) catch {};
            },
            .upload_and_send_voice => |msg| {
                const ogg_data = msg.ogg_data[0..msg.ogg_len];

                const room_id = msg.room_id_buf[0..msg.room_id_len];

                S.txn_counter += 1;
                const txn_id = S.txn_counter;

                // Upload media
                var upload_resp = client.uploadMedia(ogg_data) catch {
                    _ = ctx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                    continue;
                };

                // Parse mxc:// URL from response: {"content_uri":"mxc://..."}
                const mxc_url = parseMxcUrl(upload_resp.body) orelse {
                    upload_resp.deinit();
                    _ = ctx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                    continue;
                };

                // Send voice message event
                client.sendVoiceMessage(room_id, mxc_url, msg.duration_ms, txn_id) catch {
                    upload_resp.deinit();
                    _ = ctx.ui_queue.push(.{ .send_failed = .{ .txn_id = txn_id } });
                    continue;
                };

                upload_resp.deinit();
                _ = ctx.ui_queue.push(.{ .send_complete = .{ .txn_id = txn_id } });
            },
            .download_and_play => |dl| {
                const mxc_url = dl.mxc_url_buf[0..dl.mxc_url_len];
                var resp = client.downloadMedia(mxc_url) catch {
                    _ = ctx.ui_queue.push(.playback_error);
                    continue;
                };
                // Dupe the data so it outlives the response buffer
                const ogg_copy = ctx.allocator.dupe(u8, resp.body) catch {
                    resp.deinit();
                    _ = ctx.ui_queue.push(.playback_error);
                    continue;
                };
                resp.deinit();
                // Send to audio thread for playback
                if (build_options.use_audio) {
                    if (ctx.audio_cmd_queue) |acq| {
                        _ = acq.push(.{ .play = .{
                            .ogg_data = ogg_copy,
                            .allocator = ctx.allocator,
                        } });
                    }
                } else {
                    ctx.allocator.free(ogg_copy);
                    _ = ctx.ui_queue.push(.playback_error);
                }
            },
        }
    }
}

fn parseMxcUrl(json_body: []const u8) ?[]const u8 {
    // Simple extraction of "content_uri":"mxc://..."
    const key = "\"content_uri\":\"";
    const start = std.mem.indexOf(u8, json_body, key) orelse return null;
    const val_start = start + key.len;
    const end = std.mem.indexOfPos(u8, json_body, val_start, "\"") orelse return null;
    return json_body[val_start..end];
}
