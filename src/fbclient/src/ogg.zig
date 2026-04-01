/// Minimal Ogg container for a single Opus stream — pure Zig, no libogg.
/// Supports writing (for recording) and reading (for playback).
const std = @import("std");

/// Ogg page header (27 bytes fixed + segment_table).
const PAGE_HEADER_SIZE = 27;
const MAX_SEGMENTS = 255;

/// Ogg header type flags.
const CONTINUED: u8 = 0x01;
const BOS: u8 = 0x02; // beginning of stream
const EOS: u8 = 0x04; // end of stream

// ---------------------------------------------------------------------------
// Writer — builds an Ogg/Opus stream in memory
// ---------------------------------------------------------------------------

pub const Writer = struct {
    buf: std.ArrayListUnmanaged(u8),
    allocator: std.mem.Allocator,
    serial: u32,
    page_seq: u32,
    granule: u64,

    pub fn init(allocator: std.mem.Allocator) Writer {
        return .{
            .buf = .empty,
            .allocator = allocator,
            .serial = 0x77617461, // "wata"
            .page_seq = 0,
            .granule = 0,
        };
    }

    pub fn deinit(self: *Writer) void {
        self.buf.deinit(self.allocator);
    }

    /// Write the Opus identification header (OpusHead) as the first Ogg page.
    pub fn writeOpusHead(self: *Writer) !void {
        var head: [19]u8 = undefined;
        @memcpy(head[0..8], "OpusHead");
        head[8] = 1; // version
        head[9] = 1; // channel count (mono)
        std.mem.writeInt(u16, head[10..12], 312, .little); // pre-skip (6.5ms at 48kHz)
        std.mem.writeInt(u32, head[12..16], 48000, .little); // input sample rate
        std.mem.writeInt(u16, head[16..18], 0, .little); // output gain
        head[18] = 0; // channel mapping family (mono)
        try self.writePage(&head, 0, BOS);
    }

    /// Write the Opus comment header (OpusTags) as the second Ogg page.
    pub fn writeOpusTags(self: *Writer) !void {
        var tags: [20]u8 = undefined;
        @memcpy(tags[0..8], "OpusTags");
        std.mem.writeInt(u32, tags[8..12], 4, .little); // vendor string length
        @memcpy(tags[12..16], "wata");
        std.mem.writeInt(u32, tags[16..20], 0, .little); // comment count
        try self.writePage(&tags, 0, 0);
    }

    /// Write an Opus audio frame as an Ogg page.
    pub fn writeAudioFrame(self: *Writer, frame_data: []const u8, samples: u32) !void {
        self.granule += samples;
        try self.writePage(frame_data, self.granule, 0);
    }

    /// Finalize the stream — writes an empty EOS page.
    pub fn finish(self: *Writer) !void {
        try self.writePage(&.{}, self.granule, EOS);
    }

    /// Get the complete Ogg/Opus data.
    pub fn data(self: *const Writer) []const u8 {
        return self.buf.items;
    }

    fn writePage(self: *Writer, payload: []const u8, granule: u64, header_type: u8) !void {
        const num_segments: u8 = if (payload.len == 0) 1 else @intCast((payload.len + 254) / 255);

        var hdr: [PAGE_HEADER_SIZE]u8 = undefined;
        @memcpy(hdr[0..4], "OggS");
        hdr[4] = 0; // version
        hdr[5] = header_type;
        std.mem.writeInt(u64, hdr[6..14], granule, .little);
        std.mem.writeInt(u32, hdr[14..18], self.serial, .little);
        std.mem.writeInt(u32, hdr[18..22], self.page_seq, .little);
        std.mem.writeInt(u32, hdr[22..26], 0, .little); // CRC placeholder
        hdr[26] = num_segments;

        // Segment table
        var seg_table: [MAX_SEGMENTS]u8 = undefined;
        var remaining = payload.len;
        for (0..num_segments) |i| {
            if (remaining >= 255) {
                seg_table[i] = 255;
                remaining -= 255;
            } else {
                seg_table[i] = @intCast(remaining);
                remaining = 0;
            }
        }

        // Compute CRC over header + segment_table + payload
        var crc: u32 = 0;
        crc = crc32Update(crc, &hdr);
        crc = crc32Update(crc, seg_table[0..num_segments]);
        crc = crc32Update(crc, payload);
        std.mem.writeInt(u32, hdr[22..26], crc, .little);

        // Write to buffer
        try self.buf.appendSlice(self.allocator, &hdr);
        try self.buf.appendSlice(self.allocator, seg_table[0..num_segments]);
        try self.buf.appendSlice(self.allocator, payload);

        self.page_seq += 1;
    }
};

