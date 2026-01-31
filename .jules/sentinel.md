## 2025-05-27 - Zip Slip Vulnerability Fixed
**Vulnerability:** The application was vulnerable to Zip Slip, where a malicious zip archive containing path traversal characters (e.g., `../../evil.txt`) could write files outside the intended extraction directory.
**Learning:** `ZipInputStream` does not automatically validate file paths. Developers must explicitly check that the canonical path of the extraction target lies within the intended directory.
**Prevention:** Always validate the canonical path of the target file before writing to it during zip extraction. Ensure the check includes the directory separator to prevent partial path matching attacks.
