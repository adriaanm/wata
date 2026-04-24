/// On-device audio self-test. Invoked via `wata-fb --selftest`. Spawns the
/// real production audio thread and drives it via its public command queue
/// — exactly how the UI does it in the app — so a passing selftest means
/// the production audio path works end-to-end.
///
/// Stages:
///   1. echo_test (record 2s → encode → decode → play). Primary gate: user
///      should hear their own voice played back.
///   2. play an Ogg/Opus buffer of a 440 Hz tone. Tests the Matrix-receive
///      path (decode-and-play of an Ogg buffer).
const std = @import("std");
const build_options = @import("build_options");
const alsa = @import("alsa.zig");
const audio_thread = @import("audio_thread.zig");
const opus_mod = @import("opus.zig");
const ogg = @import("ogg.zig");

const print = std.debug.print;
const linux = std.os.linux;

fn nowMs() u64 {
    var ts: linux.timespec = undefined;
    _ = linux.clock_gettime(.MONOTONIC, &ts);
    return @as(u64, @intCast(ts.sec)) * 1000 + @as(u64, @intCast(ts.nsec)) / 1_000_000;
}

fn sleepMs(ms: u64) void {
    const ts = linux.timespec{
        .sec = @intCast(ms / 1000),
        .nsec = @intCast((ms % 1000) * 1_000_000),
    };
    _ = linux.nanosleep(&ts, null);
}

/// Collect events until `target` is received, or an error tag fires.
/// Times out after `timeout_ms`.
fn waitFor(evt_queue: *audio_thread.EventQueue, target: std.meta.Tag(audio_thread.Event), timeout_ms: u64) !void {
    const deadline = nowMs() + timeout_ms;
    while (nowMs() < deadline) {
        if (evt_queue.tryReceive()) |ev| {
            print("  [event] {s}\n", .{@tagName(ev)});
            const tag = std.meta.activeTag(ev);
            if (tag == target) return;
            switch (ev) {
                .recording_error, .playback_error, .echo_error => return error.AudioFailure,
                .recording_done => |r| r.allocator.free(@constCast(r.ogg_data)),
                else => {},
            }
        } else {
            sleepMs(10);
        }
    }
    return error.Timeout;
}

/// Build a valid Ogg/Opus buffer containing a 440 Hz tone. Uses the
/// production Encoder + Ogg Writer so the output is byte-identical to what
/// the app produces from a real recording.
fn synthesizeOggTone(allocator: std.mem.Allocator, duration_ms: u32) ![]u8 {
    const OPUS_FRAME_BYTES = opus_mod.FRAME_SAMPLES * alsa.FRAME_SIZE * alsa.CHANNELS;
    const total_frames = (alsa.SAMPLE_RATE * duration_ms) / 1000;
    const num_opus_frames = total_frames / opus_mod.FRAME_SAMPLES;

    var encoder = try opus_mod.Encoder.init();
    defer encoder.deinit();

    var writer = ogg.Writer.init(allocator);
    errdefer writer.deinit();
    try writer.writeOpusHead();
    try writer.writeOpusTags();

    var pcm_frame: [OPUS_FRAME_BYTES]u8 = undefined;
    var opus_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    const amp: f32 = 0.25 * 32767.0;
    const samples_per_sec: f32 = @floatFromInt(alsa.SAMPLE_RATE);

    var f: u32 = 0;
    while (f < num_opus_frames) : (f += 1) {
        // Fill one 20ms PCM frame with the tone
        var i: u32 = 0;
        while (i < opus_mod.FRAME_SAMPLES) : (i += 1) {
            const sample_idx: u32 = f * opus_mod.FRAME_SAMPLES + i;
            const t: f32 = @as(f32, @floatFromInt(sample_idx)) / samples_per_sec;
            const v: f32 = amp * std.math.sin(2.0 * std.math.pi * 440.0 * t);
            const s: i16 = @intFromFloat(v);
            const us: u16 = @bitCast(s);
            pcm_frame[i * 2] = @truncate(us);
            pcm_frame[i * 2 + 1] = @truncate(us >> 8);
        }
        const encoded = try encoder.encode(&pcm_frame, &opus_buf);
        try writer.writeAudioFrame(opus_buf[0..encoded], opus_mod.FRAME_SAMPLES);
    }
    try writer.finish();
    return allocator.dupe(u8, writer.data()) catch |e| {
        writer.deinit();
        return e;
    };
}

pub const Stage = enum { all, echo, play };

pub fn run(allocator: std.mem.Allocator, stage: Stage) u8 {
    if (!build_options.use_audio) {
        print("selftest: build_options.use_audio=false — nothing to test\n", .{});
        return 2;
    }

    // Spawn the real production audio thread.
    var cmd_queue: audio_thread.CommandQueue = .{};
    var evt_queue: audio_thread.EventQueue = .{};
    var ctx = audio_thread.Context{
        .cmd_queue = &cmd_queue,
        .event_queue = &evt_queue,
        .allocator = allocator,
    };
    const handle = std.Thread.spawn(.{}, audio_thread.audioThreadMain, .{&ctx}) catch {
        print("failed to spawn audio thread\n", .{});
        return 1;
    };
    defer {
        cmd_queue.close();
        handle.join();
    }

    if (stage == .all or stage == .echo) {
        print("\n=== echo_test — record 2s, play back (SPEAK, then LISTEN) ===\n", .{});
        if (!cmd_queue.send(.echo_test)) {
            print("  failed to send echo_test command\n", .{});
            return 1;
        }
        waitFor(&evt_queue, .echo_done, 15_000) catch |e| {
            print("  echo_test failed: {s}\n", .{@errorName(e)});
            return 1;
        };
        print("  echo_test completed\n", .{});
    }

    if (stage == .all or stage == .play) {
        print("\n=== play Ogg/Opus 440Hz tone (LISTEN for a beep) ===\n", .{});
        const ogg_bytes = synthesizeOggTone(allocator, 1500) catch |e| {
            print("  failed to synthesize tone: {s}\n", .{@errorName(e)});
            return 1;
        };
        print("  synthesized {d} bytes of Ogg/Opus\n", .{ogg_bytes.len});
        if (!cmd_queue.send(.{ .play = .{ .ogg_data = ogg_bytes, .allocator = allocator } })) {
            print("  failed to send play command\n", .{});
            allocator.free(ogg_bytes);
            return 1;
        }
        // Ownership transferred to audio thread — don't free ogg_bytes here.
        waitFor(&evt_queue, .playback_done, 10_000) catch |e| {
            print("  play failed: {s}\n", .{@errorName(e)});
            return 1;
        };
        print("  playback_done\n", .{});
    }

    print("\n=== stages passed ===\n", .{});
    return 0;
}
