/// Integration tests for MatrixClient — exercise the live runtime against a
/// real Matrix homeserver. Skipped when the homeserver is unreachable.
///
/// Mirrors the scenarios in test/integration/*.ts but scoped to the Matrix
/// client core (no UI, no audio thread). Audio is decoupled by passing
/// `audio_cmd_queue = null` to MatrixClient.init — actions that would have
/// played audio surface as `playback_error` UI events instead.
///
/// The Conduit homeserver and alice/bob test users are provisioned by the
/// shared `test/docker/setup.sh` harness (same one used by `pnpm dev:server`
/// and the TypeScript integration suite). The `just fb-test-integration`
/// recipe brings it up automatically before invoking `zig build test-integration`.
///
/// Defaults (override via env):
///   WATA_TEST_HOMESERVER  http://localhost:8008
///   WATA_TEST_USER1       alice
///   WATA_TEST_PASS1       testpass123
///   WATA_TEST_USER2       bob
///   WATA_TEST_PASS2       testpass123

const std = @import("std");
const testing = std.testing;
const Io = std.Io;

const client_mod = @import("client.zig");
const http = @import("http.zig");
const types = @import("../types.zig");

const DEFAULT_HOMESERVER = "http://localhost:8008";
const DEFAULT_USER1 = "alice";
const DEFAULT_USER2 = "bob";
const DEFAULT_PASS = "testpass123";

const TestEnv = struct {
    homeserver: []const u8,
    user1: []const u8,
    pass1: []const u8,
    user2: []const u8,
    pass2: []const u8,
};

/// Fetch an env var via libc getenv. Returns the default if unset.
/// All returned slices borrow from libc/static memory — no allocation.
fn envOr(name: [*:0]const u8, default: []const u8) []const u8 {
    if (std.c.getenv(name)) |raw| return std.mem.span(raw);
    return default;
}

fn loadEnv(_: std.mem.Allocator) TestEnv {
    return .{
        .homeserver = envOr("WATA_TEST_HOMESERVER", DEFAULT_HOMESERVER),
        .user1 = envOr("WATA_TEST_USER1", DEFAULT_USER1),
        .pass1 = envOr("WATA_TEST_PASS1", DEFAULT_PASS),
        .user2 = envOr("WATA_TEST_USER2", DEFAULT_USER2),
        .pass2 = envOr("WATA_TEST_PASS2", DEFAULT_PASS),
    };
}

/// Probe the homeserver by issuing a GET /_matrix/client/versions. Returns
/// `error.SkipZigTest` when unreachable so the rest of the integration tests
/// are skipped cleanly on machines without a running Conduit.
fn requireHomeserver(allocator: std.mem.Allocator, io: Io, base_url: []const u8) !void {
    var hclient: std.http.Client = .{ .allocator = allocator, .io = io };
    defer hclient.deinit();

    var url_buf: [512]u8 = undefined;
    const url = std.fmt.bufPrint(&url_buf, "{s}/_matrix/client/versions", .{base_url}) catch return error.SkipZigTest;

    var response_writer: Io.Writer.Allocating = .init(allocator);
    defer response_writer.deinit();

    const result = hclient.fetch(.{
        .location = .{ .url = url },
        .method = .GET,
        .response_writer = &response_writer.writer,
    }) catch {
        std.debug.print("\n[integration] homeserver unreachable at {s} — skipping\n", .{base_url});
        return error.SkipZigTest;
    };

    if (result.status != .ok) {
        std.debug.print("\n[integration] homeserver returned {} — skipping\n", .{result.status});
        return error.SkipZigTest;
    }
}

// ---------------------------------------------------------------------------
// Action builders — mirror the helpers in src/fbclient/src/applets/wata.zig
// so tests don't need to know the inline-buffer layout.
// ---------------------------------------------------------------------------

fn buildSendVoiceAction(
    contact_id: []const u8,
    room_id: []const u8,
    ogg_data: []const u8,
    duration_ms: u64,
) types.Action {
    std.debug.assert(contact_id.len <= 128);
    std.debug.assert(room_id.len <= 128);
    var action = types.Action{ .upload_and_send_voice = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(room_id.len),
        .contact_id_buf = undefined,
        .contact_id_len = @intCast(contact_id.len),
        .ogg_data = ogg_data.ptr,
        .ogg_len = @intCast(ogg_data.len),
        .duration_ms = duration_ms,
    } };
    @memcpy(action.upload_and_send_voice.room_id_buf[0..room_id.len], room_id);
    @memcpy(action.upload_and_send_voice.contact_id_buf[0..contact_id.len], contact_id);
    return action;
}

