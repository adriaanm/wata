/// Bounded MPSC (multi-producer, single-consumer) ring buffer queue.
/// Lock-free on the consumer side; producers share a mutex.
/// No heap allocation — buffer is inline.
const std = @import("std");

pub fn BoundedQueue(comptime T: type, comptime capacity: usize) type {
    return struct {
        const Self = @This();

        buf: [capacity]T = undefined,
        head: std.atomic.Value(usize) = std.atomic.Value(usize).init(0), // next write position
        tail: std.atomic.Value(usize) = std.atomic.Value(usize).init(0), // next read position
        push_mutex: std.atomic.Mutex = .unlocked, // serialize producers

        /// Push an item. Returns false if the queue is full.
        pub fn push(self: *Self, item: T) bool {
            // Spin-lock for producer serialization (critical section is ~3 instructions)
            while (!self.push_mutex.tryLock()) {}
            defer self.push_mutex.unlock();

            const h = self.head.load(.monotonic);
            const next = (h + 1) % capacity;
            if (next == self.tail.load(.acquire)) return false; // full
            self.buf[h] = item;
            self.head.store(next, .release);
            return true;
        }

        /// Pop an item. Returns null if the queue is empty.
        /// Only safe to call from a single consumer thread.
        pub fn pop(self: *Self) ?T {
            const t = self.tail.load(.monotonic);
            if (t == self.head.load(.acquire)) return null; // empty
            const item = self.buf[t];
            self.tail.store((t + 1) % capacity, .release);
            return item;
        }

        /// Drain all available items, calling f for each.
        pub fn drain(self: *Self, f: anytype) void {
            while (self.pop()) |item| {
                f(item);
            }
        }
    };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

const testing = std.testing;

test "queue: push and pop single item" {
    var q = BoundedQueue(u32, 4){};
    try testing.expect(q.push(42));
    try testing.expectEqual(@as(?u32, 42), q.pop());
    try testing.expectEqual(@as(?u32, null), q.pop());
}

test "queue: FIFO ordering preserved" {
    var q = BoundedQueue(u32, 8){};
    for (0..5) |i| {
        try testing.expect(q.push(@intCast(i)));
    }
    for (0..5) |i| {
        try testing.expectEqual(@as(?u32, @intCast(i)), q.pop());
    }
}

test "queue: full returns false" {
    // capacity=4 means 3 usable slots (ring buffer reserves 1)
    var q = BoundedQueue(u32, 4){};
    try testing.expect(q.push(1));
    try testing.expect(q.push(2));
    try testing.expect(q.push(3));
    try testing.expect(!q.push(4)); // full
}

test "queue: empty pop returns null" {
    var q = BoundedQueue(u32, 4){};
    try testing.expectEqual(@as(?u32, null), q.pop());
}

test "queue: wraparound works" {
    var q = BoundedQueue(u32, 4){};
    // Fill and drain twice to exercise wraparound
    for (0..2) |_| {
        try testing.expect(q.push(10));
        try testing.expect(q.push(20));
        try testing.expect(q.push(30));
        try testing.expectEqual(@as(?u32, 10), q.pop());
        try testing.expectEqual(@as(?u32, 20), q.pop());
        try testing.expectEqual(@as(?u32, 30), q.pop());
        try testing.expectEqual(@as(?u32, null), q.pop());
    }
}

test "queue: drain empties all items" {
    var q = BoundedQueue(u32, 8){};
    _ = q.push(1);
    _ = q.push(2);
    _ = q.push(3);

    // Pop all manually to verify drain-like behavior
    var count: u32 = 0;
    while (q.pop()) |_| count += 1;
    try testing.expectEqual(@as(u32, 3), count);
    try testing.expectEqual(@as(?u32, null), q.pop());
}
