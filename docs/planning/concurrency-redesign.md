# Concurrency Redesign: Mailbox-based Actor Model

## Status: Draft

## Problem

When wata-fb runs with network threads active, the device experiences hard crashes
(spontaneous reboots). Disconnecting from the network eliminates the crashes.
Root cause analysis identified a critical memory leak plus several structural
concurrency issues.

---

## Root Cause: Snapshot Arena Leak

Every sync cycle allocates a new `ArenaAllocator`, builds a `StateSnapshot` in it,
and publishes a pointer via `StateStore`. But:

1. **`StateStore.publish()` overwrites unconsumed snapshots** — if the UI thread
   hasn't called `acquire()` before the next publish, the old arena is silently
   dropped (pointer overwritten, no free).

2. **The UI thread never frees old snapshots** — `main.zig:185` replaces
   `current_snapshot` without freeing the previous one's arena. The arena handle
   isn't even available to the UI thread.

3. **The arena handle is lost** — it's a stack local in the sync loop
   (`sync_thread.zig:220`). After `publish()`, there's no reference to it anywhere.

**Effect**: Each sync cycle leaks an arena containing all contacts, conversations,
messages, and string data. On a memory-constrained device, this causes OOM within
minutes.

---

## Secondary Issues

### Single `should_stop` flag

All threads share one `std.atomic.Value(bool)`. Consequences:

- Settings "disconnect" (`settings.zig:81`) sets `should_stop = true`, which stops
  the audio thread too (not just network).
- Sync thread's defer block (`sync_thread.zig:144`) also sets `should_stop = true`
  on any exit, killing audio along with network.

### Sleep-polling

- Action thread: `sleepMs(ctx.io, 50)` busy-loop (`sync_thread.zig:275`)
- Audio thread: `nanosleep 10ms` busy-loop (`audio_thread.zig:66`)

These waste CPU, add latency (up to 50ms for actions, 10ms for audio), and on a
battery-powered device, drain power needlessly.

### Echo test fire-and-forget

`settings.zig:257` spawns a thread with `_ = std.Thread.spawn(...)` — the handle
is discarded. The thread writes to `s.echo` (shared `State` struct) without any
synchronization. If the settings applet is deinitialized while the echo test runs,
this is use-after-free.

### HTTP client churn

`http.zig:224` creates a new `std.http.Client` for every single HTTP request.
No connection reuse, high allocation pressure. The sync thread does login + sync +
backfill + join, each creating/destroying a client.

### Raw pointer in Action union

`upload_and_send_voice.ogg_data` is `[*]const u8` — a raw pointer that crosses
from audio thread → UI thread → action thread with a separate `ogg_len` field.
No ownership semantics, no way to free on drop if the action is never consumed.

---

## Proposed Design

### 1. Mailbox: Blocking Bounded Queue

Replace `BoundedQueue` + sleep-polling with a proper blocking mailbox.
Uses `std.Thread.Mutex` + `std.Thread.Condition` (POSIX-backed, available since
Zig 0.13+).

```zig
/// Bounded MPSC mailbox with blocking receive.
/// Replaces BoundedQueue + sleep-polling for threads that can block.
pub fn Mailbox(comptime T: type, comptime capacity: usize) type {
    return struct {
        const Self = @This();

        ring: [capacity]T = undefined,
        head: usize = 0,   // next write position
        tail: usize = 0,   // next read position
        mu: std.Thread.Mutex = .{},
        not_empty: std.Thread.Condition = .{},
        closed: bool = false,

        /// Blocking receive. Returns null when mailbox is closed and empty.
        pub fn receive(self: *Self) ?T {
            self.mu.lock();
            defer self.mu.unlock();
            while (self.head == self.tail and !self.closed) {
                self.not_empty.wait(&self.mu);
            }
            if (self.head == self.tail) return null; // closed + empty
            const item = self.ring[self.tail];
            self.tail = (self.tail + 1) % capacity;
            return item;
        }

        /// Non-blocking try (for UI thread polling loop).
        pub fn tryReceive(self: *Self) ?T {
            self.mu.lock();
            defer self.mu.unlock();
            if (self.head == self.tail) return null;
            const item = self.ring[self.tail];
            self.tail = (self.tail + 1) % capacity;
            return item;
        }

        /// Send an item. Returns false if full (caller decides: drop or block).
        pub fn send(self: *Self, item: T) bool {
            self.mu.lock();
            defer self.mu.unlock();
            const next = (self.head + 1) % capacity;
            if (next == self.tail) return false; // full
            self.ring[self.head] = item;
            self.head = next;
            self.not_empty.signal();
            return true;
        }

        /// Close the mailbox. Wakes all blocked receivers.
        /// After close, receive() drains remaining items then returns null.
        pub fn close(self: *Self) void {
            self.mu.lock();
            defer self.mu.unlock();
            self.closed = true;
            self.not_empty.broadcast();
        }
    };
}
```

