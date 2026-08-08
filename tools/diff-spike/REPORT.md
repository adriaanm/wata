# diff-spike — does `Diff.diff` retain the trees it walks? (DIFF-RETAINS-REPRO)

**Result: no. Every arm is flat or bounded; a bare loop over `Diff.diff`
does not leak.** The wata-mac idle leak (MAC-IDLE-LEAK) therefore needed
the app around it — the retaining edge is not below `wataui/diff.scala`.

**Postscript — the leak was found anyway, and this spike's clean bill of
health is part of the finding.** The retainer is sgola's `slab List`
allocator (`SLAB-DEAD-CELLS-RETAIN`, filed): a slab is one GC object, so
dead cells' un-zeroed pointer fields are scanned as long as any cell in
the slab is live, and in the app the diff's transient mirror cells chain
each frame's tree to the previous one. This spike runs the SAME slab
allocator and stays flat — six arms, including the pump's exact shapes —
so a tight single-goroutine loop demonstrably does not produce the
interleaving the chain needs. That is why the arms below stay useful:
they prove any future "the differ leaks" reading is about the
allocator's surroundings, and they are the wrong tool to verify the slab
fix (use `just mac-leak --arm diffonly` for that).

## The question

wata-mac leaks ~200 MB/hour while idle, and the pump bisect landed on the
diff call: with `Diff.diff` running — nothing encoded, nothing handed to
the backend — the live heap climbs in a straight line (1 2 4 6 8 … 34 MB
over 39 GCs), and the heap profile shows the retained objects are the
VIEW nodes, not the patches. If diffing a tree is what makes that tree
stay reachable, a loop with no app around it must reproduce it.

## Method

The measurement is `runtime.ReadMemStats().HeapAlloc` read AFTER an
explicit `runtime.GC()` (`go-pkgs/memprobe`, the spike's one facade) —
never RSS, whose scavenger-driven sawtooth is what made the app leak read
as "steady" for a whole session. 100k iterations per arm, sampled every
5k; each arm runs in its own process so no arm's residue contaminates
another's series.

The trees are app-shaped: a `VGroup` of 10 KEYED rows (keys make the
child scan take the keyed path), each row a group of a highlight `VRect`
and a `VText`, with row 0's text differing between old and new so the
script is non-empty (1 `PSet`) — an empty diff may take a path that never
walks the nodes, a clean false negative. A sanity line asserts the
non-empty script on every run.

## The four arms, and what they read

| arm | shape | series (KB, live heap after GC) |
|---|---|---|
| a | build two trees per iter, NO diff (control) | 209 181 … 192 — **flat** |
| b | fresh new vs long-lived old, script held one iter (the app's shape) | ~350–450, spikes to 18 MB that vanish by the next sample |
| c | both trees long-lived, nothing fresh walked | ~300–500, one-sample spikes, ends 504 |
| d | as b, script dropped immediately | ~300–470, one-sample spikes |
| e | old = last iteration's new (the pump's REAL `st.last` shape) | ~270–460, one-sample spikes, ends 271 |

Arm e chains the trees the way the pump actually does — every tree is
first the fresh argument, then the long-lived one — and it is as flat as
the rest. A `big` second argument (`diff-spike e big`) grows the trees
20x to 200 rows (~40 KB); arms b and e stay bounded there too (b ends
788 KB, e ends 862 KB — proportional to the tree, not to the iteration
count).

The one-sample spikes are measurement noise, not retention: a re-run of
arm b moved them to different iterations and shrank them (max 1 MB
instead of 18 MB) — floating garbage racing the forced GC in a tight
allocation loop. What matters is the envelope: if `Diff.diff` retained
what it walked, 100k iterations of a ~2 KB tree would read ~200 MB by the
end; every arm ends under 0.6 MB. No arm climbs.

Diffing does carry a bounded working set the control doesn't (~350 KB vs
~190 KB — the walk's own allocation, alive at the moment the probe runs),
but it is a plateau, not a line.

## What this means for MAC-IDLE-LEAK

The leak needs something the app adds. Two suspects are already
eliminated by arms this spike grew while it was open: the `st.last`
replacement chain (arm e — flat) and plain tree size (`big` — bounded,
proportional to the tree). What remains of the delta between this loop
and the pump's leaking bisect arm:

- the real trees' CONTENT: `VImage`/`Bytes` leaves, strings out of the
  client snapshot rather than constants (this spike's leaves are
  constants and small built strings),
- whether the emitted pump code lets the patch list escape (this loop's
  scripts provably die),
- the pump's goroutine/closure structure around the call — the diff runs
  inside a closure the frame loop re-enters, not a flat `while`.

## Found en route: a sgola emitter bug (filed)

`==` between two `View` values emits an `equalsList` helper whose type
switch names the cons class unmangled — `case *:::` / `b.(*::)` — which
is not Go and fails the build. Unexercised elsewhere: wataui's oracle
compares views via the hand-written `Views.eqView` (which exists for a
semantic reason — `VImage`'s `Bytes` identity), so no shipping code hits
the path. Filed as `EQUALS-LIST-EMIT-BROKEN-CONS`; the spike's
workaround (`rootLen` instead of `==`) names the key.

## Running it

```
just diff-spike        # build + all five arms
just diff-spike b      # one arm
just diff-spike e big  # one arm, 200-row trees
```
