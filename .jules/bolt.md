## 2026-02-03 - ByteArrayOutputStream in Recording Loop
**Learning:** `ByteArrayOutputStream.toByteArray()` allocates a new array containing the ENTIRE stream history. Calling this in a high-frequency loop (e.g., audio recording every 30ms) causes $O(N^2)$ memory allocation and massive GC pressure.
**Action:** Use a rolling buffer (ring buffer or shift buffer) with `System.arraycopy` to process only the latest data window without re-allocating the full history.
