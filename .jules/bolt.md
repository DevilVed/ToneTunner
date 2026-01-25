# Bolt's Journal

Critical performance learnings and insights.

## 2024-05-22 - O(N^2) Array Copying in Audio Loop
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a hot recording loop creates O(N^2) memory copying overhead as the buffer grows. In a micro-benchmark, replacing this with a shift-and-append `System.arraycopy` strategy reduced execution time for 30s of audio processing from ~814ms to ~10ms (~80x speedup).
**Action:** Avoid converting growing buffers to arrays inside loops. Use sliding windows or fixed-size buffers with circular updates for real-time processing like VAD.
