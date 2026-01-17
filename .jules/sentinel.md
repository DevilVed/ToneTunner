## 2024-03-21 - [Zip Slip Vulnerability in SetupActivity]
**Vulnerability:** A Zip Slip vulnerability was identified in `SetupActivity.java` where zip entries were extracted without validating that the destination path remained within the target directory.
**Learning:** `getCanonicalPath()` is essential for validating file paths derived from user input (like zip entries), but checks must explicitly include `File.separator` to prevent partial path matching bypasses.
**Prevention:** Always resolve the canonical path of the target directory, append a file separator, and verify that the canonical path of any extracted file starts with this safe prefix.
