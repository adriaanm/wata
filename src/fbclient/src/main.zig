const std = @import("std");
const Io = std.Io;
const build_options = @import("build_options");
const display = @import("display.zig");
const input = @import("input.zig");
const shell_mod = @import("shell.zig");
const types = @import("types.zig");
const queue = @import("queue.zig");
const mailbox = @import("mailbox.zig");
const snake = @import("applets/snake.zig");
const clock_applet = @import("applets/clock.zig");
const charmap = @import("applets/charmap.zig");
const settings_applet = @import("applets/settings.zig");
const wata_applet = @import("applets/wata.zig");
const sync_thread = @import("matrix/sync_thread.zig");
const audio_thread = if (build_options.use_audio) @import("audio_thread.zig") else struct {};
const led = if (!build_options.use_sdl) @import("led.zig") else struct {};

const sdl = if (build_options.use_sdl) @import("sdl.zig").c else struct {};

var debug_mode: bool = false;
var g_io: Io = undefined;

fn debugLog(comptime fmt: []const u8, args: anytype) void {
    if (!debug_mode) return;
    var buf: [512]u8 = undefined;
    const msg = std.fmt.bufPrint(&buf, fmt ++ "\n", args) catch return;
    var stderr_buf: [1024]u8 = undefined;
    var file_writer = Io.File.Writer.initStreaming(Io.File.stderr(), g_io, &stderr_buf);
    file_writer.interface.writeAll(msg) catch return;
    file_writer.interface.flush() catch return;
}

