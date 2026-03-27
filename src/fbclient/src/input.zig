/// Input abstraction: maps platform keys to app-level Key enum.
/// Backend: SDL2 events (dev) or evdev (device).
const std = @import("std");
const build_options = @import("build_options");

pub const Key = enum {
    up,
    down,
    left,
    right,
    enter, // SELECT / DPAD_CENTER
    back, // BACK / ESC
    ptt, // F1 — always routed to wata
    dot1, // F3 — prev applet
    dot2, // F6 — next applet
    f2, // F2 — spare
    unknown,
};

pub const KeyState = enum { pressed, released, repeat };

pub const InputEvent = struct {
    key: Key,
    state: KeyState,
};

pub const Backend = if (build_options.use_sdl) SdlInput else EvdevInput;

// ---------------------------------------------------------------------------
// SDL2 input
// ---------------------------------------------------------------------------
const SdlInput = struct {
    const sdl = @import("sdl.zig").c;

    pub fn init() !SdlInput {
        return .{};
    }
    pub fn deinit(_: *SdlInput) void {}

    /// Poll all pending events. Returns a slice of InputEvents (up to max_events).
    /// Also returns true for `quit` if the window is closed.
    pub fn poll(self: *SdlInput, buf: []InputEvent) struct { events: []InputEvent, quit: bool } {
        _ = self;
        var count: usize = 0;
        var quit = false;
        var ev: sdl.SDL_Event = undefined;

        while (sdl.SDL_PollEvent(&ev) != 0) {
            if (ev.type == sdl.SDL_QUIT) {
                quit = true;
                continue;
            }
            if (ev.type == sdl.SDL_KEYDOWN or ev.type == sdl.SDL_KEYUP) {
                const state: KeyState = if (ev.type == sdl.SDL_KEYDOWN)
                    (if (ev.key.repeat != 0) .repeat else .pressed)
                else
                    .released;

                const key: Key = switch (ev.key.keysym.sym) {
                    sdl.SDLK_UP => .up,
                    sdl.SDLK_DOWN => .down,
                    sdl.SDLK_LEFT => .left,
                    sdl.SDLK_RIGHT => .right,
                    sdl.SDLK_RETURN, sdl.SDLK_KP_ENTER => .enter,
                    sdl.SDLK_ESCAPE, sdl.SDLK_BACKSPACE => .back,
                    sdl.SDLK_SPACE => .ptt,
                    sdl.SDLK_F1 => .ptt,
                    sdl.SDLK_F2 => .f2,
                    sdl.SDLK_F3 => .dot1,
                    sdl.SDLK_F6 => .dot2,
                    // Tab and shift-tab as dot1/dot2 for convenience
                    sdl.SDLK_TAB => if ((sdl.SDL_GetModState() & sdl.KMOD_SHIFT) != 0) .dot1 else .dot2,
                    else => .unknown,
                };

                if (key != .unknown and count < buf.len) {
                    buf[count] = .{ .key = key, .state = state };
                    count += 1;
                }
            }
        }

        return .{ .events = buf[0..count], .quit = quit };
    }
};

// ---------------------------------------------------------------------------
// evdev input (Linux device)
// ---------------------------------------------------------------------------
const EvdevInput = struct {
    pub fn init() !EvdevInput {
        // TODO: open /dev/input/eventN
        return .{};
    }
    pub fn deinit(_: *EvdevInput) void {}

    pub fn poll(_: *EvdevInput, buf: []InputEvent) struct { events: []InputEvent, quit: bool } {
        return .{ .events = buf[0..0], .quit = false };
    }
};
