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
        push_mutex: std.Thread.Mutex = .{}, // serialize producers

        /// Push an item. Returns false if the queue is full.
        pub fn push(self: *Self, item: T) bool {
            self.push_mutex.lock();
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