pub fn main(init: std.process.Init) !void {
    debug_mode = build_options.debug_mode;
    g_io = init.io;
    const allocator = init.gpa;

    // Share io with applets that need it
    clock_applet.setIo(init.io);
    snake.setIo(init.io);

    debugLog("[main] wata-fb starting in debug mode", .{});

    // Inter-thread communication
    var ui_queue: queue.BoundedQueue(types.UiEvent, 256) = .{};
    var action_queue: mailbox.Mailbox(types.Action, 64) = .{};
    var state_store: types.StateStore = .{};
    var should_stop = std.atomic.Value(bool).init(false);

    // Audio queues (created before sync thread so it can reference them)
    var audio_cmd_queue: if (build_options.use_audio) audio_thread.CommandQueue else void = if (build_options.use_audio) .{} else {};
    var audio_evt_queue: if (build_options.use_audio) audio_thread.EventQueue else void = if (build_options.use_audio) .{} else {};

    // Sync thread context
    var sync_ctx = sync_thread.SyncThreadContext{
        .config = sync_thread.DEFAULT_CONFIG,
        .ui_queue = &ui_queue,
        .action_queue = &action_queue,
        .audio_cmd_queue = if (build_options.use_audio) &audio_cmd_queue else null,
        .state_store = &state_store,
        .should_stop = &should_stop,
        .allocator = allocator,
        .io = init.io,
    };

    // Spawn sync thread (unless offline mode — UI + audio only, no network)
    const sync_handle = if (!build_options.offline) blk: {
        debugLog("[main] connecting to {s} as {s}", .{ sync_ctx.config.homeserver, sync_ctx.config.username });
        break :blk std.Thread.spawn(.{}, sync_thread.syncThreadMain, .{&sync_ctx}) catch null;
    } else blk: {
        debugLog("[main] offline mode — network disabled", .{});
        break :blk @as(?std.Thread, null);
    };

    // Spawn audio thread (device only)
    var audio_ctx: if (build_options.use_audio) audio_thread.Context else void = if (build_options.use_audio) .{
        .cmd_queue = &audio_cmd_queue,
        .event_queue = &audio_evt_queue,
        .allocator = allocator,
    } else {};
    const audio_handle = if (build_options.use_audio)
        std.Thread.spawn(.{}, audio_thread.audioThreadMain, .{&audio_ctx}) catch null
    else
        null;

    defer {
        should_stop.store(true, .release);
        action_queue.close(); // wake action thread from blocking receive
        if (build_options.use_audio) {
            audio_cmd_queue.close(); // wake audio thread from blocking receive
            if (audio_handle) |h| h.join();
        }
        if (sync_handle) |h| h.join();
        state_store.deinit();
    }

    if (debug_mode) {
        // Headless debug mode — just log events, no UI
        debugLog("[main] running headless (no display)", .{});
        var connection: types.ConnectionState = .disconnected;
        var snapshot_count: u32 = 0;
        var current_owned: ?*types.OwnedSnapshot = null;
        defer if (current_owned) |o| o.release();

        while (!should_stop.load(.acquire)) {
            // Drain events
            while (ui_queue.pop()) |ev| {
                switch (ev) {
                    .connection_state => |cs| {
                        if (cs != connection) {
                            debugLog("[main] connection: {s}", .{@tagName(cs)});
                            connection = cs;
                        }
                    },
                    .snapshot_ready => {
                        if (state_store.acquire()) |new_owned| {
                            if (current_owned) |old| old.release();
                            current_owned = new_owned;
                            const snap = &new_owned.snapshot;
                            snapshot_count += 1;
                            debugLog("[main] snapshot #{d}: {d} contacts, {d} conversations", .{
                                snapshot_count,
                                snap.contacts.len,
                                snap.conversations.len,
                            });
                            for (snap.contacts) |contact| {
                                debugLog("[main]   contact: {s}", .{contact.user.display_name});
                            }
                            for (snap.conversations) |conv| {
                                const name = if (conv.contact) |c| c.user.display_name else "?";
                                debugLog("[main]   conv: {s} ({d} msgs, {d} unplayed)", .{
                                    name, conv.messages.len, conv.unplayed_count,
                                });
                            }
                        }
                    },
                    else => {},
                }
            }
            // Sleep 100ms
            init.io.sleep(.fromMilliseconds(100), .awake) catch {};
        }
        return;
    }

    // Normal UI mode
    var disp = try display.Backend.init();
    defer disp.deinit();

    // Device-specific init: backlight + button LEDs
    if (!build_options.use_sdl) {
        led.setBacklight(40); // full brightness
        led.setButtonBacklight(true);
    }
    defer if (!build_options.use_sdl) {
        led.setBacklight(0);
        led.setButtonBacklight(false);
        led.setRedLed(false);
        led.setGreenLed(false);
    };

    var inp = try input.Backend.init();
    defer inp.deinit();

    const applets = [_]shell_mod.Applet{
        wata_applet.applet,
        settings_applet.applet,
        snake.applet,
        clock_applet.applet,
        charmap.applet,
    };

    var sh = try shell_mod.Shell.init(allocator, &applets);
    defer sh.deinit();

    var last_ms: i64 = if (build_options.use_sdl) @intCast(sdl.SDL_GetTicks()) else clockMs();
    var event_buf: [32]input.InputEvent = undefined;
    var connection: types.ConnectionState = .disconnected;
    var current_owned: ?*types.OwnedSnapshot = null;
    var current_snapshot: ?*const types.StateSnapshot = null;
    defer if (current_owned) |o| o.release();

    while (true) {
        const now_ms: i64 = if (build_options.use_sdl) @intCast(sdl.SDL_GetTicks()) else clockMs();
        const dt_raw = now_ms - last_ms;
        last_ms = now_ms;
        const dt_clamped: u32 = @intCast(@min(@max(dt_raw, 0), 1000));
        const dt: f32 = @as(f32, @floatFromInt(dt_clamped)) / 1000.0;

        // Pick up new snapshot (release previous owned snapshot)
        if (state_store.acquire()) |new_owned| {
            if (current_owned) |old| old.release();
            current_owned = new_owned;
            current_snapshot = &new_owned.snapshot;
            sh.updateContext(current_snapshot);
        }

        // Drain UI events
        while (ui_queue.pop()) |ev| {
            switch (ev) {
                .connection_state => |cs| {
                    connection = cs;
                    sh.status = shell_mod.Status.fromConnection(cs);
                    // Mirror connection state on hardware LEDs
                    if (!build_options.use_sdl) {
                        led.setGreenLed(cs == .syncing or cs == .connected);
                        led.setRedLed(cs == .err or cs == .disconnected);
                    }
                },
                // Forward send status to wata applet for user feedback
                .send_failed => {
                    if (sh.states[0]) |wata_state| {
                        wata_applet.notifySendStatus(wata_state, true);
                    }
                },
                .send_complete => {
                    if (sh.states[0]) |wata_state| {
                        wata_applet.notifySendStatus(wata_state, false);
                    }
                },
                .playback_error => {
                    if (sh.states[0]) |wata_state| {
                        wata_applet.notifyPlayError(wata_state);
                    }
                },
                else => {},
            }
        }

        // Push snapshot + queues to applets that need them
        if (sh.states[0]) |wata_state| {
            wata_applet.setContext(wata_state, current_snapshot, connection, &action_queue, if (build_options.use_audio) &audio_cmd_queue else null, if (build_options.use_audio) &audio_evt_queue else null);
        }
        if (sh.states[1]) |settings_state| {
            settings_applet.setContext(settings_state, current_snapshot, &action_queue, &should_stop, if (build_options.use_audio) &audio_cmd_queue else null, if (build_options.use_audio) &audio_evt_queue else null);
        }

        // Poll input
        const result = inp.poll(&event_buf);
        if (result.quit) break;

        for (result.events) |ev| {
            const action = sh.handleInput(ev.key, ev.state);
            if (action == .quit) break;
        }

        sh.update(dt);

        var fb = disp.framebuffer();
        fb.clear(display.colors.black);
        sh.render(&fb);
        disp.present();

        if (build_options.use_sdl) {
            sdl.SDL_Delay(16); // ~60fps for dev
        } else {
            // ~30fps for 27Hz display
            var ts = std.os.linux.timespec{ .sec = 0, .nsec = 33_000_000 };
            _ = std.os.linux.nanosleep(&ts, null);
        }
    }
}

/// Monotonic clock in milliseconds (Linux only, used for frame timing).
fn clockMs() i64 {
    var ts: std.os.linux.timespec = undefined;
    _ = std.os.linux.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1_000_000);
}