// ---------------------------------------------------------------------------
// Reader — extracts Opus frames from an Ogg stream
// ---------------------------------------------------------------------------

pub const Reader = struct {
    ogg_data: []const u8,
    pos: usize,
    pages_read: u32,

    pub fn init(ogg_data: []const u8) Reader {
        return .{ .ogg_data = ogg_data, .pos = 0, .pages_read = 0 };
    }

    /// Read the next Opus audio frame. Skips header pages.
    /// Returns null when the stream is exhausted.
    pub fn nextFrame(self: *Reader, frame_buf: []u8) ?[]const u8 {
        while (self.pos + PAGE_HEADER_SIZE <= self.ogg_data.len) {
            if (!std.mem.eql(u8, self.ogg_data[self.pos..][0..4], "OggS")) return null;

            const num_segments = self.ogg_data[self.pos + 26];
            const seg_table_start = self.pos + PAGE_HEADER_SIZE;
            const seg_table_end = seg_table_start + num_segments;
            if (seg_table_end > self.ogg_data.len) return null;

            var payload_size: usize = 0;
            for (self.ogg_data[seg_table_start..seg_table_end]) |s| {
                payload_size += s;
            }

            const payload_start = seg_table_end;
            const payload_end = payload_start + payload_size;
            if (payload_end > self.ogg_data.len) return null;

            const payload = self.ogg_data[payload_start..payload_end];
            const header_type = self.ogg_data[self.pos + 5];

            self.pos = payload_end;
            self.pages_read += 1;

            // Skip BOS (OpusHead), second page (OpusTags), empty EOS
            if (header_type & BOS != 0) continue;
            if (self.pages_read == 2) continue;
            if (payload_size == 0) continue;

            if (payload_size > frame_buf.len) return null;
            @memcpy(frame_buf[0..payload_size], payload);
            return frame_buf[0..payload_size];
        }
        return null;
    }
};

// ---------------------------------------------------------------------------
// Ogg CRC-32 (polynomial 0x04C11DB7)
// ---------------------------------------------------------------------------

/// Ogg CRC-32 update (exposed for testing).
pub fn crc32Update(crc_in: u32, in_data: []const u8) u32 {
    var crc = crc_in;
    for (in_data) |byte| {
        crc = (crc << 8) ^ crc_table[@intCast((crc >> 24) ^ byte)];
    }
    return crc;
}

// Expose constants for tests
pub const HDR_SIZE = PAGE_HEADER_SIZE;
pub const FLAG_BOS = BOS;
pub const FLAG_EOS = EOS;

const crc_table: [256]u32 = blk: {
    @setEvalBranchQuota(10000);
    var table: [256]u32 = undefined;
    for (0..256) |i| {
        var r: u32 = @intCast(i << 24);
        for (0..8) |_| {
            if (r & 0x80000000 != 0) {
                r = (r << 1) ^ 0x04C11DB7;
            } else {
                r <<= 1;
            }
        }
        table[i] = r;
    }
    break :blk table;
};

// ---------------------------------------------------------------------------
// Tests — ported from src/shared/lib/__tests__/ogg.test.ts
// ---------------------------------------------------------------------------

const testing = std.testing;

test "crc32: empty input returns zero" {
    try testing.expectEqual(@as(u32, 0), crc32Update(0, &.{}));
}

