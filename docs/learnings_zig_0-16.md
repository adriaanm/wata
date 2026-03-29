# Zig 0.16-dev Learnings

Collected while building the wata framebuffer client on `0.16.0-dev.2984+cb7d2b056` (2026-03-24).

## The Big Picture: std.Io

0.16 replaces `std.io` with `std.Io` — a concrete, non-generic I/O interface that every blocking operation must receive, like `Allocator` for memory. Two backends:

- **`std.Io.Threaded`** — synchronous/blocking (thread pool). This is what you use today.
- **`std.Io.Evented`** — async (io_uring on Linux, GCD on macOS). Experimental.

Both produce an `std.Io` value; application code doesn't care which backend.

```zig
var threaded: std.Io.Threaded = .init(gpa, .{ .environ = init.environ });
defer threaded.deinit();
const io = threaded.io();
```

**Impact:** Every API that does I/O (HTTP, filesystem, sleep, random, stdout) now takes `io` as a parameter. This is the single biggest breaking change in 0.16.

## Main Function Signature

Three valid forms:

```zig
// 1. No args (our current approach — works but no access to io/argv/environ):
pub fn main() !void { }

// 2. Minimal (raw argv + environ, no allocators):
pub fn main(init: std.process.Init.Minimal) !void {
    var args = init.args.iterate();
}

// 3. Full (allocators, Io, environ map, arena — the "right" way):
pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const alloc = init.gpa;
}
```

**Our workaround:** We use form 1 with `DebugAllocator` created manually and `std.c.*` for I/O. To adopt `std.Io` properly, switch to form 3 and thread `init.io` through to `std.http.Client`.

## std.http.Client

Now requires `io` field:

```zig
var client: std.http.Client = .{ .allocator = allocator, .io = io };
```

High-level API is `fetch()`:

```zig
const result = try client.fetch(.{
    .location = .{ .url = url },
    .method = .GET,
    .payload = body,           // []const u8 for POST body
    .response_writer = &writer, // *std.Io.Writer — receives response body
    .extra_headers = &.{...},
});
// result.status is the HTTP status
```

**Gotcha:** If `response_writer` is null, the response body is **discarded**. You must provide a writer. The writer type is `std.Io.Writer`, not the old `std.io.Writer`. Creating one that writes to an ArrayList requires `std.Io.Writer.Allocating`.

**Our workaround:** We use libcurl via `@cImport` instead. The std.http.Client API is correct but requires threading `std.Io` through the entire call chain, which we haven't done yet. Libcurl is pragmatic for now.

## Removed / Moved APIs

| Old (0.15) | New (0.16-dev) | Notes |
|------------|----------------|-------|
| `std.time.nanoTimestamp()` | `std.Io.Clock.now(.real, io)` | Requires Io |
| `std.time.sleep(ns)` | `io.sleep(.fromNanoseconds(ns), .awake)` | Requires Io |
| `std.io.getStdErr()` | `std.Io.File.stderr()` | Returns Io.File, not fs.File |
| `std.io.getStdOut()` | `std.Io.File.stdout()` | Same |
| `std.process.args()` | `init.args.iterate()` or `init.minimal.args` | Via main param |
| `std.os.argv` | removed | Via main param |
| `std.os.environ` | removed | Via main param |
| `std.crypto.random` | `std.Io.randomSecure(io)` | Requires Io |
| `std.Thread.Pool` | removed | Use `std.Io` concurrency |
| `std.Thread.Mutex` | `std.Io.Mutex` (lock/unlock take Io) | Or `std.atomic.Mutex` for spinlock |
| `std.fs.File` | `std.Io.File` | |
| `std.fs.Dir` | `std.Io.Dir` | |

## Our C Workarounds (when std.Io isn't threaded through)

```zig
// Sleep (instead of std.time.sleep):
const c = @cImport(@cInclude("time.h"));
const ts = c.struct_timespec{ .tv_sec = secs, .tv_nsec = nanos };
_ = c.nanosleep(&ts, null);

// Write to stderr (instead of std.io.getStdErr):
_ = std.c.write(2, msg.ptr, msg.len);

// Get wall clock time (instead of std.time.timestamp):
const ct = @cImport(@cInclude("time.h"));
const epoch: i64 = @intCast(ct.time(null));
```

These work because we link libc anyway (for libcurl and SDL2).

## ArrayList (Unmanaged by Default)

`std.ArrayList(T)` is now the **unmanaged** variant. The allocator is passed to every mutating call:

```zig
var list: std.ArrayList(i32) = .{};   // or .empty
defer list.deinit(allocator);
try list.append(allocator, 42);
try list.appendSlice(allocator, &.{ 1, 2, 3 });
// Read-only access (no allocator needed):
for (list.items) |item| { ... }
```

The old managed variant (allocator stored in struct) is at `std.array_list.Managed`.

**Gotcha:** `std.ArrayListUnmanaged` still exists as an alias but `std.ArrayList` IS unmanaged now. The naming is confusing during the transition.

## HashMap / StringArrayHashMap

`std.StringArrayHashMap(V)` is still the **managed** variant (stores its allocator):

```zig
var map: std.StringArrayHashMap(V) = .init(allocator);
defer map.deinit();
try map.put(key, value);        // no allocator arg
```

