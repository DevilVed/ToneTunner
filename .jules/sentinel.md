## 2024-03-22 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** `SetupActivity.zipExtract` blindly trusted zip entry names, allowing `../` sequences to traverse outside the target directory and overwrite arbitrary files.
**Learning:** Android's `ZipInputStream` does not perform path validation. Developers often assume `File(parent, child)` prevents traversal, but it does not if `child` contains `../`.
**Prevention:** Always validate `file.getCanonicalPath()` starts with `targetDir.getCanonicalPath() + File.separator` before writing any file from an untrusted archive.
