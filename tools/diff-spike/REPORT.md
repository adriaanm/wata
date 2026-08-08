# diff-spike — does `Diff.diff` retain the trees it walks? (DIFF-RETAINS-REPRO)

**Result: no. Every arm is flat or bounded; a bare loop over `Diff.diff`
does not leak.** The wata-mac idle leak (MAC-IDLE-LEAK) therefore needs
the app around it — the retaining edge is not below `wataui/diff.scala`,
and this does not become a sgola ticket.

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

The leak needs something the app adds. The deltas between this loop and
the pump's leaking bisect arm are the suspect list:

- `st.last` REPLACED each frame (the spike's long-lived `old` is fixed;
  the app's old is last frame's new, so the retained edge could be the
  chain old→…→new if something links successive trees),
- the real trees: bigger, deeper, `VImage`/`Bytes` leaves, strings out of
  the client snapshot rather than constants,
- whether the emitted pump code lets the patch list escape (this loop's
  scripts provably die),
- the pump's goroutine/closure structure around the call.

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
just diff-spike        # build + all four arms
just diff-spike b      # one arm
```
