## Performance Learnings

* **Primitive Array Lookup Over Collection Methods:** Optimizing `$O(M)$` membership checks (like `ArrayList.contains(i)`) within an `$O(N)$` loop by converting to a primitive `boolean[]` lookup changes the complexity to `$O(N + M)$`. This avoids object allocation overhead entirely. In `Utils.getIndexOfLargest`, a primitive boolean array lookup was ~600x faster than `ArrayList` in a large search space, and importantly ~10-15x faster than using a `HashSet`.
* **Caching Shared Preferences:** Caching `SharedPreferences` values and using an `OnSharedPreferenceChangeListener` avoids synchronized map lookups.
* **Avoiding Regex Compilation:** Manual string searching instead of `String.split()` yields massive improvements on hot paths.
