## 2025-10-27 - Avoid ByteArrayOutputStream.toByteArray() in Hot Loops
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a high-frequency loop (like audio processing) creates a new copy of the *entire* growing buffer on every iteration, leading to O(N^2) complexity. In the `Recorder.java` loop, this caused significant overhead.
**Action:** Use `System.arraycopy` to copy directly from the source buffer (e.g., `audioData`) when the data is available there, avoiding the full buffer copy.