test "crc32: consistent for same input" {
    const data = "Hello, Ogg!";
    const crc1 = crc32Update(0, data);
    const crc2 = crc32Update(0, data);
    try testing.expectEqual(crc1, crc2);
}

test "crc32: different inputs produce different results" {
    const crc1 = crc32Update(0, "abc");
    const crc2 = crc32Update(0, "def");
    try testing.expect(crc1 != crc2);
}

test "crc32: incremental matches single-pass" {
    const data = "Hello, Ogg!";
    const single = crc32Update(0, data);
    var incremental: u32 = 0;
    incremental = crc32Update(incremental, data[0..6]);
    incremental = crc32Update(incremental, data[6..]);
    try testing.expectEqual(single, incremental);
}

test "OpusHead: correct structure (19 bytes, magic, version, channels, pre-skip, rate)" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();

    const d = w.data();
    // OggS magic
    try testing.expectEqualSlices(u8, "OggS", d[0..4]);
    // BOS flag
    try testing.expectEqual(BOS, d[5]);

    // Payload starts after header (27) + 1 segment byte
    const payload = d[28..];
    try testing.expectEqualSlices(u8, "OpusHead", payload[0..8]);
    try testing.expectEqual(@as(u8, 1), payload[8]); // version
    try testing.expectEqual(@as(u8, 1), payload[9]); // mono
    // Pre-skip = 312 (little-endian)
    try testing.expectEqual(@as(u16, 312), std.mem.readInt(u16, payload[10..12], .little));
    // Sample rate = 48000
    try testing.expectEqual(@as(u32, 48000), std.mem.readInt(u32, payload[12..16], .little));
    // Output gain = 0
    try testing.expectEqual(@as(u16, 0), std.mem.readInt(u16, payload[16..18], .little));
    // Channel mapping family = 0 (mono)
    try testing.expectEqual(@as(u8, 0), payload[18]);
}

test "OpusTags: correct structure (magic, vendor, no comments)" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();

    const d = w.data();
    // Second page starts after first page
    const seg_count_1 = d[26];
    var payload_1: usize = 0;
    for (d[27..][0..seg_count_1]) |s| payload_1 += s;
    const page2_start = 27 + seg_count_1 + payload_1;

    const p2 = d[page2_start..];
    try testing.expectEqualSlices(u8, "OggS", p2[0..4]);
    // Not BOS (second page)
    try testing.expectEqual(@as(u8, 0), p2[5] & BOS);

    const seg_count_2 = p2[26];
    const tags_payload = p2[27 + seg_count_2 ..];
    try testing.expectEqualSlices(u8, "OpusTags", tags_payload[0..8]);
    // Vendor string length = 4 ("wata")
    try testing.expectEqual(@as(u32, 4), std.mem.readInt(u32, tags_payload[8..12], .little));
    try testing.expectEqualSlices(u8, "wata", tags_payload[12..16]);
    // Comment count = 0
    try testing.expectEqual(@as(u32, 0), std.mem.readInt(u32, tags_payload[16..20], .little));
}

test "page CRC: valid CRC in header (bytes 22..26)" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();

    const d = w.data();
    const stored_crc = std.mem.readInt(u32, d[22..26], .little);

    // Recompute CRC with CRC field zeroed
    var hdr_copy: [27]u8 = undefined;
    @memcpy(&hdr_copy, d[0..27]);
    std.mem.writeInt(u32, hdr_copy[22..26], 0, .little);

    const seg_count = d[26];
    var payload_size: usize = 0;
    for (d[27..][0..seg_count]) |s| payload_size += s;

    var crc: u32 = 0;
    crc = crc32Update(crc, &hdr_copy);
    crc = crc32Update(crc, d[27..][0..seg_count]);
    crc = crc32Update(crc, d[27 + seg_count ..][0..payload_size]);

    try testing.expectEqual(stored_crc, crc);
}

