/// Shell: manages the status line, applet lifecycle, and input routing.
const std = @import("std");
const display = @import("display.zig");
const font = @import("font.zig");
const input = @import("input.zig");
const types = @import("types.zig");
const queue = @import("queue.zig");

pub const Applet = struct {
    name: []const u8,
    init_fn: *const fn () *anyopaque,
    deinit_fn: *const fn (*anyopaque) void,
    handle_input_fn: *const fn (*anyopaque, input.Key, input.KeyState) Action,
    update_fn: *const fn (*anyopaque, f32) void,
    render_fn: *const fn (*anyopaque, *display.Framebuffer) void,
};

pub const Action = enum { none, quit };

/// Shared context available to applets that need it (wata).
/// Simple applets (snake, clock) can ignore this.
pub const AppContext = struct {
    state: ?*const types.StateSnapshot = null,
    connection: types.ConnectionState = .disconnected,
    action_queue: ?*queue.BoundedQueue(types.Action, 64) = null,
};

/// Connection status for status line color.
pub const Status = enum {
    idle,
    connected,
    syncing,
    recording,
    err,
    disconnected,

    pub fn color(self: Status) display.Color {
        return switch (self) {
            .idle => display.colors.mid_gray,
            .connected => display.colors.green,
            .syncing => display.colors.cyan,
            .recording => display.colors.yellow,
            .err => display.colors.red,
            .disconnected => display.colors.red,
        };
    }

    pub fn fromConnection(conn: types.ConnectionState) Status {
        return switch (conn) {
            .disconnected => .disconnected,
            .connecting => .idle,
            .connected => .connected,
            .syncing => .syncing,
            .err => .err,
        };
    }
};

pub const Shell = struct {
    applets: []const Applet,
    states: []?*anyopaque,
    active: usize,
    status: Status,
    ctx: AppContext,
    allocator: std.mem.Allocator,

    pub fn init(allocator: std.mem.Allocator, applets: []const Applet) !Shell {
        const states = try allocator.alloc(?*anyopaque, applets.len);
        for (applets, 0..) |applet, i| {
            states[i] = applet.init_fn();
        }
        return .{
            .applets = applets,
            .states = states,
            .active = 0,
            .status = .idle,
            .ctx = .{},
            .allocator = allocator,
        };
    }

    pub fn deinit(self: *Shell) void {
        for (self.applets, self.states) |applet, state_opt| {
            if (state_opt) |state| {
                applet.deinit_fn(state);
            }
        }
        self.allocator.free(self.states);
    }

    /// Update the context from a new state snapshot.
    pub fn updateContext(self: *Shell, snapshot: ?*const types.StateSnapshot) void {
        self.ctx.state = snapshot;
        if (snapshot) |s| {
            self.ctx.connection = s.connection;
            self.status = Status.fromConnection(s.connection);
        }
    }

    pub fn handleInput(self: *Shell, key: input.Key, state: input.KeyState) Action {
        // App switching on dot buttons (press only)
        if (state == .pressed) {
            switch (key) {
                .dot2 => {
                    self.active = (self.active + 1) % self.applets.len;
                    return .none;
                },
                .dot1 => {
                    self.active = if (self.active == 0) self.applets.len - 1 else self.active - 1;
                    return .none;
                },
                else => {},
            }
        }

        // PTT always routes to wata (applet 0), regardless of active applet
        if (key == .ptt) {
            if (self.states[0]) |wata_state| {
                return self.applets[0].handle_input_fn(wata_state, key, state);
            }
            return .none;
        }

        // Route to active applet
        if (self.states[self.active]) |applet_state| {
            return self.applets[self.active].handle_input_fn(applet_state, key, state);
        }
        return .none;
    }

    pub fn update(self: *Shell, dt: f32) void {
        if (self.states[self.active]) |applet_state| {
            self.applets[self.active].update_fn(applet_state, dt);
        }
    }

    pub fn render(self: *Shell, fb: *display.Framebuffer) void {
        // 1px status line
        fb.hline(0, 0, display.width, self.status.color());

        // Active applet renders below the status line
        if (self.states[self.active]) |applet_state| {
            self.applets[self.active].render_fn(applet_state, fb);
        }

    }
};
