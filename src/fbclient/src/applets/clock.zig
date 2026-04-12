/// Apple Watch-style clock — two modes:
///   Main:    ketchup-mayonnaise background, black digits filling white from bottom
///   Outline: black background, outlined digits in ketchup-mayonnaise
/// Switches to outline mode before screen-off (screensaver).
const std = @import("std");
const Io = std.Io;
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");

var g_io: ?Io = null;

/// Set the Io instance for clock time. Called once from main.
pub fn setIo(io: Io) void {
    g_io = io;
}

const Mode = enum { filled, outline };

const State = struct {
    elapsed: f32,
    mode: Mode = .filled,
};

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{ .elapsed = 0 };
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(_: *anyopaque) void {}

fn handleInput(ptr: *anyopaque, key: input.Key, state: input.KeyState) shell.Action {
    if (state != .pressed) return .none;
    const s: *State = @ptrCast(@alignCast(ptr));
    if (key == .enter) {
        s.mode = if (s.mode == .filled) .outline else .filled;
        return .none;
    }
    return .none;
}

fn update(ptr: *anyopaque, dt: f32) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    s.elapsed += dt;
}

fn getEpochSeconds() i64 {
    const io = g_io orelse return 0;
    const ts = Io.Clock.real.now(io);
    return @intCast(@divFloor(ts.nanoseconds, std.time.ns_per_s));
}

// -- Layout constants ---------------------------------------------------------
const char_scale: u32 = 7;
const outline_thickness: u32 = 2; // outline mode stroke width
const glyph_data_w: u32 = 5;
const digit_w: u32 = glyph_data_w * char_scale; // 35
const digit_h: u32 = font.glyph_h * char_scale; // 56
const digit_gap: u32 = 4;
const pair_w: u32 = digit_w * 2 + digit_gap; // 74
const row_gap: u32 = 10;
const total_h: u32 = digit_h * 2 + row_gap; // 122

// Colours (ketchup-mayonnaise)
const color_mayo = display.colors.rgb(255, 255, 162); // mayonnaise
const color_ketchup = display.colors.rgb(225, 64, 128); // ketchup

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    const epoch = getEpochSeconds();
    const day_secs: u64 = @intCast(@mod(epoch, 86400));
    const hours: u64 = day_secs / 3600;
    const minutes: u64 = (day_secs % 3600) / 60;
    const secs: u64 = day_secs % 60;

    // Divider moves UP as seconds progress (hourglass filling from bottom).
    // sec 0 → divider at bottom, sec 59 → divider near top.
    const usable_h: u32 = display.height - 1; // 127
    const divider_y: i32 = @as(i32, @intCast(display.height)) -
        @as(i32, @intCast(secs * (usable_h - 1) / 59));

    // Flip colours every minute so the sweep appears to bring in the new colour.
    const even_minute = (minutes % 2 == 0);
    const bg_above = if (even_minute) color_mayo else color_ketchup;
    const bg_below = if (even_minute) color_ketchup else color_mayo;
    const fg_above = display.colors.black;
    const fg_below = display.colors.white;

    // Centering
    const x_start: i32 = @intCast((display.width - pair_w) / 2);
    const y_top: i32 = @intCast(1 + (usable_h - total_h) / 2);
    const y_bot: i32 = y_top + @as(i32, @intCast(digit_h + row_gap));

    const x_right: i32 = x_start + @as(i32, @intCast(digit_w + digit_gap));

    switch (s.mode) {
        .filled => {
            // Two-tone background (flips each minute)
            if (divider_y > 1) {
                fb.fillRect(0, 1, display.width, @intCast(divider_y - 1), bg_above);
            }
            if (divider_y < display.height) {
                fb.fillRect(0, divider_y, display.width, @intCast(@as(i32, @intCast(display.height)) - divider_y), bg_below);
            }

            // Digits: black above divider, white below (filling up)
            drawFilledChar(fb, digitChar(hours / 10), x_start, y_top, divider_y, fg_above, fg_below);
            drawFilledChar(fb, digitChar(hours % 10), x_right, y_top, divider_y, fg_above, fg_below);
            drawFilledChar(fb, digitChar(minutes / 10), x_start, y_bot, divider_y, fg_above, fg_below);
            drawFilledChar(fb, digitChar(minutes % 10), x_right, y_bot, divider_y, fg_above, fg_below);

            // Divider line (2px)
            fb.hline(0, divider_y, display.width, bg_below);
            fb.hline(0, divider_y - 1, display.width, bg_below);
        },
        .outline => {
            // Black background
            fb.fillRect(0, 1, display.width, display.height - 1, display.colors.black);

            // Outlined digits (colour flips each minute)
            const ol_above = bg_above; // reuse the flipped scheme
            const ol_below = bg_below;
            drawOutlinedChar(fb, digitChar(hours / 10), x_start, y_top, divider_y, ol_above, ol_below);
            drawOutlinedChar(fb, digitChar(hours % 10), x_right, y_top, divider_y, ol_above, ol_below);
            drawOutlinedChar(fb, digitChar(minutes / 10), x_start, y_bot, divider_y, ol_above, ol_below);
            drawOutlinedChar(fb, digitChar(minutes % 10), x_right, y_bot, divider_y, ol_above, ol_below);

            // Divider line
            fb.hline(0, divider_y, display.width, ol_below);
            fb.hline(0, divider_y - 1, display.width, ol_below);
        },
    }
}

