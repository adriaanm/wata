// Package qr adapts rsc.io/qr — an ORDINARY fetched Go dependency (see
// README) — to the one shape wata's device client wants.
//
// The device draws QR codes straight into an RGB565 framebuffer, so it wants
// the module grid, not a PNG: one byte per module, row-major, 1 = dark. The
// side length is the integer square root of the returned length, which the
// caller recomputes rather than being handed a second return value — the
// Sgola facade binds one result plus an error.
//
// This module exists ONLY for that adaptation. It is the module that carries
// the `require rsc.io/qr` line, so wata-fb's `godep` (which names a local
// directory) still reaches an upstream package through it.

package qr

import (
	"errors"

	"rsc.io/qr"
)

// Matrix encodes text at error-correction level L and returns the module grid
// as size*size bytes, row-major, 1 = dark, 0 = light. Level L (the least
// redundant) is deliberate: the payload is a URL near 110 bytes, the reader is
// a phone camera held against a clean 160x128 LCD, and every redundancy step
// costs QR versions — i.e. modules — which on this panel costs the one thing
// that decides whether a scan works at all, the pixels per module.
func Matrix(text string) ([]byte, error) {
	c, err := qr.Encode(text, qr.L)
	if err != nil {
		return nil, err
	}
	if c.Size <= 0 {
		return nil, errors.New("qr: empty code")
	}
	out := make([]byte, c.Size*c.Size)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; x++ {
			if c.Black(x, y) {
				out[y*c.Size+x] = 1
			}
		}
	}
	return out, nil
}
