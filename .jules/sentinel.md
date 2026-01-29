## 2026-01-29 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** `SetupActivity.zipExtract` blindly concatenated `targetDir` with `zipEntry.getName()`, allowing path traversal via `..` segments in malicious zip files.
**Learning:** Android's `ZipInputStream` and `File` APIs do not automatically sanitize paths or sandbox operations. Trusting file names from external archives is a common pitfall. The memory stated this was protected, but code inspection proved otherwise.
**Prevention:** Always resolve the canonical path (`getCanonicalPath()`) of the destination file and verify it starts with the canonical path of the target directory. Ensure the target directory path ends with `File.separator`.
