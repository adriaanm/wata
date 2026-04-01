/// Wata voice messaging applet — contact list and conversation views.
/// Uses FreeType-rendered font for legibility on the small display.
const std = @import("std");
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig"); // bitmap font — used only for status icons
const ft_font = if (build_options.use_freetype) @import("../ft_font.zig") else FtFontStub;
const input = @import("../input.zig");
const shell = @import("../shell.zig");
const types = @import("../types.zig");
const queue = @import("../queue.zig");
const audio_thread = if (build_options.use_audio) @import("../audio_thread.zig") else struct {
    pub const CommandQueue = void;
    pub const EventQueue = void;
};

/// Stub for when FreeType is not available (cross-compile without freetype2).
/// Font.init always fails → wata applet falls back to bitmap font rendering.
const FtFontStub = struct {
    pub const Font = struct {
        line_height: i32 = 0,
        pub fn init(_: std.mem.Allocator, _: []const u8, _: u32) error{NotAvailable}!Font {
            return error.NotAvailable;
        }
        pub fn deinit(_: *Font) void {}
        pub fn drawText(_: *Font, _: *display.Framebuffer, _: []const u8, _: i32, _: i32, _: display.Color, _: ?display.Color) void {}
        pub fn drawTextRight(_: *Font, _: *display.Framebuffer, _: []const u8, _: i32, _: i32, _: display.Color) void {}
        pub fn drawTextCentered(_: *Font, _: *display.Framebuffer, _: []const u8, _: i32, _: display.Color) void {}
        pub fn measureText(_: *Font, _: []const u8) i32 { return 0; }
    };
};

const View = enum { contacts, conversation };

const FONT_SIZE = 12; // pixels — good legibility on 128×160
const HEADER_H = 14; // header area height
const FOOTER_H = 12; // footer area height
const LINE_H = 14; // per-row height for list items

/// How many visible rows fit between header and footer.
fn visibleRows() u32 {
    return (display.height - HEADER_H - FOOTER_H) / LINE_H;
}

const State = struct {
    view: View = .contacts,
    selected: usize = 0,
    scroll_offset: usize = 0,
    snapshot: ?*const types.StateSnapshot = null,
    connection: types.ConnectionState = .disconnected,
    action_queue: ?*queue.BoundedQueue(types.Action, 64) = null,
    audio_cmd: ?*audio_thread.CommandQueue = null,
    audio_evt: ?*audio_thread.EventQueue = null,
    // For conversation view
    conv_contact_idx: usize = 0,
    msg_selected: usize = 0,
    msg_scroll: usize = 0,
    // PTT recording state
    ptt_held: bool = false,
    ptt_hold_time: f32 = 0, // seconds held
    // Playback state
    playing: bool = false,
    // Font (initialized lazily on first render)
    ft: ?ft_font.Font = null,
};

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{};
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(ptr: *anyopaque) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (s.ft) |*f| f.deinit();
}

fn getFont(s: *State) ?*ft_font.Font {
    if (!build_options.use_freetype) return null;
    if (s.ft != null) return &s.ft.?;
    // Lazy init — needs allocator but we don't have one in the applet interface.
    // Use a page allocator as fallback (font init is a one-time cost).
    const ttf_data = @embedFile("../fonts/Inter.ttf");
    s.ft = ft_font.Font.init(std.heap.page_allocator, ttf_data, FONT_SIZE) catch return null;
    return &s.ft.?;
}

fn handleInput(ptr: *anyopaque, key: input.Key, key_state: input.KeyState) shell.Action {
    const s: *State = @ptrCast(@alignCast(ptr));

    // PTT: track press/release (always, regardless of view)
    if (key == .ptt) {
        if (key_state == .pressed and !s.ptt_held) {
            s.ptt_held = true;
            s.ptt_hold_time = 0;
            if (build_options.use_audio) {
                if (s.audio_cmd) |cmd_q| _ = cmd_q.push(.start_recording);
            }
        } else if (key_state == .released and s.ptt_held) {
            s.ptt_held = false;
            if (build_options.use_audio) {
                if (s.audio_cmd) |cmd_q| _ = cmd_q.push(.stop_recording);
            }
        }
        return .none;
    }

    if (key_state != .pressed) return .none;

    switch (s.view) {
        .contacts => handleContactsInput(s, key),
        .conversation => handleConversationInput(s, key),
    }
    return .none;
}

