/// HTTP client for Matrix API.
/// Uses libcurl via C interop — simpler and more robust than fighting
/// Zig 0.16-dev's rapidly-changing std.http.Client API.
const std = @import("std");
const json_types = @import("json_types.zig");

const c = @cImport({
    @cInclude("curl/curl.h");
});

pub const HttpError = error{
    ConnectionFailed,
    RequestFailed,
    InvalidResponse,
    MatrixError,
    JsonParseFailed,
    OutOfMemory,
    UriParseFailed,
    CurlInitFailed,
};

pub const RawResponse = struct {
    body: []const u8,
    allocator: std.mem.Allocator,

    pub fn deinit(self: *RawResponse) void {
        self.allocator.free(self.body);
    }
};

pub const MatrixHttpClient = struct {
    base_url: []const u8,
    access_token: ?[]const u8 = null,
    allocator: std.mem.Allocator,

    pub fn init(allocator: std.mem.Allocator, base_url: []const u8) MatrixHttpClient {
        return .{ .allocator = allocator, .base_url = base_url };
    }

    /// POST /_matrix/client/v3/login
    pub fn login(self: *MatrixHttpClient, username: []const u8, password: []const u8) HttpError!json_types.LoginResponse {
        var buf: [1024]u8 = undefined;
        const body = std.fmt.bufPrint(&buf, "{{\"type\":\"m.login.password\",\"user\":\"{s}\",\"password\":\"{s}\"}}", .{ username, password }) catch return HttpError.OutOfMemory;
        return self.requestJson(json_types.LoginResponse, "POST", "/_matrix/client/v3/login", body);
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

        return self.curlRequest("GET", path, null);
    }

    /// POST receipt
    pub fn sendReadReceipt(self: *MatrixHttpClient, room_id: []const u8, event_id: []const u8) HttpError!void {
        var path_buf: [512]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buf, "/_matrix/client/v3/rooms/{s}/receipt/m.read/{s}", .{ room_id, event_id }) catch return HttpError.OutOfMemory;
        const resp = try self.curlRequest("POST", path, "{}");
        self.allocator.free(resp.body);
    }

    fn requestJson(self: *MatrixHttpClient, comptime T: type, method: []const u8, path: []const u8, body: ?[]const u8) HttpError!T {
        const resp = try self.curlRequest(method, path, body);
        defer self.allocator.free(resp.body);

        const parsed = std.json.parseFromSlice(T, self.allocator, resp.body, .{ .ignore_unknown_fields = true }) catch return HttpError.JsonParseFailed;
        defer parsed.deinit();
        return parsed.value;
    }

    fn curlRequest(self: *MatrixHttpClient, method: []const u8, path: []const u8, body: ?[]const u8) HttpError!RawResponse {
        // Build URL
        var url_buf: [1024]u8 = undefined;
        const url = std.fmt.bufPrint(&url_buf, "{s}{s}", .{ self.base_url, path }) catch return HttpError.OutOfMemory;

        const curl_handle = c.curl_easy_init() orelse return HttpError.CurlInitFailed;
        defer c.curl_easy_cleanup(curl_handle);

        // URL (null-terminate)
        var url_z: [1025]u8 = undefined;
        if (url.len >= url_z.len) return HttpError.OutOfMemory;
        @memcpy(url_z[0..url.len], url);
        url_z[url.len] = 0;
        _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_URL, @as([*:0]const u8, url_z[0..url.len :0]));

        // Method
        if (std.mem.eql(u8, method, "POST")) {
            _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_POST, @as(c_long, 1));
        } else if (std.mem.eql(u8, method, "PUT")) {
            _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_CUSTOMREQUEST, @as([*:0]const u8, "PUT"));
        }

        // Body
        if (body) |b| {
            _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_POSTFIELDS, @as([*]const u8, b.ptr));
            _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_POSTFIELDSIZE, @as(c_long, @intCast(b.len)));
        }

        // Headers
        var headers: ?*c.curl_slist = null;
        headers = c.curl_slist_append(headers, "Content-Type: application/json");
        if (self.access_token) |token| {
            var auth_buf: [512]u8 = undefined;
            const auth = std.fmt.bufPrint(&auth_buf, "Authorization: Bearer {s}", .{token}) catch return HttpError.OutOfMemory;
            var auth_z: [513]u8 = undefined;
            @memcpy(auth_z[0..auth.len], auth);
            auth_z[auth.len] = 0;
            headers = c.curl_slist_append(headers, @as([*:0]const u8, auth_z[0..auth.len :0]));
        }
        _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_HTTPHEADER, headers);
        defer c.curl_slist_free_all(headers);

        // Response body callback
        var response_data = ResponseData{ .allocator = self.allocator };
        _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_WRITEFUNCTION, writeCallback);
        _ = c.curl_easy_setopt(curl_handle, c.CURLOPT_WRITEDATA, @as(*anyopaque, @ptrCast(&response_data)));

        // Perform
        const res = c.curl_easy_perform(curl_handle);
        if (res != c.CURLE_OK) {
            if (response_data.buf) |b| self.allocator.free(b);
            return HttpError.ConnectionFailed;
        }

        // Check HTTP status
        var http_code: c_long = 0;
        _ = c.curl_easy_getinfo(curl_handle, c.CURLINFO_RESPONSE_CODE, &http_code);
        if (http_code != 200) {
            if (response_data.buf) |b| self.allocator.free(b);
            return HttpError.MatrixError;
        }

        const result_body = if (response_data.buf) |b| b[0..response_data.len] else "";

        return .{ .body = result_body, .allocator = self.allocator };
    }
};

const ResponseData = struct {
    allocator: std.mem.Allocator,
    buf: ?[]u8 = null,
    len: usize = 0,
    capacity: usize = 0,
};

fn writeCallback(contents: [*]const u8, size: usize, nmemb: usize, userdata: *anyopaque) callconv(.c) usize {
    const total = size * nmemb;
    const data: *ResponseData = @ptrCast(@alignCast(userdata));

    // Grow buffer if needed
    const needed = data.len + total;
    if (needed > data.capacity) {
        const new_cap = @max(needed * 2, 4096);
        if (data.buf) |old| {
            const new_buf = data.allocator.realloc(old[0..data.capacity], new_cap) catch return 0;
            data.buf = new_buf;
            data.capacity = new_cap;
        } else {
            const new_buf = data.allocator.alloc(u8, new_cap) catch return 0;
            data.buf = new_buf;
            data.capacity = new_cap;
        }
    }

    @memcpy(data.buf.?[data.len .. data.len + total], contents[0..total]);
    data.len += total;
    return total;
}
