# Sentinel's Security Journal

This file records critical security learnings, vulnerabilities, and patterns found in the codebase.

## 2025-02-18 - Zip Slip Vulnerability
**Vulnerability:** Zip Slip vulnerability in `SetupActivity.java` where zip entry names were concatenated with the target directory without validation, allowing path traversal.
**Learning:** `ZipEntry.getName()` can contain relative paths (`../`), which must be sanitized or validated against the target directory's canonical path.
**Prevention:** Always validate that the canonical path of the destination file starts with the canonical path of the target directory.
