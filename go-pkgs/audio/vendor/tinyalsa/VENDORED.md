# Vendored tinyalsa — pinned, deliberately not upstreamed

This tree is tinyalsa with one local patch (grep `SGOLA PATCH` in
`src/pcm.c`): on non-MMAP handles, `pcm_sync_ptr` must GET the kernel's
pointers, never push stale application state back — pushing races the
driver's own updates and produced the long-standing replay/stutter bug
(both the Zig client and this port hit it; wata-fb's audio thread was
the second independent reproduction).

Decision (owner, 2026-08-05): **keep it vendored; no upstream PR.**
The patch is kernel-semantics-correct for our use, but upstream
acceptance is doubtful (the maintained surface leans MMAP-first, our
pull-mode path is a minority configuration, and arguing the case
publicly is effort this project doesn't owe). The cost accepted with
this ruling: tracking upstream tinyalsa means re-applying one
well-commented patch, and this file is the record of why it exists.

If the patch ever stops applying cleanly, the two `SGOLA PATCH`
comments in `src/pcm.c` carry the invariant to preserve; the analysis
lives in the wata M8 notes referenced there.