fn handleContactsInput(s: *State, key: input.Key) void {
    const count = if (s.snapshot) |snap| snap.conversations.len else 0;
    if (count == 0) return;
    const vis = visibleRows();

    switch (key) {
        .down => {
            if (s.selected < count - 1) s.selected += 1;
            if (s.selected >= s.scroll_offset + vis) {
                s.scroll_offset = s.selected - vis + 1;
            }
        },
        .up => {
            if (s.selected > 0) s.selected -= 1;
            if (s.selected < s.scroll_offset) {
                s.scroll_offset = s.selected;
            }
        },
        .enter => {
            s.conv_contact_idx = s.selected;
            s.msg_selected = 0;
            s.msg_scroll = 0;
            s.view = .conversation;
            // Send read receipt for the latest message in this conversation
            sendReadReceiptForConversation(s);
        },
        else => {},
    }
}

fn handleConversationInput(s: *State, key: input.Key) void {
    const vis = visibleRows();
    switch (key) {
        .back => {
            s.view = .contacts;
        },
        .down => {
            const count = msgCount(s);
            if (count > 0 and s.msg_selected < count - 1) {
                s.msg_selected += 1;
                if (s.msg_selected >= s.msg_scroll + vis) {
                    s.msg_scroll = s.msg_selected - vis + 1;
                }
            }
        },
        .up => {
            if (s.msg_selected > 0) {
                s.msg_selected -= 1;
                if (s.msg_selected < s.msg_scroll) {
                    s.msg_scroll = s.msg_selected;
                }
            }
        },
        .enter => {
            const snap = s.snapshot orelse return;
            if (s.conv_contact_idx >= snap.conversations.len) return;
            const conv = snap.conversations[s.conv_contact_idx];
            if (s.msg_selected >= conv.messages.len) return;
            const msg = conv.messages[s.msg_selected];

            // Send read receipt
            if (!msg.is_played) {
                pushReadReceipt(s, conv.room_id, msg.id);
            }

            // Download and play audio
            if (build_options.use_audio) {
                requestPlayback(s, msg.mxc_url);
            }
        },
        .f2 => {
            // Delete the selected message (own messages only)
            deleteSelectedMessage(s);
        },
        else => {},
    }
}

/// Send a read receipt for the latest unplayed message in the current conversation.
fn sendReadReceiptForConversation(s: *State) void {
    const snap = s.snapshot orelse return;
    if (s.conv_contact_idx >= snap.conversations.len) return;
    const conv = snap.conversations[s.conv_contact_idx];
    if (conv.messages.len == 0) return;

    // Find the latest message (they're in chronological order)
    const last_msg = conv.messages[conv.messages.len - 1];
    pushReadReceipt(s, conv.room_id, last_msg.id);
}

/// Push a read receipt action to the sync thread's action queue.
fn pushReadReceipt(s: *State, room_id: []const u8, event_id: []const u8) void {
    const aq = s.action_queue orelse return;
    if (room_id.len > 128 or event_id.len > 128) return;

    var action = types.Action{ .send_read_receipt = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(room_id.len),
        .event_id_buf = undefined,
        .event_id_len = @intCast(event_id.len),
    } };
    @memcpy(action.send_read_receipt.room_id_buf[0..room_id.len], room_id);
    @memcpy(action.send_read_receipt.event_id_buf[0..event_id.len], event_id);
    _ = aq.push(action);
}

