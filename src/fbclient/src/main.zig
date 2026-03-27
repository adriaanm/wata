const std = @import("std");
const build_options = @import("build_options");
const display = @import("display.zig");
const input = @import("input.zig");
const shell_mod = @import("shell.zig");
const snake = @import("applets/snake.zig");
const clock_applet = @import("applets/clock.zig");
const charmap = @import("applets/charmap.zig");

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

    // Register applets
    const applets = [_]shell_mod.Applet{
        snake.applet,
        clock_applet.applet,
        charmap.applet,
    };

    var sh = try shell_mod.Shell.init(allocator, &applets);
    defer sh.deinit();
    sh.status = .connected;

    // Main loop
    var last_ticks: u32 = if (build_options.use_sdl) sdl.SDL_GetTicks() else 0;
    var event_buf: [32]input.InputEvent = undefined;

    while (true) {
        // Compute dt
        const now_ticks: u32 = if (build_options.use_sdl) sdl.SDL_GetTicks() else 0;
        const dt_ms = now_ticks -% last_ticks;
        last_ticks = now_ticks;
        const dt: f32 = @as(f32, @floatFromInt(dt_ms)) / 1000.0;

        // Poll input
        const result = inp.poll(&event_buf);
        if (result.quit) break;

        for (result.events) |ev| {
            const action = sh.handleInput(ev.key, ev.state);
            if (action == .quit) return;
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
