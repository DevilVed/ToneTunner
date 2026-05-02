
## $(date +%Y-%m-%d) - [Java Streams overhead in hot paths]
**Learning:** Replacing Java Streams (`Arrays.stream(array).mapToLong(i -> i).toArray()`) with manual for-loops for primitive array conversions (e.g. `int[]` to `long[]`) in tensor creation functions yields a significant performance boost (e.g., ~2x speedup in micro-benchmarks for ONNX token arrays of size 512). The object allocation and setup overhead of Streams outweighs their convenience in hot path scenarios where small-to-medium primitive arrays are repeatedly processed.
**Action:** Always favor manual for-loops over Java Streams for primitive array type conversions on performance-critical paths, specifically when preparing data for AI model inputs (like tensor creation).
