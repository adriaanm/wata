/// FreeType-based font rendering for the wata applet.
/// Loads an embedded TTF, rasterizes and caches glyphs, blits to RGB565 framebuffer.
const std = @import("std");
const Allocator = std.mem.Allocator;
const freetype = @import("freetype.zig");
const display = @import("display.zig");

/// A cached glyph: grayscale bitmap + positioning metrics.
const CachedGlyph = struct {
    /// Grayscale bitmap (1 byte per pixel, 0=transparent, 255=opaque).
    bitmap: []const u8,
    width: u32,
    rows: u32,
    pitch: u32,
    /// Pixels from pen position to left edge of bitmap.
    bearing_x: i32,
    /// Pixels from baseline to top edge of bitmap.
    bearing_y: i32,
    /// Horizontal advance (pixels to move pen after this glyph).
    advance: i32,
};

pub const Font = struct {
    ft_lib: freetype.Library,
    ft_face: freetype.Face,
    cache: std.AutoHashMapUnmanaged(u32, CachedGlyph),
    allocator: Allocator,
    /// Font metrics (set after setSize).
    ascender: i32,
    descender: i32,
    line_height: i32,

    pub fn init(allocator: Allocator, ttf_data: []const u8, size_px: u32) !Font {
        const lib = freetype.Library.init() catch return error.FreetypeInitFailed;
        errdefer lib.deinit();

        const face = lib.initMemoryFace(ttf_data) catch return error.FreetypeFaceLoadFailed;
        errdefer face.deinit();

        face.selectCharmap() catch return error.FreetypeCharmapFailed;
        face.setPixelSizes(0, size_px) catch return error.FreetypeSizeFailed;

        const metrics = face.sizeMetrics();

        return .{
            .ft_lib = lib,
            .ft_face = face,
            .cache = .{},
            .allocator = allocator,
            .ascender = metrics.ascender,
            .descender = metrics.descender,
            .line_height = metrics.height,
        };
    }

    pub fn deinit(self: *Font) void {
        var it = self.cache.iterator();
        while (it.next()) |entry| {
            if (entry.value_ptr.bitmap.len > 0) {
                self.allocator.free(entry.value_ptr.bitmap);
            }
        }
        self.cache.deinit(self.allocator);
        self.ft_face.deinit();
        self.ft_lib.deinit();
    }

    /// Get a cached glyph, rasterizing it on first access.
    fn getGlyph(self: *Font, codepoint: u32) ?*const CachedGlyph {
        if (self.cache.getPtr(codepoint)) |g| return g;

        // Rasterize
        const glyph_index = self.ft_face.getCharIndex(codepoint) orelse
            // Fallback to '?' for unknown codepoints
            self.ft_face.getCharIndex('?') orelse return null;

        const slot = self.ft_face.loadAndRender(glyph_index) catch return null;
        const bmp = slot.bitmap();

        // Copy bitmap data (FreeType reuses the slot buffer)
        const owned_bitmap = if (bmp.buffer.len > 0)
            self.allocator.dupe(u8, bmp.buffer) catch return null
        else
            &.{};

        const cached = CachedGlyph{
            .bitmap = owned_bitmap,
            .width = bmp.width,
            .rows = bmp.rows,
            .pitch = bmp.pitch,
            .bearing_x = slot.bitmapLeft(),
            .bearing_y = slot.bitmapTop(),
            .advance = slot.advanceX(),
        };

        self.cache.put(self.allocator, codepoint, cached) catch return null;
        return self.cache.getPtr(codepoint);
    }

    /// Measure the width of a string in pixels.
    pub fn measureText(self: *Font, text: []const u8) i32 {
        var pen_x: i32 = 0;
        for (text) |byte| {
            if (self.getGlyph(byte)) |g| {
                pen_x += g.advance;
            }
        }
        return pen_x;
    }

    /// Draw a string to the framebuffer at pixel position (x, y) where y is the top of the line.
    /// fg is the text color, bg (if provided) fills behind the text.
    pub fn drawText(
        self: *Font,
        fb: *display.Framebuffer,
        text: []const u8,
        x: i32,
        y: i32,
        fg: display.Color,
        bg: ?display.Color,
    ) void {
        // Fill background if requested
        if (bg) |bg_color| {
            const w = self.measureText(text);
            fb.fillRect(x, y, @intCast(@max(0, w)), @intCast(@max(0, self.line_height)), bg_color);
        }

        const baseline_y = y + self.ascender;
        var pen_x: i32 = x;

        for (text) |byte| {
            const glyph = self.getGlyph(byte) orelse continue;

            // Blit position
            const gx = pen_x + glyph.bearing_x;
            const gy = baseline_y - glyph.bearing_y;

            blitGlyph(fb, glyph, gx, gy, fg);
            pen_x += glyph.advance;
        }
    }

    /// Draw text right-aligned at (right_x, y).
    pub fn drawTextRight(
        self: *Font,
        fb: *display.Framebuffer,
        text: []const u8,
        right_x: i32,
        y: i32,
        fg: display.Color,
    ) void {
        const w = self.measureText(text);
        self.drawText(fb, text, right_x - w, y, fg, null);
    }

    /// Draw text horizontally centered on the screen at y.
    pub fn drawTextCentered(
        self: *Font,
        fb: *display.Framebuffer,
        text: []const u8,
        y: i32,
        fg: display.Color,
    ) void {
        const w = self.measureText(text);
        self.drawText(fb, text, @divTrunc(@as(i32, display.width) - w, 2), y, fg, null);
    }
};

