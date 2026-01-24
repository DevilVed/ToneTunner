## 2026-01-24 - Avoiding O(N^2) in Recording Loops
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a hot loop (like audio recording) creates an O(N^2) performance bottleneck because it allocates and copies the entire growing buffer on every iteration.
**Action:** Use a fixed-size sliding window buffer (shift-and-append) for processing the most recent data (like VAD), while only appending to the main storage buffer.
