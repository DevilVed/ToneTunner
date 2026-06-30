## 2024-05-18 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** Zip Path Traversal (Zip Slip) in `SetupActivity.zipExtract`
**Learning:** `java.util.zip.ZipInputStream` does not automatically validate if a `ZipEntry` path attempts to escape the target extraction directory (e.g., using `../`). Because the zip extraction is trigged from user-provided input via an intent (`ACTION_OPEN_DOCUMENT`), an attacker could craft a malicious zip file designed to overwrite arbitrary internal app data.
**Prevention:** Always sanitize zip entry paths before extraction by getting the `.getCanonicalPath()` of both the base destination directory and the intended extracted file path, then explicitly verify that the extracted file path `.startsWith(baseDirectoryCanonicalPath + File.separator)`.
