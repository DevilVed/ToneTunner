## 2024-05-22 - [ByteArrayOutputStream in Hot Loops]
**Learning:** `ByteArrayOutputStream.toByteArray()` performs a full copy of the underlying buffer. Using this method inside a recording loop to inspect only the latest data results in O(N^2) copying behavior, causing massive GC pressure and latency as the recording grows.
**Action:** In hot data processing loops, always access the raw input buffer (e.g., `audioData`) or use stream methods that don't require full copying (like `System.arraycopy` on the buffer) for transient checks like VAD.
