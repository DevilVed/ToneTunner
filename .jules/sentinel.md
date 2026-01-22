## 2026-01-21 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** Unvalidated file path extraction in `SetupActivity.zipExtract` allowed writing files outside the intended directory via directory traversal sequences (`../../`).
**Learning:** `ZipEntry.getName()` returns the raw path from the zip file, which can include malicious traversal characters. The `ZipInputStream` API does not automatically sanitize these paths.
**Prevention:** Always validate the canonical path of the destination file to ensure it starts with the canonical path of the target directory (plus a separator) before writing any data.
