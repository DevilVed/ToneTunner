## 2024-05-24 - Zip Slip in SetupActivity
**Vulnerability:** The `zipExtract` method in `SetupActivity.java` used `ZipEntry.getName()` blindly when creating destination files. This allowed a malicious zip file containing entries with `..` (e.g., `../../evil.sh`) to write files outside the intended target directory (`getExternalFilesDir`).
**Learning:** Android's `ZipInputStream` does not validate paths automatically. Developers must manually validate that the canonical path of the extracted file resides within the canonical path of the target directory.
**Prevention:** Always use `getCanonicalPath()` to resolve paths and check `startsWith(targetDirCanonicalPath + File.separator)` before writing any file from a Zip archive.
