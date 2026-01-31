## 2024-05-21 - ByteArrayOutputStream.toByteArray() Anti-Pattern
**Learning:** Using `ByteArrayOutputStream.toByteArray()` inside a hot loop (like audio processing) causes massive O(N^2) copying overhead because it creates a new array of the total accumulated size on every iteration.
**Action:** Use a circular buffer or shift-and-append strategy with `System.arraycopy` to maintain a fixed-size window of the latest data without re-allocating the entire history.
