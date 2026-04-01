/// Audio thread: background capture (PTT recording) and playback.
/// Communicates with the UI thread via command/event queues.
const std = @import("std");
const build_options = @import("build_options");
const alsa = @import("alsa.zig");
const opus_mod = @import("opus.zig");
const ogg = @import("ogg.zig");
const queue_mod = @import("queue.zig");
const mailbox_mod = @import("mailbox.zig");
const types = @import("types.zig");

// ---------------------------------------------------------------------------
// Commands (UI → audio thread)
// ---------------------------------------------------------------------------

pub const Command = union(enum) {
    start_recording,
    stop_recording,
    /// Play an Ogg/Opus buffer. Caller transfers ownership of the data.
    play: struct {
        ogg_data: []const u8,
        allocator: std.mem.Allocator,
    },
    stop_playback,
    /// Record 2s + playback through speaker (echo test from settings).
    echo_test,
    quit,
};

// ---------------------------------------------------------------------------
// Events (audio thread → UI)
// ---------------------------------------------------------------------------

pub const Event = union(enum) {
    /// Recording finished — Ogg/Opus data ready for upload.
    recording_done: struct {
        ogg_data: []const u8,
        allocator: std.mem.Allocator,
        duration_ms: u64,
    },
    recording_error,
    playback_done,
    playback_error,
    // Echo test lifecycle
    echo_recording,
    echo_playing,
    echo_done,
    echo_error,
};

pub const CommandQueue = mailbox_mod.Mailbox(Command, 16);
pub const EventQueue = mailbox_mod.Mailbox(Event, 16);

// ---------------------------------------------------------------------------
// Audio thread context
// ---------------------------------------------------------------------------

pub const Context = struct {
    cmd_queue: *CommandQueue,
    event_queue: *EventQueue,
    allocator: std.mem.Allocator,
};