/// Request playback of a voice message by mxc:// URL.
/// Pushes a download_and_play action to the sync thread, which downloads the
/// media and hands the Ogg data to the audio thread.
fn requestPlayback(s: *State, mxc_url: []const u8) void {
    const aq = s.action_queue orelse return;
    if (mxc_url.len > 256) return;

    var action = types.Action{ .download_and_play = .{
        .mxc_url_buf = undefined,
        .mxc_url_len = @intCast(mxc_url.len),
    } };
    @memcpy(action.download_and_play.mxc_url_buf[0..mxc_url.len], mxc_url);
    _ = aq.push(action);
    s.playing = true;
}

/// Delete the currently selected message (redact via Matrix API).
fn deleteSelectedMessage(s: *State) void {
    const aq = s.action_queue orelse return;
    const snap = s.snapshot orelse return;
    if (s.conv_contact_idx >= snap.conversations.len) return;
    const conv = snap.conversations[s.conv_contact_idx];
    if (s.msg_selected >= conv.messages.len) return;
    const msg = conv.messages[s.msg_selected];

    if (conv.room_id.len > 128 or msg.id.len > 128) return;

    var action = types.Action{ .delete_message = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(conv.room_id.len),
        .event_id_buf = undefined,
        .event_id_len = @intCast(msg.id.len),
    } };
    @memcpy(action.delete_message.room_id_buf[0..conv.room_id.len], conv.room_id);
    @memcpy(action.delete_message.event_id_buf[0..msg.id.len], msg.id);
    _ = aq.push(action);
}

fn msgCount(s: *const State) usize {
    const snap = s.snapshot orelse return 0;
    if (s.conv_contact_idx >= snap.conversations.len) return 0;
    return snap.conversations[s.conv_contact_idx].messages.len;
}

fn update(ptr: *anyopaque, dt: f32) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (s.ptt_held) {
        s.ptt_hold_time += dt;
    }

    // Drain audio events
    if (build_options.use_audio) {
        if (s.audio_evt) |evt_q| {
            while (evt_q.pop()) |evt| {
                switch (evt) {
                    .recording_done => |rec| {
                        // Upload and send the recorded voice message
                        uploadRecording(s, rec.ogg_data, rec.duration_ms, rec.allocator);
                    },
                    .recording_error => {},
                    .playback_done => {
                        s.playing = false;
                    },
                    .playback_error => {
                        s.playing = false;
                    },
                }
            }
        }
    }
}

/// Upload recorded audio and send as a voice message to the current conversation.
/// Upload recorded audio and send as a voice message to the current conversation.
fn uploadRecording(s: *State, ogg_data: []const u8, duration_ms: u64, data_allocator: std.mem.Allocator) void {
    _ = data_allocator;
    const aq = s.action_queue orelse return;
    const snap = s.snapshot orelse return;

    // Send to the conversation we're currently viewing (or the selected contact)
    const conv_idx = if (s.view == .conversation) s.conv_contact_idx else s.selected;
    if (conv_idx >= snap.conversations.len) return;
    const room_id = snap.conversations[conv_idx].room_id;
    if (room_id.len > 128) return;

    var action = types.Action{ .upload_and_send_voice = .{
        .room_id_buf = undefined,
        .room_id_len = @intCast(room_id.len),
        .ogg_data = ogg_data.ptr,
        .ogg_len = @intCast(ogg_data.len),
        .duration_ms = duration_ms,
    } };
    @memcpy(action.upload_and_send_voice.room_id_buf[0..room_id.len], room_id);
    _ = aq.push(action);
}

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));

    switch (s.view) {
        .contacts => renderContacts(s, fb),
        .conversation => renderConversation(s, fb),
    }

    // PTT recording overlay (shown on top of any view)
    if (s.ptt_held) {
        renderRecordingOverlay(s, fb);
    }
}

// ---------------------------------------------------------------------------
// Contacts view
// ---------------------------------------------------------------------------