/// Alpha-blend a grayscale glyph bitmap onto the RGB565 framebuffer.
fn blitGlyph(
    fb: *display.Framebuffer,
    glyph: *const CachedGlyph,
    gx: i32,
    gy: i32,
    fg: display.Color,
) void {
    // Decompose fg color to 8-bit RGB
    const fg_r: u16 = (fg >> 11) & 0x1F;
    const fg_g: u16 = (fg >> 5) & 0x3F;
    const fg_b: u16 = fg & 0x1F;
    // Scale to 0-255
    const fg_r8: u16 = (fg_r << 3) | (fg_r >> 2);
    const fg_g8: u16 = (fg_g << 2) | (fg_g >> 4);
    const fg_b8: u16 = (fg_b << 3) | (fg_b >> 2);

    var row: u32 = 0;
    while (row < glyph.rows) : (row += 1) {
        const py = gy + @as(i32, @intCast(row));
        if (py < 0 or py >= display.height) continue;

        var col: u32 = 0;
        while (col < glyph.width) : (col += 1) {
            const px = gx + @as(i32, @intCast(col));
            if (px < 0 or px >= display.width) continue;

            const alpha: u16 = glyph.bitmap[row * glyph.pitch + col];
            if (alpha == 0) continue;

            const pixel_idx: usize = @intCast(@as(u32, @intCast(py)) * display.width + @as(u32, @intCast(px)));

            if (alpha >= 250) {
                // Fully opaque — skip blend
                fb.pixels[pixel_idx] = fg;
                continue;
            }

            // Read background pixel
            const bg = fb.pixels[pixel_idx];
            const bg_r: u16 = ((bg >> 11) & 0x1F) << 3 | ((bg >> 11) & 0x1F) >> 2;
            const bg_g: u16 = ((bg >> 5) & 0x3F) << 2 | ((bg >> 5) & 0x3F) >> 4;
            const bg_b: u16 = (bg & 0x1F) << 3 | (bg & 0x1F) >> 2;

            const inv_a: u16 = 255 - alpha;
            const r = (fg_r8 * alpha + bg_r * inv_a) / 255;
            const g = (fg_g8 * alpha + bg_g * inv_a) / 255;
            const b = (fg_b8 * alpha + bg_b * inv_a) / 255;

            fb.pixels[pixel_idx] = display.colors.rgb(@intCast(r), @intCast(g), @intCast(b));
        }
    }
}
