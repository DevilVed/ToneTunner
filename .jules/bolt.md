## 2024-12-27 - O(N^2) Array Copying in Loops
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a high-frequency loop (like audio recording) creates a new array copy of the *entire* stream every iteration, leading to O(N^2) complexity and massive GC pressure.
**Action:** Use a circular buffer or shift-and-append strategy with a fixed-size array to maintain only the necessary window of data (O(1) per iteration).