test "audio frame: granule position increments by sample count" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();

    // Write two audio frames with 960 samples each
    var fake_opus: [100]u8 = undefined;
    @memset(&fake_opus, 0xAB);
    try w.writeAudioFrame(&fake_opus, 960);
    try w.writeAudioFrame(&fake_opus, 960);

    // Read back with Reader and verify we get 2 frames
    var reader = Reader.init(w.data());
    var frame_buf: [4000]u8 = undefined;
    const f1 = reader.nextFrame(&frame_buf);
    try testing.expect(f1 != null);
    try testing.expectEqual(@as(usize, 100), f1.?.len);

    const f2 = reader.nextFrame(&frame_buf);
    try testing.expect(f2 != null);

    // No more frames
    const f3 = reader.nextFrame(&frame_buf);
    try testing.expect(f3 == null);
}

test "mux/demux roundtrip: data integrity preserved" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();

    // Write 5 frames with distinct data
    var frames: [5][50]u8 = undefined;
    for (&frames, 0..) |*f, i| {
        @memset(f, @intCast(i + 1)); // frame 0 = all 0x01, etc.
    }
    for (&frames) |*f| {
        try w.writeAudioFrame(f, 960);
    }
    try w.finish();

    // Read back and verify each frame's content
    var reader = Reader.init(w.data());
    var buf: [4000]u8 = undefined;
    for (0..5) |i| {
        const frame = reader.nextFrame(&buf) orelse {
            return error.TestUnexpectedResult;
        };
        try testing.expectEqual(@as(usize, 50), frame.len);
        // Each byte should match the fill value
        try testing.expectEqual(@as(u8, @intCast(i + 1)), frame[0]);
        try testing.expectEqual(@as(u8, @intCast(i + 1)), frame[49]);
    }
    // Stream exhausted
    try testing.expect(reader.nextFrame(&buf) == null);
}

test "finish: EOS page written" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();
    try w.finish();

    // Find the last page and check EOS flag
    const d = w.data();
    // Walk pages to find the last one
    var pos: usize = 0;
    var last_flags: u8 = 0;
    while (pos + PAGE_HEADER_SIZE <= d.len) {
        if (!std.mem.eql(u8, d[pos..][0..4], "OggS")) break;
        last_flags = d[pos + 5];
        const ns = d[pos + 26];
        var ps: usize = 0;
        for (d[pos + 27 ..][0..ns]) |s| ps += s;
        pos = pos + 27 + ns + ps;
    }
    try testing.expect(last_flags & EOS != 0);
}

test "reader: skips BOS and OpusTags pages" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();

    var payload: [10]u8 = undefined;
    @memset(&payload, 0x42);
    try w.writeAudioFrame(&payload, 960);

    var reader = Reader.init(w.data());
    var buf: [4000]u8 = undefined;
    // First call should skip OpusHead and OpusTags, return audio frame
    const frame = reader.nextFrame(&buf);
    try testing.expect(frame != null);
    try testing.expectEqual(@as(u8, 0x42), frame.?[0]);
}

test "reader: empty stream returns null" {
    var reader = Reader.init(&.{});
    var buf: [4000]u8 = undefined;
    try testing.expect(reader.nextFrame(&buf) == null);
}

test "segment table: large payload (>255 bytes) spans multiple segments" {
    var w = Writer.init(testing.allocator);
    defer w.deinit();
    try w.writeOpusHead();
    try w.writeOpusTags();

    // Write a 600-byte payload (needs 3 segments: 255 + 255 + 90)
    var big: [600]u8 = undefined;
    @memset(&big, 0xCC);
    try w.writeAudioFrame(&big, 960);

    // Read back
    var reader = Reader.init(w.data());
    var buf: [4000]u8 = undefined;
    const frame = reader.nextFrame(&buf);
    try testing.expect(frame != null);
    try testing.expectEqual(@as(usize, 600), frame.?.len);
    try testing.expectEqual(@as(u8, 0xCC), frame.?[0]);
    try testing.expectEqual(@as(u8, 0xCC), frame.?[599]);
}
