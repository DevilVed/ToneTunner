## 2026-01-27 - Zip Slip in SetupActivity
**Vulnerability:** Unsanitized ZipEntry names allowed writing files outside the target directory (Zip Slip).
**Learning:** `java.util.zip.ZipInputStream` does not automatically validate paths. Developers must manually check `getCanonicalPath()` against the target directory.
**Prevention:** Always validate that `entry.getCanonicalPath()` starts with `targetDir.getCanonicalPath() + File.separator` before extracting.
