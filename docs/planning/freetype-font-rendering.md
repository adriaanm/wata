# FreeType Font Rendering for Wata Applet

## Goal

Replace the 5×8 bitmap font in the wata applet with FreeType-rendered antialiased text for better legibility on the 128×160 display. Other applets (snake, clock, charmap) keep the vintage bitmap font.

## Architecture

Inspired by Ghostty's font system, simplified for our CPU framebuffer:

```
TTF file (embedded at compile time via @embedFile)
  → FreeType rasterizes glyphs to grayscale bitmaps
    → Glyph cache (HashMap: codepoint → GlyphInfo + bitmap)
      → Alpha-blended blit to RGB565 framebuffer
```

### Components

1. **`src/freetype.zig`** — Thin Zig wrapper around FreeType C API
   - Library init/deinit
   - Face loading from memory
   - Glyph loading + rendering
   - Inspired by `ghostty/pkg/freetype/main.zig`

2. **`src/ft_font.zig`** — High-level font rendering module
   - Loads embedded TTF, sets size
   - Glyph cache: `HashMap(u32, CachedGlyph)` where key is Unicode codepoint
   - `CachedGlyph`: grayscale bitmap + metrics (width, height, bearingX, bearingY, advance)
   - `drawText(fb, text, x, y, color)` — renders string to framebuffer
   - `measureText(text)` — returns width in pixels
   - Alpha blending: grayscale value blended with fg color onto RGB565 background

3. **`src/fonts/`** — Directory for embedded TTF file(s)

### Build Changes

- Link system `freetype2` library (dev: Homebrew, device: cross-compiled)
- `link_libc = true` always (FreeType requires it)
- Add `@cImport` for FreeType headers with include path

### Font Size

Target: ~10-12px for body text on 128×160. This gives roughly:
- 10px: ~12-14 chars wide, ~15 rows
- 12px: ~10-12 chars wide, ~13 rows

### Alpha Blending on RGB565

```
// For each pixel in glyph bitmap:
alpha = glyph_bitmap[y * pitch + x]  // 0-255 grayscale
if (alpha == 0) continue;            // fully transparent
if (alpha == 255) { setPixel(fg); continue; }  // fully opaque

// Blend: result = bg * (1-a) + fg * a
bg = getPixel(x, y)
r = (bg_r * (255-a) + fg_r * a) / 255
g = (bg_g * (255-a) + fg_g * a) / 255
b = (bg_b * (255-a) + fg_b * a) / 255
setPixel(rgb565(r, g, b))
```

## Reference

- Ghostty source: `/Users/adriaan/g/ghostty/src/font/`
- Ghostty FreeType wrapper: `/Users/adriaan/g/ghostty/pkg/freetype/`
