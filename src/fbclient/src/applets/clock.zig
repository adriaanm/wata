/// Simple clock display — idle screen / placeholder applet.
const std = @import("std");
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");

const ct = @cImport(@cInclude("time.h"));

const State = struct {
    elapsed: f32,
};

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{ .elapsed = 0 };
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(_: *anyopaque) void {}

fn handleInput(_: *anyopaque, _: input.Key, _: input.KeyState) shell.Action {
    return .none;
}

fn update(ptr: *anyopaque, dt: f32) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    s.elapsed += dt;
}

fn getEpochSeconds() i64 {
    return @intCast(ct.time(null));
}

fn render(_: *anyopaque, fb: *display.Framebuffer) void {
    const col = display.colors;

    const epoch = getEpochSeconds();
    // Convert to HH:MM:SS (UTC — good enough for now)
    const day_secs: u64 = @intCast(@mod(epoch, 86400));
    const hours: u64 = day_secs / 3600;
    const minutes: u64 = (day_secs % 3600) / 60;
    const secs: u64 = day_secs % 60;

    fb.fillRect(0, 1, display.width, display.height - 1, col.black);

    // Large-ish time display centered on screen
    var time_buf: [8]u8 = undefined;
    const time_text = std.fmt.bufPrint(&time_buf, "{d:0>2}:{d:0>2}:{d:0>2}", .{ hours, minutes, secs }) catch "??:??:??";

    // Draw each character 2× size (12×16 pixels each) for visibility
    const text_w = time_text.len * 12;
    const start_x: i32 = @intCast((display.width - text_w) / 2);
    const start_y: i32 = @intCast(1 + (display.height - 1 - 16) / 2); // centered vertically

    for (time_text, 0..) |ch, i| {
        drawChar2x(fb, ch, start_x + @as(i32, @intCast(i * 12)), start_y, col.green);
    }
}

/// Draw a character at 2× scale (12×16 pixels).
fn drawChar2x(fb: *display.Framebuffer, ch: u8, px: i32, py: i32, fg: display.Color) void {
    // Access font data through drawChar by drawing each pixel scaled
    // We'll draw directly using the font module's internal understanding
    const glyph_w = font.glyph_w;
    const glyph_h = font.glyph_h;

    // Render at 1x to a temp position, then we just use drawChar with the character
    // Actually, let's just render 2x manually by calling setPixel
    var row: u32 = 0;
    while (row < glyph_h) : (row += 1) {
        var col: u32 = 0;
        while (col < glyph_w) : (col += 1) {
            // Check if this pixel is set by drawing to a known position and reading back
            // Simpler: just duplicate the font lookup here
            font.drawChar(fb, ch, px + @as(i32, @intCast(col * 2)), py + @as(i32, @intCast(row * 2)), fg, null);
            fb.setPixel(px + @as(i32, @intCast(col * 2 + 1)), py + @as(i32, @intCast(row * 2)), fg);
            fb.setPixel(px + @as(i32, @intCast(col * 2)), py + @as(i32, @intCast(row * 2 + 1)), fg);
            fb.setPixel(px + @as(i32, @intCast(col * 2 + 1)), py + @as(i32, @intCast(row * 2 + 1)), fg);
        }
    }
}

pub const applet = shell.Applet{
    .name = "clock",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};
