## 2024-05-24 - Array flattening overhead
**Learning:** Guava's `Floats.concat` and `Ints.concat` methods perform poorly when nested for multi-dimensional arrays, as they allocate many short-lived intermediate array objects leading to garbage collection pressure and memory copy overhead.
**Action:** Replace nested `concat` operations with a single, flat destination array allocation and nested `System.arraycopy` loops. This yields massive performance improvements (e.g. ~10x-15x faster for 3D/4D arrays in benchmarks) for ML tensor preparations.

## 2024-05-24 - Repository test hygiene
**Learning:** When generating standalone Java benchmark scripts to test performance optimizations locally, the resulting `.class` files pollute the git working directory.
**Action:** Always include a cleanup step in the bash script to remove generated `.java` and `.class` binaries from the workspace before committing to ensure the repository remains clean.
