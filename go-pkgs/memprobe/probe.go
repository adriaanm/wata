// The one measurement DIFF-RETAINS-REPRO is judged by: the LIVE heap after an
// explicit collection. RSS is a sawtooth the scavenger drives and reads as
// "steady" while the heap climbs — HeapAlloc after runtime.GC() counts exactly
// the bytes still reachable, so a leak is a straight line and a bounded
// working set plateaus.
package memprobe

import "runtime"

// LiveHeap forces a full GC and returns HeapAlloc: bytes of live (reachable)
// heap objects. Calling it IS the measurement barrier — nothing collectable
// survives into the number.
func LiveHeap() int64 {
	runtime.GC()
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	return int64(ms.HeapAlloc)
}
