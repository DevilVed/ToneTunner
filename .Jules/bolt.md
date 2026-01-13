## 2024-05-23 - Exponential Memory Allocation in Audio Loop
**Learning:** `ByteArrayOutputStream.toByteArray()` inside a hot loop (like audio recording) creates a copy of the *entire* buffer each iteration. This leads to O(N²) memory copying and GC pressure as the recording grows.
**Action:** Always check if the current chunk contains the data needed (e.g., tail) and use it directly. Only fallback to expensive copies for edge cases (e.g., partial reads).
