// The pixel pipeline: RGB565 (the device panel's format, and therefore the
// view algebra's) into what AppKit wants. Pure functions, no AppKit import,
// so the conversions have plain-Go tests on any platform.
//
// Channel widening is BIT REPLICATION, the same inverse of Color.rgb that
// the PNG golden encoder, the terminal sim and the Gio shell
// (go-pkgs/gioshell/pixels.go) use — a color that round-trips through any
// backend is comparable to a golden by construction.

package nativeui

// RGB565Components widens one RGB565 color to sRGB components in 0..1, for
// NSColor colorWithSRGBRed:green:blue:alpha:.
func RGB565Components(c int) (r, g, b float64) {
	r5 := (c >> 11) & 0x1f
	g6 := (c >> 5) & 0x3f
	b5 := c & 0x1f
	return float64(r5<<3|r5>>2) / 255,
		float64(g6<<2|g6>>4) / 255,
		float64(b5<<3|b5>>2) / 255
}

// ExpandRGB565 converts w*h RGB565 LITTLE-ENDIAN byte pairs — VImage's exact
// payload — into opaque RGBA bytes (w*h*4, row-major).
func ExpandRGB565(src []byte, w, h int) []byte {
	dst := make([]byte, w*h*4)
	for i := 0; i < w*h; i++ {
		c := int(src[i*2]) | int(src[i*2+1])<<8
		r5 := (c >> 11) & 0x1f
		g6 := (c >> 5) & 0x3f
		b5 := c & 0x1f
		dst[i*4+0] = byte(r5<<3 | r5>>2)
		dst[i*4+1] = byte(g6<<2 | g6>>4)
		dst[i*4+2] = byte(b5<<3 | b5>>2)
		dst[i*4+3] = 0xff
	}
	return dst
}

// ScaleRGBANearest magnifies RGBA bytes by an integer factor with
// nearest-neighbour sampling: every source pixel becomes an s*s block of the
// identical value. The panel's pixels are the design; the image handed to
// NSImageView is pre-scaled here so no interpolating scaler ever touches it.
func ScaleRGBANearest(src []byte, w, h, s int) []byte {
	if s < 1 {
		s = 1
	}
	dst := make([]byte, w*s*h*s*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			px := src[(y*w+x)*4 : (y*w+x)*4+4]
			for dy := 0; dy < s; dy++ {
				row := ((y*s+dy)*w*s + x*s) * 4
				for dx := 0; dx < s; dx++ {
					copy(dst[row+dx*4:row+dx*4+4], px)
				}
			}
		}
	}
	return dst
}
