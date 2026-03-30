/// Thin Zig wrapper around the FreeType C API.
/// Inspired by ghostty/pkg/freetype — only the subset we need.
const std = @import("std");

pub const c = @cImport({
    @cInclude("ft2build.h");
    @cInclude("freetype/freetype.h");
    @cInclude("freetype/ftbitmap.h");
});

pub const Error = error{FreetypeError};

fn check(err: c_int) Error!void {
    if (err != 0) return Error.FreetypeError;
}

pub const Library = struct {
    handle: c.FT_Library,

    pub fn init() Error!Library {
        var lib: Library = .{ .handle = undefined };
        try check(c.FT_Init_FreeType(&lib.handle));
        return lib;
    }

    pub fn deinit(self: Library) void {
        _ = c.FT_Done_FreeType(self.handle);
    }

    /// Load a font face from memory (embedded TTF bytes).
    pub fn initMemoryFace(self: Library, data: []const u8) Error!Face {
        var face: Face = .{ .handle = undefined };
        try check(c.FT_New_Memory_Face(
            self.handle,
            data.ptr,
            @intCast(data.len),
            0,
            &face.handle,
        ));
        return face;
    }
};

pub const Face = struct {
    handle: c.FT_Face,

    pub fn deinit(self: Face) void {
        _ = c.FT_Done_Face(self.handle);
    }

    pub fn selectCharmap(self: Face) Error!void {
        try check(c.FT_Select_Charmap(self.handle, c.FT_ENCODING_UNICODE));
    }

    /// Set the font size. size_px is the desired pixel height.
    pub fn setPixelSizes(self: Face, width: u32, height: u32) Error!void {
        try check(c.FT_Set_Pixel_Sizes(self.handle, width, height));
    }

    /// Get the glyph index for a Unicode codepoint. Returns null if not found.
    pub fn getCharIndex(self: Face, codepoint: u32) ?u32 {
        const idx = c.FT_Get_Char_Index(self.handle, codepoint);
        return if (idx == 0) null else idx;
    }

    /// Load and render a glyph. After this call, the glyph slot contains
    /// the rendered bitmap and metrics.
    pub fn loadAndRender(self: Face, glyph_index: u32) Error!GlyphSlot {
        try check(c.FT_Load_Glyph(self.handle, glyph_index, c.FT_LOAD_DEFAULT));
        try check(c.FT_Render_Glyph(self.handle.*.glyph, c.FT_RENDER_MODE_NORMAL));
        return .{ .slot = self.handle.*.glyph };
    }

    /// Access font-level metrics (requires size to be set first).
    pub fn sizeMetrics(self: Face) SizeMetrics {
        const m = self.handle.*.size.*.metrics;
        return .{
            .ascender = @intCast(m.ascender >> 6),
            .descender = @intCast(m.descender >> 6),
            .height = @intCast(m.height >> 6),
        };
    }
};

pub const SizeMetrics = struct {
    ascender: i32, // pixels above baseline
    descender: i32, // pixels below baseline (negative)
    height: i32, // line height in pixels
};

pub const GlyphSlot = struct {
    slot: c.FT_GlyphSlot,

    pub fn bitmap(self: GlyphSlot) Bitmap {
        const bmp = self.slot.*.bitmap;
        const rows: u32 = @intCast(bmp.rows);
        const width: u32 = @intCast(bmp.width);
        const pitch: u32 = if (bmp.pitch >= 0) @intCast(bmp.pitch) else @intCast(-bmp.pitch);
        return .{
            .buffer = if (rows > 0 and width > 0)
                bmp.buffer[0 .. rows * pitch]
            else
                &.{},
            .width = width,
            .rows = rows,
            .pitch = pitch,
        };
    }

    /// Left bearing: pixels from pen position to left edge of bitmap.
    pub fn bitmapLeft(self: GlyphSlot) i32 {
        return @intCast(self.slot.*.bitmap_left);
    }

    /// Top bearing: pixels from baseline to top edge of bitmap.
    pub fn bitmapTop(self: GlyphSlot) i32 {
        return @intCast(self.slot.*.bitmap_top);
    }

    /// Horizontal advance width in pixels (26.6 fixed-point → integer).
    pub fn advanceX(self: GlyphSlot) i32 {
        return @intCast(self.slot.*.advance.x >> 6);
    }
};

pub const Bitmap = struct {
    buffer: []const u8,
    width: u32,
    rows: u32,
    pitch: u32, // bytes per row
};
