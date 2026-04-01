/// Opus encoder/decoder wrapper — 48kHz mono, 20ms frames.
const std = @import("std");
const build_options = @import("build_options");

const c = if (build_options.use_audio) @cImport({
    @cInclude("opus.h");
}) else struct {};

pub const SAMPLE_RATE: u32 = 48000;
pub const CHANNELS: u32 = 1;
/// 20ms at 48kHz = 960 samples
pub const FRAME_SAMPLES: u32 = 960;
/// Max encoded frame size (Opus recommends 4000 for safety)
pub const MAX_FRAME_BYTES: u32 = 4000;

pub const OpusError = error{ EncoderCreateFailed, DecoderCreateFailed, EncodeFailed, DecodeFailed };

pub const Encoder = struct {
    enc: *anyopaque,

    pub fn init() OpusError!Encoder {
        var err: c_int = 0;
        const enc = c.opus_encoder_create(
            @intCast(SAMPLE_RATE),
            @intCast(CHANNELS),
            c.OPUS_APPLICATION_VOIP,
            &err,
        ) orelse return error.EncoderCreateFailed;
        if (err != c.OPUS_OK) return error.EncoderCreateFailed;

        // Set bitrate for voice (16 kbps is good for walkie-talkie)
        _ = c.opus_encoder_ctl(enc, c.OPUS_SET_BITRATE_REQUEST, @as(c_int, 16000));
        // Enable DTX for silence compression
        _ = c.opus_encoder_ctl(enc, c.OPUS_SET_DTX_REQUEST, @as(c_int, 1));

        return .{ .enc = enc };
    }

    /// Encode one frame (960 S16_LE samples) → compressed Opus bytes.
    /// Returns the number of encoded bytes written to `out`.
    pub fn encode(self: *Encoder, pcm: []const u8, out: []u8) OpusError!u32 {
        const pcm_ptr: [*]const i16 = @ptrCast(@alignCast(pcm.ptr));
        const ret = c.opus_encode(
            @ptrCast(self.enc),
            pcm_ptr,
            @intCast(FRAME_SAMPLES),
            out.ptr,
            @intCast(out.len),
        );
        if (ret < 0) return error.EncodeFailed;
        return @intCast(ret);
    }

    pub fn deinit(self: *Encoder) void {
        c.opus_encoder_destroy(@ptrCast(self.enc));
    }
};

pub const Decoder = struct {
    dec: *anyopaque,

    pub fn init() OpusError!Decoder {
        var err: c_int = 0;
        const dec = c.opus_decoder_create(
            @intCast(SAMPLE_RATE),
            @intCast(CHANNELS),
            &err,
        ) orelse return error.DecoderCreateFailed;
        if (err != c.OPUS_OK) return error.DecoderCreateFailed;
        return .{ .dec = dec };
    }

    /// Decode one Opus frame → PCM S16_LE samples.
    /// Returns the number of decoded samples (should be FRAME_SAMPLES).
    pub fn decode(self: *Decoder, data: []const u8, pcm_out: []u8) OpusError!u32 {
        const pcm_ptr: [*]i16 = @ptrCast(@alignCast(pcm_out.ptr));
        const ret = c.opus_decode(
            @ptrCast(self.dec),
            data.ptr,
            @intCast(data.len),
            pcm_ptr,
            @intCast(FRAME_SAMPLES),
            0, // no FEC
        );
        if (ret < 0) return error.DecodeFailed;
        return @intCast(ret);
    }

    pub fn deinit(self: *Decoder) void {
        c.opus_decoder_destroy(@ptrCast(self.dec));
    }
};
