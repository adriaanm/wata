const std = @import("std");
const build_options = @import("build_options");
const display = @import("display.zig");
const input = @import("input.zig");
const shell_mod = @import("shell.zig");
const types = @import("types.zig");
const queue = @import("queue.zig");
const snake = @import("applets/snake.zig");
const clock_applet = @import("applets/clock.zig");
const charmap = @import("applets/charmap.zig");
const wata_applet = @import("applets/wata.zig");
const sync_thread = @import("matrix/sync_thread.zig");

const sdl = if (build_options.use_sdl) @import("sdl.zig").c else struct {};

pub fn main() !void {
    var da = std.heap.DebugAllocator(.{}){};
    defer _ = da.deinit();
    const allocator = da.allocator();

    // Init platform backends
    var disp = try display.Backend.init();
    defer disp.deinit();

    var inp = try input.Backend.init();
    defer inp.deinit();

    // Inter-thread communication
    var ui_queue: queue.BoundedQueue(types.UiEvent, 256) = .{};
    var state_store: types.StateStore = .{};
    var should_stop = std.atomic.Value(bool).init(false);

    // Sync thread context
    var sync_ctx = sync_thread.SyncThreadContext{
        .config = sync_thread.DEFAULT_CONFIG,
        .ui_queue = &ui_queue,
        .state_store = &state_store,
        .should_stop = &should_stop,
        .allocator = allocator,
    };

    // Spawn sync thread
    const sync_handle = std.Thread.spawn(.{}, sync_thread.syncThreadMain, .{&sync_ctx}) catch null;
    defer {
        should_stop.store(true, .release);
        if (sync_handle) |h| h.join();
    }

    // Register applets — wata first (default)
    const applets = [_]shell_mod.Applet{
        wata_applet.applet,
        snake.applet,
        clock_applet.applet,
        charmap.applet,
    };

    var sh = try shell_mod.Shell.init(allocator, &applets);
    defer sh.deinit();

    // Main loop
    var last_ticks: u32 = if (build_options.use_sdl) sdl.SDL_GetTicks() else 0;
    var event_buf: [32]input.InputEvent = undefined;
    var connection: types.ConnectionState = .disconnected;
    var current_snapshot: ?*const types.StateSnapshot = null;

    while (true) {
        // Compute dt
        const now_ticks: u32 = if (build_options.use_sdl) sdl.SDL_GetTicks() else 0;
        const dt_ms = now_ticks -% last_ticks;
        last_ticks = now_ticks;
        const dt: f32 = @as(f32, @floatFromInt(dt_ms)) / 1000.0;

        // Pick up new snapshot if available
        if (state_store.acquire()) |new_snap| {
            current_snapshot = new_snap;
            sh.updateContext(new_snap);
        }

        // Drain UI event queue
        while (ui_queue.pop()) |ev| {
            switch (ev) {
                .connection_state => |cs| {
                    connection = cs;
                    sh.status = shell_mod.Status.fromConnection(cs);
                },
                .snapshot_ready => {
                    // Snapshot already acquired above
                },
                else => {},
            }
        }

        // Push snapshot to wata applet
        if (sh.states[0]) |wata_state| {
            wata_applet.setSnapshot(wata_state, current_snapshot, connection);
        }

        // Poll input
        const result = inp.poll(&event_buf);
        if (result.quit) break;

        for (result.events) |ev| {
            const action = sh.handleInput(ev.key, ev.state);
            if (action == .quit) break;
        }

        // Update
        sh.update(dt);

        // Render
        var fb = disp.framebuffer();
        fb.clear(display.colors.black);
        sh.render(&fb);
        disp.present();

        // ~60fps target (SDL vsync may also throttle)
        if (build_options.use_sdl) {
            sdl.SDL_Delay(16);
        }
    }
}