fn buildReadReceiptAction(room_id: []const u8, event_id: []const u8) types.Action {
    std.debug.assert(room_id.len <= 128);
    std.debug.assert(event_id.len <= 128);
    var action = types.Action{ .send_read_receipt = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(room_id.len),
        .event_id_buf = undefined,
        .event_id_len = @intCast(event_id.len),
    } };
    @memcpy(action.send_read_receipt.room_id_buf[0..room_id.len], room_id);
    @memcpy(action.send_read_receipt.event_id_buf[0..event_id.len], event_id);
    return action;
}

fn buildDeleteAction(room_id: []const u8, event_id: []const u8) types.Action {
    std.debug.assert(room_id.len <= 128);
    std.debug.assert(event_id.len <= 128);
    var action = types.Action{ .delete_message = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(room_id.len),
        .event_id_buf = undefined,
        .event_id_len = @intCast(event_id.len),
    } };
    @memcpy(action.delete_message.room_id_buf[0..room_id.len], room_id);
    @memcpy(action.delete_message.event_id_buf[0..event_id.len], event_id);
    return action;
}

// Minimal valid Ogg page (just the magic) — Matrix media repo accepts arbitrary
// bytes; we don't need a real Opus stream to exercise upload + send.
const FAKE_OGG: []const u8 = "OggS\x00\x02\x00\x00\x00\x00\x00\x00\x00\x00";

// ---------------------------------------------------------------------------
// Snapshot predicates for waitForSnapshot
// ---------------------------------------------------------------------------

const SelfReadyCtx = struct {};
fn selfReady(_: SelfReadyCtx, snap: *const types.StateSnapshot) bool {
    return snap.self_user != null;
}

const HasContactCtx = struct { contact_id: []const u8 };
fn hasContact(ctx: HasContactCtx, snap: *const types.StateSnapshot) bool {
    for (snap.contacts) |c| {
        if (std.mem.eql(u8, c.user.id, ctx.contact_id)) return true;
    }
    return false;
}

const HasMessageCtx = struct { contact_id: []const u8, min_count: usize };
fn hasMessageFromContact(ctx: HasMessageCtx, snap: *const types.StateSnapshot) bool {
    for (snap.conversations) |conv| {
        if (conv.contact) |c| {
            if (std.mem.eql(u8, c.user.id, ctx.contact_id) and conv.messages.len >= ctx.min_count) return true;
        }
    }
    return false;
}

const MessagePlayedCtx = struct { contact_id: []const u8, event_id: []const u8 };
fn messagePlayed(ctx: MessagePlayedCtx, snap: *const types.StateSnapshot) bool {
    for (snap.conversations) |conv| {
        if (conv.contact) |c| {
            if (!std.mem.eql(u8, c.user.id, ctx.contact_id)) continue;
            for (conv.messages) |m| {
                if (std.mem.eql(u8, m.id, ctx.event_id) and m.is_played) return true;
            }
        }
    }
    return false;
}

test "integration: login and reach syncing state" {
    const allocator = testing.allocator;

    var threaded = std.Io.Threaded.init(allocator, .{});
    defer threaded.deinit();
    const io = threaded.io();

    const env = loadEnv(allocator);

    try requireHomeserver(allocator, io, env.homeserver);

    var client = client_mod.MatrixClient.init(
        allocator,
        io,
        .{
            .homeserver = env.homeserver,
            .username = env.user1,
            .password = env.pass1,
            .sync_timeout_ms = 3000,
        },
        null, // audio stubbed
    );
    defer client.deinit();

    try client.start();

    // Connection should reach `syncing` within 15s of login.
    try client.waitForConnection(.syncing, 15_000);

    // First snapshot with self_user populated should arrive shortly after.
    const owned = try client.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer owned.release();

    try testing.expect(owned.snapshot.self_user != null);
}

// ---------------------------------------------------------------------------
// TestPair — alice + bob, both syncing, ready to interact.
// Sets up two MatrixClients sharing one Threaded io.
// ---------------------------------------------------------------------------

