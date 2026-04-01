/// Settings applet — device info, audio echo test, brightness control.
const std = @import("std");
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");
const types = @import("../types.zig");
const queue_mod = @import("../queue.zig");
const mailbox_mod = @import("../mailbox.zig");
const config = @import("../config.zig");
const led = if (!build_options.use_sdl) @import("../led.zig") else struct {
    pub fn readBatteryPercent() ?u8 { return null; }
    pub fn setBacklight(_: u8) void {}
};
const audio_thread = if (build_options.use_audio) @import("../audio_thread.zig") else struct {
    pub const CommandQueue = void;
    pub const EventQueue = void;
};

const MenuItem = enum {
    echo_test,
    brightness,
    display_name,
    disconnect,
    info,
};

const ITEMS = [_]MenuItem{ .echo_test, .brightness, .display_name, .disconnect, .info };

const EchoState = enum { idle, recording, playing, done, err };

const DISPLAY_NAMES = [_][]const u8{ "Alice", "Bob", "Charlie", "Device" };

const State = struct {
    selected: usize = 0,
    brightness: u8 = 40,
    echo: EchoState = .idle,
    action_queue: ?*mailbox_mod.Mailbox(types.Action, 64) = null,
    audio_cmd: ?*audio_thread.CommandQueue = null,
    audio_evt: ?*audio_thread.EventQueue = null,
    snapshot: ?*const types.StateSnapshot = null,
    name_idx: usize = 0, // index into DISPLAY_NAMES
    should_stop: ?*std.atomic.Value(bool) = null,
    connected: bool = true,
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
                .disconnect => {
                    // Disconnect network only — audio thread keeps running.
                    // Stops sync thread (should_stop) and action thread (mailbox close).
                    if (s.connected) {
                        if (s.should_stop) |stop| stop.store(true, .release);
                        if (s.action_queue) |aq| aq.close();
                        s.connected = false;
                        // Reconnect requires app restart (threads can't be respawned)
                    }
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
    const s: *State = @ptrCast(@alignCast(ptr));
    // Drain echo test events from audio thread
    if (build_options.use_audio) {
        if (s.audio_evt) |evt_q| {
            while (evt_q.tryReceive()) |evt| {
                switch (evt) {
                    .echo_recording => s.echo = .recording,
                    .echo_playing => s.echo = .playing,
                    .echo_done => s.echo = .done,
                    .echo_error => s.echo = .err,
                    else => {}, // other events handled by wata applet
                }
            }
        }
    }
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
            .disconnect => {
                font.drawText(fb, "Network", 1, row, fg, null);
                font.drawText(fb, if (s.connected) "ON" else "OFF", 12, row, if (s.connected) c.green else c.red, null);
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
        .disconnect => {
            if (s.connected) {
                font.drawText(fb, "OK to disconnect", 1, detail_row, c.mid_gray, null);
                font.drawText(fb, "Stops sync threads", 1, detail_row + 1, c.mid_gray, null);
            } else {
                font.drawText(fb, "Disconnected.", 1, detail_row, c.mid_gray, null);
                font.drawText(fb, "Restart to reconn.", 1, detail_row + 1, c.mid_gray, null);
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
    _ = aq.send(action);
}

/// Called by main loop to provide shared context.
pub fn setContext(
    applet_state: *anyopaque,
    snapshot: ?*const types.StateSnapshot,
    action_q: *mailbox_mod.Mailbox(types.Action, 64),
    should_stop: *std.atomic.Value(bool),
    audio_cmd_q: ?*audio_thread.CommandQueue,
    audio_evt_q: ?*audio_thread.EventQueue,
) void {
    const s: *State = @ptrCast(@alignCast(applet_state));
    s.snapshot = snapshot;
    s.action_queue = action_q;
    s.should_stop = should_stop;
    if (build_options.use_audio) {
        s.audio_cmd = audio_cmd_q;
        s.audio_evt = audio_evt_q;
    }
}

// ---------------------------------------------------------------------------
// Audio echo test — sends command to audio thread (no ad-hoc thread spawn).
// Audio thread sends back echo_recording/echo_playing/echo_done/echo_error
// events which are consumed in update().
// ---------------------------------------------------------------------------

fn startEchoRecord(s: *State) void {
    if (!build_options.use_audio) {
        s.echo = .err;
        return;
    }
    if (s.audio_cmd) |cmd_q| {
        if (cmd_q.send(.echo_test)) {
            s.echo = .recording;
        } else {
            s.echo = .err;
        }
    } else {
        s.echo = .err;
    }
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