**Key properties:**
- `receive()` blocks without burning CPU — thread sleeps on condition variable
- `close()` provides clean shutdown — no need for external `should_stop` flag
- `tryReceive()` for the UI thread which must not block (it runs the render loop)
- Same `send()` API as before — drop-in replacement for producers

### 2. OwnedSnapshot: Fix the Arena Leak

Bundle the arena with the snapshot so ownership can be tracked and transferred:

```zig
pub const OwnedSnapshot = struct {
    snapshot: StateSnapshot,
    arena: std.heap.ArenaAllocator,
    alloc: std.mem.Allocator, // parent allocator (for freeing this struct)

    /// Release the snapshot and all its arena-allocated data.
    pub fn release(self: *OwnedSnapshot) void {
        const a = self.alloc;
        self.arena.deinit();  // free all snapshot data (contacts, messages, strings)
        a.destroy(self);       // free this wrapper struct
    }
};
```

Updated `StateStore` with proper lifecycle:

```zig
pub const StateStore = struct {
    current: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

    /// Publish a new snapshot. Frees any unconsumed previous snapshot.
    pub fn publish(self: *StateStore, owned: *OwnedSnapshot) void {
        const old = self.current.swap(@intFromPtr(owned), .acq_rel);
        if (old != 0) {
            // Previous snapshot was never consumed by UI — free it
            var old_snap: *OwnedSnapshot = @ptrFromInt(old);
            old_snap.release();
        }
    }

    /// Acquire the latest snapshot. Caller owns it and must call release().
    pub fn acquire(self: *StateStore) ?*OwnedSnapshot {
        const ptr = self.current.swap(0, .acquire);
        if (ptr == 0) return null;
        return @ptrFromInt(ptr);
    }

    /// Clean up on shutdown (free any unconsumed snapshot).
    pub fn deinit(self: *StateStore) void {
        const ptr = self.current.swap(0, .monotonic);
        if (ptr != 0) {
            var snap: *OwnedSnapshot = @ptrFromInt(ptr);
            snap.release();
        }
    }
};
```

Sync thread publishing (updated):

```zig
// Build and publish snapshot
var snapshot_arena = std.heap.ArenaAllocator.init(allocator);
if (processor.buildSnapshot(snapshot_arena.allocator())) |snapshot| {
    const owned = allocator.create(OwnedSnapshot) catch {
        snapshot_arena.deinit();
        continue;
    };
    owned.* = .{
        .snapshot = snapshot,
        .arena = snapshot_arena,
        .alloc = allocator,
    };
    ctx.state_store.publish(owned); // old unconsumed snapshot freed automatically
    _ = ctx.ui_queue.send(.snapshot_ready);
} else |_| {
    snapshot_arena.deinit();
}
```

UI thread consuming (updated):

```zig
var current_owned: ?*OwnedSnapshot = null;
defer if (current_owned) |o| o.release();

// In the loop:
if (state_store.acquire()) |new_owned| {
    if (current_owned) |old| old.release(); // free previous
    current_owned = new_owned;
    current_snapshot = &new_owned.snapshot;
    sh.updateContext(&new_owned.snapshot);
}
```

### 3. Per-Subsystem Lifecycle (Structured Shutdown)

Replace the single `should_stop` with per-mailbox close semantics:

```
main thread
├── action_mailbox ──→ Action thread (blocks on receive)
│     └── ui_mailbox ←── (sends events back)
├── audio_cmd_mailbox ──→ Audio thread (blocks on receive)
│     └── audio_evt_mailbox ←── (sends events back)
├── state_store ←── Sync thread (publishes snapshots)
│     └── ui_mailbox ←── (sends connection state)
└── sync_stop: atomic(bool) ──→ Sync thread (checked between long-polls)
```

**Shutdown sequence** (main thread defer):
```zig
defer {
    // 1. Stop sync thread (can't use mailbox — it blocks on HTTP, not receive)
    sync_stop.store(true, .release);

    // 2. Close action mailbox — action thread wakes from receive(), sees null, exits
    action_mailbox.close();

    // 3. Close audio mailbox — audio thread wakes from receive(), sees null, exits
    audio_cmd_mailbox.close();

    // 4. Join all threads (they've already exited due to close)
    if (action_handle) |h| h.join();
    if (audio_handle) |h| h.join();
    if (sync_handle) |h| h.join();

    // 5. Clean up state store
    state_store.deinit();
}
```

