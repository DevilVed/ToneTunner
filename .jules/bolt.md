## 2024-05-23 - [Direct Byte Access vs ByteBuffer]
**Learning:** In hot loops processing audio data, `ByteBuffer.getShort()` adds significant overhead compared to manual bitwise operations on the byte array, even with JIT.
**Action:** For performance-critical audio buffering/processing on Android (Little Endian), manually assemble shorts from bytes `(high << 8) | (low & 0xFF)` instead of using `ByteBuffer` wrappers. Also avoid redundant floating point division by normalizing directly against the max value.
