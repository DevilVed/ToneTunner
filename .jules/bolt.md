## 2025-01-20 - [Avoid toByteArray() in Hot Loops]
**Learning:** `ByteArrayOutputStream.toByteArray()` creates a new array copy every time. Calling this in a high-frequency loop (e.g., audio processing @ 30ms) causes massive allocation churn and GC pressure.
**Action:** Access the underlying buffer or the source data directly when possible. In `Recorder.java`, the loop was copying the entire growing buffer just to access the last 480 bytes.
