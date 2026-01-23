# Bolt's Journal

## 2024-12-27 - ByteArrayOutputStream Overhead
**Learning:** `ByteArrayOutputStream.toByteArray()` creates a copy of the entire buffer. Using it inside a high-frequency loop (like audio processing) creates O(N^2) memory allocation pressure, causing massive GC overhead.
**Action:** Use a manual sliding window or ring buffer to access recent data without copying the entire history.
