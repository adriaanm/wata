/// MatrixClient — bundles the Matrix runtime (sync + action threads, queues,
/// state store, auth) behind a single handle. The presentation layer (main.zig,
/// applets, or test harness) owns one `MatrixClient` and drives it via
/// `sendAction` / `pollEvent` / `acquireSnapshot`.
///
/// Mirrors the role of `WataClient` in src/shared/lib/wata-client for the TUI:
/// a stand-alone runtime that does not depend on display/input/audio.
///
/// Audio is fully decoupled: callers pass an optional audio command queue
/// pointer. When null, `download_and_play` actions emit `playback_error` instead
/// of routing to the audio thread, so integration tests can run with
/// `-Daudio=false` and skip audio entirely.

const std = @import("std");
const Io = std.Io;
const build_options = @import("build_options");
const http = @import("http.zig");
const sync_thread = @import("sync_thread.zig");
const types = @import("../types.zig");
const queue = @import("../queue.zig");
const mailbox_mod = @import("../mailbox.zig");

const audio_thread = if (build_options.use_audio) @import("../audio_thread.zig") else struct {
    pub const CommandQueue = void;
};

pub const Config = struct {
    homeserver: []const u8,
    username: []const u8,
    password: []const u8,
    sync_timeout_ms: u32 = 30_000,
};

pub const UiEventQueue = queue.BoundedQueue(types.UiEvent, 256);
pub const ActionQueue = sync_thread.ActionQueue;

pub const MatrixClient = struct {
    allocator: std.mem.Allocator,
    io: Io,
    config: Config,

    // Inter-thread state — owned by the client, referenced by thread contexts.
    ui_queue: UiEventQueue = .{},
    action_queue: ActionQueue = .{},
    state_store: types.StateStore = .{},
    auth_store: sync_thread.AuthStore = .{},
    should_stop: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),

    // Optional audio command queue — `null` means actions that need audio
    // will emit `playback_error` instead of routing to the audio thread.
    audio_cmd_queue: ?*audio_thread.CommandQueue = null,

    // Thread contexts — stored here so they outlive start()/stop() calls.
    sync_ctx: sync_thread.SyncThreadContext = undefined,
    action_ctx: sync_thread.ActionThreadContext = undefined,

    // Thread handles (null when not running).
    sync_handle: ?std.Thread = null,
    action_handle: ?std.Thread = null,

    pub fn init(
        allocator: std.mem.Allocator,
        io: Io,
        config: Config,
        audio_cmd_queue: ?*audio_thread.CommandQueue,
    ) MatrixClient {
        return .{
            .allocator = allocator,
            .io = io,
            .config = config,
            .audio_cmd_queue = audio_cmd_queue,
        };
    }

    /// Spawn sync + action threads. After this, the client is live: it will
    /// log in, start syncing, publish snapshots, and execute actions.
    pub fn start(self: *MatrixClient) !void {
        const thread_config = sync_thread.Config{
            .homeserver = self.config.homeserver,
            .username = self.config.username,
            .password = self.config.password,
            .sync_timeout_ms = self.config.sync_timeout_ms,
        };

        self.sync_ctx = .{
            .config = thread_config,
            .ui_queue = &self.ui_queue,
            .action_queue = &self.action_queue,
            .audio_cmd_queue = self.audio_cmd_queue,
            .state_store = &self.state_store,
            .should_stop = &self.should_stop,
            .auth_store = &self.auth_store,
            .allocator = self.allocator,
            .io = self.io,
        };
        self.action_ctx = .{
            .config = thread_config,
            .ui_queue = &self.ui_queue,
            .action_queue = &self.action_queue,
            .audio_cmd_queue = self.audio_cmd_queue,
            .auth_store = &self.auth_store,
            .allocator = self.allocator,
            .io = self.io,
        };

        self.sync_handle = try std.Thread.spawn(.{}, sync_thread.syncThreadMain, .{&self.sync_ctx});
        self.action_handle = try std.Thread.spawn(.{}, sync_thread.actionThreadMain, .{&self.action_ctx});
    }

    /// Signal threads to stop and join them. Idempotent.
    pub fn stop(self: *MatrixClient) void {
        self.should_stop.store(true, .release);
        self.action_queue.close();
        if (self.action_handle) |h| {
            h.join();
            self.action_handle = null;
        }
        if (self.sync_handle) |h| {
            h.join();
            self.sync_handle = null;
        }
    }

    /// Free any owned state. Safe to call after stop().
    pub fn deinit(self: *MatrixClient) void {
        if (self.sync_handle != null or self.action_handle != null) self.stop();
        self.state_store.deinit();
    }

    // --- Drive the client from the presentation layer / test harness ---

    /// Enqueue an action for the action thread to execute.
    pub fn sendAction(self: *MatrixClient, action: types.Action) bool {
        return self.action_queue.send(action);
    }

    /// Pop one UI event if available. Non-blocking.
    pub fn pollEvent(self: *MatrixClient) ?types.UiEvent {
        return self.ui_queue.pop();
    }

    /// Acquire the latest published snapshot, if any. Caller must `release()` it.
    pub fn acquireSnapshot(self: *MatrixClient) ?*types.OwnedSnapshot {
        return self.state_store.acquire();
    }

    // --- Test helpers ---

    /// Wait until `pollEvent` yields a `connection_state` matching `want` or
    /// `timeout_ms` elapses. Drains intermediate events.
    pub fn waitForConnection(self: *MatrixClient, want: types.ConnectionState, timeout_ms: u64) !void {
        const deadline = nowMs() + @as(i64, @intCast(timeout_ms));
        while (nowMs() < deadline) {
            while (self.pollEvent()) |ev| {
                switch (ev) {
                    .connection_state => |cs| if (cs == want) return,
                    else => {},
                }
            }
            sleepMs(self.io, 20);
        }
        return error.Timeout;
    }

    /// Wait until `state_store` publishes a snapshot for which `predicate`
    /// returns true. Takes ownership of each snapshot it inspects and releases
    /// all but the final match (which is returned, owned by the caller).
    pub fn waitForSnapshot(
        self: *MatrixClient,
        context: anytype,
        comptime predicate: fn (@TypeOf(context), *const types.StateSnapshot) bool,
        timeout_ms: u64,
    ) !*types.OwnedSnapshot {
        const deadline = nowMs() + @as(i64, @intCast(timeout_ms));
        while (nowMs() < deadline) {
            if (self.acquireSnapshot()) |owned| {
                if (predicate(context, &owned.snapshot)) return owned;
                owned.release();
            }
            sleepMs(self.io, 20);
        }
        return error.Timeout;
    }
};

fn nowMs() i64 {
    var ts: std.os.linux.timespec = undefined;
    _ = std.os.linux.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1_000_000);
}

fn sleepMs(io: Io, ms: u64) void {
    io.sleep(.fromMilliseconds(@intCast(ms)), .awake) catch {};
}