fn renderContacts(s: *State, fb: *display.Framebuffer) void {
    const c = display.colors;
    const f = getFont(s) orelse {
        // Fallback to bitmap font if FreeType failed
        font.drawText(fb, "Font error", 0, 2, c.red, null);
        return;
    };
    const snap = s.snapshot orelse {
        renderConnecting(s, fb, f);
        return;
    };

    // Header
    f.drawText(fb, "WATA", 2, 0, c.cyan, null);
    // Connection indicator
    const conn_str = switch (s.connection) {
        .syncing => "ok",
        .connected, .connecting => "..",
        .err => "ERR",
        .disconnected => "off",
    };
    const conn_color: display.Color = switch (s.connection) {
        .syncing => c.green,
        .err => c.red,
        else => c.mid_gray,
    };
    f.drawTextRight(fb, conn_str, display.width - 2, 0, conn_color);

    // Build combined list: family group (if exists) + DM contacts
    // The conversations list has family at index 0 (if present), then DMs.
    const total = snap.conversations.len;
    if (total == 0) {
        f.drawTextCentered(fb, "No contacts", 40, c.mid_gray);
        f.drawTextCentered(fb, "Waiting for sync", 56, c.mid_gray);
        return;
    }

    const vis = visibleRows();
    const end = @min(total, s.scroll_offset + vis);
    for (s.scroll_offset..end) |i| {
        const row_y: i32 = @intCast(HEADER_H + (i - s.scroll_offset) * LINE_H);
        const conv = snap.conversations[i];
        const selected = i == s.selected;

        const fg: display.Color = if (selected) c.black else c.green;
        const bg: display.Color = if (selected) c.green else c.black;

        if (selected) {
            fb.fillRect(0, row_y, display.width, LINE_H, bg);
        }

        // Name: family group or contact DM
        const name = if (conv.conv_type == .family)
            (if (snap.family) |fam| fam.name else "Family")
        else if (conv.contact) |ct|
            ct.user.display_name
        else
            "?";
        const max_chars = 18;
        const display_len = @min(name.len, max_chars);

        // Family gets a different color accent
        const name_color: display.Color = if (conv.conv_type == .family and !selected) c.cyan else fg;
        f.drawText(fb, name[0..display_len], 2, row_y, name_color, null);

        // Unplayed count badge
        if (conv.unplayed_count > 0) {
            var badge_buf: [4]u8 = undefined;
            const badge = std.fmt.bufPrint(&badge_buf, "{d}", .{conv.unplayed_count}) catch "?";
            f.drawTextRight(fb, badge, display.width - 2, row_y, c.yellow);
        }
    }

    // Footer
    f.drawText(fb, "\x18\x19 select  OK open", 2, @intCast(display.height - FOOTER_H), c.mid_gray, null);
}

fn renderConnecting(s: *const State, fb: *display.Framebuffer, f: *ft_font.Font) void {
    const c = display.colors;
    const msg: []const u8 = switch (s.connection) {
        .disconnected => "Disconnected",
        .connecting => "Connecting...",
        .connected => "Logging in...",
        .syncing => "Syncing...",
        .err => "Connection error",
    };
    f.drawTextCentered(fb, msg, 60, c.mid_gray);
}

// ---------------------------------------------------------------------------
// Conversation view
// ---------------------------------------------------------------------------

