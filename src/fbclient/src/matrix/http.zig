/// HTTP client wrapper for Matrix API requests.
/// Handles auth header injection, JSON request/response encoding,
/// and connection management via std.http.Client.
const std = @import("std");
const json_types = @import("json_types.zig");

pub const HttpError = error{
    ConnectionFailed,
    RequestFailed,
    InvalidResponse,
    MatrixError,
    JsonParseFailed,
    OutOfMemory,
    UriParseFailed,
};

pub const MatrixHttpClient = struct {
    base_url: []const u8,
    access_token: ?[]const u8 = null,
    allocator: std.mem.Allocator,

    pub fn init(allocator: std.mem.Allocator, base_url: []const u8) MatrixHttpClient {
        return .{
            .allocator = allocator,
            .base_url = base_url,
        };
    }

    /// POST /_matrix/client/v3/login
    pub fn login(self: *MatrixHttpClient, username: []const u8, password: []const u8) HttpError!json_types.LoginResponse {
        var buf: [1024]u8 = undefined;
        const body = std.fmt.bufPrint(&buf, "{{\"type\":\"m.login.password\",\"user\":\"{s}\",\"password\":\"{s}\"}}", .{ username, password }) catch return HttpError.OutOfMemory;
        return self.requestJson(json_types.LoginResponse, .POST, "/_matrix/client/v3/login", body, null);
    }

    /// GET /_matrix/client/v3/sync
    pub fn sync(self: *MatrixHttpClient, since: ?[]const u8, timeout_ms: u32) HttpError!struct { body: []const u8, arena: std.heap.ArenaAllocator } {
        // Build query string
        var query_buf: [512]u8 = undefined;
        const query = if (since) |s|
            std.fmt.bufPrint(&query_buf, "?timeout={d}&since={s}", .{ timeout_ms, s }) catch return HttpError.OutOfMemory
        else
            std.fmt.bufPrint(&query_buf, "?timeout={d}", .{timeout_ms}) catch return HttpError.OutOfMemory;

        var path_buf: [600]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/sync{s}", .{query}) catch return HttpError.OutOfMemory;

        // For sync, return the raw body + arena so caller can parse with their own arena
        return self.requestRaw(path, timeout_ms + 10000);
    }

    /// POST /_matrix/client/v3/rooms/{roomId}/receipt/m.read/{eventId}
    pub fn sendReadReceipt(self: *MatrixHttpClient, room_id: []const u8, event_id: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/receipt/m.read/{s}", .{ room_id, event_id }) catch return HttpError.OutOfMemory;
        _ = self.requestRaw(path, 10000) catch |e| return e;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    fn requestJson(self: *MatrixHttpClient, comptime T: type, method: std.http.Method, path: []const u8, body: ?[]const u8, extra_timeout: ?u32) HttpError!T {
        const result = self.requestRawMethod(method, path, body, extra_timeout orelse 10000) catch return HttpError.ConnectionFailed;
        defer result.arena.deinit();

        const parsed = std.json.parseFromSlice(T, self.allocator, result.body, .{ .ignore_unknown_fields = true }) catch return HttpError.JsonParseFailed;
        defer parsed.deinit();

        // Copy the result so it outlives the arena
        return parsed.value;
    }

    fn requestRaw(self: *MatrixHttpClient, path: []const u8, timeout_ms: u32) HttpError!struct { body: []const u8, arena: std.heap.ArenaAllocator } {
        return self.requestRawMethod(.GET, path, null, timeout_ms);
    }

    fn requestRawMethod(self: *MatrixHttpClient, method: std.http.Method, path: []const u8, body: ?[]const u8, timeout_ms: u32) HttpError!struct { body: []const u8, arena: std.heap.ArenaAllocator } {
        _ = timeout_ms; // TODO: use for connection timeout

        var arena = std.heap.ArenaAllocator.init(self.allocator);
        errdefer arena.deinit();
        const arena_alloc = arena.allocator();

        // Build full URL
        var url_buf: [1024]u8 = undefined;
        const url_str = std.fmt.bufPrint(&url_buf, "{s}{s}", .{ self.base_url, path }) catch return HttpError.OutOfMemory;

        const uri = std.Uri.parse(url_str) catch return HttpError.UriParseFailed;

        // Create a per-request HTTP client
        var client = std.http.Client{ .allocator = arena_alloc };
        defer client.deinit();

        // Build headers
        var headers = std.http.Client.Request.Headers{};
        if (body != null) {
            headers.content_type = .{ .override = "application/json" };
        }

        var extra_headers_buf: [1]std.http.Header = undefined;
        var extra_headers_len: usize = 0;
        if (self.access_token) |token| {
            var auth_buf = arena_alloc.alloc(u8, 7 + token.len) catch return HttpError.OutOfMemory;
            @memcpy(auth_buf[0..7], "Bearer ");
            @memcpy(auth_buf[7..], token);
            extra_headers_buf[0] = .{ .name = "Authorization", .value = auth_buf };
            extra_headers_len = 1;
        }

        var req = client.open(method, uri, .{
            .headers = headers,
            .extra_headers = extra_headers_buf[0..extra_headers_len],
        }) catch return HttpError.ConnectionFailed;
        defer req.deinit();

        if (body) |b| {
            req.transfer_encoding = .{ .content_length = b.len };
        }

        req.send() catch return HttpError.RequestFailed;

        if (body) |b| {
            req.writer().writeAll(b) catch return HttpError.RequestFailed;
        }
        req.finish() catch return HttpError.RequestFailed;
        req.wait() catch return HttpError.RequestFailed;

        if (req.status != .ok) {
            return HttpError.MatrixError;
        }

        const response_body = req.reader().readAllAlloc(arena_alloc, 4 * 1024 * 1024) catch return HttpError.InvalidResponse;

        return .{ .body = response_body, .arena = arena };
    }
};
