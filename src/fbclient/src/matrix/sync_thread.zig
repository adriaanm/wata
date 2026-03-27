/// Sync thread: login, then long-poll /sync in a loop.
/// Publishes StateSnapshots for the UI thread via atomic swap.
const std = @import("std");
const http = @import("http.zig");
const json_types = @import("json_types.zig");
const sync_engine = @import("sync_engine.zig");
const types = @import("../types.zig");
const queue = @import("../queue.zig");

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
    state_store: *types.StateStore,
    should_stop: *std.atomic.Value(bool),
    allocator: std.mem.Allocator,
};

fn sleepMs(ms: u64) void {
    const c = @cImport(@cInclude("time.h"));
    const ts = c.struct_timespec{
        .tv_sec = @intCast(ms / 1000),
        .tv_nsec = @intCast((ms % 1000) * 1_000_000),
    };
    _ = c.nanosleep(&ts, null);
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

    var client = http.MatrixHttpClient.init(allocator, ctx.config.homeserver);
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
            sleepMs(retry_delay_ms);
            retry_delay_ms = @min(retry_delay_ms * 2, max_retry_delay_ms);
            continue;
        };

        // Parse sync response
        const parsed = std.json.parseFromSlice(
            json_types.SyncResponse,
            allocator,
            sync_result.body,
            .{ .ignore_unknown_fields = true },
        ) catch {
            allocator.free(sync_result.full_buf);
            _ = ctx.ui_queue.push(.{ .connection_state = .err });
            continue;
        };

        // Process sync response
        var event_arena = std.heap.ArenaAllocator.init(allocator);
        _ = processor.process(parsed.value, event_arena.allocator()) catch {};
        event_arena.deinit();

        parsed.deinit();
        allocator.free(sync_result.body);

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
    }
}
