/// Bounded MPSC mailbox with blocking receive.
/// Uses Linux futex for efficient sleep/wake — no busy-polling.
///
/// Replaces BoundedQueue + sleep-polling for threads that can block.
/// UI thread should use tryReceive() since it must not block the render loop.
const std = @import("std");
const linux = std.os.linux;

pub fn Mailbox(comptime T: type, comptime capacity: usize) type {
    return struct {
        const Self = @This();

        ring: [capacity]T = undefined,
        head: usize = 0, // next write position (protected by mu)
        tail: usize = 0, // next read position (single consumer)
        mu: std.atomic.Mutex = .unlocked, // serialize producers
        /// Futex word: 0 = empty, 1 = has items, 2 = closed.
        state: std.atomic.Value(u32) = std.atomic.Value(u32).init(0),

        /// Send an item. Returns false if full or closed.
        pub fn send(self: *Self, item: T) bool {
            while (!self.mu.tryLock()) {}
            defer self.mu.unlock();

            if (self.state.load(.monotonic) == 2) return false; // closed

            const next = (self.head + 1) % capacity;
            if (next == self.tail) return false; // full
            self.ring[self.head] = item;
            self.head = next;

            // Signal: set state to "has items" and wake one waiter
            const prev = self.state.swap(1, .release);
            if (prev == 0) {
                // Was empty — a receiver might be blocked on futex
                futexWake(&self.state);
            }

            return true;
        }

        /// Blocking receive. Sleeps until an item is available.
        /// Returns null when the mailbox is closed and empty.
        pub fn receive(self: *Self) ?T {
            while (true) {
                // Try non-blocking first
                if (self.tryReceiveInner()) |item| return item;

                // Check if closed and empty
                if (self.state.load(.acquire) == 2) {
                    if (self.tryReceiveInner()) |item| return item;
                    return null;
                }

                // Sleep until state changes from 0 (empty)
                futexWait(&self.state, 0);
            }
        }

        /// Non-blocking try. Returns null if empty (never blocks).
        pub fn tryReceive(self: *Self) ?T {
            return self.tryReceiveInner();
        }

        /// Close the mailbox. Wakes all blocked receivers.
        /// Remaining items can still be drained via tryReceive().
        pub fn close(self: *Self) void {
            self.state.store(2, .release);
            futexWake(&self.state);
        }

        /// True if close() has been called.
        pub fn isClosed(self: *Self) bool {
            return self.state.load(.acquire) == 2;
        }

        // Internal: lock-free single-consumer pop
        fn tryReceiveInner(self: *Self) ?T {
            if (self.tail == self.head) return null; // empty (relaxed ok — single consumer)
            const item = self.ring[self.tail];
            self.tail = (self.tail + 1) % capacity;

            // If queue is now empty and not closed, set state to 0 (empty)
            // so next send() knows to wake us
            if (self.tail == self.head and self.state.load(.monotonic) != 2) {
                self.state.store(0, .release);
            }

            return item;
        }
    };
}

/// Wake one thread waiting on a futex.
fn futexWake(ptr: *std.atomic.Value(u32)) void {
    _ = linux.futex_3arg(
        @ptrCast(ptr),
        .{ .cmd = .WAKE, .private = true },
        1, // wake 1 waiter
    );
}

/// Sleep until *ptr != expected, or spurious wakeup.
fn futexWait(ptr: *std.atomic.Value(u32), expected: u32) void {
    _ = linux.futex_4arg(
        @ptrCast(ptr),
        .{ .cmd = .WAIT, .private = true },
        expected,
        null, // no timeout
    );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

const testing = std.testing;

test "mailbox: send and receive single item" {
    var m = Mailbox(u32, 4){};
    try testing.expect(m.send(42));
    try testing.expectEqual(@as(?u32, 42), m.tryReceive());
    try testing.expectEqual(@as(?u32, null), m.tryReceive());
}

test "mailbox: FIFO ordering" {
    var m = Mailbox(u32, 8){};
    for (0..5) |i| {
        try testing.expect(m.send(@intCast(i)));
    }
    for (0..5) |i| {
        try testing.expectEqual(@as(?u32, @intCast(i)), m.tryReceive());
    }
}

test "mailbox: full returns false" {
    // capacity=4 → 3 usable slots (ring buffer reserves 1)
    var m = Mailbox(u32, 4){};
    try testing.expect(m.send(1));
    try testing.expect(m.send(2));
    try testing.expect(m.send(3));
    try testing.expect(!m.send(4)); // full
}

test "mailbox: close wakes blocked receiver" {
    var m = Mailbox(u32, 4){};
    var result: ?u32 = 0xDEAD; // sentinel

    // Spawn a thread that blocks on receive
    const handle = std.Thread.spawn(.{}, struct {
        fn run(mb: *Mailbox(u32, 4), res: *?u32) void {
            res.* = mb.receive();
        }
    }.run, .{ &m, &result }) catch return;

    // Give thread time to block
    var ts = linux.timespec{ .sec = 0, .nsec = 10_000_000 }; // 10ms
    _ = linux.nanosleep(&ts, null);

    // Close should wake the blocked thread
    m.close();
    handle.join();
    try testing.expectEqual(@as(?u32, null), result);
}

test "mailbox: receive returns items then null after close" {
    var m = Mailbox(u32, 8){};
    try testing.expect(m.send(10));
    try testing.expect(m.send(20));
    m.close();

    // Should still drain existing items
    try testing.expectEqual(@as(?u32, 10), m.receive());
    try testing.expectEqual(@as(?u32, 20), m.receive());
    // Then null
    try testing.expectEqual(@as(?u32, null), m.receive());
}

test "mailbox: send after close returns false" {
    var m = Mailbox(u32, 4){};
    m.close();
    try testing.expect(!m.send(1));
}

test "mailbox: blocking receive gets item from sender" {
    var m = Mailbox(u32, 4){};
    var result: ?u32 = null;

    // Spawn receiver thread
    const handle = std.Thread.spawn(.{}, struct {
        fn run(mb: *Mailbox(u32, 4), res: *?u32) void {
            res.* = mb.receive();
        }
    }.run, .{ &m, &result }) catch return;

    // Give thread time to block on empty mailbox
    var ts = linux.timespec{ .sec = 0, .nsec = 10_000_000 };
    _ = linux.nanosleep(&ts, null);

    // Send an item — should wake the receiver
    try testing.expect(m.send(99));
    handle.join();
    try testing.expectEqual(@as(?u32, 99), result);
}

test "mailbox: wraparound works" {
    var m = Mailbox(u32, 4){};
    for (0..2) |_| {
        try testing.expect(m.send(10));
        try testing.expect(m.send(20));
        try testing.expect(m.send(30));
        try testing.expectEqual(@as(?u32, 10), m.tryReceive());
        try testing.expectEqual(@as(?u32, 20), m.tryReceive());
        try testing.expectEqual(@as(?u32, 30), m.tryReceive());
        try testing.expectEqual(@as(?u32, null), m.tryReceive());
    }
}