**Disconnect (settings)** — stops only network, keeps audio:
```zig
.disconnect => {
    sync_stop.store(true, .release);
    action_mailbox.close();
    // Audio thread keeps running — can still record/echo
}
```

### 4. Spawn Action Thread from Main (not Sync Thread)

Currently the action thread is spawned inside `syncThreadMainInner` after login.
This creates a nested lifecycle that's hard to reason about. Move it to main:

```zig
// main.zig — spawn all threads at top level
const sync_handle = std.Thread.spawn(.{}, syncThreadMain, .{&sync_ctx});
const action_handle = std.Thread.spawn(.{}, actionThreadMain, .{&action_ctx});
const audio_handle = std.Thread.spawn(.{}, audioThreadMain, .{&audio_ctx});
```

The action thread waits for the access token via a one-shot channel (or a
`std.atomic.Value(usize)` that the sync thread sets after login):

```zig
// Action thread: wait for auth before processing actions
while (true) {
    if (auth_token.load(.acquire) != 0) break; // token published by sync thread
    if (action_mailbox.receive() == null) return; // closed = shutdown
}
// Now process actions with the token
```

Benefits:
- All threads spawned/joined at one level — structured concurrency
- Sync thread crash doesn't silently kill the action thread
- Main thread controls all lifecycle

### 5. Echo Test: Use Audio Thread

Instead of spawning an ad-hoc thread, send an `echo_test` command through the
audio command mailbox:

```zig
// In Command union:
echo_test,

// In audioThreadMain:
.echo_test => doEchoTest(ctx),
```

This eliminates the fire-and-forget thread and the race on `State.echo`. The
audio thread sends back events (`echo_recording`, `echo_playing`, `echo_done`,
`echo_error`) through the audio event queue, and the settings applet updates
`s.echo` from those events in the UI thread (single-threaded, no race).

### 6. Reuse HTTP Client

Create one `std.http.Client` per thread instead of per-request:

```zig
// In sync thread:
var http_client: std.http.Client = .{ .allocator = allocator, .io = io };
defer http_client.deinit();
// Pass to MatrixHttpClient, reuse for all requests
```

This reduces allocation churn and enables TCP connection reuse.

---

## Migration Path

Incremental steps, each independently valuable and testable:

### Step 1: Fix the arena leak (critical — fixes crashes)
- Add `OwnedSnapshot` struct
- Update `StateStore` to swap-and-free
- Update UI thread to release old snapshots
- **Test**: Run with network for 30+ minutes, monitor RSS

### Step 2: Add `Mailbox` primitive
- Implement `Mailbox` alongside existing `BoundedQueue`
- Unit tests for blocking receive, close semantics
- **Test**: Unit tests pass

### Step 3: Migrate action thread to Mailbox
- Replace `action_queue: BoundedQueue` with `Mailbox`
- Remove 50ms sleep-poll in action thread
- Shutdown via `close()` instead of `should_stop`
- **Test**: Send voice messages, disconnect, verify clean shutdown

### Step 4: Migrate audio thread to Mailbox
- Replace `audio_cmd_queue: BoundedQueue` with `Mailbox`
- Remove 10ms sleep-poll in audio thread
- Route echo test through audio command mailbox
- **Test**: Record, playback, echo test, disconnect (audio still works)

### Step 5: Separate stop signals
- `sync_stop: atomic(bool)` for sync thread only
- Action/audio use mailbox close for shutdown
- Settings disconnect only stops sync + action, not audio
- **Test**: Disconnect → audio echo still works

### Step 6: Flatten thread hierarchy
- Spawn action thread from main instead of sync thread
- Add auth-ready signal (atomic or one-shot channel)
- **Test**: Full flow — login, sync, send, disconnect, reconnect-restart

### Step 7: HTTP client reuse
- One `std.http.Client` per thread, reused across requests
- **Test**: Extended run, check memory stability

---

## Compatibility Notes

- `BoundedQueue` stays for the `ui_queue` — the UI thread uses `tryReceive()`
  (non-blocking) since it must not block the render loop. Mailbox's `tryReceive()`
  works identically, so ui_queue can optionally be migrated too (just won't use
  blocking receive). Keep `BoundedQueue` initially to minimize blast radius.

- The `Action` union's `ogg_data: [*]const u8` raw pointer is a known wart but
  not a crash risk currently (the data outlives the action). Can be cleaned up
  later by bundling allocator + len into the union field.

- `StateSnapshot` and `SyncProcessor` internals are unchanged. The redesign
  only affects the plumbing between threads, not the domain logic.
