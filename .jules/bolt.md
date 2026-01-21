# Bolt's Journal

## 2025-02-18 - Avoid toByteArray() in Hot Loops
**Learning:** `ByteArrayOutputStream.toByteArray()` creates a full copy of the buffer. In a recording loop, calling this every frame (30ms) results in O(N^2) memory copying, which kills performance as the buffer grows.
**Action:** Use intermediate buffers or peeking strategies to avoid full buffer duplication when only the latest data is needed. Direct `System.arraycopy` from the input buffer is >200x faster.
