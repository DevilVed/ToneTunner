## 2026-01-28 - Gradle Dependency Authentication Failure
**Learning:** The project relies on a JitPack dependency (`com.github.DevilVed:FreeDroidWarn`) that fails with 401 Unauthorized, preventing full Gradle builds (lint/test).
**Action:** Rely on standalone `javac` compilation and custom Java benchmarks for verification until the dependency issue is resolved.

## 2026-01-28 - Float Conversion Optimization
**Learning:** Replacing division with multiplication and caching `Math.abs` in a tight audio processing loop yielded ~1.8x speedup in micro-benchmarks.
**Action:** Look for similar patterns (division in loops) in other signal processing classes.
