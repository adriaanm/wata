/// Test entry point — imports all modules that contain tests.
const std = @import("std");

comptime {
    _ = @import("matrix/sync_engine.zig");
    _ = @import("queue.zig");
}
