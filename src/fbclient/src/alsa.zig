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
/// Period size for ALSA. The Q6 ADSP prefers 6000 but accepts smaller.
/// Using 1920 (40ms) for lower latency — still a multiple of 960 (Opus frame).
pub const FRAMES_PER_PERIOD: u32 = 1920;
pub const PERIOD_BYTES: u32 = FRAMES_PER_PERIOD * CHANNELS * FRAME_SIZE;

/// Ring-buffer depth (in periods) for playback. 8 × 40 ms = 320 ms of slack
/// so writeFrames rarely blocks waiting for the DSP to drain, and a stray
/// scheduling hiccup between chunks doesn't underrun. Capture keeps a small
/// buffer (low latency) — only playback needs the cushion.
pub const PLAYBACK_PERIODS: u32 = 8;

/// Max periods handed to a single pcm_writei. The MSM Q6 ADSP rejects a large
/// one-shot write with ETIMEDOUT (its wait_event_timeout can't cover a multi-
/// second write in one call), so writeFrames splits the buffer into chunks of
/// this many periods and lets each drain — the pacing aplay does implicitly.
/// Kept <= PLAYBACK_PERIODS so a chunk always fits the ring.
pub const WRITE_CHUNK_PERIODS: u32 = 4;

pub const PcmError = error{ OpenFailed, WriteFailed, ReadFailed };

const PcmPtr = if (build_options.use_audio) ?*c.struct_pcm else ?*anyopaque;

/// Returns the last error from a tinyalsa PCM handle, or an empty string if
/// the handle is null. The returned pointer is owned by tinyalsa and valid
/// until the next pcm call.
pub fn pcmErrorStr(pcm: PcmPtr) []const u8 {
    if (!build_options.use_audio) return "";
    if (pcm == null) return "";
    const ptr = c.pcm_get_error(pcm) orelse return "";
    return std.mem.sliceTo(ptr, 0);
}

