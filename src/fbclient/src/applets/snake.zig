/// Classic snake game. First applet — validates the full rendering + input stack.
const std = @import("std");
const Io = std.Io;
const build_options = @import("build_options");
const display = @import("../display.zig");
const font = @import("../font.zig");
const input = @import("../input.zig");
const shell = @import("../shell.zig");

var g_io: ?Io = null;

/// Set the Io instance for RNG seeding. Called once from main.
pub fn setIo(io: Io) void {
    g_io = io;
}

const grid_w: u32 = font.cols; // 21
const grid_h: u32 = font.rows - 1; // 18 (leave bottom row for score)
const max_len: usize = grid_w * grid_h;

const cell_w: u32 = font.glyph_w; // 6px
const cell_h: u32 = font.glyph_h; // 8px
const grid_y_offset: i32 = 1; // below status line

const Dir = enum { up, down, left, right };

const Pos = struct {
    x: i16,
    y: i16,

    fn eql(a: Pos, b: Pos) bool {
        return a.x == b.x and a.y == b.y;
    }
};

const State = struct {
    body: [max_len]Pos,
    len: usize,
    dir: Dir,
    next_dir: Dir,
    food: Pos,
    alive: bool,
    score: u32,
    tick_timer: f32,
    tick_rate: f32, // seconds per step
    rng: std.Random.DefaultPrng,

    fn init() State {
        var s: State = undefined;
        s.reset();
        return s;
    }

    fn reset(self: *State) void {
        // Seed RNG from wall clock
        const seed: u64 = blk: {
            if (g_io) |io| {
                const ts = Io.Clock.real.now(io);
                break :blk @intCast(@as(i128, ts.nanoseconds) & 0xFFFFFFFFFFFFFFFF);
            } else if (build_options.use_sdl) {
                const sdl = @import("../sdl.zig").c;
                break :blk @as(u64, sdl.SDL_GetTicks());
            } else {
                break :blk 0;
            }
        };
        self.rng = std.Random.DefaultPrng.init(seed);

        self.len = 3;
        self.body[0] = .{ .x = 10, .y = 9 };
        self.body[1] = .{ .x = 9, .y = 9 };
        self.body[2] = .{ .x = 8, .y = 9 };
        self.dir = .right;
        self.next_dir = .right;
        self.alive = true;
        self.score = 0;
        self.tick_timer = 0;
        self.tick_rate = 0.15;
        self.placeFood();
    }

    fn placeFood(self: *State) void {
        const random = self.rng.random();
        // Try random positions, fall back to scanning
        var attempts: u32 = 0;
        while (attempts < 100) : (attempts += 1) {
            const pos = Pos{
                .x = @intCast(random.uintLessThan(u32, grid_w)),
                .y = @intCast(random.uintLessThan(u32, grid_h)),
            };
            if (!self.bodyContains(pos)) {
                self.food = pos;
                return;
            }
        }
        // Fallback: scan for first empty cell
        var y: i16 = 0;
        while (y < grid_h) : (y += 1) {
            var x: i16 = 0;
            while (x < grid_w) : (x += 1) {
                const pos = Pos{ .x = x, .y = y };
                if (!self.bodyContains(pos)) {
                    self.food = pos;
                    return;
                }
            }
        }
    }

    fn bodyContains(self: *State, pos: Pos) bool {
        for (self.body[0..self.len]) |seg| {
            if (seg.eql(pos)) return true;
        }
        return false;
    }

    fn step(self: *State) void {
        if (!self.alive) return;

        self.dir = self.next_dir;
        const head = self.body[0];
        var new_head = head;
        switch (self.dir) {
            .up => new_head.y -= 1,
            .down => new_head.y += 1,
            .left => new_head.x -= 1,
            .right => new_head.x += 1,
        }

        // Wall collision
        if (new_head.x < 0 or new_head.x >= grid_w or
            new_head.y < 0 or new_head.y >= grid_h)
        {
            self.alive = false;
            return;
        }

        // Self collision
        if (self.bodyContains(new_head)) {
            self.alive = false;
            return;
        }

        // Eat food?
        const ate = new_head.eql(self.food);

        // Shift body
        if (!ate) {
            // Move tail
            var i: usize = self.len - 1;
            while (i > 0) : (i -= 1) {
                self.body[i] = self.body[i - 1];
            }
        } else {
            // Grow: shift everything, keep tail
            if (self.len < max_len) {
                var i: usize = self.len;
                while (i > 0) : (i -= 1) {
                    self.body[i] = self.body[i - 1];
                }
                self.len += 1;
            }
            self.score += 10;
            // Speed up slightly
            if (self.tick_rate > 0.06) {
                self.tick_rate -= 0.005;
            }
            self.placeFood();
        }

        self.body[0] = new_head;
    }
};

