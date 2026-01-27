## 2024-05-22 - [Optimizing Hot Loops in Java]
**Learning:** Simple arithmetic optimizations (multiplication vs division) and local variable caching in hot loops (`RecordBuffer.getSamples`) yielded a ~40% speedup (1.4x).
**Action:** Always capture volatile or synchronized static fields into local variables before iterating over them. Prefer multiplication by inverse for constant scaling factors in loops.
