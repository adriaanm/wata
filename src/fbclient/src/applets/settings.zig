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

fn deinitApplet(_: *anyopaque) void {}

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
            const bat = led.readBatteryPercent();
            if (bat) |pct| {
                var bbuf: [12]u8 = undefined;
                const bstr = std.fmt.bufPrint(&bbuf, "Battery: {d}%", .{pct}) catch "Battery: ?";
                font.drawText(fb, bstr, 1, detail_row, c.mid_gray, null);
            }
            const ver = "v0.1-" ++ build_options.version;
            font.drawText(fb, ver, 1, detail_row + 1, c.mid_gray, null);
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
// Audio echo test — record 2s of raw PCM, play back through speaker.
// Dead simple: pre-allocated buffer, no Opus, no threading complexity.
// ---------------------------------------------------------------------------

/// 2 seconds at 48kHz mono S16_LE = 192000 bytes
const ECHO_BUF_FRAMES: u32 = alsa.SAMPLE_RATE * 2;
const ECHO_BUF_BYTES: u32 = ECHO_BUF_FRAMES * alsa.FRAME_SIZE;

fn startEchoRecord(s: *State) void {
    if (!build_options.use_audio) {
        s.echo = .err;
        return;
    }
    s.echo = .recording;

    const S = struct {
        fn thread(state: *State) void {
            doEchoTest(state);
        }
    };
    _ = std.Thread.spawn(.{}, S.thread, .{s}) catch {
        s.echo = .err;
    };
}

fn doEchoTest(s: *State) void {
    // Pre-allocate the full 2s buffer
    var pcm_buf: [ECHO_BUF_BYTES]u8 = undefined;

    // --- RECORD ---
    alsa.setupCaptureMixer();

    var capture = alsa.Capture.open() catch {
        s.echo = .err;
        return;
    };

    var rec_offset: u32 = 0;
    while (rec_offset + alsa.PERIOD_BYTES <= ECHO_BUF_BYTES) {
        _ = capture.readFrames(pcm_buf[rec_offset..][0..alsa.PERIOD_BYTES]) catch break;
        rec_offset += alsa.PERIOD_BYTES;
    }
    capture.close();

    s.echo = .playing;

    // --- PLAY BACK ---
    alsa.setupPlaybackMixer();

    var playback = alsa.Playback.open() catch {
        s.echo = .err;
        return;
    };

    // Write entire buffer in one call — the kernel handles blocking/chunking
    // internally with less scheduling overhead than a userspace loop.
    playback.writeFrames(pcm_buf[0..rec_offset]) catch {
        s.echo = .err;
        playback.close();
        return;
    };

    // Wait for the last buffered audio to finish playing.
    playback.drain();
    playback.close();

    s.echo = .done;
}

fn stopEchoAndPlay(s: *State) void {
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