fn makeConfig() c.pcm_config {
    return .{
        .channels = CHANNELS,
        .rate = SAMPLE_RATE,
        .period_size = FRAMES_PER_PERIOD,
        .period_count = 2,
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
            const msg = pcmErrorStr(pcm);
            if (msg.len > 0) std.debug.print("[alsa] capture open failed: {s}\n", .{msg});
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Read one period (960 frames = 20ms) of S16_LE mono audio.
    pub fn readFrames(self: *Capture, buf: []u8) PcmError!u32 {
        const ret = c.pcm_readi(self.pcm, buf.ptr, FRAMES_PER_PERIOD);
        if (ret < 0) {
            std.debug.print("[alsa] pcm_readi failed: {s}\n", .{pcmErrorStr(self.pcm)});
            return error.ReadFailed;
        }
        return @intCast(ret);
    }

    pub fn close(self: *Capture) void {
        _ = c.pcm_close(self.pcm);
    }
};

pub const Playback = struct {
    pcm: PcmPtr,

    /// Open for playback. Auto-starts after first period is written.
    /// (MSM Q6 ADSP requires the stream to be running before pcm_writei works.)
    pub fn open() PcmError!Playback {
        var config = makeConfig();
        config.period_count = PLAYBACK_PERIODS;
        config.start_threshold = FRAMES_PER_PERIOD;
        // stop_threshold must be the full buffer, not 0. With 0 the kernel
        // stops the stream the instant the buffer has any free space, so
        // pcm_writei keeps accepting data without the DSP rendering it in
        // real time — the write "succeeds" and drains almost instantly but
        // nothing is audible. Set to the ring size so the stream only stops
        // on a genuine full underrun (matches aplay's default).
        config.stop_threshold = FRAMES_PER_PERIOD * PLAYBACK_PERIODS;
        const pcm = c.pcm_open(0, 0, c.PCM_OUT, &config);
        if (pcm == null or c.pcm_is_ready(pcm) == 0) {
            const msg = pcmErrorStr(pcm);
            if (msg.len > 0) std.debug.print("[alsa] playback open failed: {s}\n", .{msg});
            if (pcm != null) _ = c.pcm_close(pcm);
            return error.OpenFailed;
        }
        return .{ .pcm = pcm };
    }

    /// Write frames, pacing the MSM Q6 ADSP. `buf` must be whole periods
    /// (sub-period writes stall the Q6); callers trim to PERIOD_BYTES.
    ///
    /// A single large pcm_writei returns ETIMEDOUT on this device — the Q6
    /// driver can't accept a multi-second write in one call. Feed it
    /// WRITE_CHUNK_PERIODS at a time (as aplay does implicitly) so each call
    /// blocks only until the DSP drains a little and the write keeps pace with
    /// real-time playback.
    pub fn writeFrames(self: *Playback, buf: []const u8) PcmError!void {
        const chunk_bytes: usize = WRITE_CHUNK_PERIODS * PERIOD_BYTES;
        var off: usize = 0;
        while (off < buf.len) {
            const end = @min(off + chunk_bytes, buf.len);
            const slice = buf[off..end];
            const frames: c_uint = @intCast(slice.len / (FRAME_SIZE * CHANNELS));
            const ret = c.pcm_writei(self.pcm, slice.ptr, frames);
            if (ret < 0) {
                std.debug.print("[alsa] pcm_writei failed: {s}\n", .{pcmErrorStr(self.pcm)});
                return error.WriteFailed;
            }
            off = end;
        }
    }

    /// Wait for all buffered audio to finish playing.
    /// Uses the kernel DRAIN ioctl which blocks until the hardware has
    /// played all buffered samples. No sleep/timing hacks needed.
    pub fn drain(self: *Playback) void {
        // SNDRV_PCM_IOCTL_DRAIN = _IO('A', 0x44) = 0x4144
        // tinyalsa v2 doesn't expose pcm_drain(), so we call ioctl directly
        // on the file descriptor. Safe for hw devices (no plugin indirection).
        const SNDRV_PCM_IOCTL_DRAIN: u32 = 0x4144;
        const fd: std.os.linux.fd_t = @intCast(c.pcm_get_file_descriptor(self.pcm));
        _ = std.os.linux.ioctl(fd, SNDRV_PCM_IOCTL_DRAIN, 0);
    }

    pub fn close(self: *Playback) void {
        _ = c.pcm_close(self.pcm);
    }
};

/// Set up both playback and capture mixer routes (one-time after boot).
/// Called once at startup — no per-recording switching to avoid ADSP churn
/// that was causing hard crashes (PS_HOLD reset → fastboot).
///
/// The playback settings mirror bq268-alpine's `/etc/init.d/audio-mixer`
/// (source of truth for the boot-time mixer config); see
/// bq268-alpine/docs/roadmap.md §"Audio — done" for the signal path:
/// WCD msm8x16 codec → HPHR PA → GPIO36 ext amp → speaker.
pub fn setupMixer() void {
    const mixer = c.mixer_open(0) orelse return;
    defer c.mixer_close(mixer);

    // Playback route: MultiMedia1 → PRI_MI2S_RX → RX2 (HPHR) → speaker.
    // The PRI_MI2S_RX ↔ MultiMedia1 routing gate is the one that makes
    // pcm writes on hw:0,0 (MultiMedia1) actually reach the DAI — it is
    // NOT implied by the RX2/HPHR/Ext Spk chain. Without it, PA toggles
    // as expected but no samples flow to the codec.
    setInt(mixer, "PRI_MI2S_RX Audio Mixer MultiMedia1", 1);
    setEnum(mixer, "RX2 MIX1 INP1", "RX1");
    setEnum(mixer, "RDAC2 MUX", "RX2");
    setEnum(mixer, "HPHR", "Switch");
    setEnum(mixer, "Ext Spk Switch", "On");
    setInt(mixer, "RX2 Digital Volume", 96);

    // Capture route: mic on
    setInt(mixer, "MultiMedia1 Mixer TERT_MI2S_TX", 1);
    setEnum(mixer, "DEC1 MUX", "ADC1");
    setInt(mixer, "ADC1 Volume", 6);
    setInt(mixer, "DEC1 Volume", 104);
}

/// Legacy per-mode switching (kept for echo test, but no longer used
/// in the main audio thread to avoid ADSP crashes).
pub fn setupCaptureMixer() void {
    setupMixer();
}

pub fn setupPlaybackMixer() void {
    // No-op — mixer is already set up with both routes.
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
