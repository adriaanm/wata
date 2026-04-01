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

fn crc32Update(crc_in: u32, in_data: []const u8) u32 {
    var crc = crc_in;
    for (in_data) |byte| {
        crc = (crc << 8) ^ crc_table[@intCast((crc >> 24) ^ byte)];
    }
    return crc;
}

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
