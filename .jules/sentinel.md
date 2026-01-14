## 2024-01-15 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** Found a Zip Slip vulnerability in `SetupActivity.java` where zip entries are extracted without validating that the destination path lies within the target directory.
**Learning:** This vulnerability existed because the code blindly trusted the `zipEntry.getName()` to be safe, allowing directory traversal sequences (`../`) to escape the intended directory.
**Prevention:** Always validate file paths derived from untrusted input (like zip entries) by canonicalizing the path and checking it against the expected parent directory prefix before writing.
