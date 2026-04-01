/// Settings applet — device info, audio echo test, brightness control.
const std = @import("std");
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");
const types = @import("../types.zig");
const queue_mod = @import("../queue.zig");
const config = @import("../config.zig");
const led = if (!build_options.use_sdl) @import("../led.zig") else struct {
    pub fn readBatteryPercent() ?u8 { return null; }
    pub fn setBacklight(_: u8) void {}
};
const alsa = if (build_options.use_audio) @import("../alsa.zig") else struct {};
const opus_mod = if (build_options.use_audio) @import("../opus.zig") else struct {};

const MenuItem = enum {
    echo_test,
    brightness,
    display_name,
    info,
};

const ITEMS = [_]MenuItem{ .echo_test, .brightness, .display_name, .info };

const EchoState = enum { idle, recording, playing, done, err };

const DISPLAY_NAMES = [_][]const u8{ "Alice", "Bob", "Charlie", "Device" };

const State = struct {
    selected: usize = 0,
    brightness: u8 = 40,
    echo: EchoState = .idle,
    echo_buf: ?[]u8 = null,
    echo_frames: u32 = 0,
    action_queue: ?*queue_mod.BoundedQueue(types.Action, 64) = null,
    snapshot: ?*const types.StateSnapshot = null,
    name_idx: usize = 0, // index into DISPLAY_NAMES
};

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{};
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(ptr: *anyopaque) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (s.echo_buf) |buf| std.heap.page_allocator.free(buf);
}

fn handleInput(ptr: *anyopaque, key: input.Key, key_state: input.KeyState) shell.Action {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (key_state != .pressed) return .none;

    switch (key) {
        .up => {
            if (s.selected > 0) s.selected -= 1;
        },
        .down => {
            if (s.selected < ITEMS.len - 1) s.selected += 1;
        },
        .enter => {
            switch (ITEMS[s.selected]) {
                .echo_test => {
                    if (s.echo == .idle or s.echo == .done or s.echo == .err) {
                        startEchoRecord(s);
                    } else if (s.echo == .recording) {
                        stopEchoAndPlay(s);
                    }
                },
                .display_name => {
                    // Confirm the selected display name
                    pushDisplayName(s);
                },
                .brightness => {},
                .info => {},
            }
        },
        .left => {
            if (ITEMS[s.selected] == .brightness and s.brightness > 0) {
                s.brightness -|= 5;
                if (!build_options.use_sdl) led.setBacklight(s.brightness);
            } else if (ITEMS[s.selected] == .display_name) {
                s.name_idx = if (s.name_idx == 0) DISPLAY_NAMES.len - 1 else s.name_idx - 1;
            }
        },
        .right => {
            if (ITEMS[s.selected] == .brightness and s.brightness < 40) {
                s.brightness +|= 5;
                if (!build_options.use_sdl) led.setBacklight(s.brightness);
            } else if (ITEMS[s.selected] == .display_name) {
                s.name_idx = (s.name_idx + 1) % DISPLAY_NAMES.len;
            }
        },
        else => {},
    }
    return .none;
}

fn update(ptr: *anyopaque, _: f32) void {
    _ = ptr;
}

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    const c = display.colors;

    font.drawText(fb, "SETTINGS", 0, 1, c.cyan, null);

    // Menu items
    for (ITEMS, 0..) |item, i| {
        const row: u32 = @intCast(3 + i * 2);
        const sel = i == s.selected;
        const fg: display.Color = if (sel) c.black else c.green;

        if (sel) {
            fb.fillRect(0, @intCast(1 + row * font.glyph_h), display.width, font.glyph_h + 2, c.green);
        }

        switch (item) {
            .echo_test => {
                font.drawText(fb, "Audio Echo", 1, row, fg, null);
                const status = switch (s.echo) {
                    .idle => "OK=start",
                    .recording => "OK=stop",
                    .playing => "playing..",
                    .done => "done!",
                    .err => "error",
                };
                font.drawText(fb, status, 13, row, if (s.echo == .err) c.red else fg, null);
            },
            .display_name => {
                font.drawText(fb, "Name", 1, row, fg, null);
                font.drawText(fb, DISPLAY_NAMES[s.name_idx], 8, row, c.yellow, null);
            },
            .brightness => {
                font.drawText(fb, "Brightness", 1, row, fg, null);
                var bar_buf: [5]u8 = undefined;
                const bar = std.fmt.bufPrint(&bar_buf, "{d}/40", .{s.brightness}) catch "?";
                font.drawText(fb, bar, 15, row, fg, null);
            },
            .info => {
                font.drawText(fb, "Device Info", 1, row, fg, null);
            },
        }
    }

    // Detail area below menu
    const detail_row: u32 = 3 + ITEMS.len * 2 + 1;
    switch (ITEMS[s.selected]) {
        .info => {
            font.drawText(fb, "BQ268 MSM8909", 1, detail_row, c.mid_gray, null);
            font.drawText(fb, "128x160 ST7735S", 1, detail_row + 1, c.mid_gray, null);
            font.drawText(fb, "48kHz ADSP audio", 1, detail_row + 2, c.mid_gray, null);
            const bat = led.readBatteryPercent();
            if (bat) |pct| {
                var buf: [12]u8 = undefined;
                const str = std.fmt.bufPrint(&buf, "Battery: {d}%", .{pct}) catch "Battery: ?";
                font.drawText(fb, str, 1, detail_row + 3, c.mid_gray, null);
            }
        },
        .echo_test => {
            font.drawText(fb, "Records 2s, plays", 1, detail_row, c.mid_gray, null);
            font.drawText(fb, "back through spkr.", 1, detail_row + 1, c.mid_gray, null);
            font.drawText(fb, "Tests full audio", 1, detail_row + 2, c.mid_gray, null);
            font.drawText(fb, "path: mic+spk+codec", 1, detail_row + 3, c.mid_gray, null);
        },
        .display_name => {
            font.drawText(fb, "</>  pick  OK set", 1, detail_row, c.mid_gray, null);
            // Show current name from snapshot
            if (s.snapshot) |snap| {
                if (snap.self_user) |user| {
                    font.drawText(fb, "Current:", 1, detail_row + 1, c.mid_gray, null);
                    const dlen = @min(user.display_name.len, 12);
                    font.drawText(fb, user.display_name[0..dlen], 10, detail_row + 1, c.green, null);
                }
            }
        },
        .brightness => {
            font.drawText(fb, "</>  adjust", 1, detail_row, c.mid_gray, null);
        },
    }
}

