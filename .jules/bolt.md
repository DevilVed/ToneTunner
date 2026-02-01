## 2026-02-01 - ByteArrayOutputStream.toByteArray() in Hot Loops
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a recording loop (running every ~30ms) causes O(N^2) memory copying, leading to massive performance degradation as the buffer grows (up to 1MB).
**Action:** Use a fixed-size circular buffer or shift-and-append strategy to maintain only the necessary window of data (e.g., for VAD) without copying the entire history.