`std.StringArrayHashMapUnmanaged(V)` is unmanaged:

```zig
var map: std.StringArrayHashMapUnmanaged(V) = .{};
defer map.deinit(allocator);
try map.put(allocator, key, value);
try map.getOrPut(allocator, key);
```

**Inconsistency:** ArrayList was flipped to unmanaged-by-default, but HashMap was not (yet). Use whichever matches your ownership pattern — we use Unmanaged in the sync engine since we pass the allocator explicitly everywhere.

## DebugAllocator (was GeneralPurposeAllocator)

```zig
var da: std.heap.DebugAllocator(.{}) = .{};
defer _ = da.deinit();
const allocator = da.allocator();
```

Same behavior, just renamed. The old name exists as a deprecated alias.

## Mutex

`std.Thread.Mutex` may or may not exist depending on the exact dev build. We use `std.atomic.Mutex` which is a simple spinlock enum:

```zig
push_mutex: std.atomic.Mutex = .unlocked,

// Usage:
while (!self.push_mutex.tryLock()) {}
defer self.push_mutex.unlock();
```

No blocking `lock()` method — only `tryLock()` + spin. Fine for very short critical sections (ring buffer push). For longer critical sections, use `std.Io.Mutex` which requires an Io instance.

## SDL2 @cImport on ARM macOS

Zig 0.16-dev's C translator crashes on ARM NEON intrinsics pulled in by SDL2's `SDL_cpuinfo.h`. Fix:

```zig
const sdl = @cImport({
    @cDefine("SDL_DISABLE_ARM_NEON_H", "1");
    @cInclude("SDL2/SDL.h");
});
```

Centralize this in a single `sdl.zig` file since every file that imports SDL needs the workaround.

## build.zig.zon Format

```zig
.{
    .name = .wata_fb,              // bare identifier, not string
    .version = "0.1.0",           // string, not tuple
    .fingerprint = 0x...,         // zig suggests correct value on first build
    .minimum_zig_version = "0.16.0",
    .paths = .{ "build.zig", "build.zig.zon", "src" },
}
```

## build.zig Module API

`addExecutable` now takes a `root_module` instead of `root_source_file`:

```zig
const root_mod = b.createModule(.{
    .root_source_file = b.path("src/main.zig"),
    .target = target,
    .optimize = optimize,
});
root_mod.addOptions("build_options", options);
root_mod.linkSystemLibrary("curl", .{});
root_mod.link_libc = true;

const exe = b.addExecutable(.{
    .name = "wata-fb",
    .root_module = root_mod,
});
```

## Comptime

- `comptime` keyword is redundant in const initializers (they're already comptime). Zig 0.16 errors on this:
  ```zig
  const x: [256][8]u8 = comptime blk: { ... }; // ERROR: redundant comptime
  const x: [256][8]u8 = blk: { ... };           // OK
  ```
- `@setEvalBranchQuota` is still needed for comptime loops.

## String Lifetime Pitfall: JSON Parsing

`std.json.parseFromSlice` returns a `Parsed(T)` where the value's string slices point into the parsed JSON source memory. Calling `parsed.deinit()` frees that memory, invalidating all string slices in the returned value.

**Wrong:**
```zig
fn getToken() []const u8 {
    const parsed = try std.json.parseFromSlice(T, alloc, json, .{});
    defer parsed.deinit();  // frees backing memory
    return parsed.value.token;  // DANGLING POINTER
}
```

**Right:**
```zig
fn getToken(alloc: Allocator) []const u8 {
    const parsed = try std.json.parseFromSlice(T, alloc, json, .{});
    defer parsed.deinit();
    return try alloc.dupe(u8, parsed.value.token);  // owned copy
}
```

This bit us with the Matrix login response and sync processor — any string stored beyond the parse lifetime must be duped.

## Migration Path: Current State → Proper std.Io

Our current approach uses `pub fn main() !void` with manual DebugAllocator and C workarounds for I/O. To adopt std.Io properly:

1. Switch main to `pub fn main(init: std.process.Init) !void`
2. Use `init.io` for `std.http.Client`, sleep, clock, stdout/stderr
3. Use `init.gpa` instead of manual DebugAllocator
4. Parse args via `init.minimal.args.iterate()`
5. Drop libcurl, use `std.http.Client` with `init.io`
6. Drop C `nanosleep`/`write` workarounds

This is a clean migration but touches every file. Worth doing once the 0.16 API stabilizes.

## Useful References

- [Zig's New Async I/O — Loris Cro](https://kristoff.it/blog/zig-new-async-io/)
- [0.15.1 Release Notes (I/O overhaul)](https://ziglang.org/download/0.15.1/release-notes.html)
- [Zig Devlog 2026](https://ziglang.org/devlog/2026/)
- [PR #30150: Migrate all I/O APIs to std.Io](https://codeberg.org/ziglang/zig/issues/30150)
- [PR #24698: HTTP/TLS rework for new I/O](https://github.com/ziglang/zig/pull/24698)
- [Ziggit: std.Io.Threaded environ](https://ziggit.dev/t/why-is-there-no-default-for-std-io-threaded-initoptions-environ-for-v0-16/14127)