fn renderConversation(s: *State, fb: *display.Framebuffer) void {
    const c = display.colors;
    const f = getFont(s) orelse return;
    const snap = s.snapshot orelse return;

    if (s.conv_contact_idx >= snap.conversations.len) {
        f.drawTextCentered(fb, "No conversation", 60, c.mid_gray);
        return;
    }

    const conv = snap.conversations[s.conv_contact_idx];

    // Header — contact name
    const header_name = if (conv.contact) |contact| contact.user.display_name else "Chat";
    const header_len = @min(header_name.len, 20);
    f.drawText(fb, header_name[0..header_len], 2, 0, c.cyan, null);

    if (conv.messages.len == 0) {
        f.drawTextCentered(fb, "No messages", 60, c.mid_gray);
        f.drawText(fb, "ESC back", 2, @intCast(display.height - FOOTER_H), c.mid_gray, null);
        return;
    }

    // Message list
    const vis = visibleRows();
    const end = @min(conv.messages.len, s.msg_scroll + vis);
    for (s.msg_scroll..end) |i| {
        const row_y: i32 = @intCast(HEADER_H + (i - s.msg_scroll) * LINE_H);
        const msg = conv.messages[i];
        const selected = i == s.msg_selected;

        const fg: display.Color = if (selected) c.black else (if (msg.is_played) c.mid_gray else c.green);
        const bg: display.Color = if (selected) c.green else c.black;

        if (selected) {
            fb.fillRect(0, row_y, display.width, LINE_H, bg);
        }

        // Play indicator for played messages
        if (msg.is_played) {
            font.drawChar(fb, font.icon.check, 0, row_y + 2, fg, null);
        }

        // Duration
        const dur_secs: u32 = @intFromFloat(msg.duration);
        var dur_buf: [6]u8 = undefined;
        const dur_str = std.fmt.bufPrint(&dur_buf, "{d}:{d:0>2}", .{ dur_secs / 60, dur_secs % 60 }) catch "?:??";
        const x_offset: i32 = if (msg.is_played) 8 else 2;
        f.drawText(fb, dur_str, x_offset, row_y, fg, null);

        // Sender (short)
        const sender = msg.sender.display_name;
        const dur_w = f.measureText(dur_str);
        const sender_x = x_offset + dur_w + 4;
        const avail = @as(i32, display.width) - sender_x - 2;
        if (avail > 0) {
            const max_sender = @min(sender.len, @as(usize, @intCast(@divTrunc(avail, 7))));
            if (max_sender > 0) {
                f.drawText(fb, sender[0..max_sender], sender_x, row_y, fg, null);
            }
        }
    }

    // Footer
    f.drawText(fb, "OK play F2 del", 2, @intCast(display.height - FOOTER_H), c.mid_gray, null);
}

// ---------------------------------------------------------------------------
// Recording overlay
// ---------------------------------------------------------------------------

fn renderRecordingOverlay(s: *const State, fb: *display.Framebuffer) void {
    const c = display.colors;
    const bar_h: u32 = 24;
    const bar_y: i32 = @intCast(display.height - bar_h);

    // Red background bar
    fb.fillRect(0, bar_y, display.width, bar_h, c.red);

    // Duration text
    const secs: u32 = @intFromFloat(s.ptt_hold_time);
    const tenths: u32 = @intFromFloat(@mod(s.ptt_hold_time * 10, 10));
    var dur_buf: [8]u8 = undefined;
    const dur_str = std.fmt.bufPrint(&dur_buf, "{d}.{d}s", .{ secs, tenths }) catch "?";

    if (getFont(@constCast(s))) |f| {
        f.drawTextCentered(fb, dur_str, bar_y + 6, c.white);
    } else {
        // Bitmap font: convert pixel Y to grid row
        const grid_row: u32 = @intCast(@divTrunc(@as(u32, @intCast(bar_y + 8)), font.glyph_h));
        const grid_col: u32 = (font.cols / 2) -| @as(u32, @intCast(dur_str.len / 2));
        font.drawText(fb, dur_str, grid_col, grid_row, c.white, null);
    }
}

// ---------------------------------------------------------------------------
// Applet registration
// ---------------------------------------------------------------------------

pub const applet = shell.Applet{
    .name = "wata",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};

/// Update the wata applet's context. Called by main loop each frame.
pub fn setContext(
    applet_state: *anyopaque,
    snapshot: ?*const types.StateSnapshot,
    connection: types.ConnectionState,
    action_q: *queue.BoundedQueue(types.Action, 64),
    audio_cmd_q: ?*audio_thread.CommandQueue,
    audio_evt_q: ?*audio_thread.EventQueue,
) void {
    const s: *State = @ptrCast(@alignCast(applet_state));
    s.snapshot = snapshot;
    s.connection = connection;
    s.action_queue = action_q;
    if (build_options.use_audio) {
        s.audio_cmd = audio_cmd_q;
        s.audio_evt = audio_evt_q;
    }
}
