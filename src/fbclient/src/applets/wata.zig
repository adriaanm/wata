/// Wata voice messaging applet — contact list and conversation views.
const std = @import("std");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");
const types = @import("../types.zig");

const View = enum { contacts, conversation };

const State = struct {
    view: View = .contacts,
    selected: usize = 0,
    scroll_offset: usize = 0,
    snapshot: ?*const types.StateSnapshot = null,
    connection: types.ConnectionState = .disconnected,
    // For conversation view
    conv_contact_idx: usize = 0,
    msg_selected: usize = 0,
    msg_scroll: usize = 0,
};

const visible_rows: u32 = font.rows - 2; // header + footer

fn initApplet() *anyopaque {
    const S = struct {
        var state = State{};
    };
    return @ptrCast(&S.state);
}

fn deinitApplet(_: *anyopaque) void {}

fn handleInput(ptr: *anyopaque, key: input.Key, key_state: input.KeyState) shell.Action {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (key_state != .pressed) return .none;

    switch (s.view) {
        .contacts => handleContactsInput(s, key),
        .conversation => handleConversationInput(s, key),
    }
    return .none;
}

fn handleContactsInput(s: *State, key: input.Key) void {
    const count = if (s.snapshot) |snap| snap.contacts.len else 0;
    if (count == 0) return;

    switch (key) {
        .down => {
            if (s.selected < count - 1) s.selected += 1;
            // Scroll if needed
            if (s.selected >= s.scroll_offset + visible_rows) {
                s.scroll_offset = s.selected - visible_rows + 1;
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
        },
        else => {},
    }
}

fn handleConversationInput(s: *State, key: input.Key) void {
    switch (key) {
        .back => {
            s.view = .contacts;
        },
        .down => {
            const count = msgCount(s);
            if (count > 0 and s.msg_selected < count - 1) {
                s.msg_selected += 1;
                if (s.msg_selected >= s.msg_scroll + visible_rows) {
                    s.msg_scroll = s.msg_selected - visible_rows + 1;
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
        else => {},
    }
}

fn msgCount(s: *const State) usize {
    const snap = s.snapshot orelse return 0;
    if (s.conv_contact_idx >= snap.conversations.len) return 0;
    return snap.conversations[s.conv_contact_idx].messages.len;
}

fn update(ptr: *anyopaque, _: f32) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    // Pick up latest snapshot from shell context (set by main loop)
    // This is a read of a pointer that main.zig updates each frame
    _ = s;
}

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));

    switch (s.view) {
        .contacts => renderContacts(s, fb),
        .conversation => renderConversation(s, fb),
    }
}

// ---------------------------------------------------------------------------
// Contacts view
// ---------------------------------------------------------------------------

fn renderContacts(s: *const State, fb: *display.Framebuffer) void {
    const c = display.colors;
    const snap = s.snapshot orelse {
        renderConnecting(s.connection, fb);
        return;
    };

    // Header
    font.drawText(fb, "WATA", 0, 0, c.cyan, c.black);
    // Connection indicator on the right
    const conn_str = switch (s.connection) {
        .syncing => "ok",
        .connected => "..",
        .connecting => "..",
        .err => "ERR",
        .disconnected => "off",
    };
    const conn_col = font.cols - @as(u32, @intCast(conn_str.len));
    const conn_color: display.Color = switch (s.connection) {
        .syncing => c.green,
        .err => c.red,
        else => c.mid_gray,
    };
    font.drawText(fb, conn_str, conn_col, 0, conn_color, c.black);

    if (snap.contacts.len == 0) {
        font.drawTextCentered(fb, "No contacts", 5, c.mid_gray, null);
        font.drawTextCentered(fb, "Waiting for sync", 7, c.mid_gray, null);
        return;
    }

    // Contact list
    const end = @min(snap.contacts.len, s.scroll_offset + visible_rows);
    for (s.scroll_offset..end) |i| {
        const row: u32 = @intCast(i - s.scroll_offset + 1); // +1 for header
        const contact = snap.contacts[i];
        const selected = i == s.selected;

        const fg: display.Color = if (selected) c.black else c.green;
        const bg: display.Color = if (selected) c.green else c.black;

        // Clear row background if selected
        if (selected) {
            fb.fillRect(0, @intCast(1 + row * font.glyph_h), display.width, font.glyph_h, bg);
        }

        // Name (truncate to fit)
        const max_name_len = font.cols - 3; // leave room for badge
        const name = contact.user.display_name;
        const display_len = @min(name.len, max_name_len);
        font.drawText(fb, name[0..display_len], 0, row, fg, bg);

        // Unplayed count badge
        if (i < snap.conversations.len) {
            const unplayed = snap.conversations[i].unplayed_count;
            if (unplayed > 0) {
                var badge_buf: [4]u8 = undefined;
                const badge = std.fmt.bufPrint(&badge_buf, "{d}", .{unplayed}) catch "?";
                const badge_col = font.cols - @as(u32, @intCast(badge.len));
                font.drawText(fb, badge, badge_col, row, c.yellow, if (selected) bg else null);
            }
        }
    }

    // Footer hint
    font.drawText(fb, "\x18\x19 select  OK open", 0, font.rows - 1, c.mid_gray, c.black);
}

