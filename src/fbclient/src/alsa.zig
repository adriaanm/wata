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
/// Q6 ADSP native period: 6000 frames (125ms). Using this avoids rate
/// negotiation issues where tinyalsa silently rounds to a different config.
pub const FRAMES_PER_PERIOD: u32 = 6000;
pub const PERIOD_BYTES: u32 = FRAMES_PER_PERIOD * CHANNELS * FRAME_SIZE;

pub const PcmError = error{ OpenFailed, WriteFailed, ReadFailed };

const PcmPtr = if (build_options.use_audio) ?*c.struct_pcm else ?*anyopaque;

fn makeConfig() c.pcm_config {
    return .{
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
}

/// Get the actual rate negotiated by the hardware. For diagnostics.
pub fn getRate(pcm: PcmPtr) u32 {
    return c.pcm_get_rate(pcm);
}

pub const Capture = struct {
    pcm: PcmPtr,

    pub fn open() PcmError!Capture {
        var config = makeConfig();
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

    /// Open for streaming playback (starts after first period).
    pub fn open() PcmError!Playback {
        var config = makeConfig();
        config.start_threshold = FRAMES_PER_PERIOD;
        const pcm = c.pcm_open(0, 0, c.PCM_OUT, &config);
        if (pcm == null or c.pcm_is_ready(pcm) == 0) {
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Open with large start_threshold — ALSA won't play until all data
    /// is buffered. Use for pre-buffered playback (echo test, short clips).
    pub fn openBuffered(total_frames: u32) PcmError!Playback {
        var config = makeConfig();
        // Buffer must hold all the data: need enough periods
        config.period_count = (total_frames + FRAMES_PER_PERIOD - 1) / FRAMES_PER_PERIOD + 1;
        config.start_threshold = total_frames;
        config.stop_threshold = total_frames + FRAMES_PER_PERIOD;
        const pcm = c.pcm_open(0, 0, c.PCM_OUT, &config);
        if (pcm == null or c.pcm_is_ready(pcm) == 0) {
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Write frames. `buf` length determines frame count.
    pub fn writeFrames(self: *Playback, buf: []const u8) PcmError!void {
        const frames: c_uint = @intCast(buf.len / (FRAME_SIZE * CHANNELS));
        const ret = c.pcm_writei(self.pcm, buf.ptr, frames);
        if (ret < 0) return error.WriteFailed;
    }

    /// Wait for all buffered audio to finish playing.
    pub fn drain(self: *Playback) void {
        _ = c.pcm_start(self.pcm);
        const buf_frames = c.pcm_get_buffer_size(self.pcm);
        const ms = (buf_frames * 1000) / SAMPLE_RATE + 200;
        var ts = std.os.linux.timespec{ .sec = @intCast(ms / 1000), .nsec = @intCast((ms % 1000) * 1_000_000) };
        _ = std.os.linux.nanosleep(&ts, null);
    }

    pub fn close(self: *Playback) void {
        _ = c.pcm_close(self.pcm);
    }
};

/// Set up both playback and capture mixer routes (one-time after boot).
pub fn setupMixer() void {
    setupCaptureMixer();
    setupPlaybackMixer();
}

/// Enable microphone capture route. Disables speaker to prevent feedback.
pub fn setupCaptureMixer() void {
    const mixer = c.mixer_open(0) orelse return;
    defer c.mixer_close(mixer);

    // Mute speaker completely
    setEnum(mixer, "RX2 MIX1 INP1", "ZERO");
    setEnum(mixer, "Ext Spk Switch", "Off");

    // Enable mic capture route
    setInt(mixer, "MultiMedia1 Mixer TERT_MI2S_TX", 1);
    setEnum(mixer, "DEC1 MUX", "ADC1");
    setInt(mixer, "ADC1 Volume", 6);
    setInt(mixer, "DEC1 Volume", 104);

    // Verify with amixer (diagnostic — writes to /tmp/wata-mixer.log)
    verifyMixer();
}

/// Enable speaker playback route.
pub fn setupPlaybackMixer() void {
    const mixer = c.mixer_open(0) orelse return;
    defer c.mixer_close(mixer);

    setEnum(mixer, "RX2 MIX1 INP1", "RX1");
    setEnum(mixer, "RDAC2 MUX", "RX2");
    setEnum(mixer, "HPHR", "Switch");
    setEnum(mixer, "Ext Spk Switch", "On");
    setInt(mixer, "RX2 Digital Volume", 84);
}

fn verifyMixer() void {
    const sys = @cImport(@cInclude("stdlib.h"));
    _ = sys.system("amixer cget name='Ext Spk Switch' > /tmp/wata-mixer.log 2>&1; amixer cget name='RX2 MIX1 INP1' >> /tmp/wata-mixer.log 2>&1");
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
