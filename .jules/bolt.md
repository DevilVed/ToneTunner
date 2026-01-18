## 2024-05-24 - [Avoid ByteArrayOutputStream in hot loops]
**Learning:** `ByteArrayOutputStream.toByteArray()` creates a copy of the entire buffer. Calling this inside a tight loop (like audio processing) leads to O(N^2) memory copying, which is disastrous for performance.
**Action:** Use `System.arraycopy` from the source buffer directly when possible, or maintain a circular buffer if history is needed. Avoid `toByteArray()` in critical paths.
