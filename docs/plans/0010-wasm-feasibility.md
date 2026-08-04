# 0009 — wata-server on wasm: feasibility spike

Status: done

`[WASM-HOST]`

## Problem

A hosted tier (one tiny server per family) could run on wasm platforms
(Fermyon Spin, Cloudflare Durable Objects) instead of a VPS. Before
that is worth designing, one question: does the emitted Go for
wata-server compile and run as wasm at all, and what exactly is the
native edge?

## What the spike did

Build-and-run probe only, in a throwaway worktree; no product code
changed. System Go 1.26.5 against the pinned toolchain's emitted tree
(`go.mod` floor 1.26.3, comfortably past the 1.21 `wasip1` minimum).
The emitted module is self-contained (`json` and core are compiled in),
so the wasm build needs no dep resolution.

## Findings

**Compiles clean, runs except the listener.**

- `GOOS=wasip1 GOARCH=wasm go build .` → zero errors, 11 MB.
  `GOOS=js` likewise; `-buildmode=c-shared` (the resident-instance
  shape a Spin/DO host wants, with `go:wasmexport`) also links.
- Under a minimal wazero WASI host: `selfcheck` runs to completion;
  boot + journal replay + DM migration + route registration all work.
  Driving the real mux with in-memory requests: `/versions` 200,
  `/login` returns a real token (crypto/rand works), unauthenticated
  routes 401 correctly, and journal appends land through a WASI
  filesystem mount (`persist` is plain `os.OpenFile` append —
  WASI-compatible as-is).
- `wata-server :8008` under WASI deadlocks in `net.(*fakeNetFD).accept`:
  Go's `wasip1` port has no real sockets. This is the entire blocker.
- No cgo, no `syscall` use; the only `unsafe` (zero-copy string↔bytes
  in the json module) is portable.
- Long-poll `/sync` (chan waiters + `time.After`) works on wasip1's
  single-threaded scheduler; the store is one mutex anyway.

**The seam**: everything through `Server.registerRoutes(mux, h)` in
`server.scala` is host-agnostic; only the tail of `Server.serve`
(`newServer()`/`listenAndServe()`) is native. A wasm host adapter keeps
boot + mux and calls `mux.ServeHTTP` per host request. The one product
change a wasm tier would ever need is splitting `Server.serve` into
boot-and-build-mux vs. listen — a few lines, deferred until wanted.

## Decision

The hosted tier, when it happens, starts on a plain VPS
(process-per-family; no new code). Wasm is a proven, parked option:
Spin (wasip1 + inbound-HTTP trigger, FS mount for the journal) is the
natural first target; Durable Objects would additionally need the
journal shimmed onto DO storage.

## Out of scope

- Building the host adapter or the `Server.serve` split.
- Platform choice, tenancy routing, and everything else hosted-tier —
  no hosted work is scheduled.
