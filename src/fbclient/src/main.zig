const std = @import("std");
const Io = std.Io;
const build_options = @import("build_options");
const display = @import("display.zig");
const input = @import("input.zig");
const shell_mod = @import("shell.zig");
const types = @import("types.zig");
const snake = @import("applets/snake.zig");
const clock_applet = @import("applets/clock.zig");
const charmap = @import("applets/charmap.zig");
const settings_applet = @import("applets/settings.zig");
const wata_applet = @import("applets/wata.zig");
const sync_thread = @import("matrix/sync_thread.zig");
const matrix_client_mod = @import("matrix/client.zig");
const audio_thread = if (build_options.use_audio) @import("audio_thread.zig") else struct {};
const audio_selftest = if (build_options.use_audio) @import("audio_selftest.zig") else struct {};
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

    // Handle CLI flags before any UI/network setup so --selftest runs
    // cleanly in isolation.
    var it = std.process.Args.Iterator.init(init.minimal.args);
    _ = it.skip(); // argv[0]
    while (it.next()) |arg| {
        if (std.mem.eql(u8, arg, "--selftest")) {
            if (!build_options.use_audio) {
                std.debug.print("--selftest requires an audio build (use_audio=true)\n", .{});
                std.process.exit(2);
            }
            const which = it.next() orelse "all";
            const stage: audio_selftest.Stage = if (std.mem.eql(u8, which, "echo"))
                .echo
            else if (std.mem.eql(u8, which, "play"))
                .play
            else
                .all;
            std.process.exit(audio_selftest.run(allocator, stage));
        }
    }

    // Share io with applets that need it
    clock_applet.setIo(init.io);
    snake.setIo(init.io);

    debugLog("[main] wata-fb starting in debug mode", .{});

    // Audio queues (created before the Matrix client so it can reference them)
    var audio_cmd_queue: if (build_options.use_audio) audio_thread.CommandQueue else void = if (build_options.use_audio) .{} else {};
    var audio_evt_queue: if (build_options.use_audio) audio_thread.EventQueue else void = if (build_options.use_audio) .{} else {};

    // Matrix runtime — owns sync + action threads, queues, state store, auth.
    var matrix_client = matrix_client_mod.MatrixClient.init(
        allocator,
        init.io,
        .{
            .homeserver = sync_thread.DEFAULT_CONFIG.homeserver,
            .username = sync_thread.DEFAULT_CONFIG.username,
            .password = sync_thread.DEFAULT_CONFIG.password,
            .sync_timeout_ms = sync_thread.DEFAULT_CONFIG.sync_timeout_ms,
        },
        if (build_options.use_audio) &audio_cmd_queue else null,
    );
    defer matrix_client.deinit();

    if (!build_options.offline) {
        debugLog("[main] connecting to {s} as {s}", .{ matrix_client.config.homeserver, matrix_client.config.username });
        matrix_client.start() catch {};
    } else {
        debugLog("[main] offline mode — network disabled", .{});
    }

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
        if (build_options.use_audio) {
            audio_cmd_queue.close();
            if (audio_handle) |h| h.join();
        }
    }

    if (debug_mode) {
        // Headless debug mode — just log events, no UI
        debugLog("[main] running headless (no display)", .{});
        var connection: types.ConnectionState = .disconnected;
        var snapshot_count: u32 = 0;
        var current_owned: ?*types.OwnedSnapshot = null;
        defer if (current_owned) |o| o.release();

        while (!matrix_client.should_stop.load(.acquire)) {
            // Drain events
            while (matrix_client.pollEvent()) |ev| {
                switch (ev) {
                    .connection_state => |cs| {
                        if (cs != connection) {
                            debugLog("[main] connection: {s}", .{@tagName(cs)});
                            connection = cs;
                        }
                    },
                    .snapshot_ready => {
                        if (matrix_client.acquireSnapshot()) |new_owned| {
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

    // Screensaver state
    var idle_time: f32 = 0.0;
    var display_off: bool = false;

    while (true) {
        const now_ms: i64 = if (build_options.use_sdl) @intCast(sdl.SDL_GetTicks()) else clockMs();
        const dt_raw = now_ms - last_ms;
        last_ms = now_ms;
        const dt_clamped: u32 = @intCast(@min(@max(dt_raw, 0), 1000));
        const dt: f32 = @as(f32, @floatFromInt(dt_clamped)) / 1000.0;

        // Pick up new snapshot (release previous owned snapshot)
        if (matrix_client.acquireSnapshot()) |new_owned| {
            if (current_owned) |old| old.release();
            current_owned = new_owned;
            current_snapshot = &new_owned.snapshot;
            sh.updateContext(current_snapshot);
        }

        // Drain UI events
        while (matrix_client.pollEvent()) |ev| {
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
            wata_applet.setContext(wata_state, current_snapshot, connection, &matrix_client.action_queue, if (build_options.use_audio) &audio_cmd_queue else null, if (build_options.use_audio) &audio_evt_queue else null);
        }
        if (sh.states[1]) |settings_state| {
            settings_applet.setContext(settings_state, current_snapshot, &matrix_client.action_queue, &matrix_client.should_stop, if (build_options.use_audio) &audio_cmd_queue else null, if (build_options.use_audio) &audio_evt_queue else null);
        }

        // Poll input
        const result = inp.poll(&event_buf);
        if (result.quit) break;

        // Screensaver: wake on any input
        if (result.events.len > 0) {
            if (display_off) {
                // Wake: restore backlight + button backlight
                if (!build_options.use_sdl) {
                    const brightness = if (sh.states[1]) |ss| settings_applet.getBrightness(ss) else 40;
                    led.setBacklight(brightness);
                    led.setButtonBacklight(true);
                }
                display_off = false;
                // Swallow the wake input — don't forward to applets
            } else {
                // Normal input handling
                for (result.events) |ev| {
                    const action = sh.handleInput(ev.key, ev.state);
                    if (action == .quit) break;
                }
            }
            idle_time = 0.0;
        }

        // Screensaver: accumulate idle time and check timeout
        idle_time += dt;
        if (!display_off) {
            const timeout_s: u32 = if (sh.states[1]) |ss| settings_applet.getScreenTimeout(ss) else 60;
            if (timeout_s > 0) {
                const timeout_f: f32 = @floatFromInt(timeout_s);
                if (idle_time >= timeout_f) {
                    if (!build_options.use_sdl) {
                        led.setBacklight(0);
                        led.setButtonBacklight(false);
                    }
                    display_off = true;
                }
            }
        }

        sh.update(dt);

        // Skip rendering when display is off (saves CPU)
        if (!display_off) {
            var fb = disp.framebuffer();
            fb.clear(display.colors.black);
            sh.render(&fb);
            disp.present();
        }

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
