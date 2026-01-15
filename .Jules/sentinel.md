## 2024-05-23 - Zip Slip Vulnerability Discrepancy
**Vulnerability:** Found a Zip Slip vulnerability in `SetupActivity.java` where zip entries are extracted without validating their canonical paths.
**Learning:** The existing memory claimed this was already prevented using `getCanonicalPath()`, but the code showed otherwise. This highlights the importance of "Verify, don't just trust" even for internal documentation/memory.
**Prevention:** Always verify security claims against the actual code. Implement `getCanonicalPath()` checks for all file extraction operations.
