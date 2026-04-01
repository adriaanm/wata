/// Audio thread: background capture (PTT recording) and playback.
/// Communicates with the UI thread via command/event queues.
const std = @import("std");
const build_options = @import("build_options");
const alsa = @import("alsa.zig");
const opus_mod = @import("opus.zig");
const ogg = @import("ogg.zig");
const queue_mod = @import("queue.zig");
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
};

pub const CommandQueue = queue_mod.BoundedQueue(Command, 16);
pub const EventQueue = queue_mod.BoundedQueue(Event, 16);

// ---------------------------------------------------------------------------
// Audio thread context
// ---------------------------------------------------------------------------

pub const Context = struct {
    cmd_queue: *CommandQueue,
    event_queue: *EventQueue,
    should_stop: *std.atomic.Value(bool),
    allocator: std.mem.Allocator,
};

pub fn audioThreadMain(ctx: *Context) void {
    if (!build_options.use_audio) return;

    // Set up playback mixer by default (speaker on)
    alsa.setupPlaybackMixer();

    while (!ctx.should_stop.load(.acquire)) {
        const cmd = ctx.cmd_queue.pop() orelse {
            // No command — sleep briefly
            var ts = std.os.linux.timespec{ .sec = 0, .nsec = 10_000_000 }; // 10ms
            _ = std.os.linux.nanosleep(&ts, null);
            continue;
        };

        switch (cmd) {
            .start_recording => doRecord(ctx),
            .stop_recording => {}, // handled inside doRecord
            .play => |p| doPlayback(ctx, p.ogg_data, p.allocator),
            .stop_playback => {}, // handled inside doPlayback
            .quit => break,
        }
    }
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

fn doRecord(ctx: *Context) void {
    alsa.setupCaptureMixer(); // speaker off during recording
    var capture = alsa.Capture.open() catch {
        _ = ctx.event_queue.push(.recording_error);
        return;
    };
    defer capture.close();

    var encoder = opus_mod.Encoder.init() catch {
        _ = ctx.event_queue.push(.recording_error);
        return;
    };
    defer encoder.deinit();

    var writer = ogg.Writer.init(ctx.allocator);
    defer {} // don't deinit — ownership transfers on success

    writer.writeOpusHead() catch {
        writer.deinit();
        _ = ctx.event_queue.push(.recording_error);
        return;
    };
    writer.writeOpusTags() catch {
        writer.deinit();
        _ = ctx.event_queue.push(.recording_error);
        return;
    };

    var pcm_buf: [alsa.PERIOD_BYTES]u8 = undefined;
    var opus_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    var total_samples: u64 = 0;

    // Record until stop_recording command arrives
    while (!ctx.should_stop.load(.acquire)) {
        // Check for stop command (non-blocking peek)
        if (ctx.cmd_queue.pop()) |cmd| {
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
            _ = ctx.event_queue.push(.recording_error);
            return;
        };

        // Encode with Opus
        const encoded_bytes = encoder.encode(&pcm_buf, &opus_buf) catch {
            writer.deinit();
            _ = ctx.event_queue.push(.recording_error);
            return;
        };

        // Write to Ogg stream
        writer.writeAudioFrame(opus_buf[0..encoded_bytes], opus_mod.FRAME_SAMPLES) catch {
            writer.deinit();
            _ = ctx.event_queue.push(.recording_error);
            return;
        };

        total_samples += opus_mod.FRAME_SAMPLES;
    }

    // Finalize Ogg stream
    writer.finish() catch {
        writer.deinit();
        _ = ctx.event_queue.push(.recording_error);
        return;
    };

    const duration_ms = (total_samples * 1000) / alsa.SAMPLE_RATE;

    // Re-enable speaker after recording
    alsa.setupPlaybackMixer();

    // Transfer ownership of the Ogg data to the UI thread
    _ = ctx.event_queue.push(.{ .recording_done = .{
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
        _ = ctx.event_queue.push(.playback_error);
        return;
    };
    defer playback.close();

    var decoder = opus_mod.Decoder.init() catch {
        _ = ctx.event_queue.push(.playback_error);
        return;
    };
    defer decoder.deinit();

    var reader = ogg.Reader.init(ogg_data);
    var frame_buf: [opus_mod.MAX_FRAME_BYTES]u8 = undefined;
    var pcm_buf: [alsa.PERIOD_BYTES]u8 = undefined;

    // Stream decoded frames — auto-starts after first period is written.
    while (!ctx.should_stop.load(.acquire)) {
        // Check for stop command
        if (ctx.cmd_queue.pop()) |cmd| {
            switch (cmd) {
                .stop_playback => break,
                .quit => return,
                else => {},
            }
        }

        // Get next Opus frame from Ogg container
        const frame = reader.nextFrame(&frame_buf) orelse break; // end of stream

        // Decode to PCM
        _ = decoder.decode(frame, &pcm_buf) catch break;

        // Write to ALSA
        playback.writeFrames(&pcm_buf) catch break;
    }

    // Wait for buffered audio to finish playing.
    playback.drain();
    _ = ctx.event_queue.push(.playback_done);
}