pub fn audioThreadMain(ctx: *Context) void {
    if (!build_options.use_audio) return;

    // Set up playback mixer by default (speaker on)
    alsa.setupPlaybackMixer();

    // Block on mailbox receive — no polling, no sleep
    while (ctx.cmd_queue.receive()) |cmd| {
        switch (cmd) {
            .start_recording => doRecord(ctx),
            .stop_recording => {}, // handled inside doRecord
            .play => |p| doPlayback(ctx, p.ogg_data, p.allocator),
            .stop_playback => {}, // handled inside doPlayback
            .echo_test => doEchoTest(ctx),
            .quit => return,
        }
    }
    // receive() returned null → mailbox closed → clean exit
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

fn doRecord(ctx: *Context) void {
    // Mixer is set up once at startup — no per-recording switching
    // to avoid ADSP churn that caused hard crashes.
    var capture = alsa.Capture.open() catch {
        _ = ctx.event_queue.send(.recording_error);
        return;
    };
    defer capture.close();

    var encoder = opus_mod.Encoder.init() catch {
        _ = ctx.event_queue.send(.recording_error);
        return;
    };
    defer encoder.deinit();

    var writer = ogg.Writer.init(ctx.allocator);
    defer {} // don't deinit — ownership transfers on success

    writer.writeOpusHead() catch {
        writer.deinit();
        _ = ctx.event_queue.send(.recording_error);
        return;
    };
    writer.writeOpusTags() catch {
        writer.deinit();
        _ = ctx.event_queue.send(.recording_error);
        return;
    };

    var pcm_buf: [alsa.PERIOD_BYTES]u8 = undefined;
    var opus_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    var total_samples: u64 = 0;

    // Record until stop_recording command arrives
    while (!ctx.cmd_queue.isClosed()) {
        // Check for stop command (non-blocking)
        if (ctx.cmd_queue.tryReceive()) |cmd| {
            switch (cmd) {
                .stop_recording => break,
                .quit => {
                    writer.deinit();
                    return;
                },
                else => {}, // ignore other commands during recording
            }
        }

        // Read one period from ALSA
        _ = capture.readFrames(&pcm_buf) catch {
            writer.deinit();
            _ = ctx.event_queue.send(.recording_error);
            return;
        };

        // Encode with Opus
        const encoded_bytes = encoder.encode(&pcm_buf, &opus_buf) catch {
            writer.deinit();
            _ = ctx.event_queue.send(.recording_error);
            return;
        };

        // Write to Ogg stream
        writer.writeAudioFrame(opus_buf[0..encoded_bytes], opus_mod.FRAME_SAMPLES) catch {
            writer.deinit();
            _ = ctx.event_queue.send(.recording_error);
            return;
        };

        total_samples += opus_mod.FRAME_SAMPLES;
    }

    // Finalize Ogg stream
    writer.finish() catch {
        writer.deinit();
        _ = ctx.event_queue.send(.recording_error);
        return;
    };

    const duration_ms = (total_samples * 1000) / alsa.SAMPLE_RATE;

    // Transfer ownership of the Ogg data to the UI thread
    _ = ctx.event_queue.send(.{ .recording_done = .{
        .ogg_data = writer.data(),
        .allocator = ctx.allocator,
        .duration_ms = duration_ms,
    } });
    // Don't deinit writer — data ownership transferred
}

// ---------------------------------------------------------------------------
// Playback
// ---------------------------------------------------------------------------

fn doPlayback(ctx: *Context, ogg_data: []const u8, data_allocator: std.mem.Allocator) void {
    defer data_allocator.free(@constCast(ogg_data));

    var playback = alsa.Playback.open() catch {
        _ = ctx.event_queue.send(.playback_error);
        return;
    };
    defer playback.close();

    var decoder = opus_mod.Decoder.init() catch {
        _ = ctx.event_queue.send(.playback_error);
        return;
    };
    defer decoder.deinit();

    var reader = ogg.Reader.init(ogg_data);
    var frame_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    const opus_frame_bytes = opus_mod.FRAME_SAMPLES * alsa.FRAME_SIZE * alsa.CHANNELS;

    // Decode in ~500ms chunks and write each as a single large pcm_writei call.
    // The kernel handles period-by-period DMA blocking internally, which is much
    // smoother than a userspace per-period write loop (avoids scheduler-induced
    // underruns on the BQ268's small 80ms ALSA buffer). See docs/voice.md.
    const CHUNK_PERIODS = 12; // 12 periods × 40ms = 480ms
    const CHUNK_BYTES = CHUNK_PERIODS * alsa.PERIOD_BYTES;
    var chunk_buf: [CHUNK_BYTES]u8 = undefined;
    var chunk_off: u32 = 0;
    var stream_done = false;

    while (!stream_done and !ctx.cmd_queue.isClosed()) {
        // Fill a chunk by decoding Opus frames
        chunk_off = 0;
        while (chunk_off + opus_frame_bytes <= CHUNK_BYTES) {
            // Check for stop command between frames (non-blocking)
            if (ctx.cmd_queue.tryReceive()) |cmd| {
                switch (cmd) {
                    .stop_playback => {
                        stream_done = true;
                        break;
                    },
                    .quit => return,
                    else => {},
                }
            }

            const frame = reader.nextFrame(&frame_buf) orelse {
                stream_done = true;
                break;
            };

            _ = decoder.decode(frame, chunk_buf[chunk_off..][0..opus_frame_bytes]) catch {
                stream_done = true;
                break;
            };
            chunk_off += opus_frame_bytes;
        }

        // Write the accumulated chunk in one call (must be period-aligned for MSM ADSP).
        const write_bytes = chunk_off - (chunk_off % alsa.PERIOD_BYTES);
        if (write_bytes > 0) {
            playback.writeFrames(chunk_buf[0..write_bytes]) catch break;
        }
    }

    // Wait for buffered audio to finish playing.
    playback.drain();
    _ = ctx.event_queue.send(.playback_done);
}

// ---------------------------------------------------------------------------
// Echo test — record 2s, encode to Opus/Ogg, decode and play back.
// Runs on the audio thread (not a fire-and-forget thread) to avoid races.
// ---------------------------------------------------------------------------

fn doEchoTest(ctx: *Context) void {
    const OPUS_FRAME_BYTES: u32 = opus_mod.FRAME_SAMPLES * alsa.FRAME_SIZE * alsa.CHANNELS;

    _ = ctx.event_queue.send(.echo_recording);

    // --- RECORD + ENCODE ---
    alsa.setupCaptureMixer();

    var capture = alsa.Capture.open() catch {
        _ = ctx.event_queue.send(.echo_error);
        return;
    };

    var encoder = opus_mod.Encoder.init() catch {
        capture.close();
        _ = ctx.event_queue.send(.echo_error);
        return;
    };

    var writer = ogg.Writer.init(ctx.allocator);
    writer.writeOpusHead() catch {
        encoder.deinit();
        capture.close();
        writer.deinit();
        _ = ctx.event_queue.send(.echo_error);
        return;
    };
    writer.writeOpusTags() catch {
        encoder.deinit();
        capture.close();
        writer.deinit();
        _ = ctx.event_queue.send(.echo_error);
        return;
    };

    var pcm_buf: [alsa.PERIOD_BYTES]u8 = undefined;
    var opus_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    var total_samples: u32 = 0;
    const echo_frames: u32 = alsa.SAMPLE_RATE * 2; // 2 seconds

    while (total_samples < echo_frames) {
        _ = capture.readFrames(&pcm_buf) catch break;

        var off: u32 = 0;
        while (off + OPUS_FRAME_BYTES <= alsa.PERIOD_BYTES) {
            const encoded = encoder.encode(pcm_buf[off..][0..OPUS_FRAME_BYTES], &opus_buf) catch break;
            writer.writeAudioFrame(opus_buf[0..encoded], opus_mod.FRAME_SAMPLES) catch break;
            total_samples += opus_mod.FRAME_SAMPLES;
            off += OPUS_FRAME_BYTES;
        }
    }

    capture.close();
    encoder.deinit();
    writer.finish() catch {};

    const ogg_data = writer.data();

    _ = ctx.event_queue.send(.echo_playing);

    // --- DECODE + PLAY BACK ---
    alsa.setupPlaybackMixer();

    var decoder = opus_mod.Decoder.init() catch {
        writer.deinit();
        _ = ctx.event_queue.send(.echo_error);
        return;
    };

    var playback = alsa.Playback.open() catch {
        decoder.deinit();
        writer.deinit();
        _ = ctx.event_queue.send(.echo_error);
        return;
    };

    const ECHO_BUF_BYTES: u32 = echo_frames * alsa.FRAME_SIZE;
    var pcm_out: [ECHO_BUF_BYTES]u8 = undefined;
    var decode_off: u32 = 0;
    var reader = ogg.Reader.init(ogg_data);
    var frame_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;

    while (reader.nextFrame(&frame_buf)) |frame| {
        if (decode_off + OPUS_FRAME_BYTES > ECHO_BUF_BYTES) break;
        _ = decoder.decode(frame, pcm_out[decode_off..][0..OPUS_FRAME_BYTES]) catch break;
        decode_off += OPUS_FRAME_BYTES;
    }

    const playback_bytes = decode_off - (decode_off % alsa.PERIOD_BYTES);
    if (playback_bytes > 0) {
        playback.writeFrames(pcm_out[0..playback_bytes]) catch {};
    }

    playback.drain();
    playback.close();
    decoder.deinit();
    writer.deinit();

    _ = ctx.event_queue.send(.echo_done);
}
