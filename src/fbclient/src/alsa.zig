/// ALSA audio via tinyalsa — PCM capture/playback + mixer setup.
/// BQ268 constraints: hw:0,0, 48000 Hz, S16_LE, mono.
const std = @import("std");
const build_options = @import("build_options");

pub const c = if (build_options.use_audio) @cImport({
    @cInclude("tinyalsa/pcm.h");
    @cInclude("tinyalsa/mixer.h");
}) else struct {};

pub const SAMPLE_RATE: u32 = 48000;
pub const CHANNELS: u32 = 1;
pub const FRAME_SIZE: u32 = 2; // S16_LE = 2 bytes per sample per channel
/// 20ms of audio at 48kHz = 960 frames
pub const FRAMES_PER_PERIOD: u32 = 960;
pub const PERIOD_BYTES: u32 = FRAMES_PER_PERIOD * CHANNELS * FRAME_SIZE;

pub const PcmError = error{ OpenFailed, WriteFailed, ReadFailed };

const PcmPtr = if (build_options.use_audio) ?*c.struct_pcm else ?*anyopaque;

pub const Capture = struct {
    pcm: PcmPtr,

    pub fn open() PcmError!Capture {
        const config = c.pcm_config{
            .channels = CHANNELS,
            .rate = SAMPLE_RATE,
            .period_size = FRAMES_PER_PERIOD,
            .period_count = 4,
            .format = c.PCM_FORMAT_S16_LE,
            .start_threshold = 0,
            .stop_threshold = 0,
            .silence_threshold = 0,
            .silence_size = 0,
            .avail_min = 0,
        };
        const pcm = c.pcm_open(0, 0, c.PCM_IN, &config);
        if (pcm == null or c.pcm_is_ready(pcm) == 0) {
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Read one period (960 frames = 20ms) of S16_LE mono audio.
    pub fn readFrames(self: *Capture, buf: []u8) PcmError!u32 {
        const ret = c.pcm_readi(self.pcm, buf.ptr, FRAMES_PER_PERIOD);
        if (ret < 0) return error.ReadFailed;
        return @intCast(ret);
    }

    pub fn close(self: *Capture) void {
        _ = c.pcm_close(self.pcm);
    }
};

pub const Playback = struct {
    pcm: PcmPtr,

    pub fn open() PcmError!Playback {
        const config = c.pcm_config{
            .channels = CHANNELS,
            .rate = SAMPLE_RATE,
            .period_size = FRAMES_PER_PERIOD,
            .period_count = 4,
            .format = c.PCM_FORMAT_S16_LE,
            .start_threshold = FRAMES_PER_PERIOD,
            .stop_threshold = 0,
            .silence_threshold = 0,
            .silence_size = 0,
            .avail_min = 0,
        };
        const pcm = c.pcm_open(0, 0, c.PCM_OUT, &config);
        if (pcm == null or c.pcm_is_ready(pcm) == 0) {
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Write one period of S16_LE mono audio.
    pub fn writeFrames(self: *Playback, buf: []const u8) PcmError!void {
        const ret = c.pcm_writei(self.pcm, buf.ptr, FRAMES_PER_PERIOD);
        if (ret < 0) return error.WriteFailed;
    }

    pub fn close(self: *Playback) void {
        _ = c.pcm_close(self.pcm);
    }
};

/// Set up the audio mixer routes for the BQ268 (one-time after boot).
pub fn setupMixer() void {
    const mixer = c.mixer_open(0) orelse return;
    defer c.mixer_close(mixer);

    // Speaker playback route
    setEnum(mixer, "RX2 MIX1 INP1", "RX1");
    setEnum(mixer, "RDAC2 MUX", "RX2");
    setSwitch(mixer, "HPHR", true);
    setSwitch(mixer, "Ext Spk Switch", true);
    setInt(mixer, "RX2 Digital Volume", 84);

    // Microphone capture route
    setSwitch(mixer, "MultiMedia1 Mixer TERT_MI2S_TX", true);
    setEnum(mixer, "DEC1 MUX", "ADC1");
    setInt(mixer, "ADC1 Volume", 6);
    setInt(mixer, "DEC1 Volume", 104);
}

fn setEnum(mixer: anytype, name: [*:0]const u8, value: [*:0]const u8) void {
    const ctl = c.mixer_get_ctl_by_name(mixer, name) orelse return;
    const num_enums = c.mixer_ctl_get_num_enums(ctl);
    var i: c_uint = 0;
    while (i < num_enums) : (i += 1) {
        const enum_str = c.mixer_ctl_get_enum_string(ctl, i) orelse continue;
        if (std.mem.orderZ(u8, enum_str, value) == .eq) {
            _ = c.mixer_ctl_set_value(ctl, 0, @as(c_int, @intCast(i)));
            return;
        }
    }
}

fn setSwitch(mixer: anytype, name: [*:0]const u8, on: bool) void {
    const ctl = c.mixer_get_ctl_by_name(mixer, name) orelse return;
    _ = c.mixer_ctl_set_value(ctl, 0, if (on) 1 else 0);
}

fn setInt(mixer: anytype, name: [*:0]const u8, value: c_int) void {
    const ctl = c.mixer_get_ctl_by_name(mixer, name) orelse return;
    _ = c.mixer_ctl_set_value(ctl, 0, value);
}
