/// Centralized SDL2 import with ARM NEON workaround for Zig 0.16-dev.
pub const c = @cImport({
    @cDefine("SDL_DISABLE_ARM_NEON_H", "1");
    @cInclude("SDL2/SDL.h");
});
