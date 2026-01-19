## 2026-01-19 - Zip Slip Vulnerability in SetupActivity

**Vulnerability:** Unchecked file extraction in `SetupActivity.zipExtract` allowed a malicious zip file containing path traversal characters (e.g., `../evil.txt`) to overwrite arbitrary files outside the target directory.
**Learning:** The code blindly concatenated `targetDir` with `zipEntry.getName()` without verifying that the resulting canonical path was still within `targetDir`. This is a classic "Zip Slip" vulnerability.
**Prevention:** Always validate file paths during extraction. Convert both the target directory and the resulting file path to their canonical forms (resolving `..` and symlinks) and ensure the file path starts with the target directory path (appended with `File.separator`).
