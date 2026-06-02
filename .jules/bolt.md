## Pre-existing learnings

## 2024-05-24 - Pre-allocating Map and caching string concatenations in tight inference loops
**Learning:** In high-frequency tight loops, such as machine learning decoder loops generating tokens one by one (`Recognizer.java`), dynamic string concatenation (e.g., `"past_key_values." + i + ".decoder.key"`) combined with creating a new `HashMap` each iteration causes immense object allocation overhead and Garbage Collection (GC) churn. We measured a >4x speedup in isolated Java microbenchmarks just by avoiding string formatting and `new HashMap()` calls.
**Action:** Always scrutinize loops on hot paths (e.g. per-audio-frame or per-inference-token). Pre-calculate static arrays for dynamically generated map keys during class initialization, pre-allocate maps with a static initial capacity sufficient to avoid resizing (e.g. `128`), and clear/reuse the map (`map.clear()`) rather than re-instantiating it.
