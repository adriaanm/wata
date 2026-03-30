/// Wata voice messaging applet — contact list and conversation views.
/// Uses FreeType-rendered font for legibility on the small display.
const std = @import("std");
const display = @import("../display.zig");
const font = @import("../font.zig"); // bitmap font — used only for status icons
const ft_font = @import("../ft_font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");
const types = @import("../types.zig");

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
    // For conversation view
    conv_contact_idx: usize = 0,
    msg_selected: usize = 0,
    msg_scroll: usize = 0,
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
    if (s.ft != null) return &s.ft.?;
    // Lazy init — needs allocator but we don't have one in the applet interface.
    // Use a page allocator as fallback (font init is a one-time cost).
    const ttf_data = @embedFile("../fonts/Inter.ttf");
    s.ft = ft_font.Font.init(std.heap.page_allocator, ttf_data, FONT_SIZE) catch return null;
    return &s.ft.?;
}

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
        else => {},
    }
}

fn msgCount(s: *const State) usize {
    const snap = s.snapshot orelse return 0;
    if (s.conv_contact_idx >= snap.conversations.len) return 0;
    return snap.conversations[s.conv_contact_idx].messages.len;
}

fn update(ptr: *anyopaque, _: f32) void {
    _ = ptr;
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

    if (snap.contacts.len == 0) {
        f.drawTextCentered(fb, "No contacts", 40, c.mid_gray);
        f.drawTextCentered(fb, "Waiting for sync", 56, c.mid_gray);
        return;
    }

    // Contact list
    const vis = visibleRows();
    const end = @min(snap.contacts.len, s.scroll_offset + vis);
    for (s.scroll_offset..end) |i| {
        const row_y: i32 = @intCast(HEADER_H + (i - s.scroll_offset) * LINE_H);
        const contact = snap.contacts[i];
        const selected = i == s.selected;

        const fg: display.Color = if (selected) c.black else c.green;
        const bg: display.Color = if (selected) c.green else c.black;

        if (selected) {
            fb.fillRect(0, row_y, display.width, LINE_H, bg);
        }

        // Name (truncated to fit)
        const name = contact.user.display_name;
        const max_chars = 18;
        const display_len = @min(name.len, max_chars);
        f.drawText(fb, name[0..display_len], 2, row_y, fg, null);

        // Unplayed count badge
        if (i < snap.conversations.len) {
            const unplayed = snap.conversations[i].unplayed_count;
            if (unplayed > 0) {
                var badge_buf: [4]u8 = undefined;
                const badge = std.fmt.bufPrint(&badge_buf, "{d}", .{unplayed}) catch "?";
                f.drawTextRight(fb, badge, display.width - 2, row_y, c.yellow);
            }
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

        // Check mark for played (use bitmap font icon)
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
    f.drawText(fb, "ESC back", 2, @intCast(display.height - FOOTER_H), c.mid_gray, null);
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