// ---------------------------------------------------------------------------
// Applet interface
// ---------------------------------------------------------------------------

fn initApplet() *anyopaque {
    // Use a static State to avoid needing an allocator
    const S = struct {
        var state: State = undefined;
        var initialized: bool = false;
    };
    if (!S.initialized) {
        S.state = State.init();
        S.initialized = true;
    }
    return @ptrCast(&S.state);
}

fn deinitApplet(_: *anyopaque) void {}

fn handleInput(ptr: *anyopaque, key: input.Key, state: input.KeyState) shell.Action {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (state != .pressed) return .none;

    if (!s.alive) {
        if (key == .enter) {
            s.reset();
        }
        return .none;
    }

    switch (key) {
        .up => if (s.dir != .down) {
            s.next_dir = .up;
        },
        .down => if (s.dir != .up) {
            s.next_dir = .down;
        },
        .left => if (s.dir != .right) {
            s.next_dir = .left;
        },
        .right => if (s.dir != .left) {
            s.next_dir = .right;
        },
        else => {},
    }
    return .none;
}

fn update(ptr: *anyopaque, dt: f32) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    if (!s.alive) return;

    s.tick_timer += dt;
    while (s.tick_timer >= s.tick_rate) {
        s.tick_timer -= s.tick_rate;
        s.step();
    }
}

fn render(ptr: *anyopaque, fb: *display.Framebuffer) void {
    const s: *State = @ptrCast(@alignCast(ptr));
    const c = display.colors;

    // Draw grid background
    fb.fillRect(0, grid_y_offset, display.width, grid_h * cell_h, c.black);

    // Draw food
    fb.fillRect(
        @as(i32, s.food.x) * @as(i32, cell_w),
        grid_y_offset + @as(i32, s.food.y) * @as(i32, cell_h),
        cell_w,
        cell_h,
        c.red,
    );

    // Draw snake
    for (s.body[0..s.len], 0..) |seg, i| {
        const color: display.Color = if (i == 0) c.green else c.dark_green;
        fb.fillRect(
            @as(i32, seg.x) * @as(i32, cell_w),
            grid_y_offset + @as(i32, seg.y) * @as(i32, cell_h),
            cell_w - 1, // 1px gap between cells
            cell_h - 1,
            color,
        );
    }

    // Score on bottom row
    var score_buf: [21]u8 = undefined;
    const score_text = std.fmt.bufPrint(&score_buf, "SCORE:{d:0>4}", .{s.score}) catch "SCORE:????";
    font.drawText(fb, score_text, 0, font.rows - 1, c.green, c.black);

    // Game over overlay
    if (!s.alive) {
        font.drawTextCentered(fb, "GAME OVER", 8, c.red, c.black);
        font.drawTextCentered(fb, "ENTER to restart", 10, c.mid_gray, c.black);
    }
}

pub const applet = shell.Applet{
    .name = "snake",
    .init_fn = initApplet,
    .deinit_fn = deinitApplet,
    .handle_input_fn = handleInput,
    .update_fn = update,
    .render_fn = render,
};