fn pushDisplayName(s: *State) void {
    const aq = s.action_queue orelse return;
    const name = DISPLAY_NAMES[s.name_idx];
    if (name.len > 64) return;

    var action = types.Action{ .set_display_name = .{
        .name_buf = undefined,
        .name_len = @intCast(name.len),
    } };
    @memcpy(action.set_display_name.name_buf[0..name.len], name);
    _ = aq.push(action);
}

/// Called by main loop to provide shared context.
pub fn setContext(
    applet_state: *anyopaque,
    snapshot: ?*const types.StateSnapshot,
    action_q: *queue_mod.BoundedQueue(types.Action, 64),
) void {
    const s: *State = @ptrCast(@alignCast(applet_state));
    s.snapshot = snapshot;
    s.action_queue = action_q;
}

// ---------------------------------------------------------------------------
// Audio echo test — record 2 seconds, play back immediately
// ---------------------------------------------------------------------------

fn startEchoRecord(s: *State) void {
    if (!build_options.use_audio) {
        s.echo = .err;
        return;
    }

    s.echo = .recording;

    // Free previous buffer
    if (s.echo_buf) |buf| {
        std.heap.page_allocator.free(buf);
        s.echo_buf = null;
    }

    // Record 2 seconds in a background thread
    const S = struct {
        fn recordThread(state: *State) void {
            doEchoRecord(state);
        }
    };
    _ = std.Thread.spawn(.{}, S.recordThread, .{s}) catch {
        s.echo = .err;
    };
}

fn doEchoRecord(s: *State) void {
    alsa.setupMixer();

    var capture = alsa.Capture.open() catch {
        s.echo = .err;
        return;
    };
    defer capture.close();

    // 2 seconds at 48kHz = 100 periods of 20ms
    const num_periods: u32 = 100;
    const total_bytes = num_periods * alsa.PERIOD_BYTES;

    const buf = std.heap.page_allocator.alloc(u8, total_bytes) catch {
        s.echo = .err;
        return;
    };

    var offset: u32 = 0;
    var periods: u32 = 0;
    while (periods < num_periods) : (periods += 1) {
        _ = capture.readFrames(buf[offset .. offset + alsa.PERIOD_BYTES]) catch {
            std.heap.page_allocator.free(buf);
            s.echo = .err;
            return;
        };
        offset += alsa.PERIOD_BYTES;
    }

    s.echo_buf = buf;
    s.echo_frames = num_periods * alsa.FRAMES_PER_PERIOD;

    // Immediately play back
    doEchoPlayback(s);
}

fn doEchoPlayback(s: *State) void {
    s.echo = .playing;

    var playback = alsa.Playback.open() catch {
        s.echo = .err;
        return;
    };
    defer playback.close();

    const buf = s.echo_buf orelse {
        s.echo = .err;
        return;
    };
    const num_periods = s.echo_frames / alsa.FRAMES_PER_PERIOD;

    var offset: u32 = 0;
    var periods: u32 = 0;
    while (periods < num_periods) : (periods += 1) {
        playback.writeFrames(buf[offset .. offset + alsa.PERIOD_BYTES]) catch {
            s.echo = .err;
            return;
        };
        offset += alsa.PERIOD_BYTES;
    }

    s.echo = .done;
}

fn stopEchoAndPlay(s: *State) void {
    // The recording thread runs for a fixed duration, so "stop" just means
    // wait for it to finish — it auto-plays when done.
    // If the user presses Enter during recording, we do nothing extra.
    _ = s;
}

// ---------------------------------------------------------------------------
// Applet registration
// ---------------------------------------------------------------------------

pub const applet = shell.Applet{
    .name = "settings",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};