fn renderConnecting(connection: types.ConnectionState, fb: *display.Framebuffer) void {
    const c = display.colors;
    const msg: []const u8 = switch (connection) {
        .disconnected => "Disconnected",
        .connecting => "Connecting...",
        .connected => "Logging in...",
        .syncing => "Syncing...",
        .err => "Connection error",
    };
    font.drawTextCentered(fb, msg, 5, c.mid_gray, null);
}

// ---------------------------------------------------------------------------
// Conversation view
// ---------------------------------------------------------------------------

fn renderConversation(s: *const State, fb: *display.Framebuffer) void {
    const c = display.colors;
    const snap = s.snapshot orelse return;

    if (s.conv_contact_idx >= snap.conversations.len) {
        font.drawTextCentered(fb, "No conversation", 5, c.mid_gray, null);
        return;
    }

    const conv = snap.conversations[s.conv_contact_idx];

    // Header — contact name
    const header_name = if (conv.contact) |contact| contact.user.display_name else "Chat";
    const header_len = @min(header_name.len, font.cols);
    font.drawText(fb, header_name[0..header_len], 0, 0, c.cyan, c.black);

    if (conv.messages.len == 0) {
        font.drawTextCentered(fb, "No messages", 5, c.mid_gray, null);
        font.drawText(fb, "ESC back", 0, font.rows - 1, c.mid_gray, c.black);
        return;
    }

    // Message list (newest at bottom, scroll from bottom)
    const end = @min(conv.messages.len, s.msg_scroll + visible_rows);
    for (s.msg_scroll..end) |i| {
        const row: u32 = @intCast(i - s.msg_scroll + 1);
        const msg = conv.messages[i];
        const selected = i == s.msg_selected;

        const fg: display.Color = if (selected) c.black else (if (msg.is_played) c.mid_gray else c.green);
        const bg: display.Color = if (selected) c.green else c.black;

        if (selected) {
            fb.fillRect(0, @intCast(1 + row * font.glyph_h), display.width, font.glyph_h, bg);
        }

        // Check mark for played
        if (msg.is_played) {
            font.drawChar(fb, font.icon.check, 0, @intCast(1 + row * font.glyph_h), fg, bg);
        }

        // Duration
        const dur_secs: u32 = @intFromFloat(msg.duration);
        var dur_buf: [6]u8 = undefined;
        const dur_str = std.fmt.bufPrint(&dur_buf, "{d}:{d:0>2}", .{ dur_secs / 60, dur_secs % 60 }) catch "?:??";
        font.drawText(fb, dur_str, 1, row, fg, bg);

        // Sender (short)
        const sender = msg.sender.display_name;
        const sender_col: u32 = 1 + @as(u32, @intCast(dur_str.len)) + 1;
        const avail = if (sender_col < font.cols) font.cols - sender_col else 0;
        const sender_len = @min(sender.len, avail);
        if (sender_len > 0) {
            font.drawText(fb, sender[0..sender_len], sender_col, row, fg, bg);
        }
    }

    // Footer
    font.drawText(fb, "ESC back", 0, font.rows - 1, c.mid_gray, c.black);
}

pub const applet = shell.Applet{
    .name = "wata",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};

/// Update the wata applet's snapshot pointer. Called by main loop.
pub fn setSnapshot(applet_state: *anyopaque, snapshot: ?*const types.StateSnapshot, connection: types.ConnectionState) void {
    const s: *State = @ptrCast(@alignCast(applet_state));
    s.snapshot = snapshot;
    s.connection = connection;
}