const TestPair = struct {
    threaded: *std.Io.Threaded,
    alice: *client_mod.MatrixClient,
    bob: *client_mod.MatrixClient,
    allocator: std.mem.Allocator,

    fn init(allocator: std.mem.Allocator, env: TestEnv) !TestPair {
        // Probe the homeserver *before* allocating anything so the skip path
        // doesn't leak. Use a throwaway Threaded io for the probe.
        {
            var probe = std.Io.Threaded.init(allocator, .{});
            defer probe.deinit();
            try requireHomeserver(allocator, probe.io(), env.homeserver);
        }

        const threaded = try allocator.create(std.Io.Threaded);
        errdefer allocator.destroy(threaded);
        threaded.* = std.Io.Threaded.init(allocator, .{});
        errdefer threaded.deinit();
        const io = threaded.io();

        const alice = try allocator.create(client_mod.MatrixClient);
        errdefer allocator.destroy(alice);
        alice.* = client_mod.MatrixClient.init(allocator, io, .{
            .homeserver = env.homeserver,
            .username = env.user1,
            .password = env.pass1,
            .sync_timeout_ms = 3000,
        }, null);

        const bob = try allocator.create(client_mod.MatrixClient);
        errdefer allocator.destroy(bob);
        bob.* = client_mod.MatrixClient.init(allocator, io, .{
            .homeserver = env.homeserver,
            .username = env.user2,
            .password = env.pass2,
            .sync_timeout_ms = 3000,
        }, null);

        try alice.start();
        try bob.start();

        try alice.waitForConnection(.syncing, 15_000);
        try bob.waitForConnection(.syncing, 15_000);

        return .{ .threaded = threaded, .alice = alice, .bob = bob, .allocator = allocator };
    }

    fn deinit(self: *TestPair) void {
        self.alice.deinit();
        self.bob.deinit();
        self.allocator.destroy(self.alice);
        self.allocator.destroy(self.bob);
        self.threaded.deinit();
        self.allocator.destroy(self.threaded);
    }
};

test "integration: alice and bob both reach syncing" {
    const allocator = testing.allocator;
    const env = loadEnv(allocator);

    var pair = TestPair.init(allocator, env) catch |err| switch (err) {
        error.SkipZigTest => return error.SkipZigTest,
        else => return err,
    };
    defer pair.deinit();

    // Both should publish a snapshot with self_user populated.
    const alice_snap = try pair.alice.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer alice_snap.release();
    const bob_snap = try pair.bob.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer bob_snap.release();

    try testing.expect(alice_snap.snapshot.self_user != null);
    try testing.expect(bob_snap.snapshot.self_user != null);
}

test "integration: alice sends voice to bob, bob sees the message" {
    const allocator = testing.allocator;
    const env = loadEnv(allocator);

    var pair = TestPair.init(allocator, env) catch |err| switch (err) {
        error.SkipZigTest => return error.SkipZigTest,
        else => return err,
    };
    defer pair.deinit();

    // Resolve bob's Matrix user_id from his own snapshot.
    const bob_snap = try pair.bob.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer bob_snap.release();
    const bob_user_id = bob_snap.snapshot.self_user.?.id;

    // Alice sends a voice message to bob (room_id empty → DM auto-created).
    _ = pair.alice.sendAction(buildSendVoiceAction(bob_user_id, "", FAKE_OGG, 1000));

    // Wait for alice's send_complete (action thread finished).
    var sent = false;
    const send_deadline = nowMs() + 20_000;
    while (!sent and nowMs() < send_deadline) {
        if (pair.alice.pollEvent()) |ev| {
            switch (ev) {
                .send_complete => sent = true,
                .send_failed => return error.SendFailed,
                else => {},
            }
        } else {
            var ts = std.os.linux.timespec{ .sec = 0, .nsec = 20_000_000 };
            _ = std.os.linux.nanosleep(&ts, null);
        }
    }
    try testing.expect(sent);

    // Bob's snapshot should contain a conversation from alice with at least one message.
    const alice_user_id = (try pair.alice.waitForSnapshot(SelfReadyCtx{}, selfReady, 5_000));
    defer alice_user_id.release();
    const alice_id = alice_user_id.snapshot.self_user.?.id;

    const bob_after = try pair.bob.waitForSnapshot(
        HasMessageCtx{ .contact_id = alice_id, .min_count = 1 },
        hasMessageFromContact,
        20_000,
    );
    defer bob_after.release();
}

