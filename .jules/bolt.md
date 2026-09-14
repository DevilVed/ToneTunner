## 2024-09-14 - Replace O(N) Array Search with O(1) HashMap Lookup
**Learning:** In `Recognizer.java`, replacing a linear search in a String array with a static `HashMap` lookup for language indices achieved an ~40x performance improvement in micro-benchmarks while preserving identical error logging and token ID calculation behavior. The `HashMap` reduces lookup complexity from O(N) to O(1).
**Action:** Replace `O(N)` loop lookups against static arrays with static `HashMap` mappings when possible for heavily called lookup methods.
