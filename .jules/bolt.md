## 2024-05-22 - [O(N^2) buffer copying in recording loop]
**Learning:** `ByteArrayOutputStream.toByteArray()` creates a full copy of the stream. Calling this inside a loop that accumulates data (like audio recording) leads to quadratic performance degradation (O(N^2)).
**Action:** Use a fixed-size rolling buffer and `System.arraycopy` to maintain only the necessary window of data for processing (like VAD), avoiding full history copies.
