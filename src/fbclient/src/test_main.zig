/// Test entry point — imports all modules that contain tests.
const std = @import("std");

comptime {
    _ = @import("matrix/sync_engine.zig");
    _ = @import("matrix/sync_thread.zig");
    _ = @import("matrix/http.zig");
    _ = @import("queue.zig");
    _ = @import("mailbox.zig");
    _ = @import("ogg.zig");
}
