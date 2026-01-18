# Sentinel Journal

## 2024-05-24 - Zip Slip Vulnerability in File Extraction
**Vulnerability:** A Zip Slip vulnerability was identified in `SetupActivity.java` where zip entries were extracted without validating that the destination path remained within the target directory.
**Learning:** Standard Java `ZipInputStream` logic does not automatically sanitize file paths. Concatenating a target directory with a zip entry name (e.g., `../../evil.txt`) allows for directory traversal attacks.
**Prevention:** Always resolve the canonical path of the target file and verify it starts with the canonical path of the intended target directory (appended with `File.separator`).
