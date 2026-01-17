## 2024-05-23 - Avoiding ByteArrayOutputStream.toByteArray() in Hot Loops
**Learning:** Calling `ByteArrayOutputStream.toByteArray()` inside a high-frequency loop (like audio processing) creates a new byte array allocation and performs a full copy of the stream content every iteration. This leads to O(N^2) copying behavior and excessive garbage collection.
**Action:** When only the most recent chunk of data is needed, access the input buffer directly or maintain a small rolling buffer, rather than converting the entire historical stream to an array.
