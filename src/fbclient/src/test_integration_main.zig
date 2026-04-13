/// Test entry for fbclient integration tests.
/// Runs against a live Matrix homeserver — skipped via `error.SkipZigTest`
/// when the server isn't reachable. Default homeserver: http://localhost:8008
/// (Conduit, started via `test/docker/setup.sh`).
///
/// Run with: `cd src/fbclient && zig build test-integration`
const std = @import("std");

comptime {
    _ = @import("matrix/client_integration_test.zig");
}
