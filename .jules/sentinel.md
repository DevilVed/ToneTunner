## 2024-02-13 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** `SetupActivity.java` blindly extracted zip entries using `new File(targetDir, zipEntry.getName())` without validating that the resulting file path was inside the target directory. This allowed a malicious zip file with paths like `../../file` to overwrite files outside the application's intended storage.
**Learning:** Java/Android `ZipInputStream` does not automatically sanitize paths. Developers must explicitly validate canonical paths before writing files. This is a common but critical oversight when handling archives.
**Prevention:** Always calculate `file.getCanonicalPath()` and verify it starts with `targetDir.getCanonicalPath() + File.separator` before writing any file from an untrusted archive.
