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
    // Linux input event struct (matches kernel's struct input_event)
    const LinuxInputEvent = extern struct {
        tv_sec: isize, // adapts to 32-bit (ARM) or 64-bit
        tv_usec: isize,
        type: u16,
        code: u16,
        value: i32,
    };

    // Event types
    const EV_KEY: u16 = 0x01;

    // Key codes (from linux/input-event-codes.h, matching HARDWARE.md)
    const KEY_ESC: u16 = 1; // Matrix keypad: Back
    const KEY_ENTER: u16 = 28; // Matrix keypad: Center
    const KEY_F1: u16 = 59; // GPIO: Main PTT
    const KEY_F2: u16 = 60; // GPIO: Headset PTT
    const KEY_F3: u16 = 61; // GPIO: Side button 3 (prev applet)
    const KEY_F4: u16 = 62; // PMIC: RESIN button
    const KEY_F10: u16 = 68; // GPIO: Side button 4 (next applet)
    const KEY_UP: u16 = 103; // Matrix keypad
    const KEY_LEFT: u16 = 105;
    const KEY_RIGHT: u16 = 106;
    const KEY_DOWN: u16 = 108;

    const linux = std.os.linux;
    const MAX_FDS = 3;

    fds: [MAX_FDS]std.posix.fd_t,
    fd_count: u8,

    pub fn init() !EvdevInput {
        var self = EvdevInput{
            .fds = .{ -1, -1, -1 },
            .fd_count = 0,
        };

        // Open all input devices — skip any that fail to open
        const paths = [_][*:0]const u8{
            "/dev/input/event0", // PMIC PON (power, RESIN)
            "/dev/input/event1", // Matrix keypad (d-pad, enter, esc)
            "/dev/input/event2", // GPIO keys (PTT, F2, F3, F10)
        };

        for (paths) |path| {
            const fd = std.posix.openatZ(std.posix.AT.FDCWD, path, .{ .ACCMODE = .RDONLY, .NONBLOCK = true }, 0) catch continue;
            self.fds[self.fd_count] = fd;
            self.fd_count += 1;
        }

        if (self.fd_count == 0) return error.NoInputDevices;
        return self;
    }

    pub fn deinit(self: *EvdevInput) void {
        for (self.fds[0..self.fd_count]) |fd| {
            _ = linux.close(fd);
        }
    }

    pub fn poll(self: *EvdevInput, buf: []InputEvent) struct { events: []InputEvent, quit: bool } {
        var count: usize = 0;

        for (self.fds[0..self.fd_count]) |fd| {
            // Drain all available events from this device
            while (count < buf.len) {
                var ev: LinuxInputEvent = undefined;
                const n = std.posix.read(fd, std.mem.asBytes(&ev)) catch break; // WouldBlock → break
                if (n != @sizeOf(LinuxInputEvent)) break;

                if (ev.type != EV_KEY) continue;

                const state: KeyState = switch (ev.value) {
                    0 => .released,
                    1 => .pressed,
                    2 => .repeat,
                    else => continue,
                };

                const key = mapKey(ev.code);
                if (key == .unknown) continue;

                buf[count] = .{ .key = key, .state = state };
                count += 1;
            }
        }

        return .{ .events = buf[0..count], .quit = false };
    }

    fn mapKey(code: u16) Key {
        return switch (code) {
            KEY_UP => .up,
            KEY_DOWN => .down,
            KEY_LEFT => .left,
            KEY_RIGHT => .right,
            KEY_ENTER => .enter,
            KEY_ESC => .back,
            KEY_F1 => .ptt, // Main PTT (side)
            KEY_F2 => .f2, // Headset PTT (side)
            KEY_F3 => .dot1, // Side button 3 → prev applet
            KEY_F10 => .dot2, // Side button 4 → next applet (HW code is KEY_F10)
            else => .unknown,
        };
    }
};
