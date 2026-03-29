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

    /// POST receipt
    pub fn sendReadReceipt(self: *MatrixHttpClient, room_id: []const u8, event_id: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/receipt/m.read/{s}", .{ room_id, event_id }) catch return HttpError.OutOfMemory;
        var resp = try self.doRequest(.POST, path, "{}");
        resp.deinit();
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

        // Set up response body writer (Allocating collects into a growable buffer)
        var response_writer: Io.Writer.Allocating = .init(self.allocator);
        // Don't defer deinit — we transfer ownership on success

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

        // Check HTTP status
        if (result.status != .ok) {
            response_writer.deinit();
            return HttpError.MatrixError;
        }

        // Transfer ownership of the response buffer
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
};
