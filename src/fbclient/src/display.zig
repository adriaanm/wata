/// 128×160 RGB565 framebuffer abstraction.
/// Backend: SDL2 window (dev) or /dev/fb0 mmap (device).
const std = @import("std");
const build_options = @import("build_options");

pub const width: u32 = 128;
pub const height: u32 = 160;
pub const scale: u32 = 4; // SDL window scale factor

/// RGB565 color — matches the ST7735S hardware format.
pub const Color = u16;

pub const colors = struct {
    pub const black: Color = 0x0000;
    pub const white: Color = 0xFFFF;
    pub const green: Color = rgb(0, 255, 0);
    pub const red: Color = rgb(255, 0, 0);
    pub const cyan: Color = rgb(0, 255, 255);
    pub const yellow: Color = rgb(255, 255, 0);
    pub const orange: Color = rgb(255, 165, 0);
    pub const dark_green: Color = rgb(0, 180, 0);
    pub const dark_gray: Color = rgb(40, 40, 40);
    pub const mid_gray: Color = rgb(100, 100, 100);

    pub fn rgb(r: u8, g: u8, b: u8) Color {
        return (@as(u16, r >> 3) << 11) | (@as(u16, g >> 2) << 5) | @as(u16, b >> 3);
    }
};

pub const Framebuffer = struct {
    pixels: *[width * height]Color,

    pub fn clear(self: *Framebuffer, color: Color) void {
        @memset(self.pixels, color);
    }

    pub fn setPixel(self: *Framebuffer, x: i32, y: i32, color: Color) void {
        if (x < 0 or y < 0 or x >= width or y >= height) return;
        self.pixels[@intCast(@as(u32, @intCast(y)) * width + @as(u32, @intCast(x)))] = color;
    }

    pub fn fillRect(self: *Framebuffer, x: i32, y: i32, w: u32, h: u32, color: Color) void {
        var dy: u32 = 0;
        while (dy < h) : (dy += 1) {
            const py = y + @as(i32, @intCast(dy));
            if (py < 0 or py >= height) continue;
            var dx: u32 = 0;
            while (dx < w) : (dx += 1) {
                const px = x + @as(i32, @intCast(dx));
                if (px < 0 or px >= width) continue;
                self.pixels[@intCast(@as(u32, @intCast(py)) * width + @as(u32, @intCast(px)))] = color;
            }
        }
    }

    /// Draw a horizontal line.
    pub fn hline(self: *Framebuffer, x: i32, y: i32, w: u32, color: Color) void {
        self.fillRect(x, y, w, 1, color);
    }

    /// Draw a 1px border rectangle (not filled).
    pub fn strokeRect(self: *Framebuffer, x: i32, y: i32, w: u32, h: u32, color: Color) void {
        self.hline(x, y, w, color);
        self.hline(x, y + @as(i32, @intCast(h)) - 1, w, color);
        self.fillRect(x, y, 1, h, color);
        self.fillRect(x + @as(i32, @intCast(w)) - 1, y, 1, h, color);
    }
};

/// Platform-specific display backend.
pub const Backend = if (build_options.use_sdl) SdlBackend else FbdevBackend;

// ---------------------------------------------------------------------------
// SDL2 backend (macOS dev)
// ---------------------------------------------------------------------------

const SdlBackend = struct {
    const sdl = @import("sdl.zig").c;

    window: *sdl.SDL_Window,
    renderer: *sdl.SDL_Renderer,
    texture: *sdl.SDL_Texture,
    buf: [width * height]Color,

    pub fn init() !SdlBackend {
        if (sdl.SDL_Init(sdl.SDL_INIT_VIDEO) != 0) return error.SdlInitFailed;

        const window = sdl.SDL_CreateWindow(
            "wata-fb (128\xc3\x97160)",
            sdl.SDL_WINDOWPOS_CENTERED,
            sdl.SDL_WINDOWPOS_CENTERED,
            @intCast(width * scale),
            @intCast(height * scale),
            0,
        ) orelse return error.SdlWindowFailed;

        const renderer = sdl.SDL_CreateRenderer(window, -1, sdl.SDL_RENDERER_PRESENTVSYNC) orelse return error.SdlRendererFailed;

        const texture = sdl.SDL_CreateTexture(
            renderer,
            sdl.SDL_PIXELFORMAT_RGB565,
            sdl.SDL_TEXTUREACCESS_STREAMING,
            @intCast(width),
            @intCast(height),
        ) orelse return error.SdlTextureFailed;

        return .{
            .window = window,
            .renderer = renderer,
            .texture = texture,
            .buf = [_]Color{0} ** (width * height),
        };
    }

    pub fn deinit(self: *SdlBackend) void {
        sdl.SDL_DestroyTexture(self.texture);
        sdl.SDL_DestroyRenderer(self.renderer);
        sdl.SDL_DestroyWindow(self.window);
        sdl.SDL_Quit();
    }

    pub fn framebuffer(self: *SdlBackend) Framebuffer {
        return .{ .pixels = &self.buf };
    }

    pub fn present(self: *SdlBackend) void {
        _ = sdl.SDL_UpdateTexture(
            self.texture,
            null,
            @ptrCast(&self.buf),
            @intCast(width * @sizeOf(Color)),
        );
        _ = sdl.SDL_RenderClear(self.renderer);
        _ = sdl.SDL_RenderCopy(self.renderer, self.texture, null, null);
        sdl.SDL_RenderPresent(self.renderer);
    }
};

// ---------------------------------------------------------------------------
// fbdev backend (device)
// ---------------------------------------------------------------------------

const FbdevBackend = struct {
    buf: [width * height]Color,
    // TODO: mmap /dev/fb0, ioctl for screen info

    pub fn init() !FbdevBackend {
        return .{ .buf = [_]Color{0} ** (width * height) };
    }

    pub fn deinit(_: *FbdevBackend) void {}

    pub fn framebuffer(self: *FbdevBackend) Framebuffer {
        return .{ .pixels = &self.buf };
    }

    pub fn present(_: *FbdevBackend) void {
        // TODO: memcpy buf to mmap'd /dev/fb0
    }
};
