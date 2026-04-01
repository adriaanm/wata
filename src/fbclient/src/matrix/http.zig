/// HTTP client for Matrix API using std.http.Client (Zig 0.16 std.Io).
const std = @import("std");
const Io = std.Io;
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

pub const RawResponse = struct {
    body: []const u8,
    /// The full buffer (may be larger than body due to pre-allocation).
    full_buf: []u8,
    allocator: std.mem.Allocator,

    pub fn deinit(self: *RawResponse) void {
        self.allocator.free(self.full_buf);
    }
};

pub const MatrixHttpClient = struct {
    base_url: []const u8,
    access_token: ?[]const u8 = null,
    allocator: std.mem.Allocator,
    io: Io,

    pub fn init(allocator: std.mem.Allocator, io: Io, base_url: []const u8) MatrixHttpClient {
        return .{ .allocator = allocator, .io = io, .base_url = base_url };
    }

    /// POST /_matrix/client/v3/login — returns raw response for caller to parse
    pub fn login(self: *MatrixHttpClient, username: []const u8, password: []const u8) HttpError!RawResponse {
        var buf: [1024]u8 = undefined;
        const body = std.fmt.bufPrint(&buf,
            \\{{"type":"m.login.password","identifier":{{"type":"m.id.user","user":"{s}"}},"password":"{s}"}}
        , .{ username, password }) catch return HttpError.OutOfMemory;
        return self.doRequest(.POST, "/_matrix/client/v3/login", body);
    }

    /// GET /_matrix/client/v3/sync
    pub fn sync(self: *MatrixHttpClient, since: ?[]const u8, timeout_ms: u32) HttpError!RawResponse {
        var query_buf: [512]u8 = undefined;
        const query = if (since) |s|
            std.fmt.bufPrint(&query_buf, "?timeout={d}&since={s}", .{ timeout_ms, s }) catch return HttpError.OutOfMemory
        else
            std.fmt.bufPrint(&query_buf, "?timeout={d}", .{timeout_ms}) catch return HttpError.OutOfMemory;

        var path_buf: [600]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/sync{s}", .{query}) catch return HttpError.OutOfMemory;

        return self.doRequest(.GET, path, null);
    }

    /// GET display name for a user.
    pub fn getDisplayName(self: *MatrixHttpClient, user_id: []const u8) HttpError!RawResponse {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/profile/{s}/displayname", .{user_id}) catch return HttpError.OutOfMemory;
        return self.doRequest(.GET, path, null);
    }

    /// SET display name for the current user.
    pub fn setDisplayName(self: *MatrixHttpClient, user_id: []const u8, display_name: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/profile/{s}/displayname", .{user_id}) catch return HttpError.OutOfMemory;
        var body_buf: [512]u8 = undefined;
        const body = std.fmt.bufPrint(&body_buf, "{{\"displayname\":\"{s}\"}}", .{display_name}) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.PUT, path, body);
        resp.deinit();
    }

    /// PUT redact (delete) an event.
    pub fn redactEvent(self: *MatrixHttpClient, room_id: []const u8, event_id: []const u8, txn_id: u32) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/redact/{s}/{d}", .{ room_id, event_id, txn_id }) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.PUT, path, "{\"reason\":\"deleted\"}");
        resp.deinit();
    }

    /// POST receipt
    pub fn sendReadReceipt(self: *MatrixHttpClient, room_id: []const u8, event_id: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/receipt/m.read/{s}", .{ room_id, event_id }) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.POST, path, "{}");
        resp.deinit();
    }

    /// Upload media (Ogg audio). Returns mxc:// URL.
    pub fn uploadMedia(self: *MatrixHttpClient, ogg_data: []const u8) HttpError!RawResponse {
        return self.doUpload("/_matrix/media/v3/upload?content_type=audio%2Fogg", ogg_data);
    }

    /// Download media from an mxc:// URL. Returns raw bytes.
    pub fn downloadMedia(self: *MatrixHttpClient, mxc_url: []const u8) HttpError!RawResponse {
        // mxc://server/media_id → /_matrix/client/v1/media/download/server/media_id
        if (!std.mem.startsWith(u8, mxc_url, "mxc://")) return HttpError.InvalidResponse;
        const rest = mxc_url[6..]; // "server/media_id"
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v1/media/download/{s}", .{rest}) catch return HttpError.OutOfMemory;
        return self.doRequest(.GET, path, null);
    }

    /// POST /_matrix/client/v3/join/{roomId} — accept an invite or join a room.
    /// Used for auto-joining room invites in the trusted family environment.
    pub fn joinRoom(self: *MatrixHttpClient, room_id: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/join/{s}", .{room_id}) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.POST, path, "{}");
        resp.deinit();
    }

    /// POST /_matrix/client/v3/createRoom — create a DM room with a contact.
    /// Returns the new room_id as a slice into the response buffer.
    pub fn createRoom(self: *MatrixHttpClient, contact_user_id: []const u8) HttpError!RawResponse {
        var body_buf: [512]u8 = undefined;
        const body = std.fmt.bufPrint(&body_buf,
            \\{{"is_direct":true,"invite":["{s}"],"preset":"trusted_private_chat","visibility":"private"}}
        , .{contact_user_id}) catch return HttpError.OutOfMemory;
        return self.doRequest(.POST, "/_matrix/client/v3/createRoom", body);
    }

    /// GET /_matrix/client/v3/user/{userId}/account_data/{type}
    pub fn getAccountData(self: *MatrixHttpClient, user_id: []const u8, data_type: []const u8) HttpError!RawResponse {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/user/{s}/account_data/{s}", .{ user_id, data_type }) catch return HttpError.OutOfMemory;
        return self.doRequest(.GET, path, null);
    }

    /// PUT /_matrix/client/v3/user/{userId}/account_data/{type}
    pub fn setAccountData(self: *MatrixHttpClient, user_id: []const u8, data_type: []const u8, data: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/user/{s}/account_data/{s}", .{ user_id, data_type }) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.PUT, path, data);
        resp.deinit();
    }

    /// GET /_matrix/client/v3/rooms/{roomId}/messages — paginate room messages.
    /// Used to backfill missed messages when sync returns limited: true.
    pub fn getMessages(self: *MatrixHttpClient, room_id: []const u8, from: []const u8, limit: u32) HttpError!RawResponse {
        var path_buf: [1024]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/messages?from={s}&dir=b&limit={d}", .{ room_id, from, limit }) catch return HttpError.OutOfMemory;
        return self.doRequest(.GET, path, null);
    }

    /// Send a voice message to a room. `mxc_url` is the uploaded media URL.
    pub fn sendVoiceMessage(
        self: *MatrixHttpClient,
        room_id: []const u8,
        mxc_url: []const u8,
        duration_ms: u64,
        txn_id: u32,
    ) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/send/m.room.message/{d}", .{ room_id, txn_id }) catch return HttpError.OutOfMemory;

        var body_buf: [1024]u8 = undefined;
        const body = std.fmt.bufPrint(&body_buf,
            \\{{"msgtype":"m.audio","body":"voice message","url":"{s}","info":{{"mimetype":"audio/ogg; codecs=opus","duration":{d}}}}}
        , .{ mxc_url, duration_ms }) catch return HttpError.OutOfMemory;

        var resp = try self.doRequest(.PUT, path, body);
        resp.deinit();
    }

    fn doUpload(self: *MatrixHttpClient, path: []const u8, body: []const u8) HttpError!RawResponse {
        var url_buf: [1024]u8 = undefined;
        const url = std.fmt.bufPrint(&url_buf, "{s}{s}", .{ self.base_url, path }) catch return HttpError.OutOfMemory;

        var client: std.http.Client = .{ .allocator = self.allocator, .io = self.io };
        defer client.deinit();

        var auth_buf: [512]u8 = undefined;
        var extra_headers_buf: [2]std.http.Header = undefined;
        var header_count: usize = 0;

        extra_headers_buf[header_count] = .{ .name = "Content-Type", .value = "audio/ogg" };
        header_count += 1;

        if (self.access_token) |token| {
            const auth = std.fmt.bufPrint(&auth_buf, "Bearer {s}", .{token}) catch return HttpError.OutOfMemory;
            extra_headers_buf[header_count] = .{ .name = "Authorization", .value = auth };
            header_count += 1;
        }

        var response_writer: Io.Writer.Allocating = .init(self.allocator);

        const result = client.fetch(.{
            .location = .{ .url = url },
            .method = .POST,
            .payload = body,
            .response_writer = &response_writer.writer,
            .extra_headers = extra_headers_buf[0..header_count],
        }) catch {
            response_writer.deinit();
            return HttpError.ConnectionFailed;
        };

        if (result.status != .ok) {
            response_writer.deinit();
            return HttpError.MatrixError;
        }

        var list = response_writer.toArrayList();
        const buf_slice = list.allocatedSlice();
        if (buf_slice.len == 0) return HttpError.InvalidResponse;

        return .{
            .body = list.items,
            .full_buf = buf_slice,
            .allocator = self.allocator,
        };
    }

    fn doRequest(self: *MatrixHttpClient, method: std.http.Method, path: []const u8, body: ?[]const u8) HttpError!RawResponse {
        // Build URL
        var url_buf: [1024]u8 = undefined;
        const url = std.fmt.bufPrint(&url_buf, "{s}{s}", .{ self.base_url, path }) catch return HttpError.OutOfMemory;

        var client: std.http.Client = .{ .allocator = self.allocator, .io = self.io };
        defer client.deinit();

        // Build extra headers
        var auth_buf: [512]u8 = undefined;
        var extra_headers_buf: [2]std.http.Header = undefined;
        var header_count: usize = 0;

        extra_headers_buf[header_count] = .{ .name = "Content-Type", .value = "application/json" };
        header_count += 1;

        if (self.access_token) |token| {
            const auth = std.fmt.bufPrint(&auth_buf, "Bearer {s}", .{token}) catch return HttpError.OutOfMemory;
            extra_headers_buf[header_count] = .{ .name = "Authorization", .value = auth };
            header_count += 1;
        }

        // Retry loop for rate limiting (HTTP 429). Matrix homeservers return
        // {"retry_after_ms": N} — we sleep and retry up to 3 times.
        const max_retries = 3;
        var attempt: usize = 0;
        while (attempt <= max_retries) : (attempt += 1) {
            var response_writer: Io.Writer.Allocating = .init(self.allocator);

            const result = client.fetch(.{
                .location = .{ .url = url },
                .method = method,
                .payload = body,
                .response_writer = &response_writer.writer,
                .extra_headers = extra_headers_buf[0..header_count],
            }) catch {
                response_writer.deinit();
                return HttpError.ConnectionFailed;
            };

            // Rate limited — sleep and retry
            if (result.status == .too_many_requests and attempt < max_retries) {
                const wait_ms: i64 = @intCast(parseRetryAfterMs(response_writer.toArrayList().items));
                response_writer.deinit();
                self.io.sleep(.fromMilliseconds(wait_ms), .awake) catch {};
                continue;
            }

            if (result.status != .ok) {
                response_writer.deinit();
                return HttpError.MatrixError;
            }

            var list = response_writer.toArrayList();
            const buf_slice = list.allocatedSlice();
            if (buf_slice.len == 0) {
                return HttpError.InvalidResponse;
            }

            return .{
                .body = list.items,
                .full_buf = buf_slice,
                .allocator = self.allocator,
            };
        }

        return HttpError.MatrixError;
    }
};

/// Parse "retry_after_ms" from a Matrix 429 response body.
/// Returns the value in milliseconds, or a 1-second default if unparseable.
fn parseRetryAfterMs(body: []const u8) u64 {
    const key = "\"retry_after_ms\":";
    const start = std.mem.indexOf(u8, body, key) orelse return 1000;
    const val_start = start + key.len;
    // Skip whitespace
    var pos = val_start;
    while (pos < body.len and body[pos] == ' ') pos += 1;
    // Parse integer
    var end = pos;
    while (end < body.len and body[end] >= '0' and body[end] <= '9') end += 1;
    if (end == pos) return 1000;
    return std.fmt.parseInt(u64, body[pos..end], 10) catch 1000;
}
