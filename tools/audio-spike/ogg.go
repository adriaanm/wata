// Ogg packet extraction with correct lacing semantics (a 255 segment
// continues the packet, < 255 ends it), dropping OpusHead/OpusTags.
// Same shape as go-pkgs/audio/foreign_decode_test.go's oggPackets — the
// production reader is the portable Scala Ogg.readFrames in
// wataclient/src/main/scala/ogg.scala; this spike is plain Go by design,
// so the test-local Go form is reused rather than dragging in an emitted tree.
package main

func oggPackets(d []byte) [][]byte {
	var packets [][]byte
	var pending []byte
	pos := 0
	for pos+27 <= len(d) && string(d[pos:pos+4]) == "OggS" {
		nseg := int(d[pos+26])
		segStart := pos + 27
		if segStart+nseg > len(d) {
			break
		}
		off := segStart + nseg
		for i := 0; i < nseg; i++ {
			sz := int(d[segStart+i])
			if off+sz > len(d) {
				return packets
			}
			pending = append(pending, d[off:off+sz]...)
			off += sz
			if sz < 255 {
				if len(pending) > 0 {
					packets = append(packets, pending)
				}
				pending = nil
			}
		}
		pos = off
	}
	// drop OpusHead + OpusTags
	if len(packets) >= 2 {
		return packets[2:]
	}
	return nil
}
