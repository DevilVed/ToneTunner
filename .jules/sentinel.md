# Sentinel's Journal

## 2025-02-14 - Unsafe Zip Extraction Pattern
**Vulnerability:** Zip Path Traversal (Zip Slip) found in `SetupActivity.java`. The application extracted zip entries without validating that the destination path is within the target directory.
**Learning:** Even simple file operations like unzipping can lead to critical vulnerabilities if input is not sanitized. The `ZipEntry.getName()` can contain relative paths like `../../` allowing arbitrary file overwrite.
**Prevention:** Always validate the canonical path of the destination file before writing to it. Ensure it starts with the canonical path of the intended target directory.
