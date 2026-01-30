## 2024-05-22 - ByteArrayOutputStream.toByteArray() in Hot Loop
**Learning:** Calling `ByteArrayOutputStream.toByteArray()` inside a high-frequency loop (e.g., audio recording callback) creates massive GC pressure (O(N^2) allocations).
**Action:** Use a rolling buffer (shift-and-append) with `System.arraycopy` to maintain the required window of data without re-allocating the entire stream history.
