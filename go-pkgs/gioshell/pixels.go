// Package gioshell is the desktop/phone window that hosts wata-fb's real
// frame loop: it owns everything Gio (window, event loop, texture upload,
// pointer zones) and exposes a primitive-typed surface the Sgola facade
// (`go.gioshell`, wata-fb/src/main/scala/gioshell.scala) binds. The client
// itself is unchanged — `GioDevice` is one more `UiDevice` backend, so the
// whole 160x128 UI ships here with no new UI code.
//
// Two event loops meet through this package. The wata frame loop runs on its
// own goroutine and calls Present/Leds/NextKey; the Gio window loop runs on
// the process's main goroutine (app.Main must) and consumes the latest frame,
// filling the key queue from pointer and keyboard events. The only shared
// state is in shell.go, behind a mutex and atomics.
//
// This file is the pixel pipeline, and it is deliberately free of any Gio
// import: the conversion and the scaling are pure functions with plain-Go
// tests (pixels_test.go), which is what makes "the blit does not touch frame
// content" an assertion rather than a hope.

package gioshell

import "image"

// Expand converts a w*h RGB565 LITTLE-ENDIAN framebuffer — the exact bytes
// wata-fb's Draw layer writes and the device memcpys into /dev/fb0 — into an
// opaque RGBA image. Channels are widened by bit replication (the same
// inverse of Color.rgb the PNG golden encoder and the terminal sim use), so a
// frame that round-trips through here is comparable to a golden by eye and by
// construction.
//
// dst is reused when it already has the right bounds; otherwise a new image
// is allocated. The image in use is returned either way.
func Expand(src []byte, w, h int, dst *image.RGBA) *image.RGBA {
	dst = fit(dst, w, h)
	for y := 0; y < h; y++ {
		row := dst.PixOffset(0, y)
		for x := 0; x < w; x++ {
			i := (y*w + x) * 2
			c := uint16(src[i]) | uint16(src[i+1])<<8
			r5 := int(c>>11) & 0x1f
			g6 := int(c>>5) & 0x3f
			b5 := int(c) & 0x1f
			o := row + x*4
			dst.Pix[o+0] = byte(r5<<3 | r5>>2)
			dst.Pix[o+1] = byte(g6<<2 | g6>>4)
			dst.Pix[o+2] = byte(b5<<3 | b5>>2)
			dst.Pix[o+3] = 0xff
		}
	}
	return dst
}

// ScaleNearest magnifies src by an integer factor with nearest-neighbour
// sampling: every source pixel becomes an s*s block of the identical value.
// No smoothing, no resampling, no colour touched — the panel is 160x128 and
// its pixels are the design, so an interpolating scaler would be a lie about
// what the device shows.
//
// s < 1 is treated as 1. dst is reused when its bounds already match.
func ScaleNearest(src *image.RGBA, s int, dst *image.RGBA) *image.RGBA {
	if s < 1 {
		s = 1
	}
	w := src.Bounds().Dx()
	h := src.Bounds().Dy()
	dst = fit(dst, w*s, h*s)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			o := src.PixOffset(x, y)
			px := src.Pix[o : o+4 : o+4]
			for dy := 0; dy < s; dy++ {
				row := dst.PixOffset(x*s, y*s+dy)
				for dx := 0; dx < s; dx++ {
					copy(dst.Pix[row+dx*4:row+dx*4+4], px)
				}
			}
		}
	}
	return dst
}

// fit returns an *image.RGBA with exactly w*h bounds, reusing img when it
// already has them.
func fit(img *image.RGBA, w, h int) *image.RGBA {
	if img != nil && img.Bounds().Dx() == w && img.Bounds().Dy() == h {
		return img
	}
	return image.NewRGBA(image.Rect(0, 0, w, h))
}
