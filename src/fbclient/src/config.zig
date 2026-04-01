/// Session persistence — read/write credentials to a JSON file.
/// Path: /etc/wata/config.json (device) or ~/.config/wata/config.json (dev).
const std = @import("std");
const build_options = @import("build_options");
const linux = std.os.linux;

pub const Session = struct {
    homeserver: []const u8,
    username: []const u8,
    access_token: []const u8,
    user_id: []const u8,
    device_id: []const u8,
};

/// Parsed session with owned memory.
pub const OwnedSession = struct {
    session: Session,
    buf: []u8,
    allocator: std.mem.Allocator,

    pub fn deinit(self: *OwnedSession) void {
        self.allocator.free(self.buf);
    }
};

const CONFIG_PATH: [*:0]const u8 = if (build_options.use_sdl)
    "/tmp/wata-config.json" // dev
else
    "/etc/wata/config.json"; // device

/// Try to load a stored session. Returns null if no config file or parse error.
pub fn loadSession(allocator: std.mem.Allocator) ?OwnedSession {
    const fd = std.posix.openatZ(std.posix.AT.FDCWD, CONFIG_PATH, .{ .ACCMODE = .RDONLY }, 0) catch return null;
    defer _ = linux.close(fd);

    // Read file (max 4KB)
    var buf: [4096]u8 = undefined;
    const n = std.posix.read(fd, &buf) catch return null;
    if (n == 0) return null;

    // Dupe into owned buffer so strings outlive the stack
    const owned = allocator.dupe(u8, buf[0..n]) catch return null;

    const parsed = std.json.parseFromSlice(
        JsonConfig,
        allocator,
        owned,
        .{ .ignore_unknown_fields = true },
    ) catch {
        allocator.free(owned);
        return null;
    };

    const v = parsed.value;
    // Strings point into `owned` (same backing as the json source)
    // but std.json.parseFromSlice with []const u8 returns slices into the source
    const session = Session{
        .homeserver = v.homeserver,
        .username = v.username,
        .access_token = v.access_token,
        .user_id = v.user_id,
        .device_id = v.device_id,
    };

    // We need the parsed handle alive for string lifetimes — but we already duped the source.
    // Actually, std.json slices point into the source buffer, which is `owned`.
    // So we can deinit the parse handle but keep `owned`.
    parsed.deinit();

    return .{
        .session = session,
        .buf = owned,
        .allocator = allocator,
    };
}

/// Save session credentials to the config file.
pub fn saveSession(session: Session) void {
    // Ensure directory exists
    ensureDir("/etc/wata");

    const fd = std.posix.openatZ(
        std.posix.AT.FDCWD,
        CONFIG_PATH,
        .{ .ACCMODE = .WRONLY, .CREAT = true, .TRUNC = true },
        0o600, // owner read/write only
    ) catch return;
    defer _ = linux.close(fd);

    var buf: [2048]u8 = undefined;
    const json = std.fmt.bufPrint(&buf,
        \\{{
        \\  "homeserver": "{s}",
        \\  "username": "{s}",
        \\  "access_token": "{s}",
        \\  "user_id": "{s}",
        \\  "device_id": "{s}"
        \\}}
    , .{
        session.homeserver,
        session.username,
        session.access_token,
        session.user_id,
        session.device_id,
    }) catch return;

    _ = linux.write(fd, json.ptr, json.len);
}

/// Clear the stored session (logout).
pub fn clearSession() void {
    // Truncate the file to 0 bytes
    const fd = std.posix.openatZ(
        std.posix.AT.FDCWD,
        CONFIG_PATH,
        .{ .ACCMODE = .WRONLY, .TRUNC = true },
        0,
    ) catch return;
    _ = linux.close(fd);
}

fn ensureDir(path: [*:0]const u8) void {
    _ = linux.mkdir(path, 0o755);
}

const JsonConfig = struct {
    homeserver: []const u8,
    username: []const u8,
    access_token: []const u8,
    user_id: []const u8,
    device_id: []const u8,
};
