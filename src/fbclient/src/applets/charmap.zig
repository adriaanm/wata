/// Character map viewer — displays all 256 glyphs, paged.
const std = @import("std");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");

const chars_per_page = font.cols * (font.rows - 1); // leave 1 row for header
const total_pages = (256 + chars_per_page - 1) / chars_per_page;

const State = struct {
    page: u32,
};

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{ .page = 0 };
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(_: *anyopaque) void {}

fn handleInput(ptr: *anyopaque, key: input.Key, state: input.KeyState) shell.Action {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (state != .pressed) return .none;

    switch (key) {
        .enter, .right, .down => {
            s.page = (s.page + 1) % total_pages;
        },
        .left, .up => {
            s.page = if (s.page == 0) total_pages - 1 else s.page - 1;
        },
        else => {},
    }
    return .none;
}

fn update(_: *anyopaque, _: f32) void {}

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    const c = display.colors;

    // Header
    var hdr_buf: [21]u8 = undefined;
    const hdr = std.fmt.bufPrint(&hdr_buf, "CHARMAP {d}/{d}", .{ s.page + 1, total_pages }) catch "CHARMAP";
    font.drawText(fb, hdr, 0, 0, c.cyan, c.black);

    // Draw characters
    const start: u32 = s.page * chars_per_page;
    var i: u32 = 0;
    while (i < chars_per_page) : (i += 1) {
        const ch: u32 = start + i;
        if (ch >= 256) break;
        const col = i % font.cols;
        const row = i / font.cols + 1; // +1 for header
        font.drawChar(fb, @intCast(ch), @intCast(col * font.glyph_w), @intCast(1 + row * font.glyph_h), c.green, c.black);
    }
}

pub const applet = shell.Applet{
    .name = "charmap",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};