/// Filled mode: draw solid scaled digit, split colour at divider.
fn drawFilledChar(fb: *display.Framebuffer, ch: u8, px: i32, py: i32, divider_y: i32, c_above: display.Color, c_below: display.Color) void {
    var gy: u32 = 0;
    while (gy < font.glyph_h) : (gy += 1) {
        var gx: u32 = 0;
        while (gx < glyph_data_w) : (gx += 1) {
            if (!font.getPixel(ch, gx, gy)) continue;
            var dy: u32 = 0;
            while (dy < char_scale) : (dy += 1) {
                const sy = py + @as(i32, @intCast(gy * char_scale + dy));
                const color = if (sy < divider_y) c_above else c_below;
                var dx: u32 = 0;
                while (dx < char_scale) : (dx += 1) {
                    const sx = px + @as(i32, @intCast(gx * char_scale + dx));
                    fb.setPixel(sx, sy, color);
                }
            }
        }
    }
}

/// Outline mode: draw only edge pixels of scaled digit, coloured by divider split.
fn drawOutlinedChar(fb: *display.Framebuffer, ch: u8, px: i32, py: i32, divider_y: i32, c_above: display.Color, c_below: display.Color) void {
    var gy: u32 = 0;
    while (gy < font.glyph_h) : (gy += 1) {
        var gx: u32 = 0;
        while (gx < glyph_data_w) : (gx += 1) {
            if (!font.getPixel(ch, gx, gy)) continue;

            const left_edge = gx == 0 or !font.getPixel(ch, gx - 1, gy);
            const right_edge = gx == glyph_data_w - 1 or !font.getPixel(ch, gx + 1, gy);
            const top_edge = gy == 0 or !font.getPixel(ch, gx, gy - 1);
            const bot_edge = gy == font.glyph_h - 1 or !font.getPixel(ch, gx, gy + 1);

            var dy: u32 = 0;
            while (dy < char_scale) : (dy += 1) {
                var dx: u32 = 0;
                while (dx < char_scale) : (dx += 1) {
                    const on_outline =
                        (left_edge and dx < outline_thickness) or
                        (right_edge and dx >= char_scale - outline_thickness) or
                        (top_edge and dy < outline_thickness) or
                        (bot_edge and dy >= char_scale - outline_thickness);

                    if (on_outline) {
                        const sy = py + @as(i32, @intCast(gy * char_scale + dy));
                        const sx = px + @as(i32, @intCast(gx * char_scale + dx));
                        const color = if (sy < divider_y) c_above else c_below;
                        fb.setPixel(sx, sy, color);
                    }
                }
            }
        }
    }
}

fn digitChar(d: u64) u8 {
    return @as(u8, @intCast(d)) + '0';
}

pub const applet = shell.Applet{
    .name = "clock",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};
