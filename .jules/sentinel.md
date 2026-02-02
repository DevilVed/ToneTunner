## 2024-05-24 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** The `zipExtract` method in `SetupActivity.java` extracts files from a ZIP archive without validating the destination path. A malicious ZIP entry with a name like `../../evil.sh` could overwrite files outside the target directory.
**Learning:** Zip Slip is a common vulnerability when handling archive extraction. Java's `ZipEntry.getName()` returns the raw path, which can include directory traversal characters.
**Prevention:** Always validate that the canonical path of the extracted file starts with the canonical path of the target directory before writing any data.
