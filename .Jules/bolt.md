## 2026-01-15 - O(N^2) Memory Allocation in Audio Loop
**Learning:** `ByteArrayOutputStream.toByteArray()` allocates a new array and copies data every time it's called. Calling this inside a high-frequency loop (like audio recording) creates O(N^2) memory pressure, leading to GC churn and potential UI stutter.
**Action:** Use a sliding window or ring buffer, or access the input buffer directly if the chunk size matches the required window, avoiding full stream conversion until the end.