test "integration: bob acks alice's message with a read receipt" {
    const allocator = testing.allocator;
    const env = loadEnv(allocator);

    var pair = TestPair.init(allocator, env) catch |err| switch (err) {
        error.SkipZigTest => return error.SkipZigTest,
        else => return err,
    };
    defer pair.deinit();

    const bob_snap = try pair.bob.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer bob_snap.release();
    const bob_user_id = bob_snap.snapshot.self_user.?.id;

    // Alice sends a voice message to bob.
    _ = pair.alice.sendAction(buildSendVoiceAction(bob_user_id, "", FAKE_OGG, 1000));

    const alice_self = try pair.alice.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer alice_self.release();
    const alice_id = alice_self.snapshot.self_user.?.id;

    // Wait for bob to see the message and capture its id + room_id.
    const bob_after = try pair.bob.waitForSnapshot(
        HasMessageCtx{ .contact_id = alice_id, .min_count = 1 },
        hasMessageFromContact,
        20_000,
    );
    defer bob_after.release();

    // Find the conversation + first message.
    var room_id: []const u8 = "";
    var event_id: []const u8 = "";
    for (bob_after.snapshot.conversations) |conv| {
        if (conv.contact) |c| if (std.mem.eql(u8, c.user.id, alice_id) and conv.messages.len > 0) {
            room_id = conv.room_id;
            event_id = conv.messages[conv.messages.len - 1].id;
            break;
        };
    }
    try testing.expect(room_id.len > 0);
    try testing.expect(event_id.len > 0);

    // Bob sends a read receipt — fire-and-forget; only assert that the action thread accepts it.
    try testing.expect(pair.bob.sendAction(buildReadReceiptAction(room_id, event_id)));
}

test "integration: bob's own snapshot reflects the receipt he sent" {
    const allocator = testing.allocator;
    const env = loadEnv(allocator);

    var pair = TestPair.init(allocator, env) catch |err| switch (err) {
        error.SkipZigTest => return error.SkipZigTest,
        else => return err,
    };
    defer pair.deinit();

    const bob_snap = try pair.bob.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer bob_snap.release();
    const bob_user_id = bob_snap.snapshot.self_user.?.id;

    _ = pair.alice.sendAction(buildSendVoiceAction(bob_user_id, "", FAKE_OGG, 1000));

    const alice_self = try pair.alice.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer alice_self.release();
    const alice_id = alice_self.snapshot.self_user.?.id;

    // Wait for bob to see the message.
    const bob_after = try pair.bob.waitForSnapshot(
        HasMessageCtx{ .contact_id = alice_id, .min_count = 1 },
        hasMessageFromContact,
        20_000,
    );
    defer bob_after.release();

    var room_id: []const u8 = "";
    var event_id: []const u8 = "";
    for (bob_after.snapshot.conversations) |conv| {
        if (conv.contact) |c| if (std.mem.eql(u8, c.user.id, alice_id) and conv.messages.len > 0) {
            room_id = conv.room_id;
            event_id = conv.messages[conv.messages.len - 1].id;
            break;
        };
    }
    try testing.expect(room_id.len > 0);
    try testing.expect(event_id.len > 0);

    // Bob sends the receipt.
    try testing.expect(pair.bob.sendAction(buildReadReceiptAction(room_id, event_id)));

    // Round-trip: wait for bob's own snapshot to mark the message as played
    // (confirms the m.receipt ephemeral event came back and was ingested).
    const bob_played = try pair.bob.waitForSnapshot(
        MessagePlayedCtx{ .contact_id = alice_id, .event_id = event_id },
        messagePlayed,
        20_000,
    );
    defer bob_played.release();
}

test "integration: alice deletes (redacts) a message" {
    const allocator = testing.allocator;
    const env = loadEnv(allocator);

    var pair = TestPair.init(allocator, env) catch |err| switch (err) {
        error.SkipZigTest => return error.SkipZigTest,
        else => return err,
    };
    defer pair.deinit();

    const bob_snap = try pair.bob.waitForSnapshot(SelfReadyCtx{}, selfReady, 15_000);
    defer bob_snap.release();
    const bob_user_id = bob_snap.snapshot.self_user.?.id;

    _ = pair.alice.sendAction(buildSendVoiceAction(bob_user_id, "", FAKE_OGG, 1000));

    // Wait until alice's own snapshot reflects the sent message.
    const alice_after = try pair.alice.waitForSnapshot(
        HasMessageCtx{ .contact_id = bob_user_id, .min_count = 1 },
        hasMessageFromContact,
        20_000,
    );
    defer alice_after.release();

    var room_id: []const u8 = "";
    var event_id: []const u8 = "";
    for (alice_after.snapshot.conversations) |conv| {
        if (conv.contact) |c| if (std.mem.eql(u8, c.user.id, bob_user_id) and conv.messages.len > 0) {
            room_id = conv.room_id;
            event_id = conv.messages[conv.messages.len - 1].id;
            break;
        };
    }
    try testing.expect(room_id.len > 0);
    try testing.expect(event_id.len > 0);

    try testing.expect(pair.alice.sendAction(buildDeleteAction(room_id, event_id)));
}

fn nowMs() i64 {
    var ts: std.os.linux.timespec = undefined;
    _ = std.os.linux.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1_000_000);
}
