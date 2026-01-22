## 2024-05-22 - O(N^2) Array Copying in Hot Loop
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a hot loop (like audio recording) creates a new copy of the entire growing buffer every iteration. This leads to O(N^2) memory copying and massive GC pressure.
**Action:** Use a fixed-size circular buffer or shift-and-append strategy with `System.arraycopy` to maintain only the necessary window of data for processing (e.g., VAD), while still appending to the main stream separately.
